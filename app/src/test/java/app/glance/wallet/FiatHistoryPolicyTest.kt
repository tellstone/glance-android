package app.glance.wallet

import app.glance.wallet.core.network.FiatProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FiatHistoryPolicyTest {
    @Test fun `historical fiat refresh progress is silent while cached chart data remains visible`() {
        assertNull(historicalFiatRefreshMessage(HistoricalFiatStatus.Loading, torEnabled = true))
        assertNull(historicalFiatRefreshMessage(HistoricalFiatStatus.Partial, torEnabled = true))
    }

    @Test fun `supported currency uses keyless mempool history`() {
        assertEquals(
            FiatProvider.MEMPOOL_SPACE,
            historicalProviderFor(currency = "USD"),
        )
    }

    @Test fun `all range uses mempool without requiring chart granularity in the provider contract`() {
        assertEquals(
            FiatProvider.MEMPOOL_SPACE,
            historicalProviderFor(currency = "USD"),
        )
    }

    @Test fun `old all range has no full history provider for an unsupported mempool currency`() {
        assertNull(
            historicalProviderFor(currency = "SEK"),
        )
    }
}
