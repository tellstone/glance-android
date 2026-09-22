package app.glance.wallet.core.network

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class BlockchainClientTest {
    @Test
    fun `Electrum endpoint is left unresolved for SOCKS DNS resolution`() {
        val address = electrumEndpointAddress("electrum.example", 50002)

        assertEquals("electrum.example", address.hostString)
        assertEquals(50002, address.port)
        assertTrue(address.isUnresolved)
    }

    @Test
    fun `bounded status work preserves input order and never exceeds its concurrency cap`() {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val release = AtomicBoolean(false)

        val results = boundedConcurrentMap((1..8).toList(), parallelism = 4) { value ->
            val current = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, current) }
            while (!release.get()) {
                if (peak.get() >= 4) release.set(true)
                Thread.yield()
            }
            active.decrementAndGet()
            value * 2
        }

        assertEquals((2..16 step 2).toList(), results)
        assertEquals(4, peak.get())
    }

    @Test
    fun `chain fallback uses Esplora for the rest of a sync after Electrum fails`() {
        val electrum = RecordingChain(failStatusRequest = true)
        val esplora = RecordingChain()
        val provider = FallbackChainDataProvider(electrum, esplora)

        provider.fetchAddressStatuses(listOf("address"))
        provider.fetchAddress("address")

        assertEquals(1, electrum.statusRequests)
        assertEquals(0, electrum.addressRequests)
        assertEquals(1, esplora.statusRequests)
        assertEquals(1, esplora.addressRequests)
    }

    @Test
    fun `route source supplies the current factory when a client request is made`() {
        val calls = AtomicInteger()
        val source = NetworkClientFactorySource {
            calls.incrementAndGet()
            NetworkClientFactory(NetworkRoute.Direct)
        }

        assertEquals(0, calls.get())
        source.current()
        assertEquals(1, calls.get())
    }

    @Test
    fun `SOCKS route configures OkHttp and raw socket clients consistently`() {
        val route = NetworkRoute.Socks(InetSocketAddress("127.0.0.1", 19050))
        val factory = NetworkClientFactory(route)

        val proxy = factory.okHttpClient().proxy!!
        assertEquals(Proxy.Type.SOCKS, proxy.type())
        assertEquals(InetSocketAddress("127.0.0.1", 19050), proxy.address())
        assertEquals(route, factory.route)
    }

    @Test
    fun `direct route does not configure an HTTP proxy`() {
        assertEquals(null, NetworkClientFactory(NetworkRoute.Direct).okHttpClient().proxy)
    }

    @Test
    fun `OkHttp clients are reused for the same route across factory instances`() {
        val route = NetworkRoute.Socks(InetSocketAddress("127.0.0.1", 19050))

        assertSame(
            NetworkClientFactory(route).okHttpClient(),
            NetworkClientFactory(route).okHttpClient(),
        )
        assertNotSame(
            NetworkClientFactory(NetworkRoute.Direct).okHttpClient(),
            NetworkClientFactory(route).okHttpClient(),
        )
    }

    @Test
    fun `TLS Electrum sockets require hostname verification`() {
        val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket

        socket.use {
            configureTlsHostnameVerification(it)

            assertEquals("HTTPS", it.sslParameters.endpointIdentificationAlgorithm)
            assertNotNull(it.sslParameters)
        }
    }

    @Test
    fun `Electrum client normalizes balance history and unspent outputs`() {
        val client = ElectrumBlockchainClient(FakeElectrumTransport())

        val snapshot = client.fetchAddress("1BoatSLRHtKNngkdXEeobR76b53LETtpyT")

        assertEquals(50L, snapshot.balance.confirmedSats)
        assertEquals(1, snapshot.history.size)
        assertEquals("a".repeat(64), snapshot.history.single().txid)
        assertEquals(1, snapshot.utxos.size)
        assertEquals(2, snapshot.utxos.single().confirmations)
    }

    @Test
    fun `Electrum status checks are issued as one batch and mapped to their addresses`() {
        val transport = FakeElectrumTransport()
        val client = ElectrumBlockchainClient(transport)
        val addresses = listOf(
            "1BoatSLRHtKNngkdXEeobR76b53LETtpyT",
            "1BitcoinEaterAddressDontSendf59kuE",
        )

        val statuses = client.fetchAddressStatuses(addresses)

        assertEquals(1, transport.batchRequestCount)
        assertEquals("status-0", statuses.getValue(addresses[0]).fingerprint)
        assertEquals("status-1", statuses.getValue(addresses[1]).fingerprint)
        assertEquals(true, statuses.getValue(addresses[0]).hasActivity)
    }

    @Test
    fun `Esplora client normalizes balance history and UTXOs`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = "101"))
            server.enqueue(MockResponse(body = """{
                "chain_stats":{"funded_txo_sum":100,"spent_txo_sum":50},
                "mempool_stats":{"funded_txo_sum":20,"spent_txo_sum":0}
            }"""))
            server.enqueue(MockResponse(body = """[{"txid":"${"b".repeat(64)}","vout":1,"value":50,"status":{"block_height":100}}]"""))
            server.enqueue(MockResponse(body = """[{"txid":"${"b".repeat(64)}","status":{"block_height":100}}]"""))
            val client = EsploraBlockchainClient(server.url("/").toString(), DirectNetworkClientFactorySource)

            val snapshot = client.fetchAddress("placeholder")

            assertEquals(AddressBalance(50, 20), snapshot.balance)
            assertEquals(2, snapshot.history.single().confirmations)
            assertEquals(50, snapshot.utxos.single().valueSats)
        }
    }

    @Test
    fun `Esplora history page uses the server cursor without walking older pages`() {
        MockWebServer().use { server ->
            server.start()
            val txid = "c".repeat(64)
            server.enqueue(MockResponse(body = "101"))
            server.enqueue(MockResponse(body = "[{\"txid\":\"$txid\",\"status\":{\"block_height\":100}}]"))
            val client = EsploraBlockchainClient(server.url("/").toString(), DirectNetworkClientFactorySource)

            val page = client.fetchAddressHistoryPage("placeholder", "b".repeat(64))

            assertEquals(listOf(txid), page.transactions.map { it.txid })
            assertEquals(null, page.nextCursor)
            assertTrue(page.isComplete)
            server.takeRequest()
            val request = server.takeRequest()
            assertEquals("/address/placeholder/txs/chain/${"b".repeat(64)}", request.url.encodedPath)
            assertEquals(null, request.url.encodedQuery)
        }
    }

    @Test
    fun `Esplora history page keeps its cursor when the server returns fifty transactions`() {
        MockWebServer().use { server ->
            server.start()
            val txids = List(50) { index -> index.toString(16).padStart(64, '0') }
            val body = txids.joinToString(prefix = "[", postfix = "]") { txid ->
                "{\"txid\":\"$txid\",\"status\":{\"block_height\":100}}"
            }
            server.enqueue(MockResponse(body = "101"))
            server.enqueue(MockResponse(body = body))
            val client = EsploraBlockchainClient(server.url("/").toString(), DirectNetworkClientFactorySource)

            val page = client.fetchAddressHistoryPage("placeholder", null)

            assertEquals(txids.last(), page.nextCursor)
            assertTrue(!page.isComplete)
        }
    }

    @Test
    fun `Esplora history page keeps its cursor when the server returns twenty-five transactions`() {
        MockWebServer().use { server ->
            server.start()
            val txids = List(25) { index -> index.toString(16).padStart(64, '0') }
            val body = txids.joinToString(prefix = "[", postfix = "]") { txid ->
                "{\"txid\":\"$txid\",\"status\":{\"block_height\":100}}"
            }
            server.enqueue(MockResponse(body = "101"))
            server.enqueue(MockResponse(body = body))
            val client = EsploraBlockchainClient(server.url("/").toString(), DirectNetworkClientFactorySource)

            val page = client.fetchAddressHistoryPage("placeholder", null)

            assertEquals(txids.last(), page.nextCursor)
            assertTrue(!page.isComplete)
        }
    }
}

private class FakeElectrumTransport : ElectrumTransport {
    var batchRequestCount = 0

    override fun request(method: String, params: List<String>) = when (method) {
        "blockchain.headers.subscribe" -> buildJsonObject { put("height", 101) }
        "blockchain.scripthash.get_balance" -> buildJsonObject { put("confirmed", 50); put("unconfirmed", 0) }
        "blockchain.scripthash.get_history" -> buildJsonArray {
            add(buildJsonObject { put("tx_hash", "a".repeat(64)); put("height", 100) })
        }
        "blockchain.scripthash.listunspent" -> buildJsonArray {
            add(buildJsonObject { put("tx_hash", "a".repeat(64)); put("tx_pos", 0); put("value", 50); put("height", 100) })
        }
        else -> error("unexpected method")
    }

    override fun requestBatch(requests: List<ElectrumRequest>): List<kotlinx.serialization.json.JsonElement> {
        batchRequestCount++
        return requests.mapIndexed { index, _ -> JsonPrimitive("status-$index") }
    }
}

private class RecordingChain(
    private val failStatusRequest: Boolean = false,
) : ChainDataProvider {
    var statusRequests = 0
    var addressRequests = 0

    override fun fetchAddressStatuses(addresses: List<String>): Map<String, AddressStatus> {
        statusRequests++
        if (failStatusRequest) error("unavailable")
        return addresses.associateWith { AddressStatus("unused", hasActivity = false) }
    }

    override fun fetchAddress(address: String): AddressSnapshot {
        addressRequests++
        return AddressSnapshot(AddressBalance(0, 0), emptyList(), emptyList(), 0)
    }

    override fun tipHeight(): Int = 0

    override fun blockTimestamp(blockHeight: Int): Long = 0
}
