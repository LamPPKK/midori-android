package io.github.lamppkk.xanhbrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateProfileManagerTest {
    @Test fun `private profile names are unique bounded and allowlisted`() {
        val first = PrivateProfileManager.newProfileName()
        val second = PrivateProfileManager.newProfileName()

        assertNotEquals(first, second)
        assertTrue(first.length <= 64)
        assertTrue(PrivateProfileManager.isPrivateProfileName(first))
        assertTrue(PrivateProfileManager.isPrivateProfileName(second))
    }

    @Test fun `profile cleanup allowlist rejects unrelated or malformed names`() {
        assertFalse(PrivateProfileManager.isPrivateProfileName("Default"))
        assertFalse(PrivateProfileManager.isPrivateProfileName("xanh-private-../../Default"))
        assertFalse(PrivateProfileManager.isPrivateProfileName("xanh-private-ABCDEF"))
        assertFalse(PrivateProfileManager.isPrivateProfileName("xanh-private-" + "0".repeat(31)))
        assertFalse(PrivateProfileManager.isPrivateProfileName("xanh-private-" + "0".repeat(33)))
    }
}
