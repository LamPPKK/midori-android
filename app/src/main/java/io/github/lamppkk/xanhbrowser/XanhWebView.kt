package io.github.lamppkk.xanhbrowser

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView

/**
 * Stable Xanh-owned widget boundary for the serviced Android provider.
 *
 * The production API-26 application keeps this backend as an explicit fallback
 * until the WPE Android backend reaches capability and device parity. Callers
 * construct this type so a later backend cutover is localized and auditable.
 */
open class XanhWebView : WebView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attributes: AttributeSet?) : super(context, attributes)

    val engineInfo: XanhWebViewEngineInfo
        get() = XanhWebViewContract.engineInfo
}

data class XanhWebViewEngineInfo(
    val apiVersion: String,
    val backendId: String,
    val replacementTarget: String,
    val isFallback: Boolean,
)

object XanhWebViewContract {
    const val API_VERSION = "0.1.0-alpha.1"
    const val BACKEND_ID = "android-system-webview"
    const val REPLACEMENT_TARGET = "wpe-android"

    val engineInfo = XanhWebViewEngineInfo(
        apiVersion = API_VERSION,
        backendId = BACKEND_ID,
        replacementTarget = REPLACEMENT_TARGET,
        isFallback = true,
    )
}
