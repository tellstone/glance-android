package app.glance.wallet

import android.app.Application
import app.glance.wallet.core.security.SecurityLogging
import app.glance.wallet.core.security.SecurityPreferencesStore
import app.glance.wallet.core.network.ServerPool
import app.glance.wallet.core.network.FileServerManifestStore
import app.glance.wallet.core.network.FileServerPoolStateStore
import app.glance.wallet.core.network.ServerManifestRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

/** Application composition root for process-wide services. */
class GlanceApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverDirectoryJob: Job? = null
    private var networkShutdownJob: Job? = null
    private var activeTorEnabled: Boolean? = null
    private val syncCoordinators = mutableSetOf<WalletSyncCoordinator>()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) SecurityLogging.plantRedactingDebugTree()
    }

    val securityPreferences: SecurityPreferencesStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SecurityPreferencesStore.create(this)
    }

    val torController: TorController by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { TorController(this) }

    val serverPool: ServerPool by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ServerPool.bootstrap(FileServerPoolStateStore(NetworkStateFiles.poolState(this)))
    }

    val networkClients: NetworkClients by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { NetworkClients(torController, serverPool) }

    /** Starts privacy-sensitive network services only for an authenticated real-wallet session. */
    fun setNetworkSessionActive(active: Boolean, torEnabled: Boolean, offlineMode: Boolean = false) {
        if (active && !offlineMode && (serverDirectoryJob == null || activeTorEnabled != torEnabled)) {
            serverDirectoryJob?.cancel()
            networkShutdownJob?.cancel()
            networkShutdownJob = null
            activeTorEnabled = torEnabled
            serverDirectoryJob = applicationScope.launch { refreshServerDirectoryForever(torEnabled) }
        } else if (!active || offlineMode) {
            synchronized(syncCoordinators) { syncCoordinators.toList() }.forEach(WalletSyncCoordinator::cancel)
            serverDirectoryJob?.cancel()
            serverDirectoryJob = null
            activeTorEnabled = null
            networkShutdownJob?.cancel()
            networkShutdownJob = applicationScope.launch {
                if (offlineMode) torController.setOffline(true) else torController.stopForInactiveSession()
            }
        }
    }

    fun registerSyncCoordinator(coordinator: WalletSyncCoordinator) = synchronized(syncCoordinators) { syncCoordinators += coordinator }
    fun unregisterSyncCoordinator(coordinator: WalletSyncCoordinator) = synchronized(syncCoordinators) { syncCoordinators -= coordinator }

    fun renewTorConnection() {
        synchronized(syncCoordinators) { syncCoordinators.toList() }.forEach(WalletSyncCoordinator::cancel)
        applicationScope.launch { torController.renew() }
    }

    fun setOfflineMode(enabled: Boolean) {
        if (enabled) synchronized(syncCoordinators) { syncCoordinators.toList() }.forEach(WalletSyncCoordinator::cancel)
        applicationScope.launch { torController.setOffline(enabled) }
    }

    private suspend fun refreshServerDirectoryForever(torEnabled: Boolean) {
        torController.setOffline(false)
        torController.setEnabled(torEnabled)
        if (!torEnabled) return
        val store = FileServerManifestStore(
            NetworkStateFiles.manifest(this),
            ManifestTrust.PUBLIC_KEY_BASE64,
        ) { System.currentTimeMillis() / 1_000 }
        store.load()?.let { serverPool.replaceFromManifest(it) }
        val refresher = ServerManifestRefresher(torController, store)
        while (true) {
            if (torController.state.value is TorState.Ready) {
                if (refresher.refresh(ManifestTrust.URL, ManifestTrust.PUBLIC_KEY_BASE64, System.currentTimeMillis() / 1_000)) {
                    store.load()?.let { serverPool.replaceFromManifest(it) }
                }
            }
            delay(6 * 60 * 60 * 1_000L)
        }
    }
}

/** Ed25519 SubjectPublicKeyInfo for the directory signing key. The private key is never shipped. */
internal object ManifestTrust {
    const val URL = "https://glancewallet.app/.well-known/glance-server-manifest.json"
    const val PUBLIC_KEY_BASE64 = "MCowBQYDK2VwAyEA11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo="
}
