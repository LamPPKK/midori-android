package io.github.lamppkk.xanhbrowser

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import io.github.lamppkk.xanhbrowser.backup.PortableBackup
import io.github.lamppkk.xanhbrowser.backup.PortableBackupPayload
import io.github.lamppkk.xanhbrowser.databinding.ActivityBrowserBinding
import io.github.lamppkk.xanhbrowser.sync.CredentialContext
import io.github.lamppkk.xanhbrowser.sync.CredentialPolicy
import io.github.lamppkk.xanhbrowser.sync.SyncReason
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.json.JSONObject

class BrowserActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBrowserBinding
    private lateinit var repository: BrowserRepository
    private val sessions = mutableMapOf<Long, WebView>()
    private val mobileUserAgents = mutableMapOf<Long, String>()
    private val desktopModes = mutableMapOf<Long, Boolean>()
    private val credentialBridges = mutableMapOf<Long, XanhCredentialBridge>()
    private val credentialDialogs = mutableMapOf<Long, AlertDialog>()
    private val rendererRecoveryUsed = mutableSetOf<Long>()
    private val rendererRecoveryPending = mutableSetOf<Long>()
    private val rendererRecoveryRunning = mutableSetOf<Long>()
    private var activeTabId: Long = 0
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var geolocationRequest: Pair<String, GeolocationPermissions.Callback>? = null
    private var geolocationDialog: AlertDialog? = null
    private var locationPermissionInFlight = false
    private var initialized = false
    private var downloadReceiverRegistered = false
    private var pendingBackupPassword: CharArray? = null

    private val createBackupDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument(PortableBackup.MIME_TYPE),
    ) { uri ->
        val password = pendingBackupPassword
        pendingBackupPassword = null
        if (uri == null || password == null) {
            password?.fill('\u0000')
        } else {
            exportBackup(uri, password)
        }
    }

    private val openBackupDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) promptBackupPassword(R.string.import_backup) { importBackup(uri, it) }
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id < 0) return
            lifecycleScope.launch { updateDownloadStatus(id) }
        }
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        fileCallback?.onReceiveValue(uris.toTypedArray())
        fileCallback = null
    }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        locationPermissionInFlight = false
        if (granted && activeWebView() != null && geolocationRequest != null) {
            confirmGeolocation()
        } else {
            completeGeolocation(false)
        }
    }

    private val libraryResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.dataString?.let(::createTab)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        repository = BrowserRepository(this)
        SyncCoordinator.get(this).scheduleBackgroundSync()
        applyInsets()
        configureAddressBar()
        configureBackNavigation()

        lifecycleScope.launch {
            val incoming = intent.dataString
                .takeIf { savedInstanceState == null }
                ?.let(AddressResolver::resolveWebIntent)
            val tab = if (incoming != null) repository.createTab(incoming)
            else repository.selectedOrCreate(getString(R.string.app_website))
            showTab(tab)
            initialized = true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AddressResolver.resolveWebIntent(intent.dataString)?.let(::createTab)
    }

    override fun onResume() {
        super.onResume()
        if (initialized) {
            if (activeTabId in rendererRecoveryPending) scheduleRendererRecovery(activeTabId)
            else synchronizeTabs()
        }
        lifecycleScope.launch { repository.allDownloads().forEach { updateDownloadStatus(it.id) } }
        activeWebView()?.onResume()
    }

    override fun onStart() {
        super.onStart()
        val sync = SyncCoordinator.get(this)
        lifecycleScope.launch {
            if (withContext(Dispatchers.IO) { sync.isDue(SyncReason.STARTUP) }) {
                runCatching { sync.sync(SyncReason.STARTUP) }
            }
        }
        if (!downloadReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED,
            )
            downloadReceiverRegistered = true
        }
    }

    override fun onStop() {
        if (downloadReceiverRegistered) {
            unregisterReceiver(downloadReceiver)
            downloadReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onPause() {
        activeWebView()?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        pendingBackupPassword?.fill('\u0000')
        pendingBackupPassword = null
        fileCallback?.onReceiveValue(null)
        geolocationDialog?.setOnCancelListener(null)
        geolocationDialog?.dismiss()
        geolocationDialog = null
        completeGeolocation(false)
        credentialBridges.values.forEach(XanhCredentialBridge::destroy)
        credentialBridges.clear()
        credentialDialogs.values.forEach(AlertDialog::dismiss)
        credentialDialogs.clear()
        sessions.values.forEach { webView ->
            binding.webContainer.removeView(webView)
            webView.stopLoading()
            webView.webChromeClient = null
            webView.destroy()
        }
        sessions.clear()
        rendererRecoveryUsed.clear()
        rendererRecoveryPending.clear()
        rendererRecoveryRunning.clear()
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
                val webView = activeWebView()
                if (webView?.canGoBack() == true) webView.goBack() else finish()
            }
        })
    }

    private fun createTab(input: String) {
        val resolved = AddressResolver.resolve(input)
        val uri = resolved.toUri()
        if (AddressResolver.isExternal(resolved)) {
            openExternal(uri)
            return
        }
        lifecycleScope.launch {
            showTab(repository.createTab(resolved))
        }
    }

    private fun loadInput(input: String) {
        val resolved = AddressResolver.resolve(input)
        val uri = resolved.toUri()
        if (AddressResolver.isExternal(resolved)) {
            openExternal(uri)
            return
        }
        if (!AddressResolver.isValidWebUrl(resolved)) return
        rendererRecoveryUsed.remove(activeTabId)
        val current = activeWebView() ?: createWebView(activeTabId).also {
            sessions[activeTabId] = it
            binding.webContainer.addView(it)
            it.visibility = View.VISIBLE
        }
        current.loadUrl(resolved)
    }

    private fun synchronizeTabs() {
        lifecycleScope.launch {
            val allTabs = repository.allTabs()
            val validIds = allTabs.mapTo(mutableSetOf()) { it.id }
            sessions.keys.filterNot(validIds::contains).forEach(::destroySession)
            val selected = allTabs.firstOrNull(BrowserTab::selected)
                ?: repository.selectedOrCreate(getString(R.string.app_website))
            if (selected.id != activeTabId) showTab(selected)
        }
    }

    private fun destroySession(tabId: Long, clearData: Boolean = false) {
        credentialDialogs.remove(tabId)?.dismiss()
        credentialBridges.remove(tabId)?.destroy()
        sessions.remove(tabId)?.let { webView ->
            binding.webContainer.removeView(webView)
            webView.stopLoading()
            if (clearData) {
                webView.clearCache(true)
                webView.clearHistory()
                webView.clearFormData()
                webView.clearSslPreferences()
            }
            webView.webChromeClient = null
            webView.destroy()
        }
        mobileUserAgents.remove(tabId)
        desktopModes.remove(tabId)
        rendererRecoveryUsed.remove(tabId)
        rendererRecoveryPending.remove(tabId)
        rendererRecoveryRunning.remove(tabId)
    }

    private fun showTab(tab: BrowserTab) {
        rendererRecoveryPending.remove(tab.id)
        if (activeTabId != tab.id) credentialDialogs.remove(activeTabId)?.dismiss()
        sessions[activeTabId]?.apply {
            visibility = View.GONE
            onPause()
        }
        val existing = sessions[tab.id]
        val webView = existing ?: createWebView(tab.id).also {
            sessions[tab.id] = it
            binding.webContainer.addView(it)
        }
        activeTabId = tab.id
        webView.visibility = View.VISIBLE
        webView.onResume()
        val safeUrl = tab.url.takeIf(AddressResolver::isValidWebUrl) ?: getString(R.string.app_website)
        binding.urlBar.setText(safeUrl)
        supportActionBar?.subtitle = tab.title
        if (existing == null) webView.loadUrl(safeUrl)
        lifecycleScope.launch { repository.selectTab(tab.id) }
        invalidateOptionsMenu()
    }

    private fun createWebView(tabId: Long): WebView = WebView(this).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            safeBrowsingEnabled = true
        }
        val configuredWebView = this
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(configuredWebView, false)
        }
        mobileUserAgents[tabId] = settings.userAgentString
        desktopModes[tabId] = false
        settings.userAgentString = "${settings.userAgentString} XanhBrowser/1.0"
        credentialBridges[tabId] = XanhCredentialBridge(this@BrowserActivity, this, tabId).also {
            it.install()
        }
        webViewClient = XanhWebViewClient(this@BrowserActivity, tabId)
        webChromeClient = XanhWebChromeClient(this@BrowserActivity, tabId, this)
        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            enqueueDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun activeWebView(): WebView? = sessions[activeTabId]

    @VisibleForTesting
    internal fun currentWebViewIdentityForTest(): Int? =
        activeWebView()?.let(System::identityHashCode)

    @VisibleForTesting
    internal fun currentWebUrlForTest(): String? = activeWebView()?.url

    @VisibleForTesting
    internal fun terminateCurrentRendererForTest(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return activeWebView()?.webViewRenderProcess?.terminate() == true
    }

    internal fun isCurrentSession(tabId: Long, view: WebView): Boolean = sessions[tabId] === view

    internal fun onPageChanged(tabId: Long, url: String?, title: String?) {
        if (url.isNullOrBlank() || url == "about:blank" || !AddressResolver.isValidWebUrl(url)) return
        lifecycleScope.launch { repository.updatePage(tabId, url, title.orEmpty()) }
        if (tabId == activeTabId) {
            binding.urlBar.setText(url)
            supportActionBar?.subtitle = title
        }
    }

    internal fun onNavigationStarted(tabId: Long, url: String?) {
        credentialDialogs.remove(tabId)?.dismiss()
        credentialBridges[tabId]?.navigationStarted(url)
    }

    internal fun onNavigationCommitted(tabId: Long, url: String?) {
        credentialBridges[tabId]?.navigationCommitted(url)
    }

    internal fun showCredentialSuggestions(
        bridge: XanhCredentialBridge,
        tabId: Long,
        origin: String,
        requestNonce: String,
        reply: (String) -> Boolean,
    ) {
        if (
            tabId != activeTabId ||
            credentialBridges[tabId] !== bridge ||
            !bridge.isRequestCurrent(requestNonce)
        ) return
        lifecycleScope.launch {
            if (credentialBridges[tabId] !== bridge || !bridge.isRequestCurrent(requestNonce)) {
                return@launch
            }
            val runtime = SyncCoordinator.get(this@BrowserActivity).runtimeOrNull() ?: return@launch
            if (!runCatching { runtime.touchVault() }.getOrDefault(false)) return@launch
            val requestedOrigin = CredentialPolicy.canonicalHttpsOrigin(
                origin,
                requireOriginOnly = true,
            ) ?: return@launch
            val documentUrl = sessions[tabId]?.url ?: return@launch
            val context = CredentialContext(
                documentUrl = documentUrl,
                topFrameOrigin = requestedOrigin,
                frameOrigin = requestedOrigin,
                isPrivate = false,
                userSelected = true,
            )
            val logins = withContext(Dispatchers.IO) {
                runCatching { runtime.credentialLogins(context) }
            }.getOrElse { return@launch }
            if (
                logins.isEmpty() ||
                tabId != activeTabId ||
                credentialBridges[tabId] !== bridge ||
                !bridge.isRequestCurrent(requestNonce) ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) return@launch
            val dialog = AlertDialog.Builder(this@BrowserActivity)
                .setTitle(R.string.sync_choose_credential)
                .setItems(logins.map { it.username.ifBlank { getString(R.string.sync_empty_username) } }.toTypedArray()) {
                    _, index ->
                    val selected = logins[index]
                    val allowed = runCatching { runtime.touchVault() }.getOrDefault(false) &&
                        CredentialPolicy.isAllowed(context, vaultUnlocked = true)
                    if (
                        !allowed ||
                        tabId != activeTabId ||
                        sessions[tabId]?.url != documentUrl ||
                        credentialBridges[tabId] !== bridge ||
                        !bridge.isRequestCurrent(requestNonce)
                    ) return@setItems
                    val delivered = reply(
                        JSONObject()
                            .put("type", "credential-selected")
                            .put("username", selected.username)
                            .put("password", selected.password)
                            .toString(),
                    )
                    if (delivered) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            runCatching {
                                runtime.touchLogin(selected.id)
                                SyncCoordinator.get(this@BrowserActivity).recordLocalChange()
                            }
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            dialog.setOnDismissListener { credentialDialogs.remove(tabId, dialog) }
            credentialDialogs.put(tabId, dialog)?.dismiss()
            if (
                tabId == activeTabId &&
                credentialBridges[tabId] === bridge &&
                bridge.isRequestCurrent(requestNonce)
            ) dialog.show()
        }
    }

    internal fun onProgress(tabId: Long, progress: Int) {
        if (tabId != activeTabId) return
        binding.loadingProgress.progress = progress
        binding.loadingProgress.visibility = if (progress in 0..99) View.VISIBLE else View.GONE
    }

    internal fun onRendererGone(tabId: Long, failed: WebView): Boolean {
        if (sessions[tabId] !== failed) return true
        sessions.remove(tabId)
        binding.webContainer.removeView(failed)
        credentialBridges.remove(tabId)?.abandonRenderer()
        credentialDialogs.remove(tabId)?.dismiss()
        if (tabId == activeTabId) {
            fileCallback?.onReceiveValue(null)
            fileCallback = null
            cancelGeolocation()
        }
        failed.destroy()
        mobileUserAgents.remove(tabId)
        desktopModes.remove(tabId)
        if (tabId == activeTabId) {
            rendererRecoveryPending.add(tabId)
            scheduleRendererRecovery(tabId)
        }
        return true
    }

    private fun scheduleRendererRecovery(tabId: Long) {
        if (
            tabId !in rendererRecoveryPending ||
            tabId != activeTabId ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            !rendererRecoveryRunning.add(tabId)
        ) return
        if (tabId in rendererRecoveryUsed) {
            rendererRecoveryPending.remove(tabId)
            rendererRecoveryRunning.remove(tabId)
            binding.loadingProgress.visibility = View.GONE
            Toast.makeText(this, R.string.renderer_recovery_stopped, Toast.LENGTH_LONG).show()
            return
        }
        rendererRecoveryUsed.add(tabId)
        lifecycleScope.launch {
            try {
                val tab = repository.allTabs().firstOrNull { it.id == tabId } ?: return@launch
                if (
                    tabId in rendererRecoveryPending &&
                    tabId == activeTabId &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                ) {
                    rendererRecoveryPending.remove(tabId)
                    val safeUrl = tab.url.takeIf(AddressResolver::isValidWebUrl)
                        ?: getString(R.string.app_website)
                    showTab(tab.copy(url = safeUrl))
                }
            } finally {
                rendererRecoveryRunning.remove(tabId)
            }
        }
    }

    internal fun openExternal(uri: Uri): Boolean {
        val external = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        if (external.resolveActivity(packageManager) == null) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
            return false
        }
        return runCatching { startActivity(external) }
            .onFailure { Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show() }
            .isSuccess
    }

    internal fun chooseFiles(callback: ValueCallback<Array<Uri>>, acceptTypes: Array<String>): Boolean {
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

    internal fun requestGeolocation(origin: String, callback: GeolocationPermissions.Callback) {
        if (activeWebView() == null) {
            callback.invoke(origin, false, false)
            return
        }
        geolocationDialog?.setOnCancelListener(null)
        geolocationDialog?.dismiss()
        geolocationDialog = null
        completeGeolocation(false)
        geolocationRequest = origin to callback
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            confirmGeolocation()
            return
        }
        if (locationPermissionInFlight) {
            completeGeolocation(false)
            return
        }
        locationPermissionInFlight = true
        locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    internal fun cancelGeolocation() {
        geolocationDialog?.setOnCancelListener(null)
        geolocationDialog?.dismiss()
        geolocationDialog = null
        completeGeolocation(false)
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

    private fun completeGeolocation(allowed: Boolean) {
        geolocationRequest?.let { (origin, callback) -> callback.invoke(origin, allowed, false) }
        geolocationRequest = null
        geolocationDialog = null
    }

    private fun enqueueDownload(url: String, userAgent: String?, disposition: String?, mimeType: String?) {
        runCatching {
            val uri = url.toUri()
            require(uri.scheme == "http" || uri.scheme == "https")
            val fileName = URLUtil.guessFileName(url, disposition, mimeType)
            val request = DownloadManager.Request(uri)
                .setTitle(fileName)
                .setDescription(getString(R.string.download_started))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            } else {
                request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            userAgent?.let { request.addRequestHeader("User-Agent", it) }
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            mimeType?.let(request::setMimeType)
            val id = (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            lifecycleScope.launch { repository.saveDownload(id, url, fileName) }
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun updateDownloadStatus(id: Long) {
        if (!repository.hasDownload(id)) return
        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val destination = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)).orEmpty()
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val state = when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> "successful"
                DownloadManager.STATUS_FAILED -> "failed"
                DownloadManager.STATUS_RUNNING -> "running"
                else -> "queued"
            }
            repository.updateDownload(id, state, destination, reason)
        }
    }

    private fun sharePage() {
        val webView = activeWebView() ?: return
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, webView.url)
            putExtra(Intent.EXTRA_TITLE, webView.title)
        }
        startActivity(Intent.createChooser(share, getString(R.string.share)))
    }

    private fun requestDesktopSite(item: MenuItem) {
        val desktop = !(desktopModes[activeTabId] ?: false)
        item.isChecked = desktop
        applyDesktopSite(desktop)
    }

    private fun applyDesktopSite(desktop: Boolean) {
        val webView = activeWebView() ?: return
        desktopModes[activeTabId] = desktop
        val mobile = mobileUserAgents[activeTabId] ?: webView.settings.userAgentString
        webView.settings.userAgentString = if (desktop) {
            mobile.replace("; wv", "").replace(" Mobile", "") + " XanhBrowser/1.0"
        } else "$mobile XanhBrowser/1.0"
        webView.settings.useWideViewPort = desktop
        webView.settings.loadWithOverviewMode = desktop
        webView.reload()
    }

    private fun chooseBackupDestination() {
        promptBackupPassword(R.string.export_backup) { password ->
            pendingBackupPassword?.fill('\u0000')
            pendingBackupPassword = password
            createBackupDocument.launch("xanh-browser-${System.currentTimeMillis()}${PortableBackup.FILE_EXTENSION}")
        }
    }

    private fun chooseBackupToImport() {
        openBackupDocument.launch(arrayOf(PortableBackup.MIME_TYPE, "application/octet-stream"))
    }

    private fun promptBackupPassword(title: Int, onAccepted: (CharArray) -> Unit) {
        val input = EditText(this).apply {
            hint = getString(R.string.backup_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(R.string.backup_password_description)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val password = input.text.toString().toCharArray()
                if (password.size < 8) {
                    password.fill('\u0000')
                    Toast.makeText(this, R.string.backup_password_too_short, Toast.LENGTH_SHORT).show()
                } else {
                    onAccepted(password)
                }
                input.text?.clear()
            }
            .show()
    }

    private fun exportBackup(uri: Uri, password: CharArray) {
        lifecycleScope.launch {
            val result = try {
                runCatching {
                    val tabs = repository.allTabs()
                        .filter { PortableBackup.isSupportedWebUrl(it.url) }
                        .take(50)
                    val urls = tabs.map(BrowserTab::url)
                        .ifEmpty { listOf(getString(R.string.app_website)) }
                    val selectedIndex = tabs.indexOfFirst { it.id == activeTabId }.coerceAtLeast(0)
                    val payload = PortableBackupPayload(
                        createdAtEpochMillis = System.currentTimeMillis(),
                        sourceEdition = "android-full",
                        urls = urls,
                        selectedIndex = selectedIndex,
                        desktopSite = desktopModes[activeTabId] ?: false,
                    )
                    withContext(Dispatchers.IO) {
                        val encoded = PortableBackup.encode(payload, password)
                        contentResolver.openOutputStream(uri, "w")?.use { it.write(encoded) }
                            ?: error("Cannot open backup destination")
                    }
                }
            } finally {
                password.fill('\u0000')
            }
            Toast.makeText(
                this@BrowserActivity,
                if (result.isSuccess) R.string.backup_exported else R.string.backup_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun importBackup(uri: Uri, password: CharArray) {
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val encoded = contentResolver.openInputStream(uri)?.use(::readBoundedBackup)
                            ?: error("Cannot open backup")
                        PortableBackup.decode(encoded, password)
                    }
                }
            } finally {
                password.fill('\u0000')
            }
            result.onSuccess { backup ->
                val imported = backup.urls.map { repository.createTab(it) }
                val selected = imported[backup.selectedIndex]
                repository.selectTab(selected.id)
                showTab(selected.copy(selected = true))
                if (backup.desktopSite) applyDesktopSite(true)
                Toast.makeText(this@BrowserActivity, R.string.backup_imported, Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@BrowserActivity, R.string.backup_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun readBoundedBackup(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= PortableBackup.MAX_ENCODED_BYTES) { "Backup is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun saveBookmark() {
        val webView = activeWebView() ?: return
        val url = webView.url ?: return
        lifecycleScope.launch {
            repository.saveBookmark(url, webView.title.orEmpty())
            Toast.makeText(this@BrowserActivity, R.string.bookmark_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearPrivateData() {
        lifecycleScope.launch {
            sessions.keys.toList().forEach { destroySession(it, clearData = true) }
            rendererRecoveryUsed.clear()
            rendererRecoveryPending.clear()
            rendererRecoveryRunning.clear()
            activeTabId = 0
            WebStorage.getInstance().deleteAllData()
            clearLegacyWebViewCredentials()
            clearCookies()
            clearClientCertificates()
            showTab(repository.clearPrivateData(getString(R.string.app_website)))
            Toast.makeText(this@BrowserActivity, R.string.private_data_cleared, Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun clearCookies() = suspendCoroutine { continuation ->
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            continuation.resume(Unit)
        }
    }

    private suspend fun clearClientCertificates() = suspendCoroutine { continuation ->
        WebView.clearClientCertPreferences { continuation.resume(Unit) }
    }

    @Suppress("DEPRECATION")
    private fun clearLegacyWebViewCredentials() {
        WebViewDatabase.getInstance(this).apply {
            clearFormData()
            clearHttpAuthUsernamePassword()
            clearUsernamePassword()
        }
    }

    private fun openLibrary(mode: LibraryActivity.Mode) {
        val intent = Intent(this, LibraryActivity::class.java).putExtra(LibraryActivity.EXTRA_MODE, mode.value)
        libraryResult.launch(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.app_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_back)?.isEnabled = activeWebView()?.canGoBack() == true
        menu.findItem(R.id.action_forward)?.isEnabled = activeWebView()?.canGoForward() == true
        menu.findItem(R.id.action_desktop_site)?.isChecked = desktopModes[activeTabId] ?: false
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_tabs -> { startActivity(Intent(this, ActivityTabs::class.java)); true }
        R.id.action_new_tab -> { createTab(getString(R.string.app_website)); true }
        R.id.action_new_private_tab -> {
            if (PrivateProfileManager.isSupported()) {
                startActivity(Intent(this, PrivateBrowserActivity::class.java))
            } else {
                Toast.makeText(this, R.string.private_mode_unavailable, Toast.LENGTH_LONG).show()
            }
            true
        }
        R.id.action_back -> { activeWebView()?.goBack(); true }
        R.id.action_forward -> { activeWebView()?.goForward(); true }
        R.id.action_reload -> { activeWebView()?.reload(); true }
        R.id.action_share -> { sharePage(); true }
        R.id.action_desktop_site -> { requestDesktopSite(item); true }
        R.id.action_add_bookmark -> { saveBookmark(); true }
        R.id.action_bookmarks -> { openLibrary(LibraryActivity.Mode.BOOKMARKS); true }
        R.id.action_history -> { openLibrary(LibraryActivity.Mode.HISTORY); true }
        R.id.action_downloads -> { openLibrary(LibraryActivity.Mode.DOWNLOADS); true }
        R.id.action_export_backup -> { chooseBackupDestination(); true }
        R.id.action_import_backup -> { chooseBackupToImport(); true }
        R.id.action_sync -> { startActivity(Intent(this, SyncSettingsActivity::class.java)); true }
        R.id.action_remote_tabs -> { libraryResult.launch(Intent(this, RemoteTabsActivity::class.java)); true }
        R.id.action_clear_private_data -> { clearPrivateData(); true }
        R.id.action_app_settings -> {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
