package app.glance.wallet.core.network

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerPoolTest {
    @Test
    fun `restored endpoints without health rows remain selectable`() {
        val endpoint = ServerDefinition.electrum("restored.example", 50002)
        val store = InMemoryServerPoolStateStore().apply {
            save(ServerPoolSnapshot(endpoints = listOf(endpoint), health = emptyList()))
        }

        val pool = ServerPool(listOf(ServerDefinition.electrum("bootstrap.example", 50002)), stateStore = store)

        assertEquals(endpoint, pool.choose(ServerRole.ELECTRUM))
    }

    @Test
    fun `pool skips quarantined endpoint and uses next healthy candidate`() {
        val first = ServerDefinition.electrum("first.example", 50002)
        val second = ServerDefinition.electrum("second.example", 50002)
        val pool = ServerPool(listOf(first, second))

        assertEquals(first, pool.choose(ServerRole.ELECTRUM))
        pool.recordFailure(first.id)

        assertEquals(second, pool.choose(ServerRole.ELECTRUM))
    }

    @Test
    fun `successful endpoint is preferred after a previous failure`() {
        val first = ServerDefinition.electrum("first.example", 50002)
        val second = ServerDefinition.electrum("second.example", 50002)
        val pool = ServerPool(listOf(first, second))

        pool.recordFailure(first.id)
        pool.recordSuccess(second.id)

        assertEquals(second, pool.choose(ServerRole.ELECTRUM))
    }

    @Test
    fun `execute does not retry a quarantined endpoint in the same operation`() {
        val first = ServerDefinition.electrum("first.example", 50002)
        val second = ServerDefinition.electrum("second.example", 50002)
        val pool = ServerPool(listOf(first, second))
        val calls = AtomicInteger()

        assertThrows(NetworkException::class.java) {
            pool.execute(ServerRole.ELECTRUM) {
                calls.incrementAndGet()
                throw NetworkException("down")
            }
        }

        assertEquals(2, calls.get())
    }

    @Test
    fun `execute rotates healthy endpoints between successful operations`() {
        val first = ServerDefinition.electrum("first.example", 50002)
        val second = ServerDefinition.electrum("second.example", 50002)
        val pool = ServerPool(listOf(first, second))
        val selected = mutableListOf<String>()

        repeat(2) {
            pool.execute(ServerRole.ELECTRUM) { endpoint ->
                selected += endpoint.id
                Unit
            }
        }

        assertEquals(listOf(first.id, second.id), selected)
    }

    @Test
    fun `execute propagates cancellation without retrying or quarantining endpoints`() {
        val first = ServerDefinition.electrum("first.example", 50002)
        val second = ServerDefinition.electrum("second.example", 50002)
        val pool = ServerPool(listOf(first, second))
        val calls = AtomicInteger()

        assertThrows(CancellationException::class.java) {
            pool.execute(ServerRole.ELECTRUM) {
                calls.incrementAndGet()
                throw CancellationException("cancelled")
            }
        }

        assertEquals(1, calls.get())
        assertEquals(0, pool.snapshot().health.sumOf { it.failures })
    }

    @Test
    fun `manifest verification rejects tampered payload`() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val endpoint = ServerDefinition.esplora("https://blockstream.info/api/")
        val signed = ServerManifestCodec.signForTesting(7, 4_000_000_000L, listOf(endpoint), keys.private)
        val tampered = signed.replace("blockstream.info", "example.invalid")

        assertThrows(InvalidServerManifestException::class.java) {
            ServerManifestCodec.decodeAndVerify(
                tampered,
                Base64.getEncoder().encodeToString(keys.public.encoded),
                nowEpochSeconds = 2_000_000_000L,
            )
        }
    }

    @Test
    fun `expired manifest is rejected`() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val signed = ServerManifestCodec.signForTesting(
            7,
            1_000L,
            listOf(ServerDefinition.esplora("https://blockstream.info/api/")),
            keys.private,
        )

        assertThrows(InvalidServerManifestException::class.java) {
            ServerManifestCodec.decodeAndVerify(
                signed,
                Base64.getEncoder().encodeToString(keys.public.encoded),
                nowEpochSeconds = 1_001L,
            )
        }
    }

    @Test
    fun `signed manifest rejects cleartext Electrum endpoint`() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val endpoints = listOf(
            ServerDefinition.electrum("electrum.example", 50001, useTls = false),
            ServerDefinition.esplora("https://blockstream.info/api/"),
        )
        val signed = ServerManifestCodec.signForTesting(7, 4_000_000_000L, endpoints, keys.private)

        assertThrows(InvalidServerManifestException::class.java) {
            ServerManifestCodec.decodeAndVerify(
                signed,
                Base64.getEncoder().encodeToString(keys.public.encoded),
                nowEpochSeconds = 2_000_000_000L,
            )
        }
    }

    @Test
    fun `http endpoints must match their declared TLS host and port`() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerDefinition(
                id = "cleartext-esplora",
                role = ServerRole.ESPLORA,
                host = "esplora.example",
                port = 443,
                useTls = true,
                baseUrl = "http://esplora.example/api/",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerDefinition(
                id = "wrong-port-esplora",
                role = ServerRole.ESPLORA,
                host = "esplora.example",
                port = 443,
                useTls = true,
                baseUrl = "https://esplora.example:8443/api/",
            )
        }

        ServerDefinition(
            id = "custom-cleartext-esplora",
            role = ServerRole.ESPLORA,
            host = "esplora.example",
            port = 80,
            useTls = false,
            baseUrl = "http://esplora.example/api/",
            isCustom = true,
        )
    }

    @Test
    fun `signed manifest rejects HTTPS declaration with cleartext HTTP endpoint`() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val payload = """{"version":7,"expiresAt":4000000000,"endpoints":[{"id":"electrum-example","role":"ELECTRUM","host":"electrum.example","port":50002,"tls":true},{"id":"cleartext-esplora","role":"ESPLORA","host":"esplora.example","port":443,"tls":true,"baseUrl":"http://esplora.example/api/"}]}"""
        val signer = Signature.getInstance("Ed25519").apply {
            initSign(keys.private)
            update(payload.toByteArray(Charsets.UTF_8))
        }
        val signed = payload.dropLast(1) + ",\"signature\":\"${Base64.getEncoder().encodeToString(signer.sign())}\"}"

        assertThrows(InvalidServerManifestException::class.java) {
            ServerManifestCodec.decodeAndVerify(
                signed,
                Base64.getEncoder().encodeToString(keys.public.encoded),
                nowEpochSeconds = 2_000_000_000L,
            )
        }
    }

    @Test
    fun `deployed manifest payload with legacy CoinGecko endpoint remains verifiable`() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val endpoints = listOf(
            ServerDefinition.electrum("electrum.example", 50002),
            ServerDefinition.esplora("https://esplora.example/api/"),
            ServerDefinition.http(ServerRole.COIN_GECKO, "coingecko-primary", "https://api.coingecko.com/api/v3/"),
        )
        val signed = ServerManifestCodec.signForTesting(7, 4_000_000_000L, endpoints, keys.private)

        val manifest = ServerManifestCodec.decodeAndVerify(
            signed,
            Base64.getEncoder().encodeToString(keys.public.encoded),
            nowEpochSeconds = 2_000_000_000L,
        )

        assertEquals(endpoints.sortedBy(ServerDefinition::id), manifest.endpoints.sortedBy(ServerDefinition::id))
    }

}
