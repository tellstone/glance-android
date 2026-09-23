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


@Composable
internal fun MandarinLine(
    points: List<ChartPoint>,
    selected: ChartPoint?,
    range: ChartRange,
    palette: ChartPalette,
    onSelect: (ChartPoint) -> Unit,
    onClear: () -> Unit,
) {
    var chartWidthPx by remember { mutableIntStateOf(0) }
    var labelWidthPx by remember { mutableIntStateOf(0) }
    val selectedIndex = selected?.let(points::indexOf) ?: -1
    val cursorX = if (selectedIndex < 0) 0f else chartWidthPx * selectedIndex.toFloat() / points.lastIndex.coerceAtLeast(1)
    Box(Modifier.fillMaxWidth().height(150.dp).onSizeChanged { chartWidthPx = it.width }) {
        androidx.compose.foundation.Canvas(
            Modifier.matchParentSize().testTag("chart_canvas").pointerInput(points) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun selectAt(x: Float) {
                        val index = chartSelectionIndex(x, size.width.toFloat(), points.size)
                        onSelect(points[index])
                    }
                    selectAt(down.position.x)
                    do {
                        val event = awaitPointerEvent()
                        event.changes.firstOrNull()?.let { selectAt(it.position.x) }
                    } while (event.changes.any { it.pressed })
                    onClear()
                }
            },
        ) {
            val minimum = points.minOf(ChartPoint::value).toFloat()
            val maximum = points.maxOf(ChartPoint::value).toFloat()
            fun yFor(point: ChartPoint): Float = if (maximum == minimum) size.height / 2f else
                size.height - ((point.value - minimum) / (maximum - minimum)) * size.height
            fun xFor(index: Int): Float = size.width * index / points.lastIndex.coerceAtLeast(1)
            fun pathFor(indices: IntRange): Path = Path().apply {
                indices.forEachIndexed { offset, index ->
                    val point = points[index]
                    if (offset == 0) moveTo(xFor(index), yFor(point)) else lineTo(xFor(index), yFor(point))
                }
            }
            fun fillPathFor(indices: IntRange): Path = Path().apply {
                addPath(pathFor(indices))
                lineTo(xFor(indices.last), size.height)
                lineTo(xFor(indices.first), size.height)
                close()
            }
            fun drawSegment(indices: IntRange, lineColor: androidx.compose.ui.graphics.Color, fillColor: androidx.compose.ui.graphics.Color) {
                drawPath(fillPathFor(indices), fillColor)
                drawPath(pathFor(indices), lineColor, style = Stroke(homeChartLineStrokeWidth))
            }
            val segments = selected?.let { point ->
                points.indexOf(point).takeIf { it >= 0 }?.let { chartRenderSegments(points.size, it) }
            }
            if (segments == null) {
                drawSegment(0..points.lastIndex, palette.line, palette.fill)
            } else {
                drawSegment(segments.normal, palette.line, palette.fill)
                segments.muted?.let { drawSegment(it, GlanceMuted, homeChartMutedAreaFill) }
            }
            selected?.let { point ->
                val index = points.indexOf(point)
                if (index >= 0) {
                    val x = xFor(index)
                    val y = yFor(point)
                    if (chartCursorShowsVerticalGuide) {
                        drawLine(GlanceMuted.copy(alpha = 0.65f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
                    }
                    if (chartCursorShowsHorizontalGuide) {
                        drawLine(GlanceMuted.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    }
                    if (chartCursorShowsMarker) drawCircle(palette.line, radius = 7f, center = Offset(x, y))
                }
            }
        }
        selected?.let { point ->
            Text(
                formatChartCursorTimestamp(point.timestampSeconds, range),
                color = GlanceMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .testTag("chart_cursor_time")
                    .offset {
                        IntOffset(
                            chartCursorLabelLeft(cursorX, chartWidthPx.toFloat(), labelWidthPx.toFloat()).roundToInt(),
                            0,
                        )
                    }
                    .offset(y = chartCursorLabelVerticalOffset)
                    .onSizeChanged { labelWidthPx = it.width },
            )
        }
    }
}

internal fun utxoToneColor(tone: UtxoVisualTone): Color? = when (tone) {
    UtxoVisualTone.PENDING -> pendingUtxoColor
    UtxoVisualTone.DUST -> GlanceMuted
    UtxoVisualTone.STANDARD -> null
}

internal fun utxoAmountTextColor(confirmations: Int, valueSats: Long, dustThresholdSats: Long): Color? =
    when (utxoVisualTone(confirmations, valueSats, dustThresholdSats)) {
        UtxoVisualTone.PENDING, UtxoVisualTone.DUST, UtxoVisualTone.STANDARD -> null
    }

