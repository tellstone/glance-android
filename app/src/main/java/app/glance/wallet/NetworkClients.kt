package app.glance.wallet

import app.glance.wallet.core.network.ElectrumBlockchainClient
import app.glance.wallet.core.network.EsploraBlockchainClient
import app.glance.wallet.core.network.FallbackChainDataProvider
import app.glance.wallet.core.network.FiatPriceClient
import app.glance.wallet.core.network.MempoolFiatPriceClient
import app.glance.wallet.core.network.NetworkClientFactorySource
import app.glance.wallet.core.network.NetworkEndpoint
import app.glance.wallet.core.network.PooledChainDataProvider
import app.glance.wallet.core.network.PooledFiatPriceClient
import app.glance.wallet.core.network.ServerPool
import app.glance.wallet.core.network.ServerRole
import app.glance.wallet.core.network.SocketElectrumTransport
import app.glance.wallet.core.data.sync.SyncEngine
import app.glance.wallet.core.data.sync.WalletSyncStore
import app.glance.wallet.core.data.sync.XpubFormatDiscovery

/** The only production composition path for network clients; it always uses the current Tor route. */
class NetworkClients(
    private val routeSource: NetworkClientFactorySource,
    private val serverPool: ServerPool? = null,
) {
    fun electrum(endpoint: NetworkEndpoint): ElectrumBlockchainClient =
        ElectrumBlockchainClient(SocketElectrumTransport(endpoint, clientFactorySource = routeSource))

    fun esplora(baseUrl: String): EsploraBlockchainClient =
        EsploraBlockchainClient(baseUrl, routeSource)

    fun mempool(baseUrl: String): FiatPriceClient =
        MempoolFiatPriceClient(baseUrl, routeSource)

    fun pooledChain(pool: ServerPool, role: ServerRole) = PooledChainDataProvider(role, pool, routeSource)

    fun pooledMempool(pool: ServerPool): FiatPriceClient =
        PooledFiatPriceClient(ServerRole.MEMPOOL_SPACE, pool, routeSource)

    fun pooledChain(role: ServerRole) = PooledChainDataProvider(
        role,
        requireNotNull(serverPool) { "Server pool is not configured" },
        routeSource,
    )

    fun pooledMempool(): FiatPriceClient = pooledMempool(
        requireNotNull(serverPool) { "Server pool is not configured" },
    )

    /** Production sync prefers Electrum and completes through Esplora if the Electrum pool is unavailable. */
    fun syncEngine(store: WalletSyncStore): SyncEngine {
        val esplora = pooledChain(ServerRole.ESPLORA)
        val singleAddressState = FallbackChainDataProvider(
            primary = pooledChain(ServerRole.ELECTRUM),
            fallback = pooledChain(ServerRole.ESPLORA),
        )
        return SyncEngine(
            store,
            FallbackChainDataProvider(
                primary = pooledChain(ServerRole.ELECTRUM),
                fallback = pooledChain(ServerRole.ESPLORA),
            ),
            historyEnricher = esplora,
            singleAddressProvider = esplora,
            singleAddressStateProvider = singleAddressState,
        )
    }

    /** Uses the identical pooled, Tor-routed chain path as first wallet reconciliation. */
    fun xpubFormatDiscovery() = XpubFormatDiscovery(
        FallbackChainDataProvider(
            primary = pooledChain(ServerRole.ELECTRUM),
            fallback = pooledChain(ServerRole.ESPLORA),
        ),
    )
}
