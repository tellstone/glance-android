package app.glance.wallet

import app.glance.wallet.presentation.ChartRange
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartRangeSelectorTest {
    @Test
    fun `chart range labels use muted text with mandarin active text`() {
        assertEquals(GlanceMuted, chartRangeLabelColor(ChartRange.WEEK, ChartRange.DAY))
        assertEquals(GlanceMandarin, chartRangeLabelColor(ChartRange.WEEK, ChartRange.WEEK))
    }

    @Test
    fun `chart cursor shows time only for intraday ranges`() {
        assertTrue(chartCursorShowsTime(ChartRange.DAY))
        assertTrue(chartCursorShowsTime(ChartRange.WEEK))
        assertFalse(chartCursorShowsTime(ChartRange.MONTH))
        assertFalse(chartCursorShowsTime(ChartRange.YEAR))
        assertFalse(chartCursorShowsTime(ChartRange.ALL))
    }

    @Test
    fun `chart cursor label follows and remains within chart bounds`() {
        assertEquals(0f, chartCursorLabelLeft(cursorX = 0f, chartWidth = 100f, labelWidth = 40f))
        assertEquals(30f, chartCursorLabelLeft(cursorX = 50f, chartWidth = 100f, labelWidth = 40f))
        assertEquals(60f, chartCursorLabelLeft(cursorX = 100f, chartWidth = 100f, labelWidth = 40f))
    }

    @Test
    fun `chart cursor timestamp sits above the chart canvas`() {
        assertEquals((-16).dp, chartCursorLabelVerticalOffset)
    }

    @Test
    fun `selected chart retains its marker and vertical guide only`() {
        assertTrue(chartCursorShowsMarker)
        assertTrue(chartCursorShowsVerticalGuide)
        assertFalse(chartCursorShowsHorizontalGuide)
    }

    @Test
    fun `selected chart splits normal and muted rendering at the cursor`() {
        assertEquals(ChartRenderSegments(normal = 0..2, muted = 2..4), chartRenderSegments(5, 2))
        assertEquals(ChartRenderSegments(normal = 0..0, muted = 0..4), chartRenderSegments(5, 0))
        assertEquals(ChartRenderSegments(normal = 0..4, muted = null), chartRenderSegments(5, 4))
    }
}
