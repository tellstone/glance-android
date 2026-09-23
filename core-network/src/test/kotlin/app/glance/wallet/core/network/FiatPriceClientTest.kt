package app.glance.wallet.core.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FiatPriceClientTest {
    @Test
    fun `mempool parses a current price without credentials`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{\"USD\":123.45}"))
            val client = MempoolFiatPriceClient(server.url("api/").toString(), DirectNetworkClientFactorySource, clock = { 1L })

            assertEquals(FiatQuote(FiatProvider.MEMPOOL_SPACE, "USD", 123.45, 1L), client.currentPrice("USD"))
            assertEquals("/api/v1/prices", server.takeRequest().requestUrl!!.encodedPath)
        }
    }

    @Test
    fun `mempool full history is fetched in one request and filters the requested range`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"prices":[{"time":100,"USD":10.0},{"time":200,"USD":20.0},{"time":300,"USD":-1.0}]}"""))
            val client = MempoolFiatPriceClient(server.url("api/").toString(), DirectNetworkClientFactorySource)

            val quotes = client.historicalPrices("USD", 150L, 250L)

            assertEquals(
                listOf(
                    FiatQuote(FiatProvider.MEMPOOL_SPACE, "USD", 10.0, 100L),
                    FiatQuote(FiatProvider.MEMPOOL_SPACE, "USD", 20.0, 200L),
                ),
                quotes,
            )
            val request = server.takeRequest()
            assertEquals("/api/v1/historical-price", request.requestUrl!!.encodedPath)
            assertEquals(null, request.requestUrl!!.query)
        }
    }

    @Test
    fun `mempool uses the timestamp endpoint for one missing bucket`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"prices":{"USD":123.45}}"""))
            val client = MempoolFiatPriceClient(server.url("api/").toString(), DirectNetworkClientFactorySource)

            assertEquals(listOf(FiatQuote(FiatProvider.MEMPOOL_SPACE, "USD", 123.45, 100L)), client.historicalPrices("USD", 100L, 100L))

            val request = server.takeRequest()
            assertEquals("USD", request.requestUrl!!.queryParameter("currency"))
            assertEquals("100", request.requestUrl!!.queryParameter("timestamp"))
        }
    }

    @Test
    fun `mempool rejects a zero price from the timestamp endpoint`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"prices":{"USD":0}}"""))
            val client = MempoolFiatPriceClient(server.url("api/").toString(), DirectNetworkClientFactorySource)

            assertThrows(NetworkException::class.java) {
                client.historicalPrices("USD", 100L, 100L)
            }
        }
    }

    @Test
    fun `unsupported currency is rejected without an alternate provider`() {
        assertThrows(IllegalArgumentException::class.java) {
            MempoolFiatPriceClient(clientFactorySource = DirectNetworkClientFactorySource).currentPrice("SEK")
        }
    }
}
