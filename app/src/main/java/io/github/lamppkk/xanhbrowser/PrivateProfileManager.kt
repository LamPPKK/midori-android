package io.github.lamppkk.xanhbrowser

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.annotation.UiThread
import androidx.lifecycle.ViewModel
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.UUID

/** Owns ephemeral WebView profiles without ever loading them through ProfileStore. */
internal object PrivateProfileManager {
    private const val PROFILE_PREFIX = "xanh-private-"
    private const val DELETION_RETRY_DELAY_MILLISECONDS = 250L
    private const val DELETION_RETRY_ATTEMPTS = 40
    private val validProfileName = Regex("^xanh-private-[0-9a-f]{32}$")

    fun isSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    fun newProfileName(): String = PROFILE_PREFIX + UUID.randomUUID().toString().replace("-", "")

    fun isPrivateProfileName(value: String): Boolean = validProfileName.matches(value)

    @UiThread
    @SuppressLint("RequiresFeature") // isSupported() is checked immediately above the API call.
    fun attach(webView: WebView, profileName: String) {
        check(isSupported()) { "This WebView provider does not support isolated profiles" }
        require(isPrivateProfileName(profileName)) { "Invalid private profile name" }
        // AndroidX requires this before navigation, script evaluation or other WebView use.
        WebViewCompat.setProfile(webView, profileName)
    }

    @UiThread
    fun delete(profileName: String): Boolean {
        if (!isSupported() || !isPrivateProfileName(profileName)) return false
        return runCatching { ProfileStore.getInstance().deleteProfile(profileName) }
            .getOrDefault(false)
    }

    /** WebView provider teardown can remain asynchronous for several seconds after destroy(). */
    @UiThread
    fun deleteWhenUnused(
        profileName: String,
        attemptsRemaining: Int = DELETION_RETRY_ATTEMPTS,
    ) {
        if (delete(profileName) || attemptsRemaining <= 0) return
        Handler(Looper.getMainLooper()).postDelayed(
            { deleteWhenUnused(profileName, attemptsRemaining - 1) },
            DELETION_RETRY_DELAY_MILLISECONDS,
        )
    }

    /** Called once at process start, before any private WebView can be alive. */
    @UiThread
    fun deleteStaleProfiles(): Int {
        if (!isSupported()) return 0
        val store = runCatching { ProfileStore.getInstance() }.getOrNull() ?: return 0
        return runCatching { store.allProfileNames }
            .getOrDefault(emptyList())
            .asSequence()
            .filter(::isPrivateProfileName)
            .count { runCatching { store.deleteProfile(it) }.getOrDefault(false) }
    }
}

internal class PrivateSessionViewModel : ViewModel() {
    val profileName: String = PrivateProfileManager.newProfileName()
    var currentUrl: String? = null
    var desktopMode: Boolean = false
    var rendererRecoveryUsed: Boolean = false
}
