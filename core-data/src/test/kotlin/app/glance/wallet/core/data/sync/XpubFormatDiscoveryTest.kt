package app.glance.wallet.core.data.sync

import app.glance.wallet.core.crypto.ScriptType
import app.glance.wallet.core.crypto.parseWatchedKey
import app.glance.wallet.core.network.AddressBalance
import app.glance.wallet.core.network.AddressSnapshot
import app.glance.wallet.core.network.AddressStatus
import app.glance.wallet.core.network.ChainDataProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XpubFormatDiscoveryTest {
    @Test
    fun `detects the only active script type in batched external and internal scans`() {
        val nativeAddress = parseWatchedKey(XPUB, ScriptType.NATIVE_SEGWIT).derive(0, 0).address
        val chain = DiscoveryChain(setOf(nativeAddress))

        val result = XpubFormatDiscovery(chain, SyncConfig(gapLimit = 2)).discover(XPUB)

        assertEquals(XpubDiscoveryResult.Match(ScriptType.NATIVE_SEGWIT), result)
        assertEquals(2, chain.requests.size)
        assertTrue(chain.requests.all { it.size == 8 })
        assertEquals(1, chain.closeCalls)
    }

    @Test
    fun `closes its provider when a discovery request fails`() {
        val chain = DiscoveryChain(emptySet(), failRequests = true)

        assertTrue(runCatching {
            XpubFormatDiscovery(chain, SyncConfig(gapLimit = 1)).discover(XPUB)
        }.isFailure)

        assertEquals(1, chain.closeCalls)
    }

    @Test
    fun `does not guess when no address has activity`() {
        val result = XpubFormatDiscovery(DiscoveryChain(emptySet()), SyncConfig(gapLimit = 1)).discover(XPUB)

        assertEquals(XpubDiscoveryResult.NoMatch, result)
    }

    @Test
    fun `returns every matching type rather than using scan order`() {
        val native = parseWatchedKey(XPUB, ScriptType.NATIVE_SEGWIT).derive(0, 0).address
        val legacy = parseWatchedKey(XPUB, ScriptType.LEGACY).derive(0, 0).address

        val result = XpubFormatDiscovery(DiscoveryChain(setOf(native, legacy)), SyncConfig(gapLimit = 1)).discover(XPUB)

        assertEquals(XpubDiscoveryResult.Multiple(setOf(ScriptType.NATIVE_SEGWIT, ScriptType.LEGACY)), result)
    }

    private class DiscoveryChain(
        private val active: Set<String>,
        private val failRequests: Boolean = false,
    ) : ChainDataProvider {
        val requests = mutableListOf<List<String>>()
        var closeCalls = 0
        override fun fetchAddressStatuses(addresses: List<String>): Map<String, AddressStatus> {
            if (failRequests) error("Expected discovery failure")
            requests += addresses
            return addresses.associateWith { AddressStatus(if (it in active) "active" else "empty", it in active) }
        }
        override fun fetchAddress(address: String) = error("Not used by discovery")
        override fun tipHeight() = error("Not used by discovery")
        override fun blockTimestamp(blockHeight: Int) = error("Not used by discovery")
        override fun close() { closeCalls++ }
    }

    private companion object {
        const val XPUB = "xpub6CUGRUonZSQ4TWtTMmzXdrXDtypWKiKrhko4egpiMZbpiaQL2jkwSB1icqYh2cfDfVxdx4df189oLKnC5fSwqPfgyP3hooxujYzAu3fDVmz"
    }
}
