package app.glance.wallet.core.network

import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CancellationException
import java.io.File
import java.util.Properties

enum class ServerRole { ELECTRUM, ESPLORA, COIN_GECKO, MEMPOOL_SPACE }

data class ServerDefinition(
    val id: String,
    val role: ServerRole,
    val host: String,
    val port: Int,
    val useTls: Boolean,
    val baseUrl: String? = null,
    val isCustom: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "Server id is required" }
        require(host.isNotBlank() && !host.contains('/')) { "Server host is invalid" }
        require(port in 1..65_535) { "Server port is invalid" }
        if (role != ServerRole.ELECTRUM) {
            val url = baseUrl?.takeIf(String::isNotBlank)?.toHttpUrl()
                ?: throw IllegalArgumentException("HTTP server base URL is required")
            require(url.host == host) { "Server host and base URL do not match" }
            require(url.port == port) { "Server port and base URL do not match" }
            require(url.isHttps == useTls) { "Server TLS setting and base URL do not match" }
        }
    }

    fun endpoint(): NetworkEndpoint = NetworkEndpoint(
        protocol = BlockchainProtocol.ELECTRUM,
        host = host,
        port = port,
        useTls = useTls,
    )

    companion object {
        fun electrum(host: String, port: Int, useTls: Boolean = true): ServerDefinition =
            ServerDefinition("electrum-$host-$port", ServerRole.ELECTRUM, host, port, useTls)

        fun esplora(baseUrl: String): ServerDefinition {
            val url = baseUrl.toHttpUrl()
            return ServerDefinition(
                id = "esplora-${url.host}",
                role = ServerRole.ESPLORA,
                host = url.host,
                port = url.port,
                useTls = url.isHttps,
                baseUrl = baseUrl,
            )
        }

        fun http(role: ServerRole, id: String, baseUrl: String): ServerDefinition {
            require(role != ServerRole.ELECTRUM) { "Electrum servers require socket configuration" }
            val url = baseUrl.toHttpUrl()
            return ServerDefinition(id, role, url.host, url.port, url.isHttps, baseUrl)
        }
    }
}

/** Selects healthy endpoints and quarantines endpoints that recently failed. */
data class EndpointHealthSnapshot(val serverId: String, val failures: Int, val lastSuccess: Long, val retryAt: Long)
data class ServerPoolSnapshot(val endpoints: List<ServerDefinition>, val health: List<EndpointHealthSnapshot>)

interface ServerPoolStateStore {
    fun load(): ServerPoolSnapshot?
    fun save(snapshot: ServerPoolSnapshot)
    fun clear()
}

class InMemoryServerPoolStateStore : ServerPoolStateStore {
    private val value = AtomicReference<ServerPoolSnapshot?>(null)
    override fun load(): ServerPoolSnapshot? = value.get()
    override fun save(snapshot: ServerPoolSnapshot) { value.set(snapshot) }
    override fun clear() { value.set(null) }
}

class ServerPool(
    initial: List<ServerDefinition>,
    private val quarantineMillis: Long = 60_000L,
    private val stateStore: ServerPoolStateStore? = null,
) {
    private data class Health(var failures: Int = 0, var lastSuccess: Long = Long.MIN_VALUE, var retryAt: Long = 0L)

    private val endpoints = initial.distinctBy(ServerDefinition::id).toMutableList()
    private val health = endpoints.associate { it.id to Health() }.toMutableMap()

    init {
        require(endpoints.isNotEmpty()) { "At least one server is required" }
        stateStore?.load()?.let { snapshot ->
            endpoints.removeAll { !it.isCustom }
            endpoints.addAll(snapshot.endpoints.filterNot(ServerDefinition::isCustom).distinctBy(ServerDefinition::id))
            snapshot.health.forEach { item ->
                health[item.serverId] = Health(item.failures, item.lastSuccess, item.retryAt)
            }
            // A process interruption can leave endpoint rows without matching health rows.
            // Treat missing health as pristine rather than allowing selection to throw.
            endpoints.forEach { endpoint -> health.putIfAbsent(endpoint.id, Health()) }
        }
    }

    @Synchronized
    fun choose(role: ServerRole, nowMillis: Long = System.currentTimeMillis()): ServerDefinition {
        val candidates = endpoints.filter { it.role == role }
        require(candidates.isNotEmpty()) { "No servers configured for $role" }
        return candidates
            .filter { health.getValue(it.id).retryAt <= nowMillis }
            .minWithOrNull(compareBy<ServerDefinition> { health.getValue(it.id).lastSuccess }.thenBy(ServerDefinition::id))
            ?: candidates.minBy { health.getValue(it.id).retryAt }
    }

    @Synchronized
    fun recordSuccess(serverId: String, nowMillis: Long = System.currentTimeMillis()) {
        health.getOrPut(serverId) { Health() }.apply {
            failures = 0
            lastSuccess = nowMillis
            retryAt = 0L
        }.also { persist() }
    }

    @Synchronized
    fun recordFailure(serverId: String, nowMillis: Long = System.currentTimeMillis()) {
        health.getOrPut(serverId) { Health() }.apply {
            failures++
            val multiplier = 1L shl (failures - 1).coerceAtMost(5)
            retryAt = nowMillis + quarantineMillis * multiplier
        }.also { persist() }
    }

    /** Runs one idempotent operation, failing over across candidates for the selected role. */
    fun <T> execute(
        role: ServerRole,
        nowMillis: Long = System.currentTimeMillis(),
        preferredServerId: String? = null,
        operation: (ServerDefinition) -> T,
    ): T {
        val candidates = synchronized(this) {
            val eligible = endpoints.filter { it.role == role && health.getValue(it.id).retryAt <= nowMillis }
            (if (eligible.isEmpty()) listOf(choose(role, nowMillis)) else eligible)
                .sortedWith(
                    compareBy<ServerDefinition> { it.id != preferredServerId }
                        .thenBy { health.getValue(it.id).lastSuccess }
                        .thenBy(ServerDefinition::id),
                )
        }
        var lastFailure: Exception? = null
        for (candidate in candidates) {
            try {
                return operation(candidate).also { recordSuccess(candidate.id) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                lastFailure = failure
                recordFailure(candidate.id)
            }
        }
        throw lastFailure ?: NetworkException("No server available for $role")
    }

    @Synchronized
    fun replaceFromManifest(manifest: ServerManifest) {
        val manifestEndpoints = manifest.endpoints.distinctBy(ServerDefinition::id)
        require(manifestEndpoints.isNotEmpty()) { "Manifest contains no servers" }
        endpoints.removeAll { !it.isCustom }
        endpoints.addAll(manifestEndpoints.filterNot(ServerDefinition::isCustom))
        manifestEndpoints.forEach { health.putIfAbsent(it.id, Health()) }
        persist()
    }

    @Synchronized fun snapshot(): ServerPoolSnapshot = ServerPoolSnapshot(
        endpoints.toList(), health.map { (id, item) -> EndpointHealthSnapshot(id, item.failures, item.lastSuccess, item.retryAt) },
    )

    @Synchronized private fun persist() { stateStore?.save(snapshot()) }

    companion object {
        fun bootstrap(stateStore: ServerPoolStateStore? = null): ServerPool = ServerPool(
            listOf(
                ServerDefinition.electrum("electrum.blockstream.info", 50002),
                ServerDefinition.electrum("electrum.emzy.de", 50002),
                ServerDefinition.electrum("electrum.bitaroo.net", 50002),
                ServerDefinition.esplora("https://blockstream.info/api/"),
                ServerDefinition.esplora("https://mempool.space/api/"),
                ServerDefinition.http(ServerRole.MEMPOOL_SPACE, "mempool-primary", "https://mempool.space/api/"),
            ), stateStore = stateStore,
        )
    }
}

data class ServerManifest(
    val version: Long,
    val expiresAtEpochSeconds: Long,
    val endpoints: List<ServerDefinition>,
    /** The verified wire document, retained so a cached manifest can be restored exactly. */
    val signedJson: String? = null,
)

class InvalidServerManifestException(message: String) : IllegalArgumentException(message)

object ServerManifestCodec {
    fun decodeAndVerify(json: String, trustedPublicKeyBase64: String, nowEpochSeconds: Long): ServerManifest {
        try {
            val root = Json.parseToJsonElement(json).jsonObject
            val version = root.requiredLong("version")
            val expiresAt = root.requiredLong("expiresAt")
            val endpointElements = root["endpoints"]?.jsonArray
                ?: throw InvalidServerManifestException("Manifest endpoints are missing")
            val endpoints = endpointElements.map(::decodeEndpoint)
            require(endpoints.any { it.role == ServerRole.ELECTRUM }) { "Manifest has no Electrum server" }
            require(endpoints.any { it.role == ServerRole.ESPLORA }) { "Manifest has no Esplora server" }
            val signature = root.requiredString("signature")
            if (expiresAt <= nowEpochSeconds) throw InvalidServerManifestException("Manifest has expired")
            val payload = payload(version, expiresAt, endpoints)
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(publicKey(trustedPublicKeyBase64))
            verifier.update(payload.toByteArray(Charsets.UTF_8))
            if (!verifier.verify(Base64.getDecoder().decode(signature))) {
                throw InvalidServerManifestException("Manifest signature is invalid")
            }
            return ServerManifest(version, expiresAt, endpoints, json)
        } catch (failure: InvalidServerManifestException) {
            throw failure
        } catch (_: Exception) {
            throw InvalidServerManifestException("Manifest is invalid")
        }
    }

    internal fun signForTesting(version: Long, expiresAt: Long, endpoints: List<ServerDefinition>, privateKey: PrivateKey): String {
        val payload = payload(version, expiresAt, endpoints)
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(privateKey)
        signer.update(payload.toByteArray(Charsets.UTF_8))
        return JsonObject(buildJsonObject {
            put("version", version)
            put("expiresAt", expiresAt)
            put("endpoints", Json.parseToJsonElement(payload).jsonObject["endpoints"]!!)
            put("signature", Base64.getEncoder().encodeToString(signer.sign()))
        }).toString()
    }

    private fun decodeEndpoint(element: kotlinx.serialization.json.JsonElement): ServerDefinition {
        val item = element.jsonObject
            val endpoint = ServerDefinition(
                id = item.requiredString("id"),
            role = runCatching { ServerRole.valueOf(item.requiredString("role")) }
                .getOrElse { throw InvalidServerManifestException("Manifest role is invalid") },
            host = item.requiredString("host"),
            port = item.requiredLong("port").toInt(),
            useTls = item.requiredBoolean("tls"),
                baseUrl = item["baseUrl"]?.jsonPrimitive?.content,
            )
            if (!endpoint.isCustom && !endpoint.useTls) {
                throw InvalidServerManifestException("Public manifest endpoints require TLS")
            }
            return endpoint
    }

    private fun payload(version: Long, expiresAt: Long, endpoints: List<ServerDefinition>): String = buildJsonObject {
        put("version", version)
        put("expiresAt", expiresAt)
        put("endpoints", buildJsonArray {
            endpoints.sortedBy(ServerDefinition::id).forEach { endpoint ->
                add(buildJsonObject {
                    put("id", endpoint.id)
                    put("role", endpoint.role.name)
                    put("host", endpoint.host)
                    put("port", endpoint.port)
                    put("tls", endpoint.useTls)
                    endpoint.baseUrl?.let { put("baseUrl", it) }
                })
            }
        })
    }.toString()

    private fun publicKey(encoded: String): PublicKey = KeyFactory.getInstance("Ed25519")
        .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encoded)))
}

interface ServerManifestStore {
    fun load(): ServerManifest?
    fun save(manifest: ServerManifest)
    fun clear()
}

class InMemoryServerManifestStore : ServerManifestStore {
    private var current: ServerManifest? = null

    override fun load(): ServerManifest? = current

    override fun save(manifest: ServerManifest) {
        current = manifest
    }

    override fun clear() { current = null }
}

/** Small private-file stores for non-wallet directory state. Callers must delete them on erase. */
class FileServerManifestStore(
    private val file: File,
    private val trustedPublicKeyBase64: String,
    private val nowEpochSeconds: () -> Long,
) : ServerManifestStore {
    override fun load(): ServerManifest? = runCatching {
        if (!file.isFile) return null
        val encoded = Properties().also { file.inputStream().use(it::load) }.getProperty("signed") ?: return null
        ServerManifestCodec.decodeAndVerify(String(Base64.getDecoder().decode(encoded), Charsets.UTF_8), trustedPublicKeyBase64, nowEpochSeconds())
    }.getOrNull()

    override fun save(manifest: ServerManifest) {
        val document = requireNotNull(manifest.signedJson) { "Only verified wire manifests can be persisted" }
        file.parentFile?.mkdirs()
        val properties = Properties().apply { setProperty("signed", Base64.getEncoder().encodeToString(document.toByteArray(Charsets.UTF_8))) }
        file.outputStream().use { properties.store(it, "Glance server manifest") }
    }

    override fun clear() { file.delete() }
}

class FileServerPoolStateStore(private val file: File) : ServerPoolStateStore {
    override fun load(): ServerPoolSnapshot? = runCatching {
        if (!file.isFile) return null
        val properties = Properties().also { file.inputStream().use(it::load) }
        val endpoints = properties.getProperty("endpointCount", "0").toInt().let { count ->
            (0 until count).map { index ->
                val p = "endpoint.$index."
                ServerDefinition(
                    id = properties.getProperty(p + "id"),
                    role = ServerRole.valueOf(properties.getProperty(p + "role")),
                    host = properties.getProperty(p + "host"),
                    port = properties.getProperty(p + "port").toInt(),
                    useTls = properties.getProperty(p + "tls").toBoolean(),
                    baseUrl = properties.getProperty(p + "baseUrl").takeUnless { it == "" },
                    isCustom = properties.getProperty(p + "custom").toBoolean(),
                )
            }
        }
        val health = properties.getProperty("healthCount", "0").toInt().let { count ->
            (0 until count).map { index ->
                val p = "health.$index."
                EndpointHealthSnapshot(properties.getProperty(p + "id"), properties.getProperty(p + "failures").toInt(), properties.getProperty(p + "success").toLong(), properties.getProperty(p + "retry").toLong())
            }
        }
        ServerPoolSnapshot(endpoints, health)
    }.getOrNull()

    override fun save(snapshot: ServerPoolSnapshot) {
        file.parentFile?.mkdirs()
        val properties = Properties()
        properties["endpointCount"] = snapshot.endpoints.size.toString()
        snapshot.endpoints.forEachIndexed { index, endpoint ->
            val p = "endpoint.$index."
            properties[p + "id"] = endpoint.id; properties[p + "role"] = endpoint.role.name
            properties[p + "host"] = endpoint.host; properties[p + "port"] = endpoint.port.toString()
            properties[p + "tls"] = endpoint.useTls.toString(); properties[p + "baseUrl"] = endpoint.baseUrl.orEmpty()
            properties[p + "custom"] = endpoint.isCustom.toString()
        }
        properties["healthCount"] = snapshot.health.size.toString()
        snapshot.health.forEachIndexed { index, item ->
            val p = "health.$index."
            properties[p + "id"] = item.serverId; properties[p + "failures"] = item.failures.toString()
            properties[p + "success"] = item.lastSuccess.toString(); properties[p + "retry"] = item.retryAt.toString()
        }
        file.outputStream().use { properties.store(it, "Glance server pool state") }
    }

    override fun clear() { file.delete() }
}

/** Fetches and verifies a signed directory without exposing wallet data to the directory host. */
class ServerManifestRefresher(
    private val routeSource: NetworkClientFactorySource,
    private val store: ServerManifestStore,
) {
    fun refresh(manifestUrl: String, trustedPublicKeyBase64: String, nowEpochSeconds: Long): Boolean {
        return try {
            val url = manifestUrl.toHttpUrl()
            require(url.isHttps) { "Manifest URL must use TLS" }
            val request = Request.Builder().url(url).build()
            val body = routeSource.current().okHttpClient().newBuilder()
                .followRedirects(false).followSslRedirects(false).build().newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw NetworkException("Server directory request failed (HTTP ${response.code})")
                response.body?.string() ?: throw NetworkException("Server directory response body is empty")
            }
            val manifest = ServerManifestCodec.decodeAndVerify(body, trustedPublicKeyBase64, nowEpochSeconds)
            val current = store.load()
            if (current != null && manifest.version < current.version) throw InvalidServerManifestException("Manifest version is older than cached manifest")
            store.save(manifest)
            true
        } catch (_: Exception) {
            false
        }
    }
}

/** Adapts the existing sync contract to a rotating server pool. */
class PooledChainDataProvider(
    private val role: ServerRole,
    private val pool: ServerPool,
    private val routeSource: NetworkClientFactorySource,
) : ChainDataProvider {
    private var activeServerId: String? = null
    private var electrumEndpointId: String? = null
    private var electrumTransport: SocketElectrumTransport? = null
    init {
        require(role == ServerRole.ELECTRUM || role == ServerRole.ESPLORA) { "Invalid chain server role" }
    }

    override fun fetchAddressStatuses(addresses: List<String>): Map<String, AddressStatus> =
        executeOnActiveServer { it.fetchAddressStatuses(addresses) }

    override fun fetchAddress(address: String): AddressSnapshot =
        executeOnActiveServer { it.fetchAddress(address) }

    override fun fetchAddressState(address: String): AddressSnapshot =
        executeOnActiveServer { it.fetchAddressState(address) }

    override fun fetchAddressHistoryPage(address: String, cursor: String?): AddressHistoryPage =
        executeOnActiveServer { it.fetchAddressHistoryPage(address, cursor) }

    override fun fetchTransactionDetail(txid: String): NetworkTransactionDetail =
        executeOnActiveServer { it.fetchTransactionDetail(txid) }

    override fun tipHeight(): Int =
        executeOnActiveServer { it.tipHeight() }

    override fun blockTimestamp(blockHeight: Int): Long =
        executeOnActiveServer { it.blockTimestamp(blockHeight) }

    private fun <T> executeOnActiveServer(operation: (ChainDataProvider) -> T): T =
        pool.execute(role, preferredServerId = activeServerId) { definition ->
            withClient(definition) { client -> operation(client).also { activeServerId = definition.id } }
        }

    private fun <T> withClient(definition: ServerDefinition, block: (ChainDataProvider) -> T): T = when (role) {
        ServerRole.ELECTRUM -> synchronized(this) {
            if (electrumEndpointId != definition.id) {
                electrumTransport?.close()
                electrumTransport = SocketElectrumTransport(definition.endpoint(), clientFactorySource = routeSource)
                electrumEndpointId = definition.id
            }
            block(ElectrumBlockchainClient(requireNotNull(electrumTransport)))
        }
        ServerRole.ESPLORA -> block(EsploraBlockchainClient(requireNotNull(definition.baseUrl), routeSource))
        else -> error("Invalid chain server role")
    }

    override fun close() = synchronized(this) {
        electrumTransport?.close()
        electrumTransport = null
        electrumEndpointId = null
        activeServerId = null
    }
}

/** Adapts a fiat provider to endpoint rotation while preserving its provider identity. */
class PooledFiatPriceClient(
    private val role: ServerRole,
    private val pool: ServerPool,
    private val routeSource: NetworkClientFactorySource,
    private val clock: () -> Long = { System.currentTimeMillis() / 1_000 },
) : FiatPriceClient {
    init {
        require(role == ServerRole.MEMPOOL_SPACE) { "Invalid fiat server role" }
    }

    override val provider: FiatProvider = FiatProvider.MEMPOOL_SPACE

    override fun currentPrice(currency: String): FiatQuote =
        pool.execute(role) { definition -> fiatClient(definition).currentPrice(currency) }

    override fun historicalPrices(currency: String, fromEpochSeconds: Long, toEpochSeconds: Long): List<FiatQuote> =
        pool.execute(role) { definition -> fiatClient(definition).historicalPrices(currency, fromEpochSeconds, toEpochSeconds) }

    private fun fiatClient(definition: ServerDefinition): FiatPriceClient =
        MempoolFiatPriceClient(requireNotNull(definition.baseUrl), routeSource, clock)
}

private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.content ?: throw InvalidServerManifestException("Manifest field is missing")

private fun JsonObject.requiredLong(name: String): Long =
    requiredString(name).toLongOrNull() ?: throw InvalidServerManifestException("Manifest number is invalid")

private fun JsonObject.requiredBoolean(name: String): Boolean =
    requiredString(name).toBooleanStrictOrNull() ?: throw InvalidServerManifestException("Manifest boolean is invalid")
