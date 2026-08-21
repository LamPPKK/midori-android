package io.github.lamppkk.xanhbrowser

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.lamppkk.xanhbrowser.sync.BridgeEnvelope
import io.github.lamppkk.xanhbrowser.sync.BridgePolicy
import io.github.lamppkk.xanhbrowser.sync.CredentialPolicy
import java.net.URI
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
            // Older Chromium WebView releases reject the non-standard
            // `https://*` rule. The listener transport accepts all origins;
            // onMessage remains fail-closed on HTTPS, the exact committed
            // origin, the main frame, tab ID and navigation nonce.
            setOf("*"),
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
        val origin = CredentialPolicy.canonicalHttpsOrigin(committedUrl!!) ?: return
        scriptHandler = WebViewCompat.addDocumentStartJavaScript(
            webView,
            bootstrapScript(tabId, navigationNonce),
            setOf(origin),
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
        val data = message.data ?: return
        if (data.length > MAX_BRIDGE_MESSAGE_CHARS) return
        val parsed = runCatching { JSONObject(data) }.getOrNull() ?: return
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
          let userGestureDeadline = 0;
          const noteUserGesture = () => { userGestureDeadline = performance.now() + 1500; };
          const requestCredential = target => {
            if (!(target instanceof HTMLInputElement) || target.type !== 'password') return;
            if (performance.now() > userGestureDeadline) return;
            userGestureDeadline = 0;
            requestedFor = target;
            window.$BRIDGE_NAME.postMessage(JSON.stringify({
              tabId: $tabId,
              navigationNonce: '$nonce',
              messageType: 'credential-request'
            }));
          };
          document.addEventListener('pointerdown', event => {
            if (!event.isTrusted) return;
            noteUserGesture();
            requestCredential(event.target);
          }, true);
          document.addEventListener('keydown', event => {
            if (event.isTrusted) noteUserGesture();
          }, true);
          document.addEventListener('focusin', event => {
            if (!event.isTrusted) return;
            requestCredential(event.target);
          }, true);
          window.$BRIDGE_NAME.onmessage = event => {
            let credential;
            try { credential = JSON.parse(event.data); } catch (_) { return; }
            if (!credential || credential.type !== 'credential-selected') return;
            const password = requestedFor;
            if (!(password instanceof HTMLInputElement) || !password.isConnected) return;
            const root = password.form || document;
            const user = root.querySelector('input[autocomplete="username"], input[type="email"], input[type="text"]');
            if (user) {
              user.value = credential.username || '';
              user.dispatchEvent(new Event('input', { bubbles: true }));
            }
            password.value = credential.password || '';
            password.dispatchEvent(new Event('input', { bubbles: true }));
            requestedFor = null;
          };
        })();
    """.trimIndent()

    private fun canonicalHttpsUrl(value: String?): String? {
        val candidate = value ?: return null
        return runCatching {
            val uri = URI(candidate)
            require(
                uri.scheme.equals("https", true) && uri.host != null && uri.userInfo == null &&
                    uri.port in -1..65_535,
            )
            uri.toASCIIString()
        }.getOrNull()
    }

    companion object {
        private const val BRIDGE_NAME = "xanhCredentials"
        private const val MAX_BRIDGE_MESSAGE_CHARS = 4_096
    }
}
