package app.glance.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.glance.wallet.core.network.DefaultNetworkEndpoints
import app.glance.wallet.core.network.MempoolFiatPriceClient
import app.glance.wallet.core.network.ServerDefinition
import app.glance.wallet.core.network.ServerPool
import app.glance.wallet.core.network.ServerRole
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in device smoke test. Supply only via instrumentation arguments; credentials never enter
 * source, logs, Gradle properties, or test output.
 */
@RunWith(AndroidJUnit4::class)
class TorLiveSmokeTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val address = arguments.getString("glance.live.address")
    private val electrumHost = arguments.getString("glance.live.electrum_host")
        ?: DefaultNetworkEndpoints.electrum.host
    private val electrumPort = arguments.getString("glance.live.electrum_port")
        ?.toIntOrNull()
        ?: DefaultNetworkEndpoints.electrum.port
    private val electrumTls = arguments.getString("glance.live.electrum_tls")
        ?.toBooleanStrictOrNull()
        ?: DefaultNetworkEndpoints.electrum.useTls
    private val esploraBaseUrl = arguments.getString("glance.live.esplora_base_url")
        ?: "https://mempool.space/api/"

    @Test
    fun allNetworkPathsWorkOverTorThenDirectAfterOptOut() = runBlocking {
        assumeTrue(!address.isNullOrBlank())
        val watchedAddress = requireNotNull(address)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tor = TorController(context)
        try {
            val pool = serverPool()
            tor.setEnabled(true)
            withTimeout(60_000L) { tor.state.filterIsInstance<TorState.Ready>().first() }
            val torNetwork = NetworkClients(tor)
            val torFailures = verifyAllPaths(torNetwork, pool, watchedAddress).toMutableList()
            verify("Mempool onion current", torFailures) {
                MempoolFiatPriceClient(MEMPOOL_ONION_API, tor).currentPrice("USD").price > 0
            }
            verify("Mempool onion history", torFailures) {
                MempoolFiatPriceClient(MEMPOOL_ONION_API, tor).historicalPrices(
                    currency = "USD",
                    fromEpochSeconds = 1_700_000_000L,
                    toEpochSeconds = 1_700_000_000L,
                ).single().price > 0
            }

            tor.setEnabled(false)
            assertTrue(tor.state.value == TorState.Disabled)
            val directFailures = verifyAllPaths(NetworkClients(tor), pool, watchedAddress)
            assertTrue("Tor failures: $torFailures; direct failures: $directFailures", torFailures.isEmpty() && directFailures.isEmpty())
        } finally {
            tor.clear()
        }
    }

    private fun serverPool(): ServerPool = ServerPool(
        listOf(
            ServerDefinition.electrum(electrumHost, electrumPort, electrumTls),
            ServerDefinition.electrum("electrum.blockstream.info", 50002),
            ServerDefinition.electrum("electrum.emzy.de", 50002),
            ServerDefinition.electrum("electrum.bitaroo.net", 50002),
            ServerDefinition.esplora(esploraBaseUrl),
            ServerDefinition.esplora("https://blockstream.info/api/"),
            ServerDefinition.http(ServerRole.MEMPOOL_SPACE, "mempool-primary", "https://mempool.space/api/"),
        ).distinctBy(ServerDefinition::id),
    )

    private fun verifyAllPaths(network: NetworkClients, pool: ServerPool, watchedAddress: String): List<String> {
        val failures = mutableListOf<String>()
        verify("Electrum", failures) { network.pooledChain(pool, ServerRole.ELECTRUM).fetchAddressStatuses(listOf(watchedAddress)).isNotEmpty() }
        verify("Esplora", failures) { network.pooledChain(pool, ServerRole.ESPLORA).fetchAddressStatuses(listOf(watchedAddress)).isNotEmpty() }
        verify("Mempool", failures) { network.pooledMempool(pool).currentPrice("USD").price > 0 }
        return failures
    }

    private fun verify(name: String, failures: MutableList<String>, request: () -> Boolean) {
        runCatching(request).onFailure { failure ->
            failures += "$name(${failure::class.simpleName}:${failure.message ?: "no-message"})"
        }.onSuccess { result ->
            if (!result) failures += "$name(false)"
        }
    }

    private companion object {
        const val MEMPOOL_ONION_API = "http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion/api/"
    }
}
