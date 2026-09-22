package app.glance.wallet.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test
import timber.log.Timber

class SensitiveLogRedactorTest {
    @Test
    fun `redacts extended keys addresses and transaction ids`() {
        val sensitive = "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1RAPBrKM1z2f3f111111111111 " +
            "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh " +
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        val result = SensitiveLogRedactor.redact(sensitive)

        assertFalse(result.contains("xpub661"))
        assertFalse(result.contains("bc1qxy"))
        assertFalse(result.contains("0123456789abcdef"))
    }

    @Test
    fun `redacts testnet Bech32 addresses in debug logs`() {
        val address = "tb1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"

        assertFalse(SensitiveLogRedactor.redact(address).contains(address))
    }

    @Test
    fun `installs the redacting tree as the sole debug logger`() {
        Timber.uprootAll()
        try {
            SecurityLogging.plantRedactingDebugTree()

            assertEquals(1, Timber.treeCount)
        } finally {
            Timber.uprootAll()
        }
    }
}
