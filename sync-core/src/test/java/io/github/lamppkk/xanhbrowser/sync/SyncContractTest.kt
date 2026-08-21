package io.github.lamppkk.xanhbrowser.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import mozilla.appservices.logins.DatabaseLoginsStorage
import mozilla.appservices.places.PlacesApi

class SyncContractTest {
    @Test
    fun `runtime lifecycle lets an entered operation finish before close`() {
        val gate = RuntimeLifecycleGate()
        val operationEntered = CountDownLatch(1)
        val releaseOperation = CountDownLatch(1)
        val cleanupEntered = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val operation = executor.submit<String> {
                gate.withOpen {
                    operationEntered.countDown()
                    check(releaseOperation.await(5, TimeUnit.SECONDS))
                    gate.withOpen { "finished" }
                }
            }
            assertTrue(operationEntered.await(5, TimeUnit.SECONDS))
            val close = executor.submit {
                gate.close {
                    cleanupEntered.countDown()
                    null
                }
            }

            assertFalse(cleanupEntered.await(100, TimeUnit.MILLISECONDS))
            releaseOperation.countDown()
            assertEquals("finished", operation.get(5, TimeUnit.SECONDS))
            close.get(5, TimeUnit.SECONDS)
            assertTrue(cleanupEntered.await(1, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { gate.withOpen { Unit } }

            var repeatedCleanup = false
            gate.close { repeatedCleanup = true; null }
            assertFalse(repeatedCleanup)
        } finally {
            releaseOperation.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `runtime lifecycle retains the original cleanup failure`() {
        val gate = RuntimeLifecycleGate()
        val failure = IllegalStateException("native cleanup failed")
        assertSame(
            failure,
            assertThrows(IllegalStateException::class.java) { gate.close { failure } },
        )
        assertSame(
            failure,
            assertThrows(IllegalStateException::class.java) { gate.close { null } },
        )
        assertThrows(IllegalStateException::class.java) { gate.withOpen { Unit } }
    }

    @Test
    fun `runtime API does not expose process global native engine handles`() {
        val forbiddenTypes = setOf(PlacesApi::class.java, DatabaseLoginsStorage::class.java)
        XanhSyncRuntime::class.java.methods.forEach { method ->
            assertFalse(
                "${method.name} returns a process-global native engine handle",
                forbiddenTypes.any { it.isAssignableFrom(method.returnType) },
            )
            assertFalse(
                "${method.name} accepts a process-global native engine handle",
                method.parameterTypes.any { parameter ->
                    forbiddenTypes.any { it.isAssignableFrom(parameter) }
                },
            )
        }
        XanhSyncRuntime::class.java.fields.forEach { field ->
            assertFalse(
                "${field.name} exposes a process-global native engine handle",
                forbiddenTypes.any { it.isAssignableFrom(field.type) },
            )
        }
    }

    @Test
    fun `application services registry permits only one live runtime lease`() {
        assertTrue(ApplicationServicesRuntimeRegistry.isAvailable())
        val first = ApplicationServicesRuntimeRegistry.acquire()
        try {
            assertFalse(ApplicationServicesRuntimeRegistry.isAvailable())
            assertThrows(IllegalStateException::class.java) {
                ApplicationServicesRuntimeRegistry.acquire()
            }
        } finally {
            first.close()
        }
        assertTrue(ApplicationServicesRuntimeRegistry.isAvailable())

        val replacement = ApplicationServicesRuntimeRegistry.acquire()
        try {
            // Closing a stale lease again must not release the replacement.
            first.close()
            assertThrows(IllegalStateException::class.java) {
                ApplicationServicesRuntimeRegistry.acquire()
            }
        } finally {
            replacement.close()
        }
        assertTrue(ApplicationServicesRuntimeRegistry.isAvailable())
    }

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
