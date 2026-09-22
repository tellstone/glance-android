package app.glance.wallet.presentation

import app.glance.wallet.UTXO_BUBBLE_LABEL_MAX_FONT_SIZE
import app.glance.wallet.utxoBubbleLabelFontWeight
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletPresentationTest {
    @Test fun `chart waits for both cached history and its confirmed balance`() {
        val events = listOf(ChartPoint(10L, 50_000L))
        assertFalse(chartDataIsReady(historyLoaded = false, confirmedBalance = 50_000L, confirmedHistoryCount = 1, events = events))
        assertFalse(chartDataIsReady(historyLoaded = true, confirmedBalance = null, confirmedHistoryCount = 1, events = events))
        assertTrue(chartDataIsReady(historyLoaded = true, confirmedBalance = 50_000L, confirmedHistoryCount = 1, events = events))
    }

    @Test fun `chart refuses a live endpoint when a confirmed row has no timestamp`() {
        assertFalse(chartDataIsReady(historyLoaded = true, confirmedBalance = 50_000L, confirmedHistoryCount = 1, events = emptyList()))
    }

    @Test fun `chart refuses a live endpoint when cached deltas do not reconcile`() {
        assertFalse(chartDataIsReady(historyLoaded = true, confirmedBalance = 50_000L, confirmedHistoryCount = 1, events = listOf(ChartPoint(10L, 25_000L))))
    }

    @Test fun `chart retains its last valid points during a transient refresh mismatch`() {
        val previous = listOf(ChartPoint(10L, 50_000L), ChartPoint(20L, 50_000L))

        assertEquals(
            previous,
            chartPointsForDisplay(
                candidate = listOf(ChartPoint(10L, 50_000L), ChartPoint(20L, 75_000L)),
                candidateIsReady = false,
                lastRenderable = previous,
            ),
        )
    }

    @Test fun `chart replaces retained points once the refreshed snapshot is valid`() {
        val previous = listOf(ChartPoint(10L, 50_000L))
        val refreshed = listOf(ChartPoint(10L, 50_000L), ChartPoint(20L, 75_000L))

        assertEquals(
            refreshed,
            chartPointsForDisplay(candidate = refreshed, candidateIsReady = true, lastRenderable = previous),
        )
    }

    @Test fun `first incomplete chart has no retained points to render`() {
        assertNull(chartPointsForDisplay(candidate = null, candidateIsReady = false, lastRenderable = null))
    }

    @Test fun `amount splits insignificant leading zeros`() {
        assertEquals(AmountParts("0.00 00", "6 839"), formatSats(6_839))
        assertEquals(AmountParts("0.00 000 00", "1"), formatSats(1))
        assertEquals(AmountParts("", "1.00 000 000"), formatSats(100_000_000))
    }

    @Test fun `compact UTXO bubble amounts use readable sat and bitcoin units`() {
        assertEquals("0 sats", formatCompactUtxoBubbleAmount(0L))
        assertEquals("23 sats", formatCompactUtxoBubbleAmount(23L))
        assertEquals("1.23k sats", formatCompactUtxoBubbleAmount(1_234L))
        assertEquals("100k sats", formatCompactUtxoBubbleAmount(100_000L))
        assertEquals("9.9 M sats", formatCompactUtxoBubbleAmount(9_900_000L))
        assertEquals("₿0.13", formatCompactUtxoBubbleAmount(13_000_000L))
        assertEquals("₿13.3", formatCompactUtxoBubbleAmount(1_330_000_000L))
    }

    @Test fun `compact UTXO bubble amounts promote rounded units`() {
        assertEquals("1 M sats", formatCompactUtxoBubbleAmount(999_999L))
        assertEquals("₿0.1", formatCompactUtxoBubbleAmount(10_000_000L))
    }

    @Test fun `UTXO bubble labels use regular smaller typography`() {
        assertEquals(FontWeight.Normal, utxoBubbleLabelFontWeight)
        assertEquals(18, UTXO_BUBBLE_LABEL_MAX_FONT_SIZE)
    }

    @Test fun `bubble sizing uses the dataset maximum`() {
        val sizing = utxoBubbleSizing(List(98) { 10L } + 1_000_000_000L + 2_000_000_000L)

        assertEquals(2_000_000_000.0, sizing.maxBalance, 0.0)
    }

    @Test fun `bubble radii use the max-normalized alpha power scale`() {
        val maxBalance = 1_000_000.0
        val balance = 100_000.0
        val expected = kotlin.math.sqrt(
            UTXO_BUBBLE_MIN_RADIUS * UTXO_BUBBLE_MIN_RADIUS +
                (balance / maxBalance).pow(0.85) *
                (UTXO_BUBBLE_MAX_RADIUS * UTXO_BUBBLE_MAX_RADIUS -
                    UTXO_BUBBLE_MIN_RADIUS * UTXO_BUBBLE_MIN_RADIUS),
        )

        val small = bubbleRadius(1_000.0, maxBalance)
        val medium = bubbleRadius(balance, maxBalance)
        val mediumLarge = bubbleRadius(750_000.0, maxBalance)
        val largest = bubbleRadius(maxBalance, maxBalance)

        assertEquals(expected, medium, 0.0001)
        assertTrue(UTXO_BUBBLE_MIN_RADIUS <= small && small < medium)
        assertTrue(medium < mediumLarge)
        assertEquals(UTXO_BUBBLE_MAX_RADIUS, largest, 0.0001)
    }

    @Test fun `bubble radius is finite and minimum for invalid inputs`() {
        listOf(
            Double.NaN to 1.0,
            Double.POSITIVE_INFINITY to 1.0,
            0.0 to 1.0,
            -1.0 to 1.0,
            1.0 to 0.0,
            1.0 to Double.POSITIVE_INFINITY,
        ).forEach { (balance, maxBalance) ->
            val radius = bubbleRadius(balance, maxBalance)
            assertTrue(radius.isFinite())
            assertEquals(UTXO_BUBBLE_MIN_RADIUS, radius, 0.0)
        }
    }

    @Test fun `singleton and equal balance datasets produce valid bounded radii`() {
        listOf(listOf<Long>(), listOf(42L), listOf(42L, 42L, 42L)).forEach { balances ->
            val sizing = utxoBubbleSizing(balances)
            val radius = bubbleRadius(42.0, sizing.maxBalance)
            assertTrue(radius.isFinite())
            assertTrue(radius in UTXO_BUBBLE_MIN_RADIUS..UTXO_BUBBLE_MAX_RADIUS)
        }

        assertTrue(isDust(4_999L))
        assertFalse(isDust(5_000L))
    }

    @Test fun `dust threshold accepts zero and uses a strict boundary`() {
        assertFalse(isDust(1L, 0L))
        assertTrue(isDust(4_999L, 5_000L))
        assertFalse(isDust(5_000L, 5_000L))
        assertTrue(isDust(24_999L, 25_000L))
    }

    @Test fun `dust bubbles share a smaller fixed radius`() {
        val bubbles = packUtxoBubbles(
            listOf(
                BubbleInput(1, 100L),
                BubbleInput(2, 4_999L),
                BubbleInput(3, 5_000L),
                BubbleInput(4, 250_000L),
            ),
            dustThresholdSats = 5_000L,
        )

        val dust = bubbles.filter { it.sats < 5_000L }
        val nonDust = bubbles.filter { it.sats >= 5_000L }
        assertTrue(dust.all { it.radius == DUST_UTXO_BUBBLE_RADIUS })
        assertTrue(nonDust.all { it.radius >= UTXO_BUBBLE_MIN_RADIUS })
        assertTrue(dust.all { it.radius < UTXO_BUBBLE_MIN_RADIUS })
    }

    @Test fun `pending UTXO styling takes priority over dust styling`() {
        assertEquals(UtxoVisualTone.PENDING, utxoVisualTone(confirmations = 0, sats = 100L, dustThresholdSats = 5_000L))
        assertEquals(UtxoVisualTone.DUST, utxoVisualTone(confirmations = 1, sats = 100L, dustThresholdSats = 5_000L))
        assertEquals(UtxoVisualTone.STANDARD, utxoVisualTone(confirmations = 1, sats = 5_000L, dustThresholdSats = 5_000L))
    }

    @Test fun `packed bubbles retain source amounts while their radii use the shared maximum`() {
        val bubbles = packUtxoBubbles(listOf(
            BubbleInput(1, 12_000_000L),
            BubbleInput(2, 100_000L),
            BubbleInput(3, 1_000_000_000L),
        ))

        val large = bubbles.first { it.id == 1L }
        val smaller = bubbles.first { it.id == 2L }
        val whale = bubbles.first { it.id == 3L }
        assertEquals(1_000_000_000L, whale.sats)
        assertTrue(smaller.radius < large.radius)
        assertEquals(UTXO_BUBBLE_MAX_RADIUS, whale.radius, 0.0001)
    }

    @Test fun `packed bubbles put the largest at the center and never overlap`() {
        val bubbles = packUtxoBubbles(listOf(
            BubbleInput(1, 12_000_000L),
            BubbleInput(2, 2_000_000L),
            BubbleInput(3, 100_000L),
            BubbleInput(4, 4_999L),
            BubbleInput(5, 25_000L),
        ))

        assertEquals(0.0, bubbles.first().centerX, 0.0)
        assertEquals(0.0, bubbles.first().centerY, 0.0)
        bubbles.forEachIndexed { index, bubble ->
            bubbles.drop(index + 1).forEach { other ->
                val distance = kotlin.math.hypot(bubble.centerX - other.centerX, bubble.centerY - other.centerY)
                assertTrue(distance + 0.0001 >= bubble.radius + other.radius)
            }
        }
    }

    @Test fun `fitted packed bubbles are all visible while keeping the largest centered`() {
        val layout = fitPackedBubbles(
            bubbles = packUtxoBubbles(listOf(
                BubbleInput(1, 12_000_000L), BubbleInput(2, 100_000L), BubbleInput(3, 5_000L),
            )),
            viewportWidth = 320.0,
            viewportHeight = 420.0,
        )

        assertEquals(160.0, layout.bubbles.first().centerX, 0.0001)
        assertEquals(210.0, layout.bubbles.first().centerY, 0.0001)
        assertTrue(layout.bubbles.all { bubble ->
            bubble.centerX - bubble.radius >= 0.0 && bubble.centerX + bubble.radius <= 320.0 &&
                bubble.centerY - bubble.radius >= 0.0 && bubble.centerY + bubble.radius <= 420.0
        })
    }

    @Test fun `timeframe filters points inclusively`() {
        val points = listOf(ChartPoint(0, 1), ChartPoint(100, 2), ChartPoint(86_500, 3))
        assertEquals(listOf(ChartPoint(100, 2), ChartPoint(86_500, 3)), pointsForRange(points, 86_500, ChartRange.DAY))
    }

    @Test fun `fiat conversion preserves fractional currency value`() {
        assertEquals(12.345678, fiatValue(1_000_000L, 1_234.5678), 0.000001)
    }

    @Test fun `chart sampling includes a live endpoint for each range`() {
        val now = 1_727_284_923L

        assertEquals(now, chartSampleTimestamps(now, ChartRange.DAY, 0L).last())
        assertEquals(now, chartSampleTimestamps(now, ChartRange.WEEK, 0L).last())
        assertEquals(now, chartSampleTimestamps(now, ChartRange.MONTH, 0L).last())
        assertEquals(now, chartSampleTimestamps(now, ChartRange.YEAR, 0L).last())
    }

    @Test fun `all timeframes can share one live endpoint without persisting it as history`() {
        val endpoint = 1_727_284_923L

        ChartRange.entries.forEach { range ->
            val samples = chartSampleTimestamps(endpoint, range, endpoint - 400L * 86_400L)
            assertEquals(endpoint, samples.last())
            assertFalse(historicalChartSampleTimestamps(samples).contains(endpoint))
        }
    }

    @Test fun `chart buckets stay UTC aligned before the live endpoint`() {
        val first = chartSampleTimestamps(1_727_284_923L, ChartRange.DAY, 0L)
        val second = chartSampleTimestamps(1_727_284_953L, ChartRange.DAY, 0L)

        assertEquals(first.dropLast(1), second.dropLast(1))
        assertTrue(first.dropLast(1).all { it % 3_600L == 0L })
        assertEquals(1_727_284_923L, first.last())
        assertEquals(1_727_284_953L, second.last())
    }

    @Test fun `chart sampling does not duplicate an aligned live endpoint`() {
        val samples = chartSampleTimestamps(86_400L, ChartRange.DAY, 0L)

        assertEquals(24, samples.size)
        assertEquals(86_400L, samples.last())
        assertEquals(1, samples.count { it == 86_400L })
    }

    @Test fun `all chart uses daily UTC buckets for a three month history`() {
        val now = 1_727_284_923L
        val buckets = chartSampleTimestamps(now, ChartRange.ALL, now - 90L * 86_400L)

        assertEquals(92, buckets.size)
        assertEquals(now, buckets.last())
        assertTrue(buckets.dropLast(1).all { it % 86_400L == 0L })
        assertTrue(buckets.dropLast(1).zipWithNext().all { (first, second) -> second - first == 86_400L })
    }

    @Test fun `all chart switches to UTC week buckets after the daily point limit`() {
        val now = 1_727_284_923L
        val buckets = chartSampleTimestamps(now, ChartRange.ALL, now - 120L * 86_400L)

        assertEquals(now, buckets.last())
        assertTrue(buckets.dropLast(1).all(::isUtcWeekStart))
        assertTrue(buckets.dropLast(1).zipWithNext().all { (first, second) -> second - first == 7L * 86_400L })
    }

    @Test fun `all chart uses at most 120 UTC month buckets for long history`() {
        val buckets = chartSampleTimestamps(1_727_284_923L, ChartRange.ALL, 946_684_800L)

        assertEquals(120, buckets.size)
        assertTrue(buckets.dropLast(1).all(::isUtcMonthStart))
    }

    @Test fun `chart snapshots carry a balance into a range with no transactions`() {
        val events = listOf(ChartPoint(10L, 50L), ChartPoint(20L, -10L))

        assertEquals(
            listOf(ChartPoint(30L, 40L), ChartPoint(40L, 40L)),
            balanceSnapshots(events, listOf(30L, 40L)),
        )
    }

    @Test fun `chart snapshots include deltas at the sample timestamp`() {
        assertEquals(
            listOf(ChartPoint(10L, 50L), ChartPoint(20L, 30L)),
            balanceSnapshots(listOf(ChartPoint(10L, 50L), ChartPoint(20L, -20L)), listOf(10L, 20L)),
        )
    }

    @Test fun `live endpoint includes a newly confirmed balance in every longer range`() {
        val now = 1_727_284_923L
        val events = listOf(ChartPoint(now - 60L, 50_000L))

        listOf(ChartRange.WEEK, ChartRange.MONTH, ChartRange.YEAR, ChartRange.ALL).forEach { range ->
            val snapshots = balanceSnapshots(events, chartSampleTimestamps(now, range, now - 90L * 86_400L))
            assertEquals("$range must end at the current balance", ChartPoint(now, 50_000L), snapshots.last())
            assertEquals("$range must retain the pre-transaction bucket value", 0L, snapshots[snapshots.lastIndex - 1].value)
        }
    }

    @Test fun `cached history starts from zero rather than the confirmed balance`() {
        val snapshots = balanceSnapshots(
            events = listOf(ChartPoint(10L, 10_000L)),
            timestamps = listOf(5L, 10L, 20L),
            currentBalance = 50_000L,
        )

        assertEquals(
            listOf(ChartPoint(5L, 0L), ChartPoint(10L, 10_000L), ChartPoint(20L, 50_000L)),
            snapshots,
        )
    }

    @Test fun `partial history does not invent a prior balance`() {
        val snapshots = balanceSnapshots(
            events = listOf(ChartPoint(100L, -30L)),
            timestamps = listOf(50L, 100L, 200L),
            currentBalance = 20L,
        )

        assertEquals(
            listOf(ChartPoint(50L, 0L), ChartPoint(100L, 0L), ChartPoint(200L, 20L)),
            snapshots,
        )
        assertTrue(snapshots.all { it.value >= 0L })
    }

    @Test fun `fiat chart requires a quote for every balance snapshot`() {
        val balances = listOf(ChartPoint(10L, 100_000_000L), ChartPoint(20L, 200_000_000L))

        assertEquals(null, fiatChartPoints(balances, mapOf(10L to 10.0)))
        assertEquals(
            listOf(ChartPoint(10L, 1_000L), ChartPoint(20L, 4_000L)),
            fiatChartPoints(balances, mapOf(10L to 10.0, 20L to 20.0)),
        )
    }

    @Test fun `fiat live endpoint uses the current quote instead of a range cache value`() {
        val balances = listOf(ChartPoint(10L, 100_000_000L), ChartPoint(30L, 200_000_000L))

        assertEquals(
            listOf(ChartPoint(10L, 1_000L), ChartPoint(30L, 4_000L)),
            fiatChartPoints(
                balancePoints = balances,
                pricesByTimestamp = mapOf(10L to 10.0, 30L to 1.0),
                liveEndpointSeconds = 30L,
                livePrice = 20.0,
            ),
        )
    }

    @Test fun `fiat chart does not turn a valid balance into zero for an invalid quote`() {
        val balances = listOf(ChartPoint(10L, 100_000_000L))

        assertEquals(null, fiatChartPoints(balances, mapOf(10L to 0.0)))
        assertEquals(null, fiatChartPoints(balances, mapOf(10L to Double.NaN)))
    }

    @Test fun `partial cached fiat history remains drawable without inventing missing points`() {
        val balances = listOf(ChartPoint(10L, 100_000_000L), ChartPoint(20L, 200_000_000L))

        assertEquals(
            listOf(ChartPoint(10L, 1_000L)),
            availableFiatChartPoints(balances, mapOf(10L to 10.0)),
        )
    }

    @Test fun `chart cursor snaps to the nearest rendered point`() {
        assertEquals(3, chartSelectionIndex(x = 63f, width = 100f, pointCount = 5))
        assertEquals(0, chartSelectionIndex(x = -1f, width = 100f, pointCount = 5))
        assertEquals(4, chartSelectionIndex(x = 120f, width = 100f, pointCount = 5))
    }

    @Test fun `historical quotes carry forward to chart buckets`() {
        assertEquals(
            mapOf(15L to 10.0, 25L to 20.0),
            historicalPriceMap(
                targets = listOf(15L, 25L),
                quotes = listOf(PricePoint(10L, 10.0), PricePoint(20L, 20.0)),
            ),
        )
    }

    @Test fun `initial fiat refresh ignores an otherwise fresh cached quote`() {
        assertTrue(isFiatRefreshDue(995L, 1_000L, forceInitial = true))
        assertFalse(isFiatRefreshDue(995L, 1_000L, forceInitial = false))
        assertTrue(isFiatRefreshDue(699L, 1_000L, forceInitial = false))
    }
}
