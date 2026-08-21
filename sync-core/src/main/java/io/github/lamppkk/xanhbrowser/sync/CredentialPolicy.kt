package io.github.lamppkk.xanhbrowser.sync

import java.net.URI
import mozilla.appservices.logins.Login

data class CredentialContext(
    val documentUrl: String,
    val topFrameOrigin: String,
    val frameOrigin: String,
    val isPrivate: Boolean,
    val userSelected: Boolean,
)

object CredentialPolicy {
    fun isAllowed(context: CredentialContext, vaultUnlocked: Boolean): Boolean {
        if (!vaultUnlocked || context.isPrivate || !context.userSelected) return false
        val document = parseHttps(context.documentUrl, originOnly = false) ?: return false
        val top = parseHttps(context.topFrameOrigin, originOnly = true) ?: return false
        val frame = parseHttps(context.frameOrigin, originOnly = true) ?: return false
        return canonicalOrigin(document) == canonicalOrigin(top) &&
            canonicalOrigin(top) == canonicalOrigin(frame)
    }

    fun canonicalHttpsOrigin(value: String, requireOriginOnly: Boolean = false): String? =
        parseHttps(value, originOnly = requireOriginOnly)?.let(::canonicalOrigin)

    internal fun isEligibleCredential(login: Login, expectedOrigin: String): Boolean =
        login.httpRealm == null &&
            canonicalHttpsOrigin(login.origin, requireOriginOnly = true) == expectedOrigin &&
            login.formActionOrigin?.let {
                canonicalHttpsOrigin(it, requireOriginOnly = true)
            } == expectedOrigin &&
            login.id.toByteArray(Charsets.UTF_8).size in 1..MAX_CREDENTIAL_ID_BYTES &&
            login.id.all {
                it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
            } &&
            login.username.toByteArray(Charsets.UTF_8).size <= MAX_CREDENTIAL_USERNAME_BYTES &&
            login.password.isNotEmpty() &&
            login.password.toByteArray(Charsets.UTF_8).size <= MAX_CREDENTIAL_PASSWORD_BYTES &&
            login.usernameField.toByteArray(Charsets.UTF_8).size <= MAX_CREDENTIAL_FIELD_BYTES &&
            login.passwordField.toByteArray(Charsets.UTF_8).size <= MAX_CREDENTIAL_FIELD_BYTES &&
            login.usernameField.none(Char::isISOControl) &&
            login.passwordField.none(Char::isISOControl) &&
            login.timesUsed >= 0 &&
            login.timeCreated >= 0 &&
            login.timeLastUsed >= 0 &&
            login.timePasswordChanged >= 0

    private fun parseHttps(value: String, originOnly: Boolean): URI? = value
        .takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_CREDENTIAL_ORIGIN_BYTES }
        ?.let { runCatching { URI(it) }.getOrNull() }
        ?.takeIf {
            it.isAbsolute && it.scheme.equals("https", true) && it.host != null &&
                it.userInfo == null && it.port in -1..65_535 &&
                (!originOnly ||
                    ((it.rawPath.isNullOrEmpty() || it.rawPath == "/") &&
                        it.rawQuery == null && it.rawFragment == null))
        }

    private fun canonicalOrigin(uri: URI): String =
        URI(
            "https",
            null,
            uri.host.lowercase(),
            uri.port.takeUnless { it == 443 } ?: -1,
            null,
            null,
            null,
        ).toASCIIString()

    internal const val MAX_CREDENTIAL_RESULTS = 100
    private const val MAX_CREDENTIAL_ORIGIN_BYTES = 8_192
    private const val MAX_CREDENTIAL_ID_BYTES = 128
    private const val MAX_CREDENTIAL_USERNAME_BYTES = 1_024
    private const val MAX_CREDENTIAL_PASSWORD_BYTES = 4_096
    private const val MAX_CREDENTIAL_FIELD_BYTES = 256
}

data class BridgeEnvelope(
    val tabId: Long,
    val navigationNonce: String,
    val claimedOrigin: String,
    val messageType: String,
)

object BridgePolicy {
    fun validate(
        envelope: BridgeEnvelope,
        expectedTabId: Long,
        expectedNonce: String,
        committedUrl: String,
        allowedTypes: Set<String>,
    ): Boolean {
        if (envelope.tabId != expectedTabId || envelope.navigationNonce != expectedNonce) return false
        if (envelope.messageType !in allowedTypes) return false
        if (envelope.navigationNonce.length !in 1..128 || envelope.messageType.length !in 1..64) {
            return false
        }
        val committed = CredentialPolicy.canonicalHttpsOrigin(committedUrl) ?: return false
        val claimed = CredentialPolicy.canonicalHttpsOrigin(
            envelope.claimedOrigin,
            requireOriginOnly = true,
        ) ?: return false
        return committed == claimed
    }
}
