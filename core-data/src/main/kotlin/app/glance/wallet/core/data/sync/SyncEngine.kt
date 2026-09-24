package app.glance.wallet.core.data.sync

import androidx.room.withTransaction
import app.glance.wallet.core.crypto.parseWatchedKey
import app.glance.wallet.core.data.db.AddressChain
import app.glance.wallet.core.data.db.AddressHistoryEntity
import app.glance.wallet.core.data.db.BlockTimestampCacheEntity
import app.glance.wallet.core.data.db.DerivedAddressEntity
import app.glance.wallet.core.data.db.GlanceDatabase
import app.glance.wallet.core.data.db.ScriptType
import app.glance.wallet.core.data.db.WatchTargetType
import app.glance.wallet.core.data.db.UtxoEntity
import app.glance.wallet.core.data.db.TransactionInputEntity
import app.glance.wallet.core.data.db.TransactionOutputEntity
import app.glance.wallet.core.network.AddressSnapshot
import app.glance.wallet.core.network.AddressHistoryPage
import app.glance.wallet.core.network.ChainDataProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class SyncConfig(val gapLimit: Int = DEFAULT_GAP_LIMIT) {
    init { require(gapLimit in 1..1_000) { "gapLimit must be between 1 and 1000" } }
    companion object { const val DEFAULT_GAP_LIMIT = 20 }
}

data class DashboardBalance(val confirmedSats: Long = 0, val unconfirmedSats: Long = 0) {
    operator fun plus(other: DashboardBalance) = DashboardBalance(
        confirmedSats + other.confirmedSats,
        unconfirmedSats + other.unconfirmedSats,
    )
}

data class SyncResult(val balance: DashboardBalance, val refreshedAddresses: Int)
data class SyncWatchedKey(
    val id: String,
    val keyMaterial: String,
    val scriptType: ScriptType?,
    val targetType: WatchTargetType = WatchTargetType.HD_KEY,
    val externalHighestUsedIndex: Int = -1,
    val internalHighestUsedIndex: Int = -1,
    val externalScannedThroughIndex: Int = -1,
    val internalScannedThroughIndex: Int = -1,
)
data class SyncAddress(
    val id: Long? = null,
    val keyId: String,
    val chain: AddressChain,
    val index: Int,
    val address: String,
    val isUsed: Boolean = false,
    val isConfirmedUnused: Boolean = false,
    val lastStatus: String? = null,
    val historyRemoteCount: Int? = null,
    val historyNextCursor: String? = null,
    val historyComplete: Boolean = false,
    val isUtxoSnapshotSuppressed: Boolean = false,
    val cachedConfirmedBalanceSats: Long = 0L,
    val cachedUnconfirmedBalanceSats: Long = 0L,
    val unspentOutputCount: Int? = null,
)
data class SingleAddressHistoryState(val address: SyncAddress, val remoteCount: Int?, val nextCursor: String?, val isComplete: Boolean)

/** Persistence boundary that keeps gap scanning testable without Android or Room. */
interface WalletSyncStore {
    suspend fun watchedKeys(): List<SyncWatchedKey>
    suspend fun addresses(keyId: String, chain: AddressChain): List<SyncAddress>
    suspend fun saveAddress(address: SyncAddress): SyncAddress
    suspend fun updateHighestUsedIndex(keyId: String, chain: AddressChain, index: Int)
    suspend fun updateScannedThroughIndex(keyId: String, chain: AddressChain, index: Int)
    /** Atomically records a used address's status and replaces its history/UTXO cache. */
    suspend fun saveUsedAddressAndReplaceSnapshot(address: SyncAddress, snapshot: AddressSnapshot): SyncAddress
    /** Fixed-address snapshots are merged so older locally loaded pages survive a normal refresh. */
    suspend fun saveSingleAddressSnapshot(address: SyncAddress, snapshot: AddressSnapshot, remoteCount: Int?, nextCursor: String?, isComplete: Boolean, suppressUtxoSnapshot: Boolean = false, unspentOutputCount: Int? = null): SyncAddress =
        saveUsedAddressAndReplaceSnapshot(address, snapshot)
    suspend fun singleAddressHistoryState(keyId: String): SingleAddressHistoryState? = null
    suspend fun mergeSingleAddressHistoryPage(state: SingleAddressHistoryState, page: AddressHistoryPage): SingleAddressHistoryState =
        throw UnsupportedOperationException("On-demand history is unavailable")
    suspend fun refreshConfirmations(keyIds: List<String>, tipHeight: Int)
    /** Distinct history transactions owned by these keys whose complete I/O cache is absent. */
    suspend fun transactionsMissingIo(keyIds: List<String>): List<String> = emptyList()
    /** Replaces both sides of one transaction's I/O cache in one atomic write. */
    suspend fun replaceTransactionIo(txid: String, inputs: List<TransactionInputEntity>, outputs: List<TransactionOutputEntity>) = Unit
    suspend fun hasTimestamp(height: Int): Boolean
    suspend fun saveTimestamp(height: Int, timestamp: Long)
    /** Confirmed history persisted before a recoverable timestamp lookup must be retried later. */
    suspend fun missingTimestampHeights(keyIds: List<String>): List<Int> = emptyList()
    suspend fun dashboardBalance(): DashboardBalance
    fun observeDashboardBalance(): Flow<DashboardBalance>
}

class SyncEngine(
    private val store: WalletSyncStore,
    private val chainData: ChainDataProvider,
    private val config: SyncConfig = SyncConfig(),
    /** Supplies signed address-level deltas when the primary protocol cannot provide them. */
    private val historyEnricher: ChainDataProvider? = null,
    /** Fixed addresses use a paged Esplora provider so public Electrum history limits cannot block import. */
    private val singleAddressProvider: ChainDataProvider? = historyEnricher,
    /** Fixed-address UTXO snapshots get a fresh Electrum-first route, independent of status fallback. */
    private val singleAddressStateProvider: ChainDataProvider? = null,
    /** Esplora-backed, pooled provider used for cache-only transaction detail enrichment. */
    private val transactionIoProvider: ChainDataProvider? = historyEnricher,
) {
    fun observeDashboardBalance(): Flow<DashboardBalance> = store.observeDashboardBalance()

    /** Explicit user action from a fixed-address Transactions tab; never called by background sync. */
    suspend fun loadMoreSingleAddressHistory(keyId: String): Boolean {
        try {
            var state = store.singleAddressHistoryState(keyId) ?: return false
            if (state.isComplete) return false
            val provider = singleAddressHistoryProvider()
            repeat(SINGLE_ADDRESS_HISTORY_PAGE_BATCH_SIZE) {
                if (state.isComplete) return@repeat
                val page = provider.fetchAddressHistoryPage(state.address.address, state.nextCursor)
                state = store.mergeSingleAddressHistoryPage(state, page)
                page.transactions.mapNotNull { it.blockHeight }.distinct().forEach { height ->
                    if (!store.hasTimestamp(height)) {
                        try { store.saveTimestamp(height, provider.blockTimestamp(height)) }
                        catch (failure: Exception) { if (failure is CancellationException) throw failure }
                    }
                }
            }
            enrichMissingTransactionIo(listOf(keyId))
            return true
        } finally {
            closeProviders()
        }
    }

    suspend fun syncAll(): SyncResult {
        return sync(store.watchedKeys())
    }

    /** Reconciles only the key the user explicitly opened, avoiding requests for other wallets. */
    suspend fun syncKey(keyId: String): SyncResult {
        return sync(store.watchedKeys().filter { it.id == keyId })
    }

    /** Reconciles every independently-derived format belonging to a visible grouped wallet. */
    suspend fun syncKeys(keyIds: Collection<String>): SyncResult =
        sync(store.watchedKeys().filter { it.id in keyIds })

    private suspend fun sync(keys: List<SyncWatchedKey>): SyncResult {
        try {
            retryMissingTimestamps(keys.map(SyncWatchedKey::id))
            var refreshed = 0
            keys.forEach { key ->
                currentCoroutineContext().ensureActive()
                if (key.targetType == WatchTargetType.SINGLE_ADDRESS) refreshed += syncSingleAddress(key)
                else AddressChain.entries.forEach { chain -> refreshed += syncChain(key, chain) }
            }
            if (keys.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                store.refreshConfirmations(keys.map { it.id }, chainData.tipHeight())
                enrichMissingTransactionIo(keys.map { it.id })
            }
            return SyncResult(store.dashboardBalance(), refreshed)
        } finally {
            closeProviders()
        }
    }

    /** Individual I/O failures are intentionally isolated: the next explicit sync retries only that txid. */
    private suspend fun enrichMissingTransactionIo(keyIds: List<String>) {
        val provider = transactionIoProvider ?: return
        store.transactionsMissingIo(keyIds).forEach { txid ->
            currentCoroutineContext().ensureActive()
            try {
                val detail = provider.fetchTransactionDetail(txid)
                currentCoroutineContext().ensureActive()
                store.replaceTransactionIo(
                    detail.txid,
                    detail.inputs.map { TransactionInputEntity(detail.txid, it.index, it.address, it.valueSats, it.isCoinbase) },
                    detail.outputs.map { TransactionOutputEntity(detail.txid, it.index, it.address, it.valueSats) },
                )
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
            }
        }
    }

    /** Timestamp enrichment never changes wallet discovery or balances, so each failure is isolated. */
    private suspend fun retryMissingTimestamps(keyIds: List<String>) {
        store.missingTimestampHeights(keyIds).forEach { height ->
            currentCoroutineContext().ensureActive()
            try {
                store.saveTimestamp(height, chainData.blockTimestamp(height))
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
            }
        }
    }

    private suspend fun syncChain(key: SyncWatchedKey, chain: AddressChain): Int {
        val watchOnlyKey = parseWatchedKey(key.keyMaterial, requireNotNull(key.scriptType).toCryptoType())
        var cached = store.addresses(key.id, chain).associateBy { it.index }.toMutableMap()
        var highestUsed = if (chain == AddressChain.EXTERNAL) key.externalHighestUsedIndex else key.internalHighestUsedIndex
        highestUsed = maxOf(highestUsed, cached.values.filter { it.isUsed }.maxOfOrNull { it.index } ?: -1)
        var scannedThrough = if (chain == AddressChain.EXTERNAL) key.externalScannedThroughIndex else key.internalScannedThroughIndex
        var target = highestUsed + config.gapLimit
        var next = 0
        var refreshed = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            while (next <= target) {
                if (next !in cached) {
                    val derived = watchOnlyKey.derive(if (chain == AddressChain.EXTERNAL) 0 else 1, next.toLong())
                    val saved = store.saveAddress(SyncAddress(keyId = key.id, chain = chain, index = next, address = derived.address))
                    cached[next] = saved
                }
                next++
            }

            // Discovery only checks indexes beyond the persisted frontier. Used
            // addresses are always reconciled so spent outputs and new history
            // remain current, while confirmed-unused cache entries stay local.
            val activeAddresses = cached.values
                .filter { address ->
                    val needsDiscovery = address.index in (scannedThrough + 1)..target && !address.isConfirmedUnused
                    needsDiscovery ||
                        (address.isUsed && address.index <= scannedThrough) ||
                        (chain == AddressChain.EXTERNAL && address.index == currentReceiveIndex(cached))
                }
                .sortedBy { it.index }
            val statuses = if (activeAddresses.isEmpty()) emptyMap()
            else {
                currentCoroutineContext().ensureActive()
                chainData.fetchAddressStatuses(activeAddresses.map { it.address })
            }
            activeAddresses.forEach { address ->
                currentCoroutineContext().ensureActive()
                val status = requireNotNull(statuses[address.address]) { "Missing address status" }
                if (status.fingerprint != address.lastStatus) {
                    if (!status.hasActivity) {
                        val updated = store.saveAddress(address.copy(
                            isConfirmedUnused = true,
                            lastStatus = status.fingerprint,
                        ))
                        cached[updated.index] = updated
                    } else {
                        val snapshot = signedHistorySnapshot(address.address)
                        val updated = store.saveUsedAddressAndReplaceSnapshot(address.copy(
                            isUsed = true,
                            isConfirmedUnused = false,
                            lastStatus = status.fingerprint,
                        ), snapshot)
                        snapshot.history.mapNotNull { it.blockHeight }.distinct().forEach { height ->
                            if (!store.hasTimestamp(height)) {
                                val timestamp = try {
                                    chainData.blockTimestamp(height)
                                } catch (failure: Exception) {
                                    if (failure is CancellationException) throw failure
                                    return@forEach
                                }
                                store.saveTimestamp(height, timestamp)
                            }
                        }
                        cached[updated.index] = updated
                    }
                    refreshed++
                }
            }
            val discoveredHighestUsed = cached.values.filter { it.isUsed }.maxOfOrNull { it.index } ?: -1
            if (discoveredHighestUsed > highestUsed) {
                highestUsed = discoveredHighestUsed
                store.updateHighestUsedIndex(key.id, chain, highestUsed)
            }
            val completedThrough = (scannedThrough + 1..target).all { index ->
                cached.getValue(index).isUsed || cached.getValue(index).isConfirmedUnused
            }
            check(completedThrough) { "Address discovery window was not fully verified" }
            scannedThrough = target
            store.updateScannedThroughIndex(key.id, chain, scannedThrough)

            val nextTarget = highestUsed + config.gapLimit
            if (scannedThrough >= nextTarget) return refreshed
            target = nextTarget
        }
    }

    /** A fixed target has one persisted address and intentionally bypasses all gap discovery. */
    private suspend fun syncSingleAddress(key: SyncWatchedKey): Int {
        currentCoroutineContext().ensureActive()
        val historyProvider = singleAddressSnapshotProvider()
        val stateProvider = singleAddressStateProvider ?: chainData
        val address = store.addresses(key.id, AddressChain.EXTERNAL).singleOrNull()
            ?: error("Single-address target has no fixed address")
        val status = requireNotNull(chainData.fetchAddressStatuses(listOf(address.address))[address.address]) {
            "Missing address status"
        }
        // Esplora exposes an inexpensive output count in its address summary. Query it before
        // requesting an unpaged UTXO response so exceptionally large fixed targets stay usable.
        val summary = if (status.unspentOutputCount != null || historyProvider === chainData) status else try {
            historyProvider.fetchAddressStatuses(listOf(address.address))[address.address] ?: status
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            status
        }
        val suppressUtxoSnapshot = summary.unspentOutputCount?.let { it > SINGLE_ADDRESS_UTXO_SNAPSHOT_LIMIT } == true
        val requiresHistoryRepair = address.historyComplete && (
            address.historyRemoteCount == null || address.historyRemoteCount > SINGLE_ADDRESS_INITIAL_HISTORY_LIMIT
        )
        if (status.fingerprint == address.lastStatus && !requiresHistoryRepair) return 0
        if (!status.hasActivity) {
            store.saveAddress(address.copy(
                isUsed = false,
                isConfirmedUnused = true,
                lastStatus = status.fingerprint,
            ))
            return 1
        }
        // Do not persist the new fingerprint until signed deltas are available. Otherwise a
        // transient Esplora enrichment failure would suppress all future chart-history retries.
        var historyRetryNeeded = false
        val initialHistory = try {
            if (suppressUtxoSnapshot) {
                boundedSingleAddressHistory(
                    historyProvider = historyProvider,
                    address = address.address,
                    state = AddressSnapshot(
                        balance = requireNotNull(summary.balance) { "Oversized address summary has no balance" },
                        history = emptyList(),
                        utxos = emptyList(),
                        tipHeight = historyProvider.tipHeight(),
                    ),
                )
            } else signedSingleAddressSnapshot(
                historyProvider = historyProvider,
                stateProvider = stateProvider,
                address = address.address,
            )
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            historyRetryNeeded = true
            SingleAddressHistorySnapshot(
                snapshot = if (suppressUtxoSnapshot) AddressSnapshot(
                    balance = requireNotNull(summary.balance) { "Oversized address summary has no balance" },
                    history = emptyList(),
                    utxos = emptyList(),
                    tipHeight = historyProvider.tipHeight(),
                ) else chainData.fetchAddressState(address.address),
                nextCursor = null,
                isComplete = false,
            )
        }
        val snapshot = initialHistory.snapshot
        val remoteHistoryCount = if (historyRetryNeeded) null else status.transactionCount
            ?: initialHistory.snapshot.history.size.takeIf { initialHistory.isComplete }
        val updated = store.saveSingleAddressSnapshot(address.copy(
            isUsed = true,
            isConfirmedUnused = false,
            lastStatus = if (historyRetryNeeded) address.lastStatus else status.fingerprint,
        ), snapshot, remoteHistoryCount, initialHistory.nextCursor,
            !historyRetryNeeded && (initialHistory.isComplete || status.transactionCount?.let { it <= snapshot.history.size } == true),
            suppressUtxoSnapshot = suppressUtxoSnapshot,
            unspentOutputCount = summary.unspentOutputCount)
        snapshot.history.mapNotNull { it.blockHeight }.distinct().forEach { height ->
            if (!store.hasTimestamp(height)) {
                try { store.saveTimestamp(height, historyProvider.blockTimestamp(height)) }
                catch (failure: Exception) { if (failure is CancellationException) throw failure }
            }
        }
        return 1
    }

    private fun currentReceiveIndex(cached: Map<Int, SyncAddress>): Int? = cached.values
        .asSequence()
        .filter { !it.isUsed }
        .minOfOrNull { it.index }

    /**
     * Electrum history only contains txids and heights. Never let such a response replace
     * persisted signed deltas with zeroes: use the configured Esplora enricher first.
     * Failure intentionally leaves lastStatus untouched so the next explicit refresh retries.
     */
    private fun signedHistorySnapshot(address: String): AddressSnapshot {
        val snapshot = chainData.fetchAddress(address)
        if (snapshot.history.isEmpty() || snapshot.hasSignedHistoryDeltas) return snapshot
        val enriched = requireNotNull(historyEnricher) {
            "Signed transaction history requires a history enricher"
        }.fetchAddress(address)
        check(enriched.hasSignedHistoryDeltas) { "History enricher returned unsigned transaction deltas" }
        return snapshot.copy(history = enriched.history, hasSignedHistoryDeltas = true)
    }

    /**
     * Fixed-address history is bounded through Esplora, while state remains Electrum-first:
     * the latter never requests address history and can serve a large UTXO set when an
     * Esplora endpoint rejects its unpaged UTXO response.
     */
    private fun signedSingleAddressSnapshot(
        historyProvider: ChainDataProvider,
        stateProvider: ChainDataProvider,
        address: String,
    ): SingleAddressHistorySnapshot {
        if (historyProvider === chainData && historyEnricher == null && singleAddressProvider == null) {
            return SingleAddressHistorySnapshot(
                snapshot = signedHistorySnapshot(address),
                nextCursor = null,
                isComplete = true,
            )
        }
        return boundedSingleAddressHistory(historyProvider, address, stateProvider.fetchAddressState(address))
    }

    private fun boundedSingleAddressHistory(
        historyProvider: ChainDataProvider,
        address: String,
        state: AddressSnapshot,
    ): SingleAddressHistorySnapshot {
        var page = historyProvider.fetchAddressHistoryPage(address, null)
        val history = page.transactions.take(SINGLE_ADDRESS_INITIAL_HISTORY_LIMIT).toMutableList()
        repeat(SINGLE_ADDRESS_HISTORY_PAGE_BATCH_SIZE - 1) {
            if (page.isComplete || history.size >= SINGLE_ADDRESS_INITIAL_HISTORY_LIMIT) return@repeat
            page = historyProvider.fetchAddressHistoryPage(address, requireNotNull(page.nextCursor))
            history += page.transactions.take(SINGLE_ADDRESS_INITIAL_HISTORY_LIMIT - history.size)
        }
        return SingleAddressHistorySnapshot(
            snapshot = state.copy(history = history, hasSignedHistoryDeltas = true),
            nextCursor = page.nextCursor,
            isComplete = page.isComplete,
        )
    }

    private fun singleAddressHistoryProvider(): ChainDataProvider =
        requireNotNull(singleAddressProvider ?: historyEnricher) {
            "Paged history requires an Esplora provider"
        }

    private fun singleAddressSnapshotProvider(): ChainDataProvider =
        singleAddressProvider ?: historyEnricher ?: chainData

    private fun closeProviders() {
        chainData.close()
        if (historyEnricher !== chainData) historyEnricher?.close()
        if (singleAddressProvider !== chainData && singleAddressProvider !== historyEnricher) singleAddressProvider?.close()
        if (singleAddressStateProvider !== chainData && singleAddressStateProvider !== historyEnricher && singleAddressStateProvider !== singleAddressProvider) singleAddressStateProvider?.close()
        if (transactionIoProvider !== chainData && transactionIoProvider !== historyEnricher && transactionIoProvider !== singleAddressProvider && transactionIoProvider !== singleAddressStateProvider) transactionIoProvider?.close()
    }
}

private data class SingleAddressHistorySnapshot(
    val snapshot: AddressSnapshot,
    val nextCursor: String?,
    val isComplete: Boolean,
)

class RoomWalletSyncStore(private val database: GlanceDatabase) : WalletSyncStore {
    override suspend fun watchedKeys() = database.watchedKeyDao().observeAll().first()
        .map {
            SyncWatchedKey(
                it.id,
                it.keyMaterial,
                it.scriptType,
                it.targetType,
                it.externalHighestUsedIndex,
                it.internalHighestUsedIndex,
                it.externalScannedThroughIndex,
                it.internalScannedThroughIndex,
            )
        }

    override suspend fun addresses(keyId: String, chain: AddressChain) = database.derivedAddressDao().forChain(keyId, chain).map { it.toSyncAddress() }

    override suspend fun saveAddress(address: SyncAddress): SyncAddress = database.withTransaction {
        val entity = address.toEntity()
        if (entity.id == 0L) {
            val existing = database.derivedAddressDao().findByAddress(entity.address)
            require(existing == null || existing.keyId == entity.keyId) {
                "A watched address is already owned by another target"
            }
            if (existing != null) return@withTransaction existing.toSyncAddress()
            val id = database.derivedAddressDao().upsert(entity)
            entity.copy(id = id).toSyncAddress()
        } else {
            database.derivedAddressDao().update(entity)
            entity.toSyncAddress()
        }
    }

    override suspend fun updateHighestUsedIndex(keyId: String, chain: AddressChain, index: Int) = database.withTransaction {
        val key = requireNotNull(database.watchedKeyDao().findById(keyId))
        database.watchedKeyDao().update(
            if (chain == AddressChain.EXTERNAL) key.copy(externalHighestUsedIndex = index)
            else key.copy(internalHighestUsedIndex = index),
        )
    }

    override suspend fun updateScannedThroughIndex(keyId: String, chain: AddressChain, index: Int) = database.withTransaction {
        val key = requireNotNull(database.watchedKeyDao().findById(keyId))
        database.watchedKeyDao().update(
            if (chain == AddressChain.EXTERNAL) key.copy(externalScannedThroughIndex = index)
            else key.copy(internalScannedThroughIndex = index),
        )
    }

    override suspend fun saveUsedAddressAndReplaceSnapshot(address: SyncAddress, snapshot: AddressSnapshot): SyncAddress = database.withTransaction {
        val id = requireNotNull(address.id)
        database.derivedAddressDao().update(address.toEntity())
        database.addressHistoryDao().deleteForAddress(id)
        database.utxoDao().deleteForAddress(id)
        database.addressHistoryDao().upsertAll(snapshot.history.map {
            AddressHistoryEntity(addressId = id, txid = it.txid, confirmations = it.confirmations, blockHeight = it.blockHeight, valueSats = it.valueSats)
        })
        database.utxoDao().upsertAll(snapshot.utxos.map {
            UtxoEntity(addressId = id, txid = it.txid, vout = it.vout, valueSats = it.valueSats, confirmations = it.confirmations, blockHeight = it.blockHeight)
        })
        address
    }

    override suspend fun saveSingleAddressSnapshot(address: SyncAddress, snapshot: AddressSnapshot, remoteCount: Int?, nextCursor: String?, isComplete: Boolean, suppressUtxoSnapshot: Boolean, unspentOutputCount: Int?): SyncAddress = database.withTransaction {
        val id = requireNotNull(address.id)
        val updated = address.copy(
            historyRemoteCount = remoteCount,
            historyNextCursor = nextCursor,
            historyComplete = isComplete,
            isUtxoSnapshotSuppressed = suppressUtxoSnapshot,
            cachedConfirmedBalanceSats = snapshot.balance.confirmedSats,
            cachedUnconfirmedBalanceSats = snapshot.balance.unconfirmedSats,
            unspentOutputCount = unspentOutputCount,
        )
        database.derivedAddressDao().update(updated.toEntity())
        database.addressHistoryDao().upsertAll(snapshot.history.map {
            AddressHistoryEntity(addressId = id, txid = it.txid, confirmations = it.confirmations, blockHeight = it.blockHeight, valueSats = it.valueSats)
        })
        database.utxoDao().deleteForAddress(id)
        database.utxoDao().upsertAll(snapshot.utxos.map {
            UtxoEntity(addressId = id, txid = it.txid, vout = it.vout, valueSats = it.valueSats, confirmations = it.confirmations, blockHeight = it.blockHeight)
        })
        updated
    }

    override suspend fun singleAddressHistoryState(keyId: String): SingleAddressHistoryState? = database.withTransaction {
        val key = database.watchedKeyDao().findById(keyId) ?: return@withTransaction null
        if (key.targetType != WatchTargetType.SINGLE_ADDRESS) return@withTransaction null
        val address = database.derivedAddressDao().forChain(keyId, AddressChain.EXTERNAL).singleOrNull() ?: return@withTransaction null
        val syncAddress = address.toSyncAddress()
        SingleAddressHistoryState(syncAddress, address.historyRemoteCount, address.historyNextCursor, address.historyComplete)
    }

    override suspend fun mergeSingleAddressHistoryPage(state: SingleAddressHistoryState, page: AddressHistoryPage): SingleAddressHistoryState = database.withTransaction {
        val address = state.address.copy(
            historyNextCursor = page.nextCursor,
            historyComplete = page.isComplete,
        )
        database.derivedAddressDao().update(address.toEntity())
        database.addressHistoryDao().upsertAll(page.transactions.map {
            AddressHistoryEntity(addressId = requireNotNull(address.id), txid = it.txid, confirmations = it.confirmations, blockHeight = it.blockHeight, valueSats = it.valueSats)
        })
        SingleAddressHistoryState(address, address.historyRemoteCount, page.nextCursor, page.isComplete)
    }

    override suspend fun refreshConfirmations(keyIds: List<String>, tipHeight: Int) = database.withTransaction {
        database.addressHistoryDao().refreshConfirmations(keyIds, tipHeight)
        database.utxoDao().refreshConfirmations(keyIds, tipHeight)
    }
    override suspend fun transactionsMissingIo(keyIds: List<String>): List<String> =
        if (keyIds.isEmpty()) emptyList() else database.transactionDetailDao().missingIoForKeys(keyIds)
    override suspend fun replaceTransactionIo(txid: String, inputs: List<TransactionInputEntity>, outputs: List<TransactionOutputEntity>) = database.withTransaction {
        database.transactionDetailDao().deleteInputs(txid)
        database.transactionDetailDao().deleteOutputs(txid)
        database.transactionDetailDao().upsertInputs(inputs)
        database.transactionDetailDao().upsertOutputs(outputs)
    }

    override suspend fun hasTimestamp(height: Int) = database.blockTimestampCacheDao().find(height) != null
    override suspend fun saveTimestamp(height: Int, timestamp: Long) = database.blockTimestampCacheDao().upsert(BlockTimestampCacheEntity(height, timestamp))
    override suspend fun missingTimestampHeights(keyIds: List<String>): List<Int> =
        if (keyIds.isEmpty()) emptyList() else database.blockTimestampCacheDao().missingConfirmedHistoryHeights(keyIds)
    override suspend fun dashboardBalance(): DashboardBalance = DashboardBalance(
        database.utxoDao().observeConfirmedBalance().first(),
        database.utxoDao().observeUnconfirmedBalance().first(),
    )
    override fun observeDashboardBalance(): Flow<DashboardBalance> = combine(
        database.utxoDao().observeConfirmedBalance(), database.utxoDao().observeUnconfirmedBalance(),
    ) { confirmed, unconfirmed -> DashboardBalance(confirmed, unconfirmed) }
}

private fun ScriptType.toCryptoType(): app.glance.wallet.core.crypto.ScriptType =
    app.glance.wallet.core.crypto.ScriptType.valueOf(name)

private const val ESPLORA_HISTORY_PAGE_SIZE = 25
private const val SINGLE_ADDRESS_HISTORY_PAGE_BATCH_SIZE = 2
private const val SINGLE_ADDRESS_INITIAL_HISTORY_LIMIT = ESPLORA_HISTORY_PAGE_SIZE * SINGLE_ADDRESS_HISTORY_PAGE_BATCH_SIZE
private const val SINGLE_ADDRESS_UTXO_SNAPSHOT_LIMIT = 1_000

private fun DerivedAddressEntity.toSyncAddress() = SyncAddress(id, keyId, chain, derivationIndex, address, isUsed, isConfirmedUnused, lastStatus, historyRemoteCount, historyNextCursor, historyComplete, isUtxoSnapshotSuppressed, cachedConfirmedBalanceSats, cachedUnconfirmedBalanceSats, unspentOutputCount)
private fun SyncAddress.toEntity() = DerivedAddressEntity(id = id ?: 0, keyId = keyId, chain = chain, derivationIndex = index, address = address, isUsed = isUsed, isConfirmedUnused = isConfirmedUnused, lastStatus = lastStatus, historyRemoteCount = historyRemoteCount, historyNextCursor = historyNextCursor, historyComplete = historyComplete, isUtxoSnapshotSuppressed = isUtxoSnapshotSuppressed, cachedConfirmedBalanceSats = cachedConfirmedBalanceSats, cachedUnconfirmedBalanceSats = cachedUnconfirmedBalanceSats, unspentOutputCount = unspentOutputCount)
