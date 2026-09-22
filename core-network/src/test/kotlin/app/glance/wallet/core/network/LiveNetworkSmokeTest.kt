package app.glance.wallet.core.network

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Public-server verification, intentionally excluded unless callers provide only public test data.
 * Run with GLANCE_LIVE_SMOKE=true and GLANCE_LIVE_ADDRESS.
 */
class LiveNetworkSmokeTest {
    @Test
    fun `default blockchain endpoints and fiat providers return data`() {
        val address = System.getenv("GLANCE_LIVE_ADDRESS")
        assumeTrue(System.getenv("GLANCE_LIVE_SMOKE") == "true" && !address.isNullOrBlank())

        SocketElectrumTransport(DefaultNetworkEndpoints.electrum, clientFactorySource = DirectNetworkClientFactorySource).use { transport ->
            val client = ElectrumBlockchainClient(transport)
            assertTrue(client.fetchAddress(address!!).balance.confirmedSats >= 0)
            assertTrue(client.fetchAddressStatuses(listOf(address)).containsKey(address))
        }
        assertTrue(EsploraBlockchainClient(clientFactorySource = DirectNetworkClientFactorySource).fetchAddress(address!!).balance.confirmedSats >= 0)
        assertTrue(MempoolFiatPriceClient(clientFactorySource = DirectNetworkClientFactorySource).currentPrice("USD").price > 0)
    }
}
