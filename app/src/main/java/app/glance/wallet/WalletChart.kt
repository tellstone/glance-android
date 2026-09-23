package app.glance.wallet

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.PersistableBundle
import androidx.biometric.BiometricManager
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Shield
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.navigation.NavType
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.room.withTransaction
import app.glance.wallet.core.crypto.normalizeWatchedKeyInput
import app.glance.wallet.core.crypto.parseSingleAddress
import app.glance.wallet.core.crypto.parseWatchedKey
import app.glance.wallet.core.crypto.classifyUnifiedImport
import app.glance.wallet.core.crypto.UnifiedImport
import app.glance.wallet.core.data.db.*
import app.glance.wallet.core.data.sync.RoomWalletSyncStore
import app.glance.wallet.core.data.sync.SyncConfig
import app.glance.wallet.core.data.sync.XpubDiscoveryResult
import app.glance.wallet.core.network.MempoolFiatPriceClient
import app.glance.wallet.core.security.*
import app.glance.wallet.presentation.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import java.util.UUID
import java.text.NumberFormat
import java.util.Currency
import java.util.Date
import java.text.DateFormat
import kotlin.math.roundToInt


internal fun chartRangeLabelColor(choice: ChartRange, selected: ChartRange) =
    if (choice == selected) GlanceMandarin else GlanceMuted

@Composable
internal fun BalanceChart(database: GlanceDatabase, fiatStore: FiatPriceStore, currency: String, torEnabled: Boolean, torState: TorState, historicalFiatRefreshRequest: Long, fiat: Boolean, liveEndpointSeconds: Long, liveFiatPrice: Double?, onSelectionChange: (DashboardChartSelection?) -> Unit, offlineMode: Boolean) {
    // DAO methods create cold Flow instances. Keep them stable across a range-change
    // recomposition so collection is not restarted with a fabricated zero balance.
    val historyFlow = remember(database) { database.walletScreenDao().observeChartHistory() }
    val confirmedBalanceFlow = remember(database) { database.utxoDao().observeChartConfirmedBalance() }
    var history by remember(database) { mutableStateOf<List<ChartHistoryRow>?>(null) }
    var currentBalance by remember(database) { mutableStateOf<Long?>(null) }
    LaunchedEffect(historyFlow) { historyFlow.collect { history = it } }
    LaunchedEffect(confirmedBalanceFlow) { confirmedBalanceFlow.collect { currentBalance = it } }
    var range by remember { mutableStateOf(ChartRange.DAY) }
    var selectedTimestamp by remember { mutableStateOf<Long?>(null) }
    var historicalStatus by remember { mutableStateOf(HistoricalFiatStatus.Complete) }
    val events = remember(history) { history.orEmpty().mapNotNull { row -> row.timestamp?.let { ChartPoint(it, row.valueSats) } } }
    val earliest = events.minOfOrNull(ChartPoint::timestampSeconds) ?: liveEndpointSeconds
    val sampleTimestamps = remember(range, liveEndpointSeconds, earliest) { chartSampleTimestamps(liveEndpointSeconds, range, earliest) }
    val historicalSampleTimestamps = remember(sampleTimestamps) { historicalChartSampleTimestamps(sampleTimestamps) }
    val historicalProvider = remember(currency) {
        historicalProviderFor(currency)
    }
    val quotes by fiatStore.observeHistorical(currency).collectAsState(emptyList())
    val balancePoints = remember(events, sampleTimestamps, currentBalance) {
        balanceSnapshots(events, sampleTimestamps, currentBalance)
    }
    val quotesByTimestamp = remember(quotes) { quotes.associate { it.timestamp to it.price } }
    val fiatPoints = fiatChartPoints(balancePoints, quotesByTimestamp, liveEndpointSeconds, liveFiatPrice)
    val partialFiatPoints = availableFiatChartPoints(balancePoints, quotesByTimestamp, liveEndpointSeconds, liveFiatPrice)
    val displayPoints = if (fiat) fiatPoints ?: partialFiatPoints.takeIf { it.isNotEmpty() } else balancePoints
    val candidateIsReady = chartDataIsReady(
        historyLoaded = history != null,
        confirmedBalance = currentBalance,
        confirmedHistoryCount = history?.size ?: 0,
        events = events,
    ) && displayPoints != null
    var lastRenderablePoints by remember(fiat, range, currency) { mutableStateOf<List<ChartPoint>?>(null) }
    SideEffect {
        if (candidateIsReady) lastRenderablePoints = displayPoints
    }
    val renderPoints = chartPointsForDisplay(displayPoints, candidateIsReady, lastRenderablePoints)
    val selectedDisplayPoint = selectedTimestamp?.let { timestamp -> renderPoints?.firstOrNull { it.timestampSeconds == timestamp } }
    val dashboardSelection = dashboardChartSelection(selectedTimestamp, balancePoints, partialFiatPoints)
    val requiresHistoricalFiat = fiat || selectedTimestamp != null

    LaunchedEffect(fiatStore, requiresHistoricalFiat, range, currency, historicalSampleTimestamps, torEnabled, offlineMode, torState, historicalFiatRefreshRequest) {
        if (requiresHistoricalFiat && isHistoricalFiatRouteReady(torEnabled, torState, offlineMode)) {
            historicalStatus = HistoricalFiatStatus.Loading
            historicalStatus = fiatStore.ensureHistorical(currency, historicalSampleTimestamps, useOnion = torEnabled)
        } else if (requiresHistoricalFiat && (torEnabled || offlineMode)) {
            historicalStatus = HistoricalFiatStatus.Unavailable
        }
    }
    LaunchedEffect(range, fiat, currency) {
        selectedTimestamp = null
    }
    LaunchedEffect(dashboardSelection) { onSelectionChange(dashboardSelection) }

    val loadedHistory = history
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("balance_chart")) {
        when {
            renderPoints != null -> MandarinLine(
                points = renderPoints,
                selected = selectedDisplayPoint,
                range = range,
                palette = chartPalette(if (fiat) DashboardDenomination.FIAT else DashboardDenomination.SATS),
                onSelect = { point ->
                    selectedTimestamp = point.timestampSeconds
                },
                onClear = {
                    selectedTimestamp = null
                },
            )
            loadedHistory == null || currentBalance == null -> Text("Loading balance chart…", color = GlanceMuted)
            loadedHistory.isEmpty() -> Text("Balance chart will appear after confirmed history is cached.", color = GlanceMuted)
            !chartDataIsReady(
                historyLoaded = true,
                confirmedBalance = currentBalance,
                confirmedHistoryCount = loadedHistory.size,
                events = events,
            ) -> Text("Balance chart is waiting for complete confirmation history.", color = GlanceMuted)
            displayPoints == null -> historicalFiatRefreshMessage(historicalStatus, torEnabled)?.let { Text(it, color = GlanceMuted) }
        }
        ChartRangeSelector(selected = range, onSelect = { nextRange ->
            if (nextRange != range) {
                range = nextRange
                selectedTimestamp = null
            }
        })
    }
}

@Composable
internal fun ChartRangeSelector(selected: ChartRange, onSelect: (ChartRange) -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().testTag("chart_range_selector_root"),
        contentAlignment = Alignment.Center,
    ) {
        Row(modifier = Modifier.testTag("chart_range_selector")) {
            ChartRange.entries.forEach { choice ->
                val label = when (choice) {
                    ChartRange.DAY -> "1D"
                    ChartRange.WEEK -> "1W"
                    ChartRange.MONTH -> "1M"
                    ChartRange.YEAR -> "1Y"
                    ChartRange.ALL -> "ALL"
                }
                TextButton(
                    onClick = { onSelect(choice) },
                    colors = ButtonDefaults.textButtonColors(contentColor = chartRangeLabelColor(choice, selected)),
                    modifier = Modifier
                        .semantics { contentDescription = if (choice == selected) "Selected chart range $label" else "Chart range $label" }
                        .testTag("chart_range_$label"),
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

