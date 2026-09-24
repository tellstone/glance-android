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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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


internal fun utxoBubbleFill(tone: UtxoVisualTone): Color = when (tone) {
    UtxoVisualTone.PENDING -> pendingUtxoBubbleFill
    UtxoVisualTone.DUST -> dustBubbleFill
    UtxoVisualTone.STANDARD -> utxoBubbleFill
}

@Composable
internal fun AmountText(sats: Long, modifier: Modifier = Modifier, large: Boolean = false, color: Color? = null) {
    val parts = formatSats(sats)
    Text(
        text = buildAnnotatedString {
            withStyle(
                androidx.compose.ui.text.SpanStyle(
                    color = color ?: bitcoinGlyphColor,
                    fontSize = bitcoinGlyphTextScale.em,
                ),
            ) { append("₿ ") }
            withStyle(androidx.compose.ui.text.SpanStyle(color = color ?: GlanceMuted)) { append(parts.muted) }
            withStyle(androidx.compose.ui.text.SpanStyle(color = color ?: GlanceText, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)) { append(parts.significant) }
            append(" sats")
        },
        modifier = modifier,
        style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.bodyLarge,
    )
}
@Composable internal fun FiatAmountText(sats: Long, price: Double?, currency: String, modifier: Modifier = Modifier, large: Boolean = false, regularStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodySmall) { val text = price?.let { NumberFormat.getCurrencyInstance().apply { this.currency = Currency.getInstance(currency) }.format(fiatValue(sats, it)) } ?: "Fiat price unavailable"; Text("≈ $text", color = if (large) GlanceText else GlanceMuted, style = if (large) MaterialTheme.typography.headlineMedium else regularStyle, modifier = modifier) }
@Composable
internal fun FiatCentsAmountText(cents: Long, currency: String, modifier: Modifier = Modifier, large: Boolean = false, regularStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodySmall) {
    Text(
        "≈ ${formatFiatCents(cents, currency)}",
        color = if (large) GlanceText else GlanceMuted,
        style = if (large) MaterialTheme.typography.headlineMedium else regularStyle,
        modifier = modifier,
    )
}

internal fun formatFiatCents(cents: Long, currency: String): String = NumberFormat.getCurrencyInstance().apply {
    this.currency = Currency.getInstance(currency)
}.format(cents / 100.0)
internal fun formatChartTimestamp(timestampSeconds: Long): String = DateFormat.getDateTimeInstance(
    DateFormat.MEDIUM,
    DateFormat.SHORT,
).format(Date(timestampSeconds * 1_000))
internal fun chartCursorShowsTime(range: ChartRange): Boolean = range == ChartRange.DAY || range == ChartRange.WEEK

internal fun formatChartCursorTimestamp(timestampSeconds: Long, range: ChartRange): String =
    if (chartCursorShowsTime(range)) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    } else {
        DateFormat.getDateInstance(DateFormat.MEDIUM)
    }.format(Date(timestampSeconds * 1_000))

internal fun chartCursorLabelLeft(cursorX: Float, chartWidth: Float, labelWidth: Float): Float =
    (cursorX - labelWidth / 2f).coerceIn(0f, (chartWidth - labelWidth).coerceAtLeast(0f))
internal val chartCursorLabelVerticalOffset = (-16).dp

internal data class ChartRenderSegments(val normal: IntRange, val muted: IntRange?)

internal fun chartRenderSegments(pointCount: Int, selectedIndex: Int): ChartRenderSegments {
    require(pointCount > 0)
    require(selectedIndex in 0 until pointCount)
    return ChartRenderSegments(
        normal = 0..selectedIndex,
        muted = (selectedIndex until pointCount).takeIf { selectedIndex < pointCount - 1 },
    )
}
internal const val FIAT_REFRESH_SECONDS = 300L
internal const val MEMPOOL_ONION_API = "http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion/api/"

/** A user-initiated dashboard refresh always asks both independent data sources for fresh data. */
internal suspend fun refreshHomeData(
    refreshWallet: suspend () -> Unit,
    refreshFiat: suspend () -> Unit,
    refreshHistoricalFiat: suspend () -> Unit,
) = coroutineScope {
    launch { refreshWallet() }
    launch { refreshFiat() }
    launch { refreshHistoricalFiat() }
}

internal fun isHistoricalFiatRouteReady(torEnabled: Boolean, torState: TorState, offlineMode: Boolean = false): Boolean =
    !offlineMode && (!torEnabled || torState is TorState.Ready)
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun BackBar(title: String, back: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) = CenterAlignedTopAppBar(
    title = { Text(title, style = MaterialTheme.typography.labelLarge.copy(fontSize = settingsHeaderTextSize)) },
    navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Navigate back") } },
    actions = actions,
    colors = TopAppBarDefaults.topAppBarColors(
        containerColor = settingsTopBarColor,
        titleContentColor = GlanceText,
        navigationIconContentColor = GlanceText,
        actionIconContentColor = GlanceText,
    ),
)

@Composable
internal fun SettingsGroup(label: String, tag: String, content: @Composable ColumnScope.() -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(label, color = GlanceMuted, style = MaterialTheme.typography.labelSmall)
    Surface(color = GlanceSurface, shape = GlanceCardShape, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(content = content)
    }
}

@Composable
internal fun FormFieldLabel(text: String) = Text(
    text,
    color = GlanceMuted,
    style = MaterialTheme.typography.labelSmall,
)

@Composable
internal fun GlanceFilledTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    singleLine: Boolean = false,
    readOnly: Boolean = false,
    trailingIcon: (@Composable (() -> Unit))? = null,
) = TextField(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier,
    placeholder = { Text(placeholder, color = GlanceMuted) },
    minLines = minLines,
    singleLine = singleLine,
    readOnly = readOnly,
    trailingIcon = trailingIcon,
    shape = watchTargetFieldShape,
    colors = TextFieldDefaults.colors(
        focusedContainerColor = watchTargetFieldColor,
        unfocusedContainerColor = watchTargetFieldColor,
        disabledContainerColor = watchTargetFieldColor,
        focusedTextColor = GlanceText,
        unfocusedTextColor = GlanceText,
        cursorColor = GlanceMandarin,
        focusedIndicatorColor = watchTargetFieldColor,
        unfocusedIndicatorColor = watchTargetFieldColor,
        disabledIndicatorColor = watchTargetFieldColor,
    ),
)

@Composable
internal fun SettingsDivider() = HorizontalDivider(color = GlanceBackground.copy(alpha = 0.7f), thickness = 1.dp, modifier = Modifier.padding(start = 16.dp))

@Composable
internal fun P7SettingSwitch(label: String, checked: Boolean, enabled: Boolean = true, tag: String? = null, change: (Boolean) -> Unit) = Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(label, Modifier.weight(1f), color = GlanceText, style = MaterialTheme.typography.bodyMedium)
    Switch(
        checked = checked,
        onCheckedChange = change,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = settingsSwitchCheckedThumbColor,
            checkedTrackColor = settingsSwitchCheckedTrackColor,
            uncheckedThumbColor = GlanceMuted,
            uncheckedTrackColor = GlanceBackground,
            disabledCheckedThumbColor = settingsSwitchCheckedThumbColor.copy(alpha = 0.38f),
            disabledCheckedTrackColor = settingsSwitchCheckedTrackColor.copy(alpha = 0.38f),
        ),
        modifier = (tag?.let(Modifier::testTag) ?: Modifier).scale(settingsSwitchScale),
    )
}

@Composable
internal fun SettingsDisclosureRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    warning: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) = Row(
    modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    icon?.let { it(); Spacer(Modifier.width(8.dp)) }
    Text(label, Modifier.weight(1f), color = if (warning) GlanceWarning else GlanceText, style = MaterialTheme.typography.bodyMedium)
    value?.let { Text(it, color = GlanceMuted, style = MaterialTheme.typography.bodySmall) }
    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = if (warning) GlanceWarning else GlanceMuted, modifier = Modifier.padding(start = 4.dp))
}

@Composable
internal fun SettingsValueRow(label: String, value: String) = Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(label, Modifier.weight(1f), color = GlanceText, style = MaterialTheme.typography.bodyMedium)
    Text(value, color = GlanceMuted, style = MaterialTheme.typography.bodySmall)
}
internal fun copy(context: Context, value: String) {
    val clip = ClipData.newPlainText("Bitcoin value", value)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
}
@Composable
internal fun GlanceQrCode(value: String, contentDescription: String, codeSize: Dp, modifier: Modifier = Modifier) {
    Surface(
        color = qrBackgroundColor,
        shape = qrCardShape,
        modifier = modifier.size(codeSize + qrCardInset * 2),
    ) {
        Image(
            bitmap = qr(value),
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize().padding(qrCardInset),
        )
    }
}

internal fun qr(value: String) = createBitmap(256, 256, Bitmap.Config.ARGB_8888).also { bitmap ->
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 256, 256)
    for (x in 0 until 256) for (y in 0 until 256) {
        bitmap[x, y] = if (matrix[x, y]) qrModuleColor else qrBackgroundColor.toArgb()
    }
}.asImageBitmap()
internal fun ScriptType.toCryptoType() = app.glance.wallet.core.crypto.ScriptType.valueOf(name)
internal fun app.glance.wallet.core.crypto.ScriptType.toDataType() = ScriptType.valueOf(name)
