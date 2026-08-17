package io.github.lamppkk.xanhbrowser.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncContractTest {
    @Test
    fun `self hosted endpoints require https`() {
        val configuration = SyncConfiguration(
            AccountServer.SelfHosted("http://accounts.example", "https://sync.example/token"),
            "client",
            "xanh-browser://oauth",
            "Xanh Browser",
        )
        assertTrue(runCatching(configuration::validate).isFailure)
    }

    @Test
    fun `oauth redirect requires an absolute non cleartext callback`() {
        val base = SyncConfiguration(
            AccountServer.Mozilla,
            "client",
            "xanh-browser://accounts/oauth",
            "Xanh Browser",
        )
        assertTrue(runCatching(base::validate).isSuccess)
        assertTrue(runCatching { base.copy(redirectUri = "http://example.test/oauth").validate() }.isFailure)
        assertTrue(runCatching { base.copy(redirectUri = "javascript:alert(1)").validate() }.isFailure)
        assertTrue(runCatching { base.copy(redirectUri = "xanh-browser://user@accounts/oauth").validate() }.isFailure)
        assertTrue(runCatching { base.copy(redirectUri = "xanh-browser://accounts/oauth#secret").validate() }.isFailure)
    }

    @Test
    fun `persisted account identity changes with server client and redirect`() {
        val base = SyncConfiguration(
            AccountServer.Mozilla,
            "client-a",
            "xanh-browser://accounts/oauth",
            "Device A",
        )
        assertTrue(base.persistenceIdentity() == base.copy(deviceName = "Device B").persistenceIdentity())
        assertFalse(base.persistenceIdentity() == base.copy(clientId = "client-b").persistenceIdentity())
        assertFalse(
            base.persistenceIdentity() == base.copy(
                server = AccountServer.SelfHosted("https://accounts.example", "https://sync.example/token"),
            ).persistenceIdentity(),
        )
        assertFalse(
            base.persistenceIdentity() == base.copy(
                redirectUri = "xanh-browser-alt://accounts/oauth",
            ).persistenceIdentity(),
        )
    }

    @Test
    fun `schedule debounces local changes and respects backoff`() {
        val schedule = SyncSchedule(localChangeEpochSeconds = 100)
        assertFalse(schedule.due(SyncReason.LOCAL_CHANGE, 129))
        assertTrue(schedule.due(SyncReason.LOCAL_CHANGE, 130))
        schedule.nextSyncAllowedEpochSeconds = 200
        assertFalse(schedule.due(SyncReason.MANUAL, 199))
    }

    @Test
    fun `credential policy denies private http and cross origin frames`() {
        val valid = CredentialContext(
            "https://example.org/login",
            "https://example.org",
            "https://example.org",
            isPrivate = false,
            userSelected = true,
        )
        assertTrue(CredentialPolicy.isAllowed(valid, vaultUnlocked = true))
        assertFalse(CredentialPolicy.isAllowed(valid.copy(isPrivate = true), true))
        assertFalse(CredentialPolicy.isAllowed(valid.copy(documentUrl = "http://example.org"), true))
        assertFalse(CredentialPolicy.isAllowed(valid.copy(frameOrigin = "https://evil.example"), true))
    }

    @Test
    fun `bridge rejects stale nonce and forged origin`() {
        val message = BridgeEnvelope(7, "fresh", "https://example.org", "credential-request")
        assertTrue(BridgePolicy.validate(message, 7, "fresh", "https://example.org/login", setOf("credential-request")))
        assertFalse(BridgePolicy.validate(message.copy(navigationNonce = "stale"), 7, "fresh", "https://example.org", setOf("credential-request")))
        assertFalse(BridgePolicy.validate(message.copy(claimedOrigin = "https://evil.example"), 7, "fresh", "https://example.org", setOf("credential-request")))
    }
}
