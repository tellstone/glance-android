package app.glance.wallet.presentation

import kotlin.math.sqrt
import kotlin.math.roundToLong
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class AmountParts(val muted: String, val significant: String)

/** Canonical BTC-with-sats-suffix display, grouped for legibility without changing value. */
fun formatSats(sats: Long): AmountParts {
    val sign = if (sats < 0) "-" else ""
    val digits = kotlin.math.abs(sats).toString().padStart(9, '0')
    val btc = digits.dropLast(8)
    val fraction = digits.takeLast(8)
    val grouped = "$btc.${fraction.take(2)} ${fraction.substring(2, 5)} ${fraction.drop(5)}"
    val firstSignificant = grouped.indexOfFirst { it in '1'..'9' }
    return if (firstSignificant < 0) AmountParts(sign + grouped.dropLast(1), "0")
    else AmountParts(sign + grouped.substring(0, firstSignificant), grouped.substring(firstSignificant))
}

internal const val UTXO_BUBBLE_MIN_RADIUS = 3.0
internal const val UTXO_BUBBLE_MAX_RADIUS = 16.0
internal const val UTXO_BUBBLE_POWER_ALPHA = 0.85
const val DEFAULT_DUST_THRESHOLD_SATS = 5_000L
const val DUST_UTXO_BUBBLE_RADIUS = 2.5

data class UtxoBubbleSizing(val maxBalance: Double)

/** Computes the shared visual scale from the positive UTXOs eligible for bubble packing. */
fun utxoBubbleSizing(balances: List<Long>): UtxoBubbleSizing {
    val maxBalance = balances.asSequence()
        .filter { it > 0L }
        .map(Long::toDouble)
        .maxOrNull()
        ?: 0.0
    return UtxoBubbleSizing(maxBalance)
}

/** Compact, readable amount for a label constrained to a UTXO bubble. */
fun formatCompactUtxoBubbleAmount(sats: Long): String {
    val sign = if (sats < 0L) "-" else ""
    val magnitude = if (sats < 0L) -sats.toDouble() else sats.toDouble()
    val amount = when {
        magnitude < 1_000.0 -> "${magnitude.toLong()} sats"
        magnitude < 1_000_000.0 -> {
            val thousands = magnitude / 1_000.0
            if (thousands >= 999.5) "${formatCompactNumber(magnitude / 1_000_000.0)} M sats"
            else "${formatCompactNumber(thousands)}k sats"
        }
        magnitude < 10_000_000.0 -> "${formatCompactNumber(magnitude / 1_000_000.0)} M sats"
        else -> "₿${formatCompactNumber(magnitude / 100_000_000.0)}"
    }
    return sign + amount
}

private fun formatCompactNumber(value: Double): String {
    val decimalPlaces = when {
        value < 10.0 -> 2
        value < 100.0 -> 1
        else -> 0
    }
    val formatted = String.format(Locale.ROOT, "%.${decimalPlaces}f", value)
    return if (decimalPlaces == 0) formatted else formatted.trimEnd('0').trimEnd('.')
}

/**
 * Maps a source balance to a bounded visual radius without modifying its source value.
 * Circle area, rather than radius, is interpolated after max-normalized power scaling.
 */
fun bubbleRadius(
    balance: Double,
    maxBalance: Double,
    minRadius: Double = UTXO_BUBBLE_MIN_RADIUS,
    maxRadius: Double = UTXO_BUBBLE_MAX_RADIUS,
    alpha: Double = UTXO_BUBBLE_POWER_ALPHA,
): Double {
    val safeMinimum = minRadius.takeIf { it.isFinite() && it >= 0.0 } ?: UTXO_BUBBLE_MIN_RADIUS
    if (
        !balance.isFinite() || !maxBalance.isFinite() || !maxRadius.isFinite() || !alpha.isFinite() ||
        balance <= 0.0 || maxBalance <= 0.0 || maxRadius < safeMinimum || alpha <= 0.0
    ) return safeMinimum

    var normalized = (balance / maxBalance).pow(alpha)
    if (!normalized.isFinite()) return safeMinimum
    normalized = normalized.coerceIn(0.0, 1.0)

    val area = safeMinimum * safeMinimum + normalized * (maxRadius * maxRadius - safeMinimum * safeMinimum)
    return sqrt(area).takeIf { it.isFinite() } ?: safeMinimum
}

fun isDust(sats: Long, dustThresholdSats: Long = DEFAULT_DUST_THRESHOLD_SATS): Boolean =
    sats < dustThresholdSats.coerceAtLeast(0L)

enum class UtxoVisualTone { PENDING, DUST, STANDARD }

fun utxoVisualTone(confirmations: Int, sats: Long, dustThresholdSats: Long): UtxoVisualTone = when {
    confirmations == 0 -> UtxoVisualTone.PENDING
    isDust(sats, dustThresholdSats) -> UtxoVisualTone.DUST
    else -> UtxoVisualTone.STANDARD
}

/** Input kept UI-independent so the bubble geometry can be verified on the JVM. */
data class BubbleInput(val id: Long, val sats: Long)

/** Circle coordinates use bounded visual-radius units around the largest source UTXO. */
data class PackedBubble(val id: Long, val sats: Long, val centerX: Double, val centerY: Double, val radius: Double)

data class FittedBubbleLayout(val bubbles: List<PackedBubble>, val scale: Double)

/**
 * Produces a stable close-packed cluster. The largest output is deliberately anchored at the
 * origin; each following circle is placed tangentially to the nearest valid frontier location.
 */
fun packUtxoBubbles(
    inputs: List<BubbleInput>,
    dustThresholdSats: Long = DEFAULT_DUST_THRESHOLD_SATS,
): List<PackedBubble> {
    val sorted = inputs.filter { it.sats > 0L }.sortedWith(compareByDescending<BubbleInput> { it.sats }.thenBy { it.id })
    if (sorted.isEmpty()) return emptyList()
    val sizing = utxoBubbleSizing(sorted.map(BubbleInput::sats))
    fun radiusFor(input: BubbleInput): Double =
        if (isDust(input.sats, dustThresholdSats)) DUST_UTXO_BUBBLE_RADIUS
        else bubbleRadius(input.sats.toDouble(), sizing.maxBalance)
    val packed = mutableListOf(PackedBubble(sorted.first().id, sorted.first().sats, 0.0, 0.0, radiusFor(sorted.first())))
    sorted.drop(1).forEach { input ->
        val radius = radiusFor(input)
        val candidates = mutableListOf<Pair<Double, Double>>()
        packed.forEach { existing ->
            val distance = existing.radius + radius
            listOf(0.0, Math.PI / 2, Math.PI, -Math.PI / 2).forEach { angle ->
                candidates += existing.centerX + cos(angle) * distance to existing.centerY + sin(angle) * distance
            }
        }
        packed.indices.forEach { first -> packed.drop(first + 1).forEach { second ->
            tangentCenters(packed[first], second, radius).forEach { candidates += it }
        } }
        val (x, y) = candidates.asSequence()
            .filter { (x, y) -> packed.all { existing -> distance(x, y, existing.centerX, existing.centerY) + 1e-7 >= radius + existing.radius } }
            .sortedWith(compareBy<Pair<Double, Double>> { (x, y) -> distance(x, y, 0.0, 0.0) }.thenBy { it.second }.thenBy { it.first })
            .firstOrNull() ?: error("Unable to pack UTXO bubble")
        packed += PackedBubble(input.id, input.sats, x, y, radius)
    }
    return packed
}

/** Scales and translates a packed cluster so the overview remains inside its viewport. */
fun fitPackedBubbles(bubbles: List<PackedBubble>, viewportWidth: Double, viewportHeight: Double): FittedBubbleLayout {
    if (bubbles.isEmpty() || viewportWidth <= 0.0 || viewportHeight <= 0.0) return FittedBubbleLayout(emptyList(), 1.0)
    val halfWidth = bubbles.maxOf { abs(it.centerX) + it.radius }.coerceAtLeast(1.0)
    val halfHeight = bubbles.maxOf { abs(it.centerY) + it.radius }.coerceAtLeast(1.0)
    val scale = min(viewportWidth / (2 * halfWidth), viewportHeight / (2 * halfHeight)) * (1.0 - 1e-9)
    return FittedBubbleLayout(
        bubbles.map { it.copy(centerX = viewportWidth / 2 + it.centerX * scale, centerY = viewportHeight / 2 + it.centerY * scale, radius = it.radius * scale) },
        scale,
    )
}

private fun tangentCenters(first: PackedBubble, second: PackedBubble, radius: Double): List<Pair<Double, Double>> {
    val dx = second.centerX - first.centerX
    val dy = second.centerY - first.centerY
    val between = kotlin.math.hypot(dx, dy)
    if (between <= 0.0) return emptyList()
    val firstDistance = first.radius + radius
    val secondDistance = second.radius + radius
    val along = (firstDistance * firstDistance - secondDistance * secondDistance + between * between) / (2 * between)
    val heightSquared = (firstDistance * firstDistance - along * along).coerceAtLeast(0.0)
    val angle = atan2(dy, dx)
    val height = sqrt(heightSquared)
    return listOf(
        first.centerX + along * cos(angle) - height * sin(angle) to first.centerY + along * sin(angle) + height * cos(angle),
        first.centerX + along * cos(angle) + height * sin(angle) to first.centerY + along * sin(angle) - height * cos(angle),
    )
}

private fun distance(x1: Double, y1: Double, x2: Double, y2: Double): Double = kotlin.math.hypot(x1 - x2, y1 - y2)

data class ChartPoint(val timestampSeconds: Long, val value: Long)
data class PricePoint(val timestampSeconds: Long, val price: Double)
enum class ChartRange(val seconds: Long?) { DAY(86_400), WEEK(604_800), MONTH(2_592_000), YEAR(31_536_000), ALL(null) }

/** Fiat values deliberately remain decimal; rounding belongs solely to the display formatter. */
fun fiatValue(sats: Long, bitcoinPrice: Double): Double = sats.toDouble() / 100_000_000.0 * bitcoinPrice

fun isFiatRefreshDue(lastTimestampSeconds: Long?, nowSeconds: Long, forceInitial: Boolean): Boolean =
    forceInitial || lastTimestampSeconds == null || nowSeconds - lastTimestampSeconds >= 300L

/**
 * Fixed chart density prevents a long wallet history from translating into an unbounded number
 * of public price requests. ALL uses the finest UTC-aligned bucket series that fits alongside
 * its live endpoint within 120 points: daily for short histories, weekly for medium histories,
 * and monthly for long histories.
 */
fun chartSampleTimestamps(nowSeconds: Long, range: ChartRange, earliestSeconds: Long): List<Long> {
    val calendar = utcCalendar(nowSeconds)
    val (count, field, amount) = when (range) {
        ChartRange.DAY -> Triple(24, Calendar.HOUR_OF_DAY, 1)
        ChartRange.WEEK -> Triple(28, Calendar.HOUR_OF_DAY, 6)
        ChartRange.MONTH -> Triple(30, Calendar.DAY_OF_MONTH, 1)
        ChartRange.YEAR -> Triple(52, Calendar.WEEK_OF_YEAR, 1)
        ChartRange.ALL -> return allChartSampleTimestamps(nowSeconds, earliestSeconds)
            .withLiveChartEndpoint(nowSeconds)
            .takeLast(MAX_ALL_CHART_BUCKETS)
    }
    when (range) {
        ChartRange.DAY -> {
            calendar.set(Calendar.MINUTE, 0)
            resetSeconds(calendar)
        }
        ChartRange.WEEK, ChartRange.MONTH -> resetTime(calendar)
        ChartRange.YEAR -> {
            calendar.firstDayOfWeek = Calendar.MONDAY
            calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            resetTime(calendar)
        }
        ChartRange.ALL -> error("Handled above")
    }
    return (count - 1 downTo 0).map { offset ->
        (calendar.clone() as Calendar).apply { add(field, -offset * amount) }.timeInMillis / 1_000
    }.withLiveChartEndpoint(nowSeconds)
}

/** A range's right edge is the current balance, while earlier points remain stable UTC buckets. */
private fun List<Long>.withLiveChartEndpoint(nowSeconds: Long): List<Long> =
    if (lastOrNull() == nowSeconds) this else this + nowSeconds

/** The final point is a live dashboard value, never a persisted historical-price bucket. */
fun historicalChartSampleTimestamps(samples: List<Long>): List<Long> = samples.dropLast(1)

private const val MAX_ALL_CHART_BUCKETS = 120
private const val SECONDS_PER_DAY = 86_400L
private const val SECONDS_PER_WEEK = 7L * SECONDS_PER_DAY

private fun allChartSampleTimestamps(nowSeconds: Long, earliestSeconds: Long): List<Long> {
    val currentDay = utcDayStart(nowSeconds)
    val earliestDay = utcDayStart(earliestSeconds)
    val days = bucketCount(earliestDay, currentDay, SECONDS_PER_DAY)
    if (days <= MAX_ALL_CHART_BUCKETS) {
        return utcBucketTimestamps(currentDay, days, Calendar.DAY_OF_MONTH)
    }

    val currentWeek = utcWeekStart(nowSeconds)
    val earliestWeek = utcWeekStart(earliestSeconds)
    val weeks = bucketCount(earliestWeek, currentWeek, SECONDS_PER_WEEK)
    if (weeks <= MAX_ALL_CHART_BUCKETS) {
        return utcBucketTimestamps(currentWeek, weeks, Calendar.WEEK_OF_YEAR)
    }

    val currentMonth = utcMonthStart(nowSeconds)
    val earliestMonth = utcMonthStart(earliestSeconds)
    val months = monthsBetween(earliestMonth, currentMonth).coerceIn(1, MAX_ALL_CHART_BUCKETS)
    return utcBucketTimestamps(currentMonth, months, Calendar.MONTH)
}

private fun bucketCount(earliestBucketSeconds: Long, currentBucketSeconds: Long, bucketSeconds: Long): Int =
    ((currentBucketSeconds - earliestBucketSeconds) / bucketSeconds + 1)
        .coerceIn(1L, Int.MAX_VALUE.toLong())
        .toInt()

private fun utcBucketTimestamps(currentBucketSeconds: Long, count: Int, field: Int): List<Long> =
    (count - 1 downTo 0).map { offset ->
        (utcCalendar(currentBucketSeconds).apply { add(field, -offset) }).timeInMillis / 1_000
    }

private fun utcDayStart(timestampSeconds: Long): Long = utcCalendar(timestampSeconds).apply {
    resetTime(this)
}.timeInMillis / 1_000

internal fun isUtcWeekStart(timestampSeconds: Long): Boolean = timestampSeconds == utcWeekStart(timestampSeconds)

private fun utcWeekStart(timestampSeconds: Long): Long = utcCalendar(timestampSeconds).apply {
    firstDayOfWeek = Calendar.MONDAY
    set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    resetTime(this)
}.timeInMillis / 1_000

fun isUtcMonthStart(timestampSeconds: Long): Boolean = timestampSeconds == utcMonthStart(timestampSeconds)

private fun utcMonthStart(timestampSeconds: Long): Long = utcCalendar(timestampSeconds).apply {
    set(Calendar.DAY_OF_MONTH, 1)
    resetTime(this)
}.timeInMillis / 1_000

private fun monthsBetween(earliestMonthSeconds: Long, currentMonthSeconds: Long): Int {
    val earliest = utcCalendar(earliestMonthSeconds)
    val current = utcCalendar(currentMonthSeconds)
    return ((current.get(Calendar.YEAR) - earliest.get(Calendar.YEAR)) * 12 +
        current.get(Calendar.MONTH) - earliest.get(Calendar.MONTH) + 1).coerceAtLeast(1)
}

private fun utcCalendar(timestampSeconds: Long): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
    timeInMillis = timestampSeconds * 1_000
}

private fun resetTime(calendar: Calendar) {
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    resetSeconds(calendar)
}

private fun resetSeconds(calendar: Calendar) {
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
}

fun pointsForRange(points: List<ChartPoint>, nowSeconds: Long, range: ChartRange): List<ChartPoint> {
    val start = range.seconds?.let { nowSeconds - it } ?: Long.MIN_VALUE
    return points.filter { it.timestampSeconds in start..nowSeconds }
}

/**
 * A balance chart must never turn an incomplete replay into a fictional current-time deposit.
 * All confirmed rows need confirmation dates and their signed deltas must explain the UTXO total.
 */
fun chartDataIsReady(
    historyLoaded: Boolean,
    confirmedBalance: Long?,
    confirmedHistoryCount: Int,
    events: List<ChartPoint>,
): Boolean = historyLoaded && confirmedBalance != null &&
    events.size == confirmedHistoryCount && events.sumOf(ChartPoint::value) == confirmedBalance

/** Keeps a truthful previously rendered chart visible while Room emits an incomplete refresh snapshot. */
fun chartPointsForDisplay(
    candidate: List<ChartPoint>?,
    candidateIsReady: Boolean,
    lastRenderable: List<ChartPoint>?,
): List<ChartPoint>? = if (candidateIsReady) candidate else lastRenderable

/**
 * Produces a fixed-density balance history by replaying cached confirmed transaction deltas from
 * zero. This deliberately includes events before a selected range so a wallet with no recent
 * transaction still has a truthful chart without inventing unavailable earlier balances.
 */
fun balanceSnapshots(
    events: List<ChartPoint>,
    timestamps: List<Long>,
    currentBalance: Long? = null,
): List<ChartPoint> {
    val deltas = events.groupBy(ChartPoint::timestampSeconds)
        .mapValues { (_, points) -> points.sumOf(ChartPoint::value) }
        .toSortedMap()
    var balance = 0L
    var nextDelta = deltas.entries.iterator()
    var pending = if (nextDelta.hasNext()) nextDelta.next() else null
    val snapshots = timestamps.sorted().map { timestamp ->
        while (pending != null && pending.key <= timestamp) {
            balance = (balance + pending.value).coerceAtLeast(0L)
            pending = if (nextDelta.hasNext()) nextDelta.next() else null
        }
        ChartPoint(timestamp, balance)
    }
    return if (currentBalance != null && snapshots.isNotEmpty()) {
        snapshots.dropLast(1) + ChartPoint(snapshots.last().timestampSeconds, currentBalance)
    } else {
        snapshots
    }
}

/** A fiat chart is valid only when every balance sample has its matching historical quote. */
fun fiatChartPoints(
    balancePoints: List<ChartPoint>,
    pricesByTimestamp: Map<Long, Double>,
    liveEndpointSeconds: Long? = null,
    livePrice: Double? = null,
): List<ChartPoint>? =
    balancePoints.map { point ->
        val price = chartPriceFor(point.timestampSeconds, pricesByTimestamp, liveEndpointSeconds, livePrice)
            ?: return null
        ChartPoint(point.timestampSeconds, (fiatValue(point.value, price) * 100).roundToLong())
    }

/** Cached historical buckets may be partial while an unavailable provider is retried later. */
fun availableFiatChartPoints(
    balancePoints: List<ChartPoint>,
    pricesByTimestamp: Map<Long, Double>,
    liveEndpointSeconds: Long? = null,
    livePrice: Double? = null,
): List<ChartPoint> =
    balancePoints.mapNotNull { point ->
        chartPriceFor(point.timestampSeconds, pricesByTimestamp, liveEndpointSeconds, livePrice)?.let { price ->
            ChartPoint(point.timestampSeconds, (fiatValue(point.value, price) * 100).roundToLong())
        }
    }

private fun isUsableChartPrice(price: Double): Boolean = price.isFinite() && price > 0.0

private fun chartPriceFor(
    timestampSeconds: Long,
    pricesByTimestamp: Map<Long, Double>,
    liveEndpointSeconds: Long?,
    livePrice: Double?,
): Double? =
    (if (timestampSeconds == liveEndpointSeconds) livePrice else pricesByTimestamp[timestampSeconds])
        ?.takeIf(::isUsableChartPrice)

/** Maps provider-native timestamps to chart buckets without inventing a future price. */
fun historicalPriceMap(targets: List<Long>, quotes: List<PricePoint>): Map<Long, Double> {
    val sortedQuotes = quotes.sortedBy(PricePoint::timestampSeconds)
    var quoteIndex = 0
    var latest: PricePoint? = null
    return targets.sorted().associateWith { target ->
        while (quoteIndex < sortedQuotes.size && sortedQuotes[quoteIndex].timestampSeconds <= target) {
            latest = sortedQuotes[quoteIndex++]
        }
        latest?.price?.takeIf(::isUsableChartPrice)
    }.filterValues { it != null }.mapValues { requireNotNull(it.value) }
}

fun chartSelectionIndex(x: Float, width: Float, pointCount: Int): Int {
    require(pointCount > 0) { "A chart needs at least one point" }
    if (pointCount == 1 || width <= 0f) return 0
    return ((x / width) * (pointCount - 1)).roundToInt().coerceIn(0, pointCount - 1)
}
