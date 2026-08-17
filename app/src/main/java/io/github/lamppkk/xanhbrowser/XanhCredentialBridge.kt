package io.github.lamppkk.xanhbrowser

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.core.net.toUri
import io.github.lamppkk.xanhbrowser.sync.BridgeEnvelope
import io.github.lamppkk.xanhbrowser.sync.BridgePolicy
import java.util.UUID
import org.json.JSONObject

/** Origin-bound, main-frame-only credential suggestion bridge.
 *
 * Pages can request a native chooser, but credentials are returned only after
 * an explicit user selection in [BrowserActivity]. The renderer never gets a
 * background or silent autofill API.
 */
internal class XanhCredentialBridge(
    private val activity: BrowserActivity,
    private val webView: WebView,
    private val tabId: Long,
) {
    private var committedUrl: String? = null
    private var navigationNonce = UUID.randomUUID().toString()
    private var scriptHandler: ScriptHandler? = null
    private var installed = false

    fun install() {
        if (installed || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(
            webView,
            BRIDGE_NAME,
            setOf("https://*"),
            WebViewCompat.WebMessageListener(::onMessage),
        )
        installed = true
    }

    fun navigationStarted(url: String?) {
        committedUrl = canonicalHttpsUrl(url)
        navigationNonce = UUID.randomUUID().toString()
        if (!installed || committedUrl == null ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            scriptHandler?.remove()
        }
        scriptHandler = WebViewCompat.addDocumentStartJavaScript(
            webView,
            bootstrapScript(tabId, navigationNonce),
            setOf(committedUrl!!.toUri().let { "${it.scheme}://${it.host}${portSuffix(it)}" }),
        )
    }

    fun navigationCommitted(url: String?) {
        committedUrl = canonicalHttpsUrl(url)
    }

    fun destroy() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            scriptHandler?.remove()
        }
        scriptHandler = null
        if (installed && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.removeWebMessageListener(webView, BRIDGE_NAME)
        }
        installed = false
        committedUrl = null
    }

    private fun onMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        reply: JavaScriptReplyProxy,
    ) {
        if (!isMainFrame || message.type != WebMessageCompat.TYPE_STRING) return
        val currentUrl = committedUrl ?: return
        val parsed = runCatching { JSONObject(message.data ?: return) }.getOrNull() ?: return
        val envelope = BridgeEnvelope(
            parsed.optLong("tabId", -1),
            parsed.optString("navigationNonce"),
            sourceOrigin.toString(),
            parsed.optString("messageType"),
        )
        if (!BridgePolicy.validate(
                envelope,
                tabId,
                navigationNonce,
                currentUrl,
                setOf("credential-request"),
            )
        ) return
        val requestUrl = view.url ?: return
        activity.showCredentialSuggestions(tabId, sourceOrigin.toString()) { response ->
            if (navigationNonce == envelope.navigationNonce && webView.url == requestUrl &&
                WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
            ) {
                reply.postMessage(response)
            }
        }
    }

    private fun bootstrapScript(tabId: Long, nonce: String): String = """
        (() => {
          if (window.top !== window || !window.$BRIDGE_NAME) return;
          let requestedFor = null;
          document.addEventListener('focusin', event => {
            const target = event.target;
            if (!(target instanceof HTMLInputElement) || target.type !== 'password') return;
            if (requestedFor === target) return;
            requestedFor = target;
            window.$BRIDGE_NAME.postMessage(JSON.stringify({
              tabId: $tabId,
              navigationNonce: '$nonce',
              messageType: 'credential-request'
            }));
          }, true);
          window.$BRIDGE_NAME.onmessage = event => {
            let credential;
            try { credential = JSON.parse(event.data); } catch (_) { return; }
            if (!credential || credential.type !== 'credential-selected') return;
            const password = document.querySelector('input[type="password"]');
            if (!password) return;
            const user = document.querySelector('input[autocomplete="username"], input[type="email"], input[type="text"]');
            if (user) {
              user.value = credential.username || '';
              user.dispatchEvent(new Event('input', { bubbles: true }));
            }
            password.value = credential.password || '';
            password.dispatchEvent(new Event('input', { bubbles: true }));
          };
        })();
    """.trimIndent()

    private fun canonicalHttpsUrl(value: String?): String? = value?.takeIf {
        val uri = it.toUri()
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
    }

    private fun portSuffix(uri: Uri): String = if (uri.port >= 0) ":${uri.port}" else ""

    companion object {
        private const val BRIDGE_NAME = "xanhCredentials"
    }
}
