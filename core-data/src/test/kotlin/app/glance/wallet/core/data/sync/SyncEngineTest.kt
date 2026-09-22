package app.glance.wallet.core.data.sync

import app.glance.wallet.core.data.db.AddressChain
import app.glance.wallet.core.data.db.ScriptType
import app.glance.wallet.core.data.db.WatchTargetType
import app.glance.wallet.core.network.AddressBalance
import app.glance.wallet.core.network.AddressSnapshot
import app.glance.wallet.core.network.AddressHistoryPage
import app.glance.wallet.core.network.AddressStatus
import app.glance.wallet.core.network.AddressTransaction
import app.glance.wallet.core.network.ChainDataProvider
import app.glance.wallet.core.network.FallbackChainDataProvider
import app.glance.wallet.core.network.NetworkUtxo
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.MnemonicCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEngineTest {

    @Test
    fun `single address sync reconciles only its fixed address without gap derivation`() = runBlocking {
        val address = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
        val store = MemoryStore(listOf(SyncWatchedKey("single", address, null, WatchTargetType.SINGLE_ADDRESS)))
        store.saveAddress(SyncAddress(keyId = "single", chain = AddressChain.EXTERNAL, index = 0, address = address))
        val chain = FakeChain(usedIndexes = emptySet(), fixedUsedAddress = address)
        val engine = SyncEngine(store, chain)

        engine.syncAll()
        engine.syncAll()

        assertEquals(listOf(address, address), chain.statusRequests.flatten())
        assertEquals(1, chain.detailRequests)
        assertEquals(listOf(0), store.addressIndexes("single", AddressChain.EXTERNAL))
        assertTrue(store.addressIndexes("single", AddressChain.INTERNAL).isEmpty())
    }

    @Test
    fun `single address retries signed history enrichment after a transient failure`() = runBlocking {
        val address = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
        val store = MemoryStore(listOf(SyncWatchedKey("single", address, null, WatchTargetType.SINGLE_ADDRESS)))
        store.saveAddress(SyncAddress(keyId = "single", chain = AddressChain.EXTERNAL, index = 0, address = address))
        val electrum = FakeChain(usedIndexes = emptySet(), fixedUsedAddress = address, historyValuePerAddress = 0, signedHistoryDeltas = false)
        val esplora = FakeChain(usedIndexes = emptySet(), fixedUsedAddress = address).apply { failOnDetailRequest = 1 }
        val engine = SyncEngine(store, electrum, historyEnricher = esplora)

        engine.syncAll()
        assertEquals(null, store.singleState("single")!!.address.lastStatus)

        esplora.failOnDetailRequest = null
        engine.syncAll()

        assertEquals(100, store.snapshotFor("single", AddressChain.EXTERNAL).history.single().valueSats)
    }

    @Test
    fun `loading more fixed-address history fetches two sequential Esplora pages and advances its cursor`() = runBlocking {
        val address = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
        val store = MemoryStore(listOf(SyncWatchedKey("single", address, null, WatchTargetType.SINGLE_ADDRESS)))
        store.saveAddress(SyncAddress(keyId = "single", chain = AddressChain.EXTERNAL, index = 0, address = address, historyNextCursor = "oldest", historyComplete = false))
        val esplora = FakeChain(usedIndexes = emptySet(), fixedUsedAddress = address).apply {
            pagedHistoryPages += AddressHistoryPage(List(25) { index -> AddressTransaction("older-$index", 99 - index, 2, 123) }, "older-24", false)
            pagedHistoryPages += AddressHistoryPage(listOf(AddressTransaction("oldest", 70, 2, 123)), null, true)
        }

        assertTrue(SyncEngine(store, FakeChain(emptySet()), historyEnricher = esplora).loadMoreSingleAddressHistory("single"))

        assertEquals(2, esplora.pagedHistoryRequests)
        assertEquals(null, store.singleState("single")!!.nextCursor)
        assertTrue(store.singleState("single")!!.isComplete)
    }

    @Test
    fun `single address sync initially caches up to two history pages`() = runBlocking {
        val address = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
        val store = MemoryStore(listOf(SyncWatchedKey("single", address, null, WatchTargetType.SINGLE_ADDRESS)))
        store.saveAddress(SyncAddress(keyId = "single", chain = AddressChain.EXTERNAL, index = 0, address = address))
        val esplora = FakeChain(usedIndexes = emptySet(), fixedUsedAddress = address, fixedTransactionCount = 33).apply {
            pagedHistoryPages += AddressHistoryPage(List(25) { index -> AddressTransaction("newest-$index", 200 - index, 2, 123) }, "newest-24", false)
            pagedHistoryPages += AddressHistoryPage(List(8) { index -> AddressTransaction("older-$index", 175 - index, 2, 123) }, null, true)
        }

        SyncEngine(store, FakeChain(emptySet(), fixedUsedAddress = address, fixedTransactionCount = 33), historyEnricher = esplora).syncAll()

        assertEquals(2, esplora.pagedHistoryRequests)
        assertEquals(33, store.snapshotFor("single", AddressChain.EXTERNAL).history.size)
        assertTrue(store.singleState("single")!!.isComplete)
    }

    @Test
    fun `high volume single address sync bypasses an Electrum history limit`() = runBlocking {
        val address = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
        val store = MemoryStore(listOf(SyncWatchedKey("single", address, null, WatchTargetType.SINGLE_ADDRESS)))
        store.saveAddress(SyncAddress(keyId = "single", chain = AddressChain.EXTERNAL, index = 0, address = address))
        val electrum = FakeChain(emptySet()).apply { failStatusRequests = true }
        val esplora = FakeChain(emptySet(), fixedUsedAddress = address, fixedTransactionCount = 2_000).apply {
            suppliesAddressSummary = true
            pagedHistoryPages += AddressHistoryPage(List(25) { index -> AddressTransaction("newest-$index", 200 - index, 2, 123) }, "newest-24", false)
            pagedHistoryPages += AddressHistoryPage(List(25) { index -> AddressTransaction("older-$index", 175 - index, 2, 123) }, "older-24", false)
        }

        SyncEngine(
            store = store,
            chainData = FallbackChainDataProvider(electrum, esplora),
            historyEnricher = esplora,
            singleAddressProvider = esplora,
        ).syncAll()

        assertEquals(1, electrum.statusBatches)
        assertEquals(0, electrum.stateRequests)
        assertEquals(1, esplora.statusBatches)
        assertEquals(2, esplora.pagedHistoryRequests)
        assertEquals(50, store.snapshotFor("single", AddressChain.EXTERNAL).history.size)
        assertEquals("older-24", store.singleState("single")!!.nextCursor)
        assertTrue(!store.singleState("single")!!.isComplete)
    }

    @Test
    fun `oversized single address uses its summary without downloading the UTXO snapshot`() = runBlocking {
        val address = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
        val store = MemoryStore(listOf(SyncWatchedKey("single", address, null, WatchTargetType.SINGLE_ADDRESS)))
        store.saveAddress(SyncAddress(keyId = "single", chain = AddressChain.EXTERNAL, index = 0, address = address))
        val state = FakeChain(emptySet(), fixedUsedAddress = address).apply { fixedUtxoCount = 1_001 }
        val history = FakeChain(emptySet(), fixedUsedAddress = address).apply {
            fixedUtxoCount = 1_001
            suppliesAddressSummary = true
        }

        val result = SyncEngine(
            store = store,
            chainData = state,
            historyEnricher = history,
            singleAddressProvider = history,
            singleAddressStateProvider = state,
        ).syncAll()

        assertEquals(0, state.stateRequests)
        assertTrue(store.singleState("single")!!.address.isUtxoSnapshotSuppressed)
        assertEquals(100L, result.balance.confirmedSats)
    }

    @Test
    fun `single address at the output cutoff retains its detailed UTXO snapshot`() = runBlocking {
        val address = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
        val store = MemoryStore(listOf(SyncWatchedKey("single", address, null, WatchTargetType.SINGLE_ADDRESS)))
        store.saveAddress(SyncAddress(keyId = "single", chain = AddressChain.EXTERNAL, index = 0, address = address))
        val state = FakeChain(emptySet(), fixedUsedAddress = address).apply { fixedUtxoCount = 1_000 }
        val history = FakeChain(emptySet(), fixedUsedAddress = address).apply {
            fixedUtxoCount = 1_000
            suppliesAddressSummary = true
        }

        SyncEngine(store, state, historyEnricher = history, singleAddressProvider = history, singleAddressStateProvider = state).syncAll()

        assertEquals(1, state.stateRequests)
        assertTrue(!store.singleState("single")!!.address.isUtxoSnapshotSuppressed)
    }

    @Test
    fun `single address state uses its dedicated Electrum provider after status falls back to Esplora`() = runBlocking {
        val address = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
        val store = MemoryStore(listOf(SyncWatchedKey("single", address, null, WatchTargetType.SINGLE_ADDRESS)))
        store.saveAddress(SyncAddress(keyId = "single", chain = AddressChain.EXTERNAL, index = 0, address = address))
        val statusElectrum = FakeChain(emptySet()).apply { failStatusRequests = true }
        val statusEsplora = FakeChain(emptySet(), fixedUsedAddress = address)
        val historyEsplora = FakeChain(emptySet(), fixedUsedAddress = address)
        val stateElectrum = FakeChain(emptySet(), fixedUsedAddress = address)

        SyncEngine(
            store = store,
            chainData = FallbackChainDataProvider(statusElectrum, statusEsplora),
            historyEnricher = historyEsplora,
            singleAddressProvider = historyEsplora,
            singleAddressStateProvider = stateElectrum,
        ).syncAll()

        assertEquals(1, statusEsplora.statusBatches)
        assertEquals(0, statusEsplora.stateRequests)
        assertEquals(1, stateElectrum.stateRequests)
    }

    @Test
    fun `single address status falls back to Electrum when Esplora rejects the summary`() = runBlocking {
        val address = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
        val store = MemoryStore(listOf(SyncWatchedKey("single", address, null, WatchTargetType.SINGLE_ADDRESS)))
        store.saveAddress(SyncAddress(keyId = "single", chain = AddressChain.EXTERNAL, index = 0, address = address))
        val electrum = FakeChain(emptySet(), fixedUsedAddress = address)
        val esplora = FakeChain(emptySet(), fixedUsedAddress = address, fixedTransactionCount = 2_000).apply {
            failStatusRequests = true
            pagedHistoryPages += AddressHistoryPage(List(25) { index -> AddressTransaction("newest-$index", 200 - index, 2, 123) }, "newest-24", false)
            pagedHistoryPages += AddressHistoryPage(List(25) { index -> AddressTransaction("older-$index", 175 - index, 2, 123) }, "older-24", false)
        }

        SyncEngine(
            store = store,
            chainData = FallbackChainDataProvider(electrum, esplora),
            historyEnricher = esplora,
            singleAddressProvider = esplora,
        ).syncAll()

        assertEquals(1, electrum.statusBatches)
        assertEquals(1, electrum.stateRequests)
        assertEquals(1, esplora.statusBatches)
        assertEquals(2, esplora.pagedHistoryRequests)
        assertEquals(50, store.snapshotFor("single", AddressChain.EXTERNAL).history.size)
    }

    @Test
    fun `single address keeps its fallback state when Esplora history is unavailable`() = runBlocking {
        val address = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT"
        val store = MemoryStore(listOf(SyncWatchedKey("single", address, null, WatchTargetType.SINGLE_ADDRESS)))
        store.saveAddress(SyncAddress(keyId = "single", chain = AddressChain.EXTERNAL, index = 0, address = address))
        val chain = FakeChain(emptySet(), fixedUsedAddress = address)
        val esplora = FakeChain(emptySet(), fixedUsedAddress = address).apply { failPagedHistory = true }

        val result = SyncEngine(store, chain, historyEnricher = esplora).syncAll()

        assertEquals(100L, result.balance.confirmedSats)
        assertTrue(store.snapshotFor("single", AddressChain.EXTERNAL).history.isEmpty())
        assertEquals(null, store.singleState("single")!!.address.lastStatus)
    }

    @Test
    fun `single address repairs a completed history cache whose count exceeds the cached limit`() = runBlocking {
        val address = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
        val store = MemoryStore(listOf(SyncWatchedKey("single", address, null, WatchTargetType.SINGLE_ADDRESS)))
        store.saveAddress(SyncAddress(
            keyId = "single",
            chain = AddressChain.EXTERNAL,
            index = 0,
            address = address,
            isUsed = true,
            lastStatus = "used",
            historyRemoteCount = 78,
            historyComplete = true,
        ))
        val chain = FakeChain(emptySet(), fixedUsedAddress = address)
        val esplora = FakeChain(emptySet(), fixedUsedAddress = address)

        SyncEngine(store, chain, historyEnricher = esplora).syncAll()

        assertEquals(1, esplora.pagedHistoryRequests)
        assertTrue(store.snapshotFor("single", AddressChain.EXTERNAL).history.isNotEmpty())
    }

    @Test
    fun `single address import caps a mixed mempool and chain page at fifty history rows`() = runBlocking {
        val address = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
        val store = MemoryStore(listOf(SyncWatchedKey("single", address, null, WatchTargetType.SINGLE_ADDRESS)))
        store.saveAddress(SyncAddress(keyId = "single", chain = AddressChain.EXTERNAL, index = 0, address = address))
        val esplora = FakeChain(emptySet(), fixedUsedAddress = address, fixedTransactionCount = 2_000).apply {
            pagedHistoryPages += AddressHistoryPage(
                List(75) { index -> AddressTransaction("newest-$index", 200 - index, 2, 123) },
                "newest-74",
                false,
            )
        }

        SyncEngine(
            store,
            FakeChain(emptySet(), fixedUsedAddress = address, fixedTransactionCount = 2_000),
            historyEnricher = esplora,
        ).syncAll()

        assertEquals(50, store.snapshotFor("single", AddressChain.EXTERNAL).history.size)
        assertEquals("newest-74", store.singleState("single")!!.nextCursor)
        assertTrue(!store.singleState("single")!!.isComplete)
    }

    @Test
    fun `an empty final fixed-address history page completes successfully`() = runBlocking {
        val address = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
        val store = MemoryStore(listOf(SyncWatchedKey("single", address, null, WatchTargetType.SINGLE_ADDRESS)))
        store.saveAddress(SyncAddress(keyId = "single", chain = AddressChain.EXTERNAL, index = 0, address = address, historyNextCursor = "oldest", historyComplete = false))
        val esplora = FakeChain(usedIndexes = emptySet(), fixedUsedAddress = address).apply {
            pagedHistory = AddressHistoryPage(emptyList(), null, true)
        }

        assertTrue(SyncEngine(store, FakeChain(emptySet()), historyEnricher = esplora).loadMoreSingleAddressHistory("single"))

        assertTrue(store.singleState("single")!!.isComplete)
    }

    @Test
    fun `syncing one watched key leaves other watched keys untouched`() = runBlocking {
        val store = MemoryStore(
            listOf(
                SyncWatchedKey("first", XPUB, ScriptType.NATIVE_SEGWIT),
                SyncWatchedKey("second", XPUB, ScriptType.NATIVE_SEGWIT),
            ),
        )
        val chain = FakeChain(usedIndexes = emptySet())

        SyncEngine(store, chain).syncKey("first")

        assertEquals(2, chain.statusBatches)
        assertTrue(store.addressIndexes("second", AddressChain.EXTERNAL).isEmpty())
        assertTrue(store.addressIndexes("second", AddressChain.INTERNAL).isEmpty())
    }
    @Test
    fun `legacy initial discovery continues through the trailing gap after 28 used addresses`() = runBlocking {
        val store = MemoryStore(listOf(SyncWatchedKey("legacy", LEGACY_XPUB, ScriptType.LEGACY)))
        val chain = FakeChain(
            usedIndexes = (0..27).toSet(),
            keyMaterial = LEGACY_XPUB,
            scriptType = app.glance.wallet.core.crypto.ScriptType.LEGACY,
        )

        SyncEngine(store, chain).syncAll()

        assertEquals(27, store.key("legacy").externalHighestUsedIndex)
        assertEquals(47, store.key("legacy").externalScannedThroughIndex)
        assertEquals((0..47).toList(), store.addressIndexes("legacy", AddressChain.EXTERNAL))
        assertEquals((0..27).toSet(), store.usedIndexes("legacy", AddressChain.EXTERNAL))
    }

    @Test
    fun `interrupted discovery resumes without marking an incomplete window complete`() = runBlocking {
        val store = MemoryStore(listOf(SyncWatchedKey("legacy", LEGACY_XPUB, ScriptType.LEGACY)))
        val chain = FakeChain(
            usedIndexes = (0..3).toSet(),
            keyMaterial = LEGACY_XPUB,
            scriptType = app.glance.wallet.core.crypto.ScriptType.LEGACY,
        ).apply { failOnDetailRequest = 2 }
        val engine = SyncEngine(store, chain)

        check(runCatching { engine.syncAll() }.isFailure)
        assertEquals(-1, store.key("legacy").externalScannedThroughIndex)

        chain.failOnDetailRequest = null
        engine.syncAll()

        assertEquals(3, store.key("legacy").externalHighestUsedIndex)
        assertEquals(23, store.key("legacy").externalScannedThroughIndex)
    }

    @Test
    fun `second sync only fetches details whose status changed`() = runBlocking {
        val store = MemoryStore(
            listOf(SyncWatchedKey("key", XPUB, ScriptType.NATIVE_SEGWIT)),
        )
        val chain = FakeChain(usedIndexes = setOf(0))
        val engine = SyncEngine(store, chain, SyncConfig(gapLimit = 2))

        engine.syncAll()
        assertEquals(2, chain.detailRequests)

        engine.syncAll()
        assertEquals(2, chain.detailRequests)
        assertEquals(6, chain.statusBatches)

        chain.changed = true
        engine.syncAll()
        assertEquals(4, chain.detailRequests)
    }

    @Test
    fun `repeat sync skips confirmed unused addresses except the current external receive address`() = runBlocking {
        val store = MemoryStore(listOf(SyncWatchedKey("key", XPUB, ScriptType.NATIVE_SEGWIT)))
        val chain = FakeChain(usedIndexes = setOf(0))
        val engine = SyncEngine(store, chain, SyncConfig(gapLimit = 2))

        engine.syncAll()
        chain.statusRequests.clear()

        engine.syncAll()

        assertEquals(2, chain.statusRequests.size)
        assertEquals(3, chain.statusRequests.flatten().size)
    }

    @Test
    fun `repeat sync rechecks the current receive address after it was cached unused`() = runBlocking {
        val store = MemoryStore(listOf(SyncWatchedKey("key", XPUB, ScriptType.NATIVE_SEGWIT)))
        val chain = FakeChain(usedIndexes = emptySet())
        val engine = SyncEngine(store, chain, SyncConfig(gapLimit = 2))

        engine.syncAll()
        chain.markIndexUsed(0)
        chain.statusRequests.clear()

        engine.syncAll()

        assertEquals(setOf(0), store.usedIndexes("key", AddressChain.EXTERNAL))
        assertTrue(chain.statusRequests.flatten().isNotEmpty())
    }

    @Test
    fun `snapshot-write failure leaves a changed address retryable`() = runBlocking {
        val store = MemoryStore(listOf(SyncWatchedKey("key", XPUB, ScriptType.NATIVE_SEGWIT))).apply {
            failNextSnapshotWrite = true
        }
        val chain = FakeChain(usedIndexes = setOf(0))
        val engine = SyncEngine(store, chain, SyncConfig(gapLimit = 1))

        assertTrue(runCatching { engine.syncAll() }.isFailure)
        engine.syncAll()

        assertEquals(200, store.dashboardBalance().confirmedSats)
    }

    @Test
    fun `confirmation counts advance when an address status is unchanged`() = runBlocking {
        val store = MemoryStore(listOf(SyncWatchedKey("key", XPUB, ScriptType.NATIVE_SEGWIT)))
        val chain = FakeChain(usedIndexes = setOf(0), tipHeight = 100)
        val engine = SyncEngine(store, chain, SyncConfig(gapLimit = 1))

        engine.syncAll()
        chain.tipHeight = 101
        engine.syncAll()

        assertEquals(2, store.snapshotFor("key", AddressChain.EXTERNAL).utxos.single().confirmations)
    }

    @Test
    fun `aggregates balances from independent watched keys`() = runBlocking {
        val store = MemoryStore(
            listOf(
                SyncWatchedKey("first", XPUB, ScriptType.NATIVE_SEGWIT),
                SyncWatchedKey("second", XPUB, ScriptType.NATIVE_SEGWIT),
            ),
        )
        val chain = FakeChain(usedIndexes = setOf(0), confirmedPerAddress = 10, unconfirmedPerAddress = 2)

        val result = SyncEngine(store, chain, SyncConfig(gapLimit = 1)).syncAll()

        assertEquals(40, result.balance.confirmedSats)
        assertEquals(8, result.balance.unconfirmedSats)
    }

    @Test
    fun `block timestamps are fetched once per confirmed height`() = runBlocking {
        val store = MemoryStore(listOf(SyncWatchedKey("key", XPUB, ScriptType.NATIVE_SEGWIT)))
        val chain = FakeChain(usedIndexes = setOf(0))
        val engine = SyncEngine(store, chain, SyncConfig(gapLimit = 1))

        engine.syncAll()
        engine.syncAll()

        assertEquals(1, chain.timestampRequests)
    }

    @Test
    fun `timestamp cache failures do not pause address discovery`() = runBlocking {
        val store = MemoryStore(listOf(SyncWatchedKey("key", XPUB, ScriptType.NATIVE_SEGWIT)))
        val chain = FakeChain(usedIndexes = setOf(0)).apply { failTimestamp = true }

        val result = SyncEngine(store, chain, SyncConfig(gapLimit = 1)).syncAll()

        assertEquals(200, result.balance.confirmedSats)
        assertEquals(1, store.key("key").externalScannedThroughIndex)
        assertEquals(1, store.key("key").internalScannedThroughIndex)
    }

    @Test
    fun `later unchanged sync retries an uncached block timestamp`() = runBlocking {
        val store = MemoryStore(listOf(SyncWatchedKey("key", XPUB, ScriptType.NATIVE_SEGWIT)))
        val chain = FakeChain(usedIndexes = setOf(0)).apply { failTimestamp = true }
        val engine = SyncEngine(store, chain, SyncConfig(gapLimit = 1))

        engine.syncAll()
        chain.failTimestamp = false
        engine.syncAll()

        assertEquals(3, chain.timestampRequests)
        assertTrue(store.hasCachedTimestamp(100))
    }

    @Test
    fun `Electrum-style history is enriched before it replaces signed cached deltas`() = runBlocking {
        val store = MemoryStore(listOf(SyncWatchedKey("key", XPUB, ScriptType.NATIVE_SEGWIT)))
        val electrum = FakeChain(
            usedIndexes = setOf(0),
            confirmedPerAddress = 100,
            historyValuePerAddress = 0,
            signedHistoryDeltas = false,
        )
        val esplora = FakeChain(
            usedIndexes = setOf(0),
            confirmedPerAddress = 100,
            historyValuePerAddress = 100,
            signedHistoryDeltas = true,
        )

        SyncEngine(
            store = store,
            chainData = electrum,
            config = SyncConfig(gapLimit = 1),
            historyEnricher = esplora,
        ).syncAll()

        assertEquals(100, store.snapshotFor("key", AddressChain.EXTERNAL).history.single().valueSats)
    }

    @Test
    fun `unsigned history is not persisted when signed enrichment is unavailable`() = runBlocking {
        val store = MemoryStore(listOf(SyncWatchedKey("key", XPUB, ScriptType.NATIVE_SEGWIT)))
        val electrum = FakeChain(
            usedIndexes = setOf(0),
            historyValuePerAddress = 0,
            signedHistoryDeltas = false,
        )

        assertTrue(runCatching {
            SyncEngine(store, electrum, SyncConfig(gapLimit = 1)).syncAll()
        }.isFailure)
        assertEquals(-1, store.key("key").externalScannedThroughIndex)
    }
}

private class FakeChain(
    usedIndexes: Set<Int>,
    private val confirmedPerAddress: Long = 100,
    private val unconfirmedPerAddress: Long = 0,
    private val historyValuePerAddress: Long = confirmedPerAddress,
    private val signedHistoryDeltas: Boolean = true,
    keyMaterial: String = XPUB,
    scriptType: app.glance.wallet.core.crypto.ScriptType = app.glance.wallet.core.crypto.ScriptType.NATIVE_SEGWIT,
    private val fixedUsedAddress: String? = null,
    private val fixedTransactionCount: Int? = null,
    var tipHeight: Int = 100,
) : ChainDataProvider {
    var detailRequests = 0
    var statusBatches = 0
    var failStatusRequests = false
    var stateRequests = 0
    var failStateRequests = false
    var fixedUtxoCount = 1
    var suppliesAddressSummary = false
    var failPagedHistory = false
    var timestampRequests = 0
    var changed = false
    var failOnDetailRequest: Int? = null
    var failTimestamp = false
    var pagedHistory: AddressHistoryPage? = null
    val pagedHistoryPages = ArrayDeque<AddressHistoryPage>()
    var pagedHistoryRequests = 0
    val statusRequests = mutableListOf<List<String>>()
    private val usedIndexes = usedIndexes.toMutableSet()
    private val usedAddresses = mutableSetOf<String>()
    private val addressIndexes = buildMap {
        val key = app.glance.wallet.core.crypto.parseWatchedKey(
            keyMaterial,
            scriptType,
        )
        AddressChain.entries.forEach { chain ->
            (0..100).forEach { index ->
                put(key.derive(if (chain == AddressChain.EXTERNAL) 0 else 1, index.toLong()).address, index)
            }
        }
    }

    override fun fetchAddressStatuses(addresses: List<String>): Map<String, AddressStatus> {
        statusBatches++
        if (failStatusRequests) error("Electrum history limit")
        statusRequests += addresses
        usedAddresses += addresses.filter { addressIndexes[it] in usedIndexes || it == fixedUsedAddress }
        return addresses.mapIndexed { index, address ->
                address to AddressStatus(
                when {
                    changed && address in usedAddresses -> "used-later"
                    address in usedAddresses -> "used"
                    else -> "unused"
                },
                    address in usedAddresses,
                    transactionCount = fixedTransactionCount,
                    balance = if (suppliesAddressSummary) if (address in usedAddresses) AddressBalance(confirmedPerAddress, unconfirmedPerAddress) else AddressBalance(0, 0) else null,
                    unspentOutputCount = if (suppliesAddressSummary) if (address in usedAddresses) fixedUtxoCount else 0 else null,
                )
        }
            .toMap()
    }

    override fun fetchAddress(address: String): AddressSnapshot {
        detailRequests++
        if (detailRequests == failOnDetailRequest) error("test network failure")
        val used = addressIndexes[address] in usedIndexes || address == fixedUsedAddress
        return AddressSnapshot(
            balance = if (used) AddressBalance(confirmedPerAddress, unconfirmedPerAddress) else AddressBalance(0, 0),
            history = if (used) listOf(AddressTransaction("tx-$detailRequests", 100, tipHeight - 99, historyValuePerAddress)) else emptyList(),
            utxos = if (used) List(fixedUtxoCount) { index -> NetworkUtxo("tx-$detailRequests-$index", index, confirmedPerAddress, tipHeight - 99, 100) } else emptyList(),
            tipHeight = tipHeight,
            hasSignedHistoryDeltas = signedHistoryDeltas,
        )
    }

    override fun fetchAddressState(address: String): AddressSnapshot {
        stateRequests++
        if (failStateRequests) error("Esplora UTXO limit")
        return fetchAddress(address).copy(history = emptyList())
    }

    override fun fetchAddressHistoryPage(address: String, cursor: String?): AddressHistoryPage {
        pagedHistoryRequests++
        if (failPagedHistory) error("Esplora history unavailable")
        if (pagedHistoryPages.isNotEmpty()) return pagedHistoryPages.removeFirst()
        return pagedHistory ?: fetchAddress(address).let { snapshot ->
            AddressHistoryPage(snapshot.history, null, true)
        }
    }

    override fun blockTimestamp(blockHeight: Int): Long {
        timestampRequests++
        if (failTimestamp) error("test timestamp failure")
        return 1_700_000_000L
    }

    override fun tipHeight(): Int = tipHeight

    fun markIndexUsed(index: Int) {
        usedIndexes += index
    }
}

private class MemoryStore(keys: List<SyncWatchedKey>) : WalletSyncStore {
    private val keyList = keys.toMutableList()
    private val addresses = mutableListOf<SyncAddress>()
    private val timestamps = mutableSetOf<Int>()
    private val snapshots = mutableMapOf<Long, AddressSnapshot>()
    var failNextSnapshotWrite = false

    override suspend fun watchedKeys(): List<SyncWatchedKey> = keyList.toList()
    override suspend fun addresses(keyId: String, chain: AddressChain): List<SyncAddress> = addresses.filter { it.keyId == keyId && it.chain == chain }
    override suspend fun saveAddress(address: SyncAddress): SyncAddress {
        val saved = address.copy(id = address.id ?: (addresses.size + 1).toLong())
        addresses.removeAll { it.id == saved.id }
        addresses += saved
        return saved
    }
    override suspend fun updateHighestUsedIndex(keyId: String, chain: AddressChain, index: Int) {
        keyList.replaceAll { key ->
            if (key.id != keyId) key else if (chain == AddressChain.EXTERNAL) {
                key.copy(externalHighestUsedIndex = index)
            } else {
                key.copy(internalHighestUsedIndex = index)
            }
        }
    }
    override suspend fun updateScannedThroughIndex(keyId: String, chain: AddressChain, index: Int) {
        keyList.replaceAll { key ->
            if (key.id != keyId) key else if (chain == AddressChain.EXTERNAL) {
                key.copy(externalScannedThroughIndex = index)
            } else {
                key.copy(internalScannedThroughIndex = index)
            }
        }
    }
    override suspend fun saveUsedAddressAndReplaceSnapshot(address: SyncAddress, snapshot: AddressSnapshot): SyncAddress {
        if (failNextSnapshotWrite) {
            failNextSnapshotWrite = false
            error("test snapshot persistence failure")
        }
        val saved = saveAddress(address)
        snapshots[saved.id!!] = snapshot
        return saved
    }
    override suspend fun saveSingleAddressSnapshot(address: SyncAddress, snapshot: AddressSnapshot, remoteCount: Int?, nextCursor: String?, isComplete: Boolean, suppressUtxoSnapshot: Boolean, unspentOutputCount: Int?): SyncAddress =
        saveUsedAddressAndReplaceSnapshot(
            address.copy(
                historyRemoteCount = remoteCount,
                historyNextCursor = nextCursor,
                historyComplete = isComplete,
                isUtxoSnapshotSuppressed = suppressUtxoSnapshot,
                cachedConfirmedBalanceSats = snapshot.balance.confirmedSats,
                cachedUnconfirmedBalanceSats = snapshot.balance.unconfirmedSats,
                unspentOutputCount = unspentOutputCount,
            ),
            snapshot,
        )
    override suspend fun singleAddressHistoryState(keyId: String): SingleAddressHistoryState? =
        addresses.singleOrNull { it.keyId == keyId && it.chain == AddressChain.EXTERNAL }?.let {
            SingleAddressHistoryState(it, it.historyRemoteCount, it.historyNextCursor, it.historyComplete)
        }
    override suspend fun mergeSingleAddressHistoryPage(state: SingleAddressHistoryState, page: AddressHistoryPage): SingleAddressHistoryState {
        val updated = state.address.copy(historyNextCursor = page.nextCursor, historyComplete = page.isComplete)
        saveAddress(updated)
        return SingleAddressHistoryState(updated, updated.historyRemoteCount, page.nextCursor, page.isComplete)
    }
    override suspend fun refreshConfirmations(keyIds: List<String>, tipHeight: Int) {
        addresses.filter { it.keyId in keyIds }.forEach { address ->
            snapshots[address.id]?.let { snapshot ->
                snapshots[address.id!!] = snapshot.copy(
                    history = snapshot.history.map { item -> item.copy(confirmations = item.blockHeight?.let { (tipHeight - it + 1).coerceAtLeast(0) } ?: 0) },
                    utxos = snapshot.utxos.map { item -> item.copy(confirmations = item.blockHeight?.let { (tipHeight - it + 1).coerceAtLeast(0) } ?: 0) },
                )
            }
        }
    }
    override suspend fun hasTimestamp(height: Int): Boolean = height in timestamps
    override suspend fun saveTimestamp(height: Int, timestamp: Long) { timestamps += height }
    override suspend fun missingTimestampHeights(keyIds: List<String>): List<Int> = addresses
        .filter { it.keyId in keyIds }
        .flatMap { address -> address.id?.let(snapshots::get)?.history.orEmpty() }
        .filter { it.confirmations > 0 }
        .mapNotNull { it.blockHeight }
        .distinct()
        .filterNot(timestamps::contains)
    override suspend fun dashboardBalance(): DashboardBalance = snapshots.values.fold(DashboardBalance()) { total, next ->
        total + DashboardBalance(next.balance.confirmedSats, next.balance.unconfirmedSats)
    }
    override fun observeDashboardBalance(): Flow<DashboardBalance> = flowOf(DashboardBalance())

    fun key(id: String): SyncWatchedKey = keyList.single { it.id == id }
    fun addressIndexes(keyId: String, chain: AddressChain): List<Int> =
        addresses.filter { it.keyId == keyId && it.chain == chain }.map { it.index }.sorted()
    fun hasCachedTimestamp(height: Int): Boolean = height in timestamps
    fun usedIndexes(keyId: String, chain: AddressChain): Set<Int> =
        addresses.filter { it.keyId == keyId && it.chain == chain && it.isUsed }.map { it.index }.toSet()
    fun snapshotFor(keyId: String, chain: AddressChain): AddressSnapshot =
        snapshots.getValue(addresses.single { it.keyId == keyId && it.chain == chain && it.isUsed }.id!!)
    fun singleState(keyId: String): SingleAddressHistoryState? = addresses.singleOrNull { it.keyId == keyId && it.chain == AddressChain.EXTERNAL }?.let {
        SingleAddressHistoryState(it, it.historyRemoteCount, it.historyNextCursor, it.historyComplete)
    }
}

private val XPUB: String by lazy {
    val seed = MnemonicCode.toSeed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", "")
    val account = DeterministicWallet.generate(seed).derivePrivateKey(listOf(
        DeterministicWallet.hardened(84),
        DeterministicWallet.hardened(0),
        DeterministicWallet.hardened(0),
    ))
    account.extendedPublicKey.encode(DeterministicWallet.zpub)
}

private val LEGACY_XPUB: String by lazy {
    val seed = MnemonicCode.toSeed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", "")
    val account = DeterministicWallet.generate(seed).derivePrivateKey(listOf(
        DeterministicWallet.hardened(44),
        DeterministicWallet.hardened(0),
        DeterministicWallet.hardened(0),
    ))
    account.extendedPublicKey.encode(DeterministicWallet.xpub)
}
