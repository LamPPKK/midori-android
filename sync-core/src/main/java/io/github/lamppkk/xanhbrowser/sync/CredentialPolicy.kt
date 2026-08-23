package io.github.lamppkk.xanhbrowser.sync

import java.net.IDN
import java.net.URI
import mozilla.appservices.logins.Login
import mozilla.appservices.logins.LoginEntry

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
            isValidCredentialId(login.id) &&
            isValidUsername(login.username) &&
            isValidPassword(login.password) &&
            isValidField(login.usernameField) &&
            isValidField(login.passwordField) &&
            login.timesUsed >= 0 &&
            login.timeCreated >= 0 &&
            login.timeLastUsed >= 0 &&
            login.timePasswordChanged >= 0

    fun isSafeManagerCredential(login: Login): Boolean {
        return sanitizedCredential(login) != null
    }

    fun isAllowedMutation(entry: LoginEntry): Boolean = canonicalMutation(entry) != null

    internal fun canonicalMutation(entry: LoginEntry): LoginEntry? {
        val origin = canonicalHttpsOrigin(entry.origin, requireOriginOnly = true) ?: return null
        if (entry.httpRealm != null ||
            entry.formActionOrigin?.let {
                canonicalHttpsOrigin(it, requireOriginOnly = true)
            } != origin ||
            !isValidUsername(entry.username) ||
            !isValidPassword(entry.password) ||
            !isValidField(entry.usernameField) ||
            !isValidField(entry.passwordField)
        ) return null
        return entry.copy(origin = origin, formActionOrigin = origin)
    }

    internal fun sanitizedCredential(login: Login, expectedOrigin: String? = null): Login? {
        val origin = canonicalHttpsOrigin(login.origin, requireOriginOnly = true) ?: return null
        if (expectedOrigin != null && origin != expectedOrigin) return null
        if (!isEligibleCredential(login, origin)) return null
        return login.copy(origin = origin, formActionOrigin = origin)
    }

    fun isValidCredentialId(value: String?): Boolean = value != null &&
        value.toByteArray(Charsets.UTF_8).size in 1..MAX_CREDENTIAL_ID_BYTES &&
        value.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
        }

    private fun isValidUsername(value: String?): Boolean = value != null &&
        value.toByteArray(Charsets.UTF_8).size <= MAX_CREDENTIAL_USERNAME_BYTES &&
        '\u0000' !in value

    private fun isValidPassword(value: String?): Boolean = value != null &&
        value.isNotEmpty() &&
        value.toByteArray(Charsets.UTF_8).size <= MAX_CREDENTIAL_PASSWORD_BYTES &&
        '\u0000' !in value

    private fun isValidField(value: String?): Boolean = value != null &&
        value.toByteArray(Charsets.UTF_8).size <= MAX_CREDENTIAL_FIELD_BYTES &&
        value.none(Char::isISOControl)

    private fun parseHttps(value: String, originOnly: Boolean): URI? = value
        .takeIf {
            it.toByteArray(Charsets.UTF_8).size <= MAX_CREDENTIAL_ORIGIN_BYTES &&
                it.none(Char::isISOControl) && '\\' !in it
        }
        ?.let(::normalizeHttpsAuthority)
        ?.let { runCatching { URI(it) }.getOrNull() }
        ?.takeIf {
            it.isAbsolute && it.scheme.equals("https", true) && it.host != null &&
                it.userInfo == null && it.port in -1..65_535 &&
                (!originOnly ||
                    ((it.rawPath.isNullOrEmpty() || it.rawPath == "/") &&
                        it.rawQuery == null && it.rawFragment == null))
        }

    private fun normalizeHttpsAuthority(value: String): String? {
        val schemeEnd = value.indexOf("://")
        if (schemeEnd <= 0 || !value.substring(0, schemeEnd).equals("https", true)) return value
        val authorityStart = schemeEnd + 3
        val authorityEnd = value.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
            .takeUnless { it < 0 } ?: value.length
        val authority = value.substring(authorityStart, authorityEnd)
        if (authority.isEmpty() || '@' in authority) return null
        if (authority.startsWith('[')) return value
        if (authority.count { it == ':' } > 1) return null
        val separator = authority.lastIndexOf(':')
        val host = if (separator >= 0) authority.substring(0, separator) else authority
        val port = if (separator >= 0) authority.substring(separator) else ""
        if (host.isEmpty() || separator >= 0 &&
            (port.length == 1 || port.drop(1).any { !it.isDigit() })
        ) return null
        val asciiHost = runCatching {
            IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase()
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return buildString(value.length + asciiHost.length - host.length) {
            append(value, 0, authorityStart)
            append(asciiHost)
            append(port)
            append(value, authorityEnd, value.length)
        }
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

    const val MAX_CREDENTIAL_RESULTS = 100
    const val MAX_CREDENTIAL_ORIGIN_BYTES = 8_192
    const val MAX_CREDENTIAL_ID_BYTES = 128
    const val MAX_CREDENTIAL_USERNAME_BYTES = 1_024
    const val MAX_CREDENTIAL_PASSWORD_BYTES = 4_096
    const val MAX_CREDENTIAL_FIELD_BYTES = 256
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
