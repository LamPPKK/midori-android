package io.github.lamppkk.xanhbrowser.sync

import java.net.URI

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
        val document = parse(context.documentUrl) ?: return false
        val top = parse(context.topFrameOrigin) ?: return false
        val frame = parse(context.frameOrigin) ?: return false
        if (!document.scheme.equals("https", true) || document.userInfo != null) return false
        return origin(document) == origin(top) && origin(top) == origin(frame)
    }

    private fun parse(value: String): URI? = runCatching { URI(value) }
        .getOrNull()
        ?.takeIf { it.host != null && it.scheme.equals("https", true) }

    private fun origin(uri: URI): Triple<String, String, Int> = Triple(
        uri.scheme.lowercase(),
        uri.host.lowercase(),
        if (uri.port >= 0) uri.port else 443,
    )
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
        val committed = runCatching { URI(committedUrl) }.getOrNull() ?: return false
        val claimed = runCatching { URI(envelope.claimedOrigin) }.getOrNull() ?: return false
        return committed.scheme.equals("https", true) &&
            claimed.scheme.equals("https", true) &&
            committed.host.equals(claimed.host, true) &&
            (if (committed.port >= 0) committed.port else 443) ==
            (if (claimed.port >= 0) claimed.port else 443)
    }
}
