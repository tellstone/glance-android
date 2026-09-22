package app.glance.wallet

import android.content.Context
import app.glance.wallet.core.network.NetworkClientFactory
import app.glance.wallet.core.network.NetworkClientFactorySource
import app.glance.wallet.core.network.NetworkRoute
import app.glance.wallet.core.security.TorStateCleaner
import io.matthewnelson.kmp.tor.resource.exec.tor.ResourceLoaderTorExec
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import io.matthewnelson.kmp.tor.runtime.Action.Companion.startDaemonAsync
import io.matthewnelson.kmp.tor.runtime.Action.Companion.stopDaemonAsync
import java.io.File
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

sealed interface TorState {
    data object Disabled : TorState
    data object Starting : TorState
    data class Ready(val route: NetworkRoute.Socks) : TorState
    data class Unavailable(val cause: Throwable) : TorState
}

/** Owns the bundled Tor process and exposes only its loopback SOCKS route to wallet clients. */
class TorController(context: Context) : TorStateCleaner, NetworkClientFactorySource {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workDirectory = File(appContext.filesDir, "tor")
    private val cacheDirectory = File(appContext.cacheDir, "tor")
    private val runtime = TorRuntime.Builder(
        TorRuntime.Environment.Builder(workDirectory, cacheDirectory, ResourceLoaderTorExec::getOrCreate),
    ) {
        config { TorOption.SocksPort.configure { auto() } }
    }
    private val mutableState = MutableStateFlow<TorState>(TorState.Disabled)
    val state: StateFlow<TorState> = mutableState
    private val mutableReadySinceMillis = MutableStateFlow<Long?>(null)
    val readySinceMillis: StateFlow<Long?> = mutableReadySinceMillis
    @Volatile private var directConnectionsAllowed = false
    @Volatile private var offlineMode = false
    private var startupJob: Job? = null
    private var lifecycleGeneration = 0L

    suspend fun setEnabled(enabled: Boolean) {
        val stop = mutex.withLock {
            if (offlineMode) {
                directConnectionsAllowed = false
                disableLocked()
                true
            } else {
                directConnectionsAllowed = !enabled
                if (enabled) startLocked() else disableLocked()
                !enabled
            }
        }
        if (stop) stopDaemon()
    }

    suspend fun setOffline(enabled: Boolean) {
        // This is deliberately set before taking the lifecycle mutex: Offline mode must reject
        // clients even while a startup operation is polling for the SOCKS port.
        offlineMode = enabled
        if (!enabled) return
        directConnectionsAllowed = false
        mutex.withLock {
            disableLocked()
        }
        stopDaemon()
    }

    /** Stops the daemon for a locked/backgrounded session without changing the privacy policy. */
    suspend fun stopForInactiveSession() {
        mutex.withLock { disableLocked() }
        stopDaemon()
    }

    override fun current(): NetworkClientFactory {
        if (offlineMode) throw OfflineModeException()
        return when (val current = state.value) {
        is TorState.Ready -> NetworkClientFactory(current.route)
        TorState.Disabled -> if (directConnectionsAllowed) NetworkClientFactory(NetworkRoute.Direct) else throw TorUnavailableException()
        TorState.Starting, is TorState.Unavailable -> throw TorUnavailableException()
        }
    }

    suspend fun retry() = mutex.withLock {
        if (offlineMode) return@withLock
        directConnectionsAllowed = false
        startLocked(force = true)
    }

    /** Starts a fresh daemon session; callers cancel in-flight requests before invoking this. */
    suspend fun renew() = mutex.withLock {
        if (offlineMode) return@withLock
        directConnectionsAllowed = false
        startLocked(force = true, stopFirst = true)
    }

    override suspend fun clear() {
        offlineMode = true
        directConnectionsAllowed = false
        val failures = mutableListOf<Throwable>()
        suspend fun attempt(block: suspend () -> Unit) {
            try {
                block()
            } catch (failure: Throwable) {
                failures += failure
            }
        }
        runExclusiveTorCleanup(
            withLifecycleLock = { block -> mutex.withLock { block() } },
            disable = ::disableLocked,
            stopDaemon = ::stopDaemon,
            cleanup = {
                attempt { check(!workDirectory.exists() || workDirectory.deleteRecursively()) { "Unable to clear Tor work state." } }
                attempt { check(!cacheDirectory.exists() || cacheDirectory.deleteRecursively()) { "Unable to clear Tor cache state." } }
                // Directory and endpoint health are network metadata, and must not survive Erase All Data.
                attempt { NetworkStateFiles.manifest(appContext).deleteOrThrow() }
                attempt { NetworkStateFiles.poolState(appContext).deleteOrThrow() }
            },
        )
        directConnectionsAllowed = false
        if (failures.isNotEmpty()) throw IllegalStateException("Unable to erase all Tor state.", failures.first())
    }

    /** Starts asynchronously so lifecycle changes can preempt the 45-second readiness wait. */
    private fun startLocked(force: Boolean = false, stopFirst: Boolean = false) {
        if (!force && (mutableState.value is TorState.Ready || mutableState.value == TorState.Starting)) return
        val predecessor = startupJob
        val generation = ++lifecycleGeneration
        mutableState.value = TorState.Starting
        startupJob = controllerScope.launch {
            predecessor?.cancel()
            predecessor?.join()
            runTorStartup(
                beforeStart = { if (stopFirst) runtime.stopDaemonAsync() },
                startDaemon = { runtime.startDaemonAsync() },
                awaitSocksPort = {
                    awaitTorSocksPort(
                        isReady = runtime::isReady,
                        socksPort = { runtime.listeners().socks.singleOrNull()?.port?.value },
                    )
                },
                onReady = { port -> publishReady(generation, port) },
                onUnavailable = { failure -> publishUnavailable(generation, failure) },
                onCancellation = { stopAndPublishDisabledAfterCancellation(generation) },
            )
        }
    }

    private fun disableLocked() {
        ++lifecycleGeneration
        startupJob?.cancel()
        startupJob = null
        mutableReadySinceMillis.value = null
        mutableState.value = TorState.Disabled
    }

    private suspend fun stopDaemon() {
        try {
            runtime.stopDaemonAsync()
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
        } finally {
            // State is already fail-closed; a cancelled predecessor cannot overwrite a newer start.
        }
    }

    private suspend fun publishReady(generation: Long, port: Int) {
        val stale = mutex.withLock {
            if (generation != lifecycleGeneration || offlineMode) true
            else {
                mutableReadySinceMillis.value = System.currentTimeMillis()
                mutableState.value = TorState.Ready(NetworkRoute.Socks(InetSocketAddress("127.0.0.1", port)))
                startupJob = null
                false
            }
        }
        if (stale) stopDaemon()
    }

    private suspend fun publishUnavailable(generation: Long, failure: Throwable) = mutex.withLock {
        if (generation == lifecycleGeneration && !offlineMode) {
            mutableState.value = TorState.Unavailable(failure)
            startupJob = null
        }
    }

    private suspend fun publishDisabledAfterCancellation(generation: Long) = mutex.withLock {
        if (generation == lifecycleGeneration) {
            mutableReadySinceMillis.value = null
            mutableState.value = TorState.Disabled
            startupJob = null
        }
    }

    private suspend fun stopAndPublishDisabledAfterCancellation(generation: Long) {
        try {
            runtime.stopDaemonAsync()
        } finally {
            publishDisabledAfterCancellation(generation)
        }
    }
}

/** Shared names for private network metadata that must be removed by Erase All Data. */
internal object NetworkStateFiles {
    private const val MANIFEST = "server-manifest.json"
    private const val POOL_STATE = "server-pool-state.properties"

    fun manifest(context: Context): File = File(context.filesDir, MANIFEST)
    fun poolState(context: Context): File = File(context.filesDir, POOL_STATE)
}

private fun File.deleteOrThrow() {
    check(!exists() || delete()) { "Unable to delete private network state." }
}

class TorUnavailableException : IllegalStateException("Tor is enabled but unavailable. Retry Tor or disable it before connecting directly.")
class OfflineModeException : IllegalStateException("Network access is disabled while Offline mode is on.")

/** Waits until the daemon reports ready and has published its dynamically assigned SOCKS port. */
internal suspend fun awaitTorSocksPort(
    isReady: () -> Boolean,
    socksPort: () -> Int?,
    timeoutMillis: Long = 45_000L,
    pollMillis: Long = 100L,
): Int = withTimeout<Int>(timeoutMillis) {
    while (true) {
        if (isReady()) socksPort()?.let { return@withTimeout it }
        delay(pollMillis)
    }
    error("Unreachable")
}

/** Converts genuine daemon failures into state while preserving structured coroutine cancellation. */
internal suspend fun runTorStartup(
    beforeStart: suspend () -> Unit = {},
    startDaemon: suspend () -> Unit,
    awaitSocksPort: suspend () -> Int,
    onReady: suspend (Int) -> Unit,
    onUnavailable: suspend (Throwable) -> Unit,
    onCancellation: suspend () -> Unit,
) {
    try {
        beforeStart()
        startDaemon()
        onReady(awaitSocksPort())
    } catch (failure: Throwable) {
        if (failure is kotlinx.coroutines.CancellationException) {
            reconcileTorCancellation(onCancellation)
            throw failure
        }
        onUnavailable(failure)
    }
}

/** Holds the lifecycle lock until daemon shutdown and private-state deletion are both complete. */
internal suspend fun runExclusiveTorCleanup(
    withLifecycleLock: suspend (block: suspend () -> Unit) -> Unit,
    disable: () -> Unit,
    stopDaemon: suspend () -> Unit,
    cleanup: suspend () -> Unit,
) {
    withLifecycleLock {
        disable()
        stopDaemon()
        cleanup()
    }
}

/** Shutdown is best effort, except that cancellation must remain visible to the owning scope. */
internal suspend fun runTorShutdown(
    stopDaemon: suspend () -> Unit,
    onDisabled: () -> Unit,
    onCancellation: suspend () -> Unit,
) {
    try {
        stopDaemon()
    } catch (failure: Throwable) {
        if (failure is kotlinx.coroutines.CancellationException) {
            reconcileTorCancellation(onCancellation)
            throw failure
        }
    }
    onDisabled()
}

/** Settles controller state after cancellation without masking the caller's cancellation. */
private suspend fun reconcileTorCancellation(onCancellation: suspend () -> Unit) {
    withContext(NonCancellable) {
        try {
            onCancellation()
        } catch (_: Throwable) {
            // The original cancellation remains the caller-visible result.
        }
    }
}
