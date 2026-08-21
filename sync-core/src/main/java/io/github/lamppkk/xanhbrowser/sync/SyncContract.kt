package io.github.lamppkk.xanhbrowser.sync

enum class AccountState { DISCONNECTED, AUTHENTICATING, CONNECTED, AUTH_ISSUES }

enum class SyncEngine(val serviceName: String) {
    BOOKMARKS("bookmarks"),
    HISTORY("history"),
    TABS("tabs"),
    PASSWORDS("passwords"),
}

enum class SyncReason { STARTUP, MANUAL, SCHEDULED, LOCAL_CHANGE, PRE_SLEEP }

enum class SyncStatus {
    IDLE,
    RUNNING,
    SUCCESS,
    PARTIAL,
    NETWORK_ERROR,
    AUTH_ERROR,
    BACKED_OFF,
}

data class SyncSnapshot(
    val accountState: AccountState,
    val status: SyncStatus,
    val enabledEngines: Set<SyncEngine>,
    val lastSyncEpochSeconds: Long?,
    val nextSyncAllowedEpochSeconds: Long?,
    val vaultUnlocked: Boolean,
)

sealed interface AccountServer {
    data object Mozilla : AccountServer
    data class SelfHosted(val accountsUrl: String, val tokenServerUrl: String) : AccountServer
}

data class SyncConfiguration(
    val server: AccountServer,
    val clientId: String,
    val redirectUri: String,
    val deviceName: String,
) {
    fun validate() {
        require(clientId.isNotBlank()) { "clientId is empty" }
        require(deviceName.isNotBlank()) { "deviceName is empty" }
        val redirect = runCatching { java.net.URI(redirectUri) }.getOrNull()
        require(
            redirect?.scheme != null &&
                !redirect.scheme.equals("http", ignoreCase = true) &&
                redirect.host != null &&
                redirect.userInfo == null &&
                redirect.rawQuery == null &&
                redirect.fragment == null
        ) {
            "redirectUri must be an absolute non-cleartext callback without userinfo, query or fragment"
        }
        if (server is AccountServer.SelfHosted) {
            requireHttpsOrigin(server.accountsUrl, "accountsUrl")
            requireHttpsOrigin(server.tokenServerUrl, "tokenServerUrl")
        }
    }

    fun accountDomain(): String = when (val value = server) {
        AccountServer.Mozilla -> "accounts.firefox.com"
        is AccountServer.SelfHosted -> java.net.URI(value.accountsUrl).host
            ?: throw IllegalArgumentException("accountsUrl has no host")
    }

    /** Non-secret identity for rejecting persisted FxA state from another server/client. */
    internal fun persistenceIdentity(): String {
        val fields = when (val value = server) {
            AccountServer.Mozilla -> listOf("mozilla")
            is AccountServer.SelfHosted -> listOf("self-hosted", value.accountsUrl, value.tokenServerUrl)
        } + listOf(clientId, redirectUri)
        return fields.joinToString(separator = "") { "${it.length}:$it" }
    }

    private fun requireHttpsOrigin(value: String, name: String) {
        val uri = runCatching { java.net.URI(value) }.getOrNull()
        require(uri?.scheme.equals("https", ignoreCase = true) && uri?.host != null && uri.userInfo == null) {
            "$name must be an HTTPS origin without userinfo"
        }
    }
}

internal class SyncSchedule(
    var lastSyncEpochSeconds: Long? = null,
    var nextSyncAllowedEpochSeconds: Long? = null,
    var localChangeEpochSeconds: Long? = null,
) {
    fun due(reason: SyncReason, now: Long): Boolean {
        if (nextSyncAllowedEpochSeconds?.let { now < it } == true) return false
        return when (reason) {
            SyncReason.MANUAL, SyncReason.PRE_SLEEP -> true
            SyncReason.STARTUP, SyncReason.SCHEDULED ->
                lastSyncEpochSeconds?.let { now - it >= FOREGROUND_INTERVAL_SECONDS } ?: true
            SyncReason.LOCAL_CHANGE ->
                localChangeEpochSeconds?.let { now - it >= LOCAL_CHANGE_DEBOUNCE_SECONDS } ?: false
        }
    }

    companion object {
        const val FOREGROUND_INTERVAL_SECONDS = 15 * 60L
        const val LOCAL_CHANGE_DEBOUNCE_SECONDS = 30L
    }
}
