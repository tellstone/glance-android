package app.glance.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.glance.wallet.core.network.MempoolFiatPriceClient
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
 * source, logs, Gradle properties, or test output. The test uses only the fixed Mempool onion
 * endpoint while Tor is enabled, so a successful request cannot silently fall back to clearnet.
 */
@RunWith(AndroidJUnit4::class)
class TorLiveSmokeTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val enabled = arguments.getString("glance.live.mempool_onion")?.toBooleanStrictOrNull() == true

    @Test
    fun mempoolCurrentAndHistoryWorkOverTorOnly() = runBlocking {
        assumeTrue(enabled)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tor = TorController(context)
        try {
            tor.setEnabled(true)
            withTimeout(60_000L) { tor.state.filterIsInstance<TorState.Ready>().first() }
            val client = MempoolFiatPriceClient(MEMPOOL_ONION_API, tor)
            assertTrue("Mempool onion current price was unavailable", client.currentPrice("USD").price > 0)
            assertTrue(
                "Mempool onion historical price was unavailable",
                client.historicalPrices(
                    currency = "USD",
                    fromEpochSeconds = 1_700_000_000L,
                    toEpochSeconds = 1_700_000_000L,
                ).single().price > 0,
            )
        } finally {
            tor.clear()
        }
    }

    private companion object {
        const val MEMPOOL_ONION_API = "http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion/api/"
    }
}
