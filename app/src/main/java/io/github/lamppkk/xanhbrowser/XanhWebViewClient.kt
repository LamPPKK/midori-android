package io.github.lamppkk.xanhbrowser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.annotation.RequiresApi

@SuppressLint("MissingOnRenderProcessGone") // onRenderProcessGone is implemented below.
internal class XanhWebViewClient(
    private val activity: BrowserActivity,
    private val tabId: Long,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        when (request.url.scheme?.lowercase()) {
            "http", "https" -> !AddressResolver.isValidWebUrl(request.url.toString())
            "mailto", "tel", "geo", "market" -> {
                if (
                    request.isForMainFrame &&
                    request.hasGesture() &&
                    !request.isRedirect &&
                    AddressResolver.isExternal(request.url.toString())
                ) activity.openExternal(request.url) else true
            }
            else -> true
        }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        if (view == null || !activity.isCurrentSession(tabId, view)) return
        activity.onNavigationStarted(tabId, url)
        activity.onProgress(tabId, 0)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        if (view == null || !activity.isCurrentSession(tabId, view)) return
        activity.onNavigationCommitted(tabId, url)
        activity.onPageChanged(tabId, url, view?.title)
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
        handler.cancel()
        if (view != null && activity.isCurrentSession(tabId, view)) {
            Toast.makeText(activity, R.string.tls_error, Toast.LENGTH_LONG).show()
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
        Toast.makeText(activity, R.string.unsafe_page_blocked, Toast.LENGTH_LONG).show()
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        return activity.onRendererGone(tabId, view)
    }
}
