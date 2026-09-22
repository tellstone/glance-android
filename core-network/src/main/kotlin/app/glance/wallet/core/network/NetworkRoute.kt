package app.glance.wallet.core.network

import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient

/** The only supported routing modes for wallet network traffic. */
sealed interface NetworkRoute {
    data object Direct : NetworkRoute
    data class Socks(val address: InetSocketAddress) : NetworkRoute {
        init {
            require(address.address?.isLoopbackAddress == true || address.hostString == "127.0.0.1") {
                "SOCKS routing must use a loopback proxy."
            }
        }
    }
}

/** Supplies the route at request/connection time so a Tor state change cannot be bypassed. */
fun interface NetworkClientFactorySource {
    fun current(): NetworkClientFactory
}

object DirectNetworkClientFactorySource : NetworkClientFactorySource {
    override fun current(): NetworkClientFactory = NetworkClientFactory(NetworkRoute.Direct)
}

/**
 * Creates protocol clients from one route so Electrum, Esplora, and both fiat providers cannot
 * accidentally diverge between Tor and direct networking.
 */
class NetworkClientFactory(val route: NetworkRoute = NetworkRoute.Direct) {
    internal val proxy: Proxy? = (route as? NetworkRoute.Socks)?.let { Proxy(Proxy.Type.SOCKS, it.address) }

    /**
     * OkHttp owns its connection pool, so retain one client for each immutable route. Sources may
     * create factories at request time as Tor state changes without forfeiting connection reuse.
     */
    fun okHttpClient(): OkHttpClient = clients.computeIfAbsent(route) { configuredRoute ->
        val configuredProxy = (configuredRoute as? NetworkRoute.Socks)
            ?.let { Proxy(Proxy.Type.SOCKS, it.address) }
        OkHttpClient.Builder().proxy(configuredProxy).build()
    }

    internal fun socket(): java.net.Socket = proxy?.let { java.net.Socket(it) } ?: java.net.Socket()

    private companion object {
        val clients = ConcurrentHashMap<NetworkRoute, OkHttpClient>()
    }
}
