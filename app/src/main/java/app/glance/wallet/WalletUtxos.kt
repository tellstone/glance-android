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


internal fun ScriptType.displayName(): String = when (this) {
    ScriptType.LEGACY -> "Legacy"
    ScriptType.SEGWIT_COMPAT -> "SegWit compatible"
    ScriptType.NATIVE_SEGWIT -> "Native SegWit"
    ScriptType.TAPROOT -> "Taproot"
}

@Composable internal fun WalletDetailTabs(selected: Int, onSelected: (Int) -> Unit) = Surface(color = GlanceSurface, shape = GlanceCardShape, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)) { Row(Modifier.padding(4.dp)) { listOf("Transactions", "UTXOs").forEachIndexed { index, title -> val active = selected == index; Surface(color = if (active) GlanceMandarin else GlanceSurface, shape = walletDetailTabShape, modifier = Modifier.weight(1f).heightIn(min = 40.dp).clickable { onSelected(index) }.semantics { contentDescription = "$title tab, ${if (active) "selected" else "not selected"}" }) { Box(contentAlignment = Alignment.Center) { Text(title, color = if (active) GlanceBackground else GlanceMuted, style = MaterialTheme.typography.labelLarge) } } } } }

@Composable
internal fun TransactionPaginationControls(page: Int, totalCount: Int, canAdvanceToLoadMore: Boolean = false, onPageSelected: (Int) -> Unit) {
    if (totalCount <= TRANSACTION_PAGE_SIZE && !canAdvanceToLoadMore) return
    val lastPage = transactionPageCount(totalCount) - 1
    Surface(color = GlanceSurface, shape = GlanceCardShape, modifier = Modifier.fillMaxWidth().testTag("transaction_pagination")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { onPageSelected(page - 1) },
                enabled = page > 0,
                modifier = Modifier.testTag("transaction_pagination_previous"),
            ) { Icon(Icons.Filled.ArrowBack, contentDescription = "Previous page", tint = GlanceText, modifier = Modifier.size(18.dp)) }
            Text(
                if (page > lastPage) "Load more transactions" else transactionPageRangeText(page, totalCount),
                color = GlanceMuted,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).semantics { contentDescription = "Transactions ${if (page > lastPage) "load more" else transactionPageRangeText(page, totalCount)}" }.testTag("transaction_pagination_range"),
            )
            IconButton(
                onClick = { onPageSelected(page + 1) },
                enabled = page < lastPage || canAdvanceToLoadMore,
                modifier = Modifier.testTag("transaction_pagination_next"),
            ) { Icon(Icons.Filled.ArrowForward, contentDescription = "Next page", tint = GlanceText, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable internal fun TransactionCard(transaction: TransactionRow, onClick: () -> Unit) = Surface(color = GlanceSurface, shape = GlanceCardShape, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).semantics { contentDescription = "${transactionDirection(transaction.valueSats, transaction.confirmations)} transaction" }) { Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) { TransactionRowContent(transaction.valueSats, transaction.confirmations, transaction.timestamp, transaction.label, Modifier.weight(1f)); AmountText(transaction.valueSats) } }

@Composable
internal fun TransactionRowContent(valueSats: Long, confirmations: Int, timestamp: Long?, label: String?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TransactionDirectionIcon(valueSats, Modifier.testTag("transaction_direction_icon_${if (valueSats < 0) "outgoing" else "incoming"}"))
            Spacer(Modifier.width(6.dp))
            Text(transactionDirection(valueSats, confirmations), color = GlanceText, style = MaterialTheme.typography.titleSmall)
        }
        Text(transactionDateText(timestamp), color = GlanceMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("transaction_timestamp"))
        Text(transactionConfirmationText(confirmations), color = GlanceMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("transaction_confirmations"))
        label?.takeIf(String::isNotBlank)?.let { Text(it, color = GlanceMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
    }
}

@Composable
internal fun TransactionDirectionHeading(valueSats: Long, confirmations: Int) = Row(verticalAlignment = Alignment.CenterVertically) {
    TransactionDirectionIcon(valueSats)
    Spacer(Modifier.width(6.dp))
    Text(transactionDirection(valueSats, confirmations), color = GlanceMandarin, style = MaterialTheme.typography.labelLarge)
}

@Composable
internal fun TransactionDirectionIcon(valueSats: Long, modifier: Modifier = Modifier) = Icon(
    imageVector = if (valueSats < 0) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
    contentDescription = null,
    tint = transactionDirectionColor(valueSats),
    modifier = modifier.size(16.dp),
)

@Composable
internal fun UtxoCard(utxo: UtxoRow, dustThresholdSats: Long, onClick: () -> Unit) = Surface(
    color = GlanceSurface,
    shape = GlanceCardShape,
    modifier = Modifier.fillMaxWidth().testTag("utxo_card_${utxo.id}").clickable(onClick = onClick)
        .semantics { contentDescription = "UTXO, ${utxoConfirmationText(utxo.confirmations)}" },
) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(utxo.label?.takeIf(String::isNotBlank) ?: "Unspent output", color = GlanceText, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text("${abbreviateTransactionIdentifier(utxo.address)} · ${utxoOutputText(utxo.txid, utxo.vout)}", color = GlanceMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Text(utxoConfirmationText(utxo.confirmations), color = GlanceMuted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        AmountText(utxo.valueSats, color = utxoAmountTextColor(utxo.confirmations, utxo.valueSats, dustThresholdSats))
    }
}

@Composable
internal fun BubbleGrid(utxos: List<UtxoRow>, dustThresholdSats: Long, modifier: Modifier, isRefreshing: Boolean, onRefresh: () -> Unit, onUtxo: (UtxoRow) -> Unit) {
    val density = LocalDensity.current
    val packed = remember(utxos, dustThresholdSats) { packUtxoBubbles(utxos.map { BubbleInput(it.id, it.valueSats) }, dustThresholdSats) }
    val byId = remember(utxos) { utxos.associateBy(UtxoRow::id) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val refreshThreshold = with(density) { WalletSyncPullThreshold.toPx() }
    val tapSlop = LocalViewConfiguration.current.touchSlop
    Column(modifier = modifier.fillMaxSize().padding(top = 14.dp)) {
        Surface(color = GlanceSurface, shape = GlanceCardShape, modifier = Modifier.fillMaxSize()) {
            if (utxos.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No unspent outputs", color = GlanceMuted) }
            } else BoxWithConstraints(Modifier.fillMaxSize()) {
                val width = with(density) { maxWidth.toPx() }.toDouble()
                val height = with(density) { maxHeight.toPx() }.toDouble()
                val overview = remember(packed, width, height) { fitPackedBubbles(packed, width, height) }
                val center = Offset((width / 2).toFloat(), (height / 2).toFloat())
                fun displayedCenter(bubble: PackedBubble): Offset = Offset(
                    center.x + ((bubble.centerX.toFloat() - center.x) * zoom) + pan.x,
                    center.y + ((bubble.centerY.toFloat() - center.y) * zoom) + pan.y,
                )
                fun hitBubble(point: Offset): UtxoRow? {
                    val untransformed = Offset(
                        center.x + (point.x - center.x - pan.x) / zoom,
                        center.y + (point.y - center.y - pan.y) / zoom,
                    )
                    return overview.bubbles.asReversed().firstOrNull { bubble ->
                        kotlin.math.hypot((untransformed.x - bubble.centerX).toDouble(), (untransformed.y - bubble.centerY).toDouble()) <= bubble.radius
                    }?.let { byId[it.id] }
                }
                fun constrainedPan(nextZoom: Float, gesture: Offset): Offset {
                    val left = overview.bubbles.minOf { bubble -> center.x + (bubble.centerX.toFloat() - center.x) * nextZoom - bubble.radius.toFloat() * nextZoom }
                    val right = overview.bubbles.maxOf { bubble -> center.x + (bubble.centerX.toFloat() - center.x) * nextZoom + bubble.radius.toFloat() * nextZoom }
                    val top = overview.bubbles.minOf { bubble -> center.y + (bubble.centerY.toFloat() - center.y) * nextZoom - bubble.radius.toFloat() * nextZoom }
                    val bottom = overview.bubbles.maxOf { bubble -> center.y + (bubble.centerY.toFloat() - center.y) * nextZoom + bubble.radius.toFloat() * nextZoom }
                    return Offset(
                        if (right - left <= width) 0f else (pan.x + gesture.x).coerceIn(width.toFloat() - right, -left),
                        if (bottom - top <= height) 0f else (pan.y + gesture.y).coerceIn(height.toFloat() - bottom, -top),
                    )
                }
                val transformState = rememberTransformableState { zoomChange, panChange, _ ->
                    val nextZoom = (zoom * zoomChange).coerceIn(1f, 8f)
                    pan = if (nextZoom == 1f) Offset.Zero else constrainedPan(nextZoom, panChange)
                    zoom = nextZoom
                }
                Canvas(
                    modifier = Modifier.fillMaxSize()
                        .semantics { contentDescription = "UTXO bubble chart. Pinch to zoom and drag to pan." }
                        .pointerInput(overview, zoom, pan, isRefreshing, onRefresh) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var previousPosition = down.position
                                var moved = false
                                var pulledDistance = 0f
                                var refreshed = false
                                do {
                                    val event = awaitPointerEvent()
                                    event.changes.firstOrNull()?.let { change ->
                                        val delta = change.position - previousPosition
                                        previousPosition = change.position
                                        if (delta != Offset.Zero) {
                                            if ((change.position - down.position).getDistance() > tapSlop) moved = true
                                            if (!isRefreshing && !refreshed && zoom <= 1.01f && delta.y > 0f && kotlin.math.abs(delta.y) >= kotlin.math.abs(delta.x)) {
                                                pulledDistance += delta.y
                                                if (pulledDistance >= refreshThreshold) {
                                                    refreshed = true
                                                    change.consume()
                                                    onRefresh()
                                                }
                                            }
                                        }
                                        if (!change.pressed && !moved) hitBubble(down.position)?.let(onUtxo)
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                        .transformable(
                            state = transformState,
                            canPan = { zoom > 1.01f },
                        ),
                ) {
                    overview.bubbles.forEach { bubble ->
                        val displayed = displayedCenter(bubble)
                        val radius = bubble.radius.toFloat() * zoom
                        val tone = utxoVisualTone(byId.getValue(bubble.id).confirmations, bubble.sats, dustThresholdSats)
                        drawCircle(utxoBubbleFill(tone), radius, displayed)
                        drawCircle(utxoToneColor(tone) ?: GlanceMandarin, radius, displayed, style = Stroke(1.5.dp.toPx()))
                    }
                }
                overview.bubbles.forEach { bubble ->
                    val displayed = displayedCenter(bubble)
                    val diameter = (bubble.radius * 2 * zoom).toFloat()
                    val diameterDp = with(density) { diameter.toDp() }
                    Box(
                        Modifier.offset { IntOffset((displayed.x - diameter / 2).roundToInt(), (displayed.y - diameter / 2).roundToInt()) }
                            .size(diameterDp)
                            .clearAndSetSemantics {},
                        contentAlignment = Alignment.Center,
                    ) { BubbleAmountText(bubble.sats, diameterDp) }
                }
                if (zoom > 1.01f || pan != Offset.Zero) {
                    IconButton(
                        onClick = { zoom = 1f; pan = Offset.Zero },
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).semantics { contentDescription = "Reset bubble chart zoom" },
                    ) { Icon(Icons.Filled.Refresh, contentDescription = null, tint = GlanceText) }
                }
            }
        }
    }
}

@Composable
internal fun BubbleAmountText(sats: Long, diameter: Dp) {
    val label = formatCompactUtxoBubbleAmount(sats)
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge.copy(color = GlanceText, fontWeight = utxoBubbleLabelFontWeight)
    val fontSize = remember(label, diameter, density, style, textMeasurer) {
        val availableWidth = with(density) { (diameter * 0.76f).toPx() }
        val availableHeight = with(density) { (diameter * 0.42f).toPx() }
        (UTXO_BUBBLE_LABEL_MAX_FONT_SIZE downTo 8).firstOrNull { candidate ->
            val measurement = textMeasurer.measure(label, style = style.copy(fontSize = candidate.sp), maxLines = 1)
            measurement.size.width <= availableWidth && measurement.size.height <= availableHeight
        }?.sp
    }
    fontSize?.let { Text(label, style = style.copy(fontSize = it), maxLines = 1) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UtxoSheet(utxo: UtxoRow, dustThresholdSats: Long, dismiss: () -> Unit) {
    val context = LocalContext.current
    var copiedField by remember(utxo.id) { mutableStateOf<String?>(null) }
    ModalBottomSheet(
        onDismissRequest = dismiss,
        containerColor = GlanceBackground,
        contentColor = GlanceText,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = HomeScreenGutter, end = HomeScreenGutter, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Unspent output", color = GlanceMuted, style = MaterialTheme.typography.labelLarge)
                AmountText(
                    utxo.valueSats,
                    large = true,
                    color = utxoAmountTextColor(utxo.confirmations, utxo.valueSats, dustThresholdSats),
                )
            }
            UtxoFactsCard(
                utxo = utxo,
                onCopyTransactionId = { copy(context, utxo.txid); copiedField = "Transaction ID copied" },
                onCopyAddress = { copy(context, utxo.address); copiedField = "Address copied" },
            )
            copiedField?.let { Text(it, color = GlanceMuted, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun UtxoFactsCard(
    utxo: UtxoRow,
    onCopyTransactionId: () -> Unit,
    onCopyAddress: () -> Unit,
) = Surface(color = GlanceSurface, shape = GlanceCardShape, modifier = Modifier.fillMaxWidth().testTag("utxo_facts_card")) {
    val facts = buildList {
        utxo.label?.takeIf(String::isNotBlank)?.let { add(UtxoFact("Label", it)) }
        add(UtxoFact("Confirmations", utxoConfirmationText(utxo.confirmations)))
        add(UtxoFact("Output", utxoOutputText(utxo.txid, utxo.vout), onCopyTransactionId, "Copy transaction ID"))
        add(UtxoFact("Address", abbreviateTransactionIdentifier(utxo.address), onCopyAddress, "Copy address"))
        add(UtxoFact("Derivation index", utxo.derivationIndex.toString()))
    }
    Column {
        facts.forEachIndexed { index, fact ->
            TransactionFactRow(fact.label, fact.value, fact.onCopy, fact.copyDescription)
            if (index < facts.lastIndex) TransactionFactDivider()
        }
    }
}

internal data class UtxoFact(
    val label: String,
    val value: String,
    val onCopy: (() -> Unit)? = null,
    val copyDescription: String? = null,
)

@Composable
internal fun ClusteredBitcoinAddress(address: String, accessibilityLabel: String, modifier: Modifier = Modifier) {
    val spans = addressDisplaySpans(address)
    var spanIndex = 0
    val text = buildAnnotatedString {
        address.chunked(4).forEachIndexed { groupIndex, group ->
            var remainingCharacters = group.length
            while (remainingCharacters > 0) {
                val span = spans[spanIndex++]
                val (color, weight) = when (span.tone) {
                    AddressTextTone.PRIMARY -> GlanceText to FontWeight.Bold
                    AddressTextTone.MUTED -> GlanceMuted to FontWeight.Normal
                    AddressTextTone.MANDARIN -> GlanceMandarin to FontWeight.Bold
                }
                withStyle(SpanStyle(color = color, fontWeight = weight)) { append(span.text) }
                remainingCharacters -= span.text.length
            }
            if (groupIndex != address.chunked(4).lastIndex) append(" ")
        }
    }
    Text(
        text = text,
        modifier = modifier.clearAndSetSemantics { contentDescription = accessibilityLabel },
        style = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 17.sp,
            lineHeight = 30.sp,
            letterSpacing = 0.02.em,
        ),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

