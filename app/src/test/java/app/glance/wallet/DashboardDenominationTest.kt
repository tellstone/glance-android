package app.glance.wallet

import androidx.compose.ui.graphics.Color
import app.glance.wallet.presentation.ChartPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardDenominationTest {
    @Test
    fun `home dashboard defaults to sats`() {
        assertEquals(DashboardDenomination.SATS, defaultDashboardDenomination)
    }

    @Test
    fun `tapping the secondary denomination promotes it to the primary chart denomination`() {
        assertEquals(
            DashboardDenomination.FIAT,
            selectedDashboardDenomination(DashboardDenomination.SATS, DashboardDenomination.FIAT),
        )
    }

    @Test
    fun `dashboard balances use the same text size when neither is selected`() {
        assertEquals(
            dashboardBalanceTextSize(DashboardDenomination.SATS, DashboardDenomination.FIAT),
            dashboardBalanceTextSize(DashboardDenomination.FIAT, DashboardDenomination.SATS),
        )
    }

    @Test
    fun `selected chart point replaces the active dashboard sats total`() {
        assertEquals(42L, dashboardSatsForChartSelection(100L, ChartPoint(10L, 42L)))
        assertEquals(100L, dashboardSatsForChartSelection(100L, null))
    }

    @Test
    fun `sats chart cursor pairs the selected balance with its historical fiat value`() {
        assertEquals(
            DashboardChartSelection(timestampSeconds = 20L, sats = 200L, fiatCents = 456L),
            dashboardChartSelection(
                timestampSeconds = 20L,
                balancePoints = listOf(ChartPoint(10L, 100L), ChartPoint(20L, 200L)),
                fiatPoints = listOf(ChartPoint(10L, 123L), ChartPoint(20L, 456L)),
            ),
        )
    }

    @Test
    fun `fiat chart cursor pairs the selected fiat value with its matching sats balance`() {
        assertEquals(
            DashboardChartSelection(timestampSeconds = 10L, sats = 100L, fiatCents = 123L),
            dashboardChartSelection(
                timestampSeconds = 10L,
                balancePoints = listOf(ChartPoint(10L, 100L), ChartPoint(20L, 200L)),
                fiatPoints = listOf(ChartPoint(10L, 123L), ChartPoint(20L, 456L)),
            ),
        )
    }

    @Test
    fun `sats and fiat charts use their own visual palettes`() {
        assertEquals(GlanceMandarin, chartPalette(DashboardDenomination.SATS).line)
        assertEquals(Color(0xFF85BB65), chartPalette(DashboardDenomination.FIAT).line)
    }
}
