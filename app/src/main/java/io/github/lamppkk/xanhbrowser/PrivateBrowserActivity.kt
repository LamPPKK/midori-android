package io.github.lamppkk.xanhbrowser

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputConnection
import android.webkit.GeolocationPermissions
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import io.github.lamppkk.xanhbrowser.databinding.ActivityBrowserBinding

// Adds the same IME privacy opt-out to HTML editors inside the WebView.
internal class PrivateWebView(context: Context) : XanhWebView(context) {
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs)
        outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        return connection
    }
}

/**
 * An ephemeral browser surface backed by a named AndroidX WebKit profile.
 *
 * It intentionally has no Room repository, Sync runtime, credential bridge,
 * bookmarks, history, session restore or portable-backup integration.
 */
class PrivateBrowserActivity : AppCompatActivity() {
    private val session by viewModels<PrivateSessionViewModel>()
    private lateinit var binding: ActivityBrowserBinding
    private lateinit var adBlockCoordinator: AdBlockCoordinator
    private var webView: WebView? = null
    private var mobileUserAgent: String? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var geolocationRequest: Pair<String, GeolocationPermissions.Callback>? = null
    private var geolocationDialog: AlertDialog? = null
    private var locationPermissionInFlight = false
    private var downloadDialog: AlertDialog? = null
    private var rendererRecoveryPending = false

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        fileCallback?.onReceiveValue(uris.toTypedArray())
        fileCallback = null
    }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        locationPermissionInFlight = false
        if (granted && webView != null && geolocationRequest != null) {
            confirmGeolocation()
        } else {
            completeGeolocation(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!PrivateProfileManager.isSupported()) {
            Toast.makeText(this, R.string.private_mode_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        adBlockCoordinator = AdBlockCoordinator.get(this)
        binding.root.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            binding.root.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        }
        binding.urlBar.isSaveEnabled = false
        binding.urlBar.setSaveFromParentEnabled(false)
        binding.urlBar.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        binding.urlBar.imeOptions =
            binding.urlBar.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        binding.toolbar.setBackgroundColor(ContextCompat.getColor(this, R.color.xanh_private))
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.private_browser)
        applyInsets()
        configureAddressBar()
        configureBackNavigation()
        createWebView(session.currentUrl ?: getString(R.string.app_website))
    }

    override fun onResume() {
        super.onResume()
        if (rendererRecoveryPending) {
            scheduleRendererRecovery()
            return
        }
        webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        geolocationDialog?.setOnCancelListener(null)
        geolocationDialog?.dismiss()
        geolocationDialog = null
        completeGeolocation(false)
        downloadDialog?.dismiss()
        downloadDialog = null
        destroyWebView()
        if (!isChangingConfigurations) PrivateProfileManager.deleteWhenUnused(session.profileName)
        super.onDestroy()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun configureAddressBar() {
        binding.urlBar.setOnEditorActionListener { view, actionId, event ->
            val submitted = actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (submitted) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(view.windowToken, 0)
                loadInput(view.text.toString())
            }
            submitted
        }
    }

    private fun configureBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = webView
                if (current?.canGoBack() == true) current.goBack() else finish()
            }
        })
    }

    private fun createWebView(initialUrl: String) {
        check(webView == null)
        val privateWebView = PrivateWebView(this)
        PrivateProfileManager.attach(privateWebView, session.profileName)
        adBlockCoordinator.installProfileServiceWorkerClient(privateWebView)
        privateWebView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        privateWebView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            privateWebView.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        }
        privateWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(privateWebView, false)
        mobileUserAgent = privateWebView.settings.userAgentString
        applyUserAgent(privateWebView)
        privateWebView.webViewClient = PrivateWebViewClient()
        privateWebView.webChromeClient = PrivateWebChromeClient(privateWebView)
        privateWebView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (webView === privateWebView) {
                confirmDownload(url, userAgent, contentDisposition, mimeType)
            }
        }
        webView = privateWebView
        binding.webContainer.addView(privateWebView)
        privateWebView.loadUrl(initialUrl.takeIf(AddressResolver::isValidWebUrl) ?: getString(R.string.app_website))
    }

    private fun destroyWebView(deadRenderer: Boolean = false) {
        val current = webView ?: return
        webView = null
        binding.webContainer.removeView(current)
        if (!deadRenderer) {
            current.stopLoading()
            current.clearHistory()
            current.webChromeClient = null
        }
        current.destroy()
        mobileUserAgent = null
    }

    private fun recoverRenderer(deadWebView: WebView) {
        if (webView !== deadWebView) {
            return
        }
        cancelRendererCallbacks()
        destroyWebView(deadRenderer = true)
        rendererRecoveryPending = true
        scheduleRendererRecovery()
    }

    private fun cancelRendererCallbacks() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        cancelGeolocation()
        downloadDialog?.dismiss()
        downloadDialog = null
    }

    private fun scheduleRendererRecovery() {
        if (!rendererRecoveryPending || isFinishing || isDestroyed) return
        if (session.rendererRecoveryUsed) {
            rendererRecoveryPending = false
            Toast.makeText(this, R.string.renderer_recovery_stopped, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        rendererRecoveryPending = false
        session.rendererRecoveryUsed = true
        val target = session.currentUrl ?: getString(R.string.app_website)
        binding.webContainer.post {
            if (
                !isFinishing &&
                !isDestroyed &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                webView == null
            ) {
                createWebView(target)
            } else if (!isFinishing && !isDestroyed && webView == null) {
                session.rendererRecoveryUsed = false
                rendererRecoveryPending = true
            }
        }
    }

    private fun loadInput(input: String) {
        val resolved = AddressResolver.resolve(input)
        val uri = resolved.toUri()
        if (AddressResolver.isExternal(resolved)) {
            openExternal(uri)
        } else if (AddressResolver.isValidWebUrl(resolved)) {
            webView?.loadUrl(resolved)
        }
    }

    private fun openExternal(uri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        if (intent.resolveActivity(packageManager) == null) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
            return false
        }
        return runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show() }
            .isSuccess
    }

    private fun chooseFiles(callback: ValueCallback<Array<Uri>>, acceptTypes: Array<String>): Boolean {
        if (webView == null) {
            callback.onReceiveValue(null)
            return false
        }
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        return runCatching {
            filePicker.launch(acceptTypes.filter(String::isNotBlank).ifEmpty { listOf("*/*") }.toTypedArray())
            true
        }.getOrElse {
            fileCallback?.onReceiveValue(null)
            fileCallback = null
            false
        }
    }

    private fun requestGeolocation(origin: String, callback: GeolocationPermissions.Callback) {
        if (webView == null) {
            callback.invoke(origin, false, false)
            return
        }
        cancelGeolocation()
        geolocationRequest = origin to callback
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            confirmGeolocation()
        } else {
            if (locationPermissionInFlight) {
                completeGeolocation(false)
                return
            }
            locationPermissionInFlight = true
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun confirmGeolocation() {
        val (origin, _) = geolocationRequest ?: return
        geolocationDialog = AlertDialog.Builder(this)
            .setTitle(R.string.location_request_title)
            .setMessage(getString(R.string.location_request_message, origin))
            .setNegativeButton(R.string.deny) { _, _ -> completeGeolocation(false) }
            .setPositiveButton(R.string.allow_once) { _, _ -> completeGeolocation(true) }
            .setOnCancelListener { completeGeolocation(false) }
            .show()
    }

    private fun cancelGeolocation() {
        geolocationDialog?.setOnCancelListener(null)
        geolocationDialog?.dismiss()
        geolocationDialog = null
        completeGeolocation(false)
    }

    private fun completeGeolocation(allowed: Boolean) {
        geolocationRequest?.let { (origin, callback) -> callback.invoke(origin, allowed, false) }
        geolocationRequest = null
        geolocationDialog = null
    }

    private fun confirmDownload(url: String, userAgent: String?, disposition: String?, mimeType: String?) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || webView == null) return
        val uri = runCatching { url.toUri() }.getOrNull() ?: return
        if (uri.scheme != "http" && uri.scheme != "https") return
        val fileName = URLUtil.guessFileName(url, disposition, mimeType)
        downloadDialog?.dismiss()
        downloadDialog = AlertDialog.Builder(this)
            .setTitle(R.string.private_download_title)
            .setMessage(getString(R.string.private_download_confirmation, fileName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.download) { _, _ ->
                enqueueDownload(url, userAgent, disposition, mimeType)
            }
            .setOnDismissListener { downloadDialog = null }
            .show()
    }

    private fun enqueueDownload(url: String, userAgent: String?, disposition: String?, mimeType: String?) {
        runCatching {
            val uri = url.toUri()
            require(uri.scheme == "http" || uri.scheme == "https")
            val fileName = URLUtil.guessFileName(url, disposition, mimeType)
            val request = DownloadManager.Request(uri)
                .setTitle(fileName)
                .setDescription(getString(R.string.private_download_notice))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            } else {
                request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            userAgent?.let { request.addRequestHeader("User-Agent", it) }
            mimeType?.let(request::setMimeType)
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, R.string.private_download_notice, Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun sharePage() {
        val current = webView ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, current.url)
            putExtra(Intent.EXTRA_TITLE, current.title)
        }, getString(R.string.share)))
    }

    private fun toggleDesktopMode(item: MenuItem) {
        session.desktopMode = !session.desktopMode
        item.isChecked = session.desktopMode
        webView?.let(::applyUserAgent)
        webView?.reload()
    }

    private fun applyUserAgent(target: WebView) {
        val mobile = mobileUserAgent ?: target.settings.userAgentString
        target.settings.userAgentString = if (session.desktopMode) {
            mobile.replace("; wv", "").replace(" Mobile", "") + " XanhBrowser/1.0"
        } else {
            "$mobile XanhBrowser/1.0"
        }
        target.settings.useWideViewPort = session.desktopMode
        target.settings.loadWithOverviewMode = session.desktopMode
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.private_browser_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_back)?.isEnabled = webView?.canGoBack() == true
        menu.findItem(R.id.action_forward)?.isEnabled = webView?.canGoForward() == true
        menu.findItem(R.id.action_desktop_site)?.isChecked = session.desktopMode
        menu.findItem(R.id.action_content_blocking)?.isChecked = adBlockCoordinator.isEnabled()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_back -> { webView?.goBack(); true }
        R.id.action_forward -> { webView?.goForward(); true }
        R.id.action_reload -> { webView?.reload(); true }
        R.id.action_share -> { sharePage(); true }
        R.id.action_desktop_site -> { toggleDesktopMode(item); true }
        R.id.action_content_blocking -> {
            val enabled = !adBlockCoordinator.isEnabled()
            adBlockCoordinator.setEnabled(enabled)
            item.isChecked = enabled
            webView?.reload()
            true
        }
        R.id.action_close_private -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }

    @SuppressLint("MissingOnRenderProcessGone") // onRenderProcessGone is implemented below.
    private inner class PrivateWebViewClient : WebViewClient() {
        @Volatile
        private var sourceUrl: String? = null

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            when (request.url.scheme?.lowercase()) {
                "http", "https" -> !AddressResolver.isValidWebUrl(request.url.toString())
                "mailto", "tel", "geo", "market" -> {
                    if (
                        request.isForMainFrame &&
                        request.hasGesture() &&
                        !request.isRedirect &&
                        AddressResolver.isExternal(request.url.toString())
                    ) openExternal(request.url) else true
                }
                else -> true
            }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? = adBlockCoordinator.shouldIntercept(request, sourceUrl)

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (view == null || webView !== view) return
            sourceUrl = url?.takeIf(AddressResolver::isValidWebUrl)
            binding.loadingProgress.progress = 0
            binding.loadingProgress.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (view == null || webView !== view) return
            sourceUrl = url?.takeIf(AddressResolver::isValidWebUrl)
            if (url != null && AddressResolver.isValidWebUrl(url)) {
                session.currentUrl = url
                binding.urlBar.setText(url)
            }
            supportActionBar?.subtitle = view?.title
            binding.loadingProgress.visibility = View.GONE
            invalidateOptionsMenu()
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
            handler.cancel()
            if (view != null && webView === view) {
                Toast.makeText(this@PrivateBrowserActivity, R.string.tls_error, Toast.LENGTH_LONG).show()
            }
        }

        @RequiresApi(Build.VERSION_CODES.O_MR1)
        override fun onSafeBrowsingHit(
            view: WebView,
            request: WebResourceRequest,
            threatType: Int,
            callback: SafeBrowsingResponse,
        ) {
            callback.backToSafety(true)
            Toast.makeText(this@PrivateBrowserActivity, R.string.unsafe_page_blocked, Toast.LENGTH_LONG).show()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            recoverRenderer(view)
            return true
        }
    }

    private inner class PrivateWebChromeClient(private val owner: WebView) : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (webView !== owner || view !== owner) return
            binding.loadingProgress.progress = newProgress
            binding.loadingProgress.visibility = if (newProgress in 0..99) View.VISIBLE else View.GONE
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            if (webView !== owner || view !== owner) return
            supportActionBar?.subtitle = title
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            if (this@PrivateBrowserActivity.webView !== owner || webView !== owner) {
                filePathCallback.onReceiveValue(null)
                return false
            }
            return chooseFiles(filePathCallback, fileChooserParams.acceptTypes)
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback,
        ) {
            if (webView !== owner) {
                callback.invoke(origin, false, false)
            } else {
                requestGeolocation(origin, callback)
            }
        }

        override fun onGeolocationPermissionsHidePrompt() {
            if (webView === owner) cancelGeolocation()
        }
    }

    @VisibleForTesting
    internal fun setPrivateAddressForTest(value: String) = binding.urlBar.setText(value)

    @VisibleForTesting
    internal fun privateAddressForTest(): String = binding.urlBar.text.toString()

    @VisibleForTesting
    internal fun isPrivateAutofillDisabledForTest(): Boolean =
        binding.root.importantForAutofill == View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS &&
            binding.urlBar.importantForAutofill == View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS &&
            webView?.importantForAutofill == View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS

    @VisibleForTesting
    internal fun isPrivateImeLearningDisabledForTest(): Boolean {
        val editorInfo = EditorInfo()
        webView?.onCreateInputConnection(editorInfo)
        return editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
    }
}
