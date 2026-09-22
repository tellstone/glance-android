package app.glance.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeChartStyleTest {
    @Test
    fun `home chart area is a restrained mandarin fill`() {
        assertEquals(GlanceMandarin.copy(alpha = 0.12f), homeChartAreaFill)
    }

    @Test
    fun `home chart line has deliberate visual weight`() {
        assertEquals(5f, homeChartLineStrokeWidth)
    }
}
