package app.glance.wallet.core.network

import app.glance.wallet.core.crypto.electrumScriptHash
import java.util.concurrent.CancellationException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

enum class BlockchainProtocol { ELECTRUM, ESPLORA }

data class NetworkEndpoint(
    val protocol: BlockchainProtocol,
    val host: String,
    val port: Int,
    val useTls: Boolean,
)

object DefaultNetworkEndpoints {
    val electrum = NetworkEndpoint(
        protocol = BlockchainProtocol.ELECTRUM,
        host = "electrum.jhoenicke.de",
        port = 50002,
        useTls = true,
    )
    val esplora = NetworkEndpoint(
        protocol = BlockchainProtocol.ESPLORA,
        host = "mempool.space",
        port = 443,
        useTls = true,
    )
}

data class AddressBalance(val confirmedSats: Long, val unconfirmedSats: Long)
/** A cheap address change token plus whether the address has ever had chain activity. */
data class AddressStatus(
    val fingerprint: String,
    val hasActivity: Boolean,
    val transactionCount: Int? = null,
    /** Present when a provider can determine the live balance without downloading UTXOs. */
    val balance: AddressBalance? = null,
    /** Present when a provider can count unspent outputs from its address summary. */
    val unspentOutputCount: Int? = null,
)
data class AddressTransaction(val txid: String, val blockHeight: Int?, val confirmations: Int, val valueSats: Long = 0)
/** One bounded, signed Esplora history page. The cursor is the last row of the page. */
data class AddressHistoryPage(
    val transactions: List<AddressTransaction>,
    val nextCursor: String?,
    val isComplete: Boolean,
)
data class NetworkUtxo(
    val txid: String,
    val vout: Int,
    val valueSats: Long,
    val confirmations: Int,
    val blockHeight: Int? = null,
)
data class AddressSnapshot(
    val balance: AddressBalance,
    val history: List<AddressTransaction>,
    val utxos: List<NetworkUtxo>,
    val tipHeight: Int,
    /** False when the provider supplies txids/heights but cannot provide signed address deltas. */
    val hasSignedHistoryDeltas: Boolean = true,
)

interface ChainDataProvider : AutoCloseable {
    /** A cheap change token. Implementations batch or bounded-parallelize this where their protocol permits it. */
    fun fetchAddressStatuses(addresses: List<String>): Map<String, AddressStatus>
    fun fetchAddress(address: String): AddressSnapshot
    /** Balance/UTXO state without transaction history, for bounded single-address reconciliation. */
    fun fetchAddressState(address: String): AddressSnapshot = fetchAddress(address)
    /** Fetches at most one remote history page. Only Esplora supports cursor pagination. */
    fun fetchAddressHistoryPage(address: String, cursor: String?): AddressHistoryPage =
        throw UnsupportedOperationException("Paged address history is unavailable for this protocol")
    /** Current best-chain height, used to refresh cached confirmation counts cheaply. */
    fun tipHeight(): Int
    fun blockTimestamp(blockHeight: Int): Long
    override fun close() = Unit
}

/** Backwards-compatible name for the protocol-agnostic chain data contract. */
typealias BlockchainRepository = ChainDataProvider

/**
 * Keeps a sync on a working protocol when the preferred protocol's pool is unavailable.
 * Once fallback succeeds, it remains active for the rest of this sync to avoid repeatedly
 * attempting a quarantined primary endpoint for every discovered address.
 */
class FallbackChainDataProvider(
    private val primary: ChainDataProvider,
    private val fallback: ChainDataProvider,
) : ChainDataProvider {
    @Volatile private var active: ChainDataProvider = primary

    override fun fetchAddressStatuses(addresses: List<String>): Map<String, AddressStatus> =
        execute { it.fetchAddressStatuses(addresses) }

    override fun fetchAddress(address: String): AddressSnapshot = execute { it.fetchAddress(address) }

    override fun fetchAddressState(address: String): AddressSnapshot = execute { it.fetchAddressState(address) }

    override fun fetchAddressHistoryPage(address: String, cursor: String?): AddressHistoryPage =
        execute { it.fetchAddressHistoryPage(address, cursor) }

    override fun tipHeight(): Int = execute { it.tipHeight() }

    override fun blockTimestamp(blockHeight: Int): Long = execute { it.blockTimestamp(blockHeight) }

    override fun close() {
        primary.close()
        if (fallback !== primary) fallback.close()
    }

    private fun <T> execute(operation: (ChainDataProvider) -> T): T {
        val selected = active
        try {
            return operation(selected)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            if (selected === fallback || primary === fallback) throw failure
            active = fallback
            return operation(fallback)
        }
    }
}

data class ElectrumRequest(val method: String, val params: List<String> = emptyList())

interface ElectrumTransport : Closeable {
    fun request(method: String, params: List<String> = emptyList()): JsonElement
    fun requestBatch(requests: List<ElectrumRequest>): List<JsonElement> =
        requests.map { request(it.method, it.params) }
    override fun close() = Unit
}

class ElectrumBlockchainClient(private val transport: ElectrumTransport) : BlockchainRepository {
    /**
     * Returns Electrum's status token for each address in one JSON-RPC batch.
     * A null token denotes an unused address.
     */
    override fun fetchAddressStatuses(addresses: List<String>): Map<String, AddressStatus> {
        if (addresses.isEmpty()) return emptyMap()
        val requests = addresses.map { address ->
            ElectrumRequest("blockchain.scripthash.subscribe", listOf(electrumScriptHash(address)))
        }
        val results = transport.requestBatch(requests)
        check(results.size == addresses.size) { "Electrum batch response count mismatch" }
        return addresses.zip(results).associate { (address, result) ->
            val hasActivity = result !is kotlinx.serialization.json.JsonNull
            address to AddressStatus(
                fingerprint = if (hasActivity) result.jsonPrimitive.content else "unused",
                hasActivity = hasActivity,
            )
        }
    }

    override fun fetchAddress(address: String): AddressSnapshot {
        val scriptHash = electrumScriptHash(address)
        val tip = tipHeight()
        val balance = transport.request("blockchain.scripthash.get_balance", listOf(scriptHash)).jsonObject
        val history = transport.request("blockchain.scripthash.get_history", listOf(scriptHash)).jsonArray.map { entry ->
            val item = entry.jsonObject
            val height = item.requiredInt("height").takeIf { it > 0 }
            AddressTransaction(item.requiredString("tx_hash"), height, confirmations(tip, height))
        }
        val utxos = transport.request("blockchain.scripthash.listunspent", listOf(scriptHash)).jsonArray.map { entry ->
            val item = entry.jsonObject
            val height = item.requiredInt("height").takeIf { it > 0 }
            NetworkUtxo(item.requiredString("tx_hash"), item.requiredInt("tx_pos"), item.requiredLong("value"), confirmations(tip, height), height)
        }
        return AddressSnapshot(
            AddressBalance(balance.requiredLong("confirmed"), balance.requiredLong("unconfirmed")),
            history,
            utxos,
            tip,
            hasSignedHistoryDeltas = false,
        )
    }

    override fun fetchAddressState(address: String): AddressSnapshot {
        val scriptHash = electrumScriptHash(address)
        val tip = tipHeight()
        val balance = transport.request("blockchain.scripthash.get_balance", listOf(scriptHash)).jsonObject
        val utxos = transport.request("blockchain.scripthash.listunspent", listOf(scriptHash)).jsonArray.map { entry ->
            val item = entry.jsonObject
            val height = item.requiredInt("height").takeIf { it > 0 }
            NetworkUtxo(item.requiredString("tx_hash"), item.requiredInt("tx_pos"), item.requiredLong("value"), confirmations(tip, height), height)
        }
        return AddressSnapshot(AddressBalance(balance.requiredLong("confirmed"), balance.requiredLong("unconfirmed")), emptyList(), utxos, tip, hasSignedHistoryDeltas = false)
    }

    override fun tipHeight(): Int =
        transport.request("blockchain.headers.subscribe").jsonObject.requiredInt("height")

    override fun blockTimestamp(blockHeight: Int): Long {
        val hex = transport.request("blockchain.block.header", listOf(blockHeight.toString())).jsonPrimitive.content
        require(hex.length >= 136) { "Invalid block header" }
        val littleEndian = hex.substring(136 - 8, 136).chunked(2).reversed().joinToString("")
        return littleEndian.toLong(16)
    }
}

class SocketElectrumTransport(
    private val endpoint: NetworkEndpoint,
    private val connectTimeoutMillis: Int = 15_000,
    private val clientFactorySource: NetworkClientFactorySource,
) : ElectrumTransport {
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var nextId = 1L

    override fun request(method: String, params: List<String>): JsonElement {
        val id = nextId++
        ensureConnected()
        return try {
            writer!!.apply { write(payload(id, method, params).toString()); newLine(); flush() }
            readResults(setOf(id)).getValue(id)
        } catch (exception: NetworkException) {
            close()
            throw exception
        } catch (_: Exception) {
            close()
            throw NetworkException("Electrum request failed")
        }
    }

    override fun requestBatch(requests: List<ElectrumRequest>): List<JsonElement> {
        if (requests.isEmpty()) return emptyList()
        val ids = requests.map { nextId++ }
        ensureConnected()
        return try {
            val batch = buildJsonArray {
                requests.zip(ids).forEach { (request, id) -> add(payload(id, request.method, request.params)) }
            }
            writer!!.apply { write(batch.toString()); newLine(); flush() }
            val results = readResults(ids.toSet())
            ids.map { results.getValue(it) }
        } catch (exception: NetworkException) {
            close()
            throw exception
        } catch (_: Exception) {
            close()
            throw NetworkException("Electrum batch request failed")
        }
    }

    private fun payload(id: Long, method: String, params: List<String>): JsonObject = buildJsonObject {
        put("id", id)
        put("method", method)
        put("params", buildJsonArray { params.forEach { add(JsonPrimitive(it)) } })
    }

    private fun readResults(expectedIds: Set<Long>): Map<Long, JsonElement> {
        val results = mutableMapOf<Long, JsonElement>()
        while (results.keys != expectedIds) {
            val line = reader!!.readLine() ?: throw NetworkException("Electrum connection closed")
            val payload = kotlinx.serialization.json.Json.parseToJsonElement(line)
            val responses = if (payload is JsonArray) payload else listOf(payload)
            responses.forEach { element ->
                val response = element.jsonObject
                val id = response["id"]?.jsonPrimitive?.longOrNull ?: return@forEach
                if (id !in expectedIds) return@forEach
                if (response["error"] != null && response["error"] !is kotlinx.serialization.json.JsonNull) {
                    throw NetworkException("Electrum request failed")
                }
                results[id] = response["result"] ?: throw NetworkException("Electrum response missing result")
            }
        }
        return results
    }

    private fun ensureConnected() {
        if (socket?.isConnected == true && socket?.isClosed == false) return
        try {
            val rawSocket = clientFactorySource.current().socket().apply {
                connect(electrumEndpointAddress(endpoint.host, endpoint.port), connectTimeoutMillis)
            }
            socket = if (endpoint.useTls) {
                ((SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(rawSocket, endpoint.host, endpoint.port, true) as SSLSocket).also {
                    configureTlsHostnameVerification(it)
                }
            } else {
                rawSocket
            }.apply { soTimeout = connectTimeoutMillis }
            reader = BufferedReader(InputStreamReader(socket!!.getInputStream(), Charsets.UTF_8))
            writer = BufferedWriter(OutputStreamWriter(socket!!.getOutputStream(), Charsets.UTF_8))
        } catch (_: Exception) {
            close()
            throw NetworkException("Electrum connection failed")
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        reader = null
        writer = null
    }
}

/**
 * JSSE does not apply hostname checks to raw SSL sockets unless explicitly requested.
 * Electrum's TLS certificate must therefore be valid for the configured server host.
 */
internal fun configureTlsHostnameVerification(socket: SSLSocket) {
    socket.sslParameters = socket.sslParameters.apply {
        endpointIdentificationAlgorithm = "HTTPS"
    }
}

class EsploraBlockchainClient(
    baseUrl: String = "https://mempool.space/api/",
    private val clientFactorySource: NetworkClientFactorySource,
) : BlockchainRepository {
    private val baseUrl = baseUrl.toHttpUrl()

    override fun fetchAddressStatuses(addresses: List<String>): Map<String, AddressStatus> =
        boundedConcurrentMap(addresses, ESPLORA_STATUS_PARALLELISM) { address ->
            address to fetchAddressStatus(address)
        }.toMap()

    private fun fetchAddressStatus(address: String): AddressStatus {
        val summary = json("address/$address").jsonObject
        val chain = summary["chain_stats"]?.jsonObject ?: throw NetworkException("Invalid address response")
        val mempool = summary["mempool_stats"]?.jsonObject ?: throw NetworkException("Invalid address response")
        val counters = listOf("tx_count", "funded_txo_count", "spent_txo_count", "funded_txo_sum", "spent_txo_sum")
            .map { name -> chain.requiredLong(name) to mempool.requiredLong(name) }
        return AddressStatus(
            fingerprint = counters.joinToString(":") { (chainValue, mempoolValue) -> "$chainValue,$mempoolValue" },
            hasActivity = counters.any { (chainValue, mempoolValue) -> chainValue != 0L || mempoolValue != 0L },
            transactionCount = (chain.requiredLong("tx_count") + mempool.requiredLong("tx_count")).toInt(),
            balance = AddressBalance(
                chain.requiredLong("funded_txo_sum") - chain.requiredLong("spent_txo_sum"),
                mempool.requiredLong("funded_txo_sum") - mempool.requiredLong("spent_txo_sum"),
            ),
            unspentOutputCount = (
                chain.requiredLong("funded_txo_count") - chain.requiredLong("spent_txo_count") +
                    mempool.requiredLong("funded_txo_count") - mempool.requiredLong("spent_txo_count")
                ).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
    }

    override fun fetchAddress(address: String): AddressSnapshot {
        val state = fetchAddressState(address)
        return state.copy(history = allTransactions(address, state.tipHeight))
    }

    override fun fetchAddressState(address: String): AddressSnapshot {
        val tip = get("blocks/tip/height").trim().toIntOrNull() ?: throw NetworkException("Invalid chain tip")
        val summary = json("address/$address").jsonObject
        val chain = summary["chain_stats"]?.jsonObject ?: throw NetworkException("Invalid address response")
        val mempool = summary["mempool_stats"]?.jsonObject ?: throw NetworkException("Invalid address response")
        val confirmed = chain.requiredLong("funded_txo_sum") - chain.requiredLong("spent_txo_sum")
        val unconfirmed = mempool.requiredLong("funded_txo_sum") - mempool.requiredLong("spent_txo_sum")
        val utxos = json("address/$address/utxo").jsonArray.map { entry ->
            val item = entry.jsonObject
            val status = item["status"]?.jsonObject ?: throw NetworkException("Invalid UTXO response")
            val height = status["block_height"]?.jsonPrimitive?.content?.toIntOrNull()
            NetworkUtxo(item.requiredString("txid"), item.requiredInt("vout"), item.requiredLong("value"), confirmations(tip, height), height)
        }
        return AddressSnapshot(AddressBalance(confirmed, unconfirmed), emptyList(), utxos, tip)
    }

    override fun fetchAddressHistoryPage(address: String, cursor: String?): AddressHistoryPage =
        fetchAddressHistoryPage(address, cursor, tipHeight())

    private fun fetchAddressHistoryPage(address: String, cursor: String?, tip: Int): AddressHistoryPage {
        val path = if (cursor == null) "address/$address/txs" else "address/$address/txs/chain/$cursor"
        val page = json(path).jsonArray.map { it.jsonObject }
        val transactions = page.map { tx ->
            val status = tx["status"]?.jsonObject ?: throw NetworkException("Invalid transaction response")
            val height = status["block_height"]?.jsonPrimitive?.content?.toIntOrNull()
            AddressTransaction(tx.requiredString("txid"), height, confirmations(tip, height), tx.netValueFor(address))
        }
        return AddressHistoryPage(
            transactions = transactions,
            nextCursor = page.lastOrNull()?.requiredString("txid").takeIf { page.size >= ESPLORA_HISTORY_MIN_PAGE_SIZE },
            isComplete = page.size < ESPLORA_HISTORY_MIN_PAGE_SIZE,
        )
    }

    private fun allTransactions(address: String, tip: Int): List<AddressTransaction> {
        val result = mutableListOf<AddressTransaction>()
        var page = fetchAddressHistoryPage(address, null, tip)
        result += page.transactions
        while (!page.isComplete) {
            page = fetchAddressHistoryPage(address, requireNotNull(page.nextCursor), tip)
            result += page.transactions
        }
        return result.distinctBy { it.txid }
    }

    override fun tipHeight(): Int =
        get("blocks/tip/height").trim().toIntOrNull() ?: throw NetworkException("Invalid chain tip")

    override fun blockTimestamp(blockHeight: Int): Long {
        val hash = get("block-height/$blockHeight").trim()
        return json("block/$hash").jsonObject.requiredLong("timestamp")
    }

    /** Esplora includes prevouts, allowing a watch-only address's signed transaction delta. */
    private fun JsonObject.netValueFor(address: String): Long {
        val received = this["vout"]?.jsonArray.orEmpty().sumOf { output ->
            val entry = output.jsonObject
            if (entry["scriptpubkey_address"]?.jsonPrimitive?.content == address) entry.requiredLong("value") else 0L
        }
        val spent = this["vin"]?.jsonArray.orEmpty().sumOf { input ->
            val previous = input.jsonObject["prevout"]?.jsonObject ?: return@sumOf 0L
            if (previous["scriptpubkey_address"]?.jsonPrimitive?.content == address) previous.requiredLong("value") else 0L
        }
        return received - spent
    }

    private fun json(path: String): JsonElement = kotlinx.serialization.json.Json.parseToJsonElement(get(path))

    private fun get(path: String): String {
        val url = baseUrl.resolve(path) ?: throw NetworkException("Invalid request")
        return clientFactorySource.current().okHttpClient().newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw NetworkException("Blockchain request failed (HTTP ${response.code})")
            response.body.string()
        }
    }

    private companion object {
        /** Public Esplora APIs do not offer a batch status endpoint; keep fallback pressure modest. */
        const val ESPLORA_STATUS_PARALLELISM = 4
        /** Public Esplora deployments return either 25 or 50 transactions per full page. */
        const val ESPLORA_HISTORY_MIN_PAGE_SIZE = 25
    }
}

/** Leaves DNS resolution to the SOCKS proxy instead of the device resolver. */
internal fun electrumEndpointAddress(host: String, port: Int): InetSocketAddress =
    InetSocketAddress.createUnresolved(host, port)

/** Executes independent blocking calls with a bounded worker pool while retaining input order. */
internal fun <T, R> boundedConcurrentMap(
    inputs: List<T>,
    parallelism: Int,
    operation: (T) -> R,
): List<R> {
    require(parallelism > 0) { "Parallelism must be positive" }
    if (inputs.isEmpty()) return emptyList()
    val executor = Executors.newFixedThreadPool(minOf(parallelism, inputs.size))
    val futures = inputs.map { input -> executor.submit(Callable { operation(input) }) }
    return try {
        futures.map { future ->
            try {
                future.get()
            } catch (failure: ExecutionException) {
                val cause = failure.cause
                when (cause) {
                    is RuntimeException -> throw cause
                    is Error -> throw cause
                    else -> throw NetworkException("Esplora status request failed")
                }
            }
        }
    } catch (failure: InterruptedException) {
        Thread.currentThread().interrupt()
        throw NetworkException("Esplora status request interrupted")
    } finally {
        executor.shutdownNow()
    }
}

private fun confirmations(tipHeight: Int, blockHeight: Int?): Int =
    if (blockHeight == null) 0 else (tipHeight - blockHeight + 1).coerceAtLeast(0)

private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.content ?: throw NetworkException("Invalid provider response")

private fun JsonObject.requiredLong(name: String): Long =
    requiredString(name).toLongOrNull() ?: throw NetworkException("Invalid provider response")

private fun JsonObject.requiredInt(name: String): Int =
    requiredString(name).toIntOrNull() ?: throw NetworkException("Invalid provider response")
