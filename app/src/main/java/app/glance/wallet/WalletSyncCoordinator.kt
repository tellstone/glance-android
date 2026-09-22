package app.glance.wallet

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/** Coordinates wallet sync with the privacy route without exposing network failure details to UI. */
sealed interface WalletSyncState {
    data object Idle : WalletSyncState
    data object ConnectingToTor : WalletSyncState
    data object Syncing : WalletSyncState
    data object Succeeded : WalletSyncState
    data object TorUnavailable : WalletSyncState
    data object Failed : WalletSyncState
}

class WalletSyncCoordinator(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val syncAll: suspend () -> Unit,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<WalletSyncState>(WalletSyncState.Idle)
    val state: StateFlow<WalletSyncState> = mutableState.asStateFlow()
    private var pending = false
    private var syncing = false
    @Volatile private var activeJob: Job? = null

    /** Cancels the caller-owned refresh so the sync engine observes cancellation between batches. */
    fun cancel() { activeJob?.cancel() }

    suspend fun requestSync(torEnabled: Boolean, torState: TorState, offlineMode: Boolean = false) {
        mutex.withLock { pending = true }
        reconcile(torEnabled, torState, offlineMode)
    }

    suspend fun reconcile(torEnabled: Boolean, torState: TorState, offlineMode: Boolean = false) {
        val callerJob = currentCoroutineContext()[Job]
        val shouldSync = mutex.withLock {
            if (!pending || syncing) return@withLock false
            if (offlineMode) {
                mutableState.value = WalletSyncState.Idle
                return@withLock false
            }
            if (torEnabled) {
                when (torState) {
                    TorState.Starting, TorState.Disabled -> mutableState.value = WalletSyncState.ConnectingToTor
                    is TorState.Unavailable -> mutableState.value = WalletSyncState.TorUnavailable
                    is TorState.Ready -> {
                        syncing = true
                        activeJob = callerJob
                        pending = false
                        mutableState.value = WalletSyncState.Syncing
                        return@withLock true
                    }
                }
                false
            } else {
                syncing = true
                activeJob = callerJob
                pending = false
                mutableState.value = WalletSyncState.Syncing
                true
            }
        }
        if (!shouldSync) return

        try {
            withContext(ioDispatcher) { syncAll() }
            val anotherSyncIsPending = mutex.withLock {
                syncing = false
                activeJob = null
                mutableState.value = WalletSyncState.Succeeded
                pending
            }
            if (anotherSyncIsPending) reconcile(torEnabled, torState, offlineMode)
        } catch (failure: Throwable) {
            if (failure is CancellationException) {
                mutex.withLock {
                    syncing = false
                    activeJob = null
                    pending = true
                    mutableState.value = WalletSyncState.Idle
                }
                throw failure
            }
            mutex.withLock {
                syncing = false
                activeJob = null
                mutableState.value = WalletSyncState.Failed
            }
        }
    }
}
