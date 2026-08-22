package io.github.lamppkk.xanhbrowser

import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

internal class XanhWebChromeClient(
    private val activity: BrowserActivity,
    private val tabId: Long,
    private val owner: WebView,
) : WebChromeClient() {
    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        if (view === owner && activity.isCurrentSession(tabId, owner)) activity.onProgress(tabId, newProgress)
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        if (view === owner && activity.isCurrentSession(tabId, owner)) {
            activity.onPageChanged(tabId, view.url, title)
        }
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean {
        if (webView !== owner || !activity.isCurrentSession(tabId, owner)) {
            filePathCallback.onReceiveValue(null)
            return false
        }
        return activity.chooseFiles(filePathCallback, fileChooserParams.acceptTypes)
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
        if (activity.isCurrentSession(tabId, owner)) activity.requestGeolocation(origin, callback)
        else callback.invoke(origin, false, false)
    }

    override fun onGeolocationPermissionsHidePrompt() {
        if (activity.isCurrentSession(tabId, owner)) activity.cancelGeolocation()
    }
}
