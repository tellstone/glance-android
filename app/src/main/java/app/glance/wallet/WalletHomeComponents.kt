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


@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TorConnectionSheet(torEnabled: Boolean, offlineMode: Boolean, torState: TorState, readySinceMillis: Long?, onDismiss: () -> Unit, onTorEnabled: (Boolean) -> Unit, onOfflineMode: (Boolean) -> Unit, onRenew: () -> Unit) {
    var confirmDirect by remember { mutableStateOf(false) }
    val indicator = torConnectionIndicator(torEnabled, torState, offlineMode)
    val (label, dot) = when (indicator) {
        TorConnectionIndicator.CONNECTED -> "Tor connected" to TorConnectedDot
        TorConnectionIndicator.CONNECTING -> "Connecting to Tor" to TorConnectingDot
        TorConnectionIndicator.OFFLINE -> "Offline" to TorUnavailableDot
        TorConnectionIndicator.NO_TOR -> if (torEnabled) "Tor unavailable" to TorUnavailableDot else "Tor off" to TorUnavailableDot
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = torConnectionSurfaceColor,
        contentColor = GlanceText,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = HomeScreenGutter, end = HomeScreenGutter, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Connection", style = MaterialTheme.typography.labelLarge, color = GlanceMuted)
            SettingsGroup("Status", "tor_connection_status") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(10.dp).background(dot, androidx.compose.foundation.shape.CircleShape).semantics { contentDescription = label })
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(label, color = GlanceText, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (offlineMode) "All Glance network access is disabled. Cached wallet data remains available."
                            else if (torEnabled) "App network requests are routed through Tor when connected."
                            else "Direct connection: querying servers can see your device IP.",
                            color = GlanceMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        readySinceMillis?.let {
                            Text("Connected for ${(System.currentTimeMillis() - it).coerceAtLeast(0L) / 60_000} min", color = GlanceMuted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            SettingsGroup("Connection controls", "tor_connection_controls") {
                P7SettingSwitch("Offline mode", offlineMode, tag = "tor_offline_mode", change = onOfflineMode)
                SettingsDivider()
                P7SettingSwitch("Use Tor", torEnabled, enabled = !offlineMode, tag = "tor_enabled") { enabled ->
                    if (enabled) onTorEnabled(true) else confirmDirect = true
                }
            }
            Button(
                onClick = onRenew,
                enabled = !offlineMode && torEnabled && (torState is TorState.Ready || torState is TorState.Unavailable),
                shape = torConnectionActionShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = torConnectionPrimaryActionColor,
                    contentColor = GlanceBackground,
                    disabledContainerColor = GlanceSurface,
                    disabledContentColor = GlanceMuted,
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("renew_tor_connection"),
            ) { Text(if (torState is TorState.Unavailable) "Retry Tor" else "Renew Tor connection") }
        }
    }
    if (confirmDirect) AlertDialog(onDismissRequest = { confirmDirect = false }, containerColor = GlanceSurface, titleContentColor = GlanceText, textContentColor = GlanceMuted, title = { Text("Turn off Tor?") }, text = { Text("The querying server will see your device's real IP address. Continue only if you accept this privacy risk.") }, confirmButton = { Button(onClick = { confirmDirect = false; onTorEnabled(false) }, shape = GlancePillShape, colors = ButtonDefaults.buttonColors(containerColor = GlanceWarning, contentColor = GlanceText)) { Text("Turn off Tor") } }, dismissButton = { TextButton(onClick = { confirmDirect = false }, colors = ButtonDefaults.textButtonColors(contentColor = GlanceText)) { Text("Keep Tor on") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeliberatePullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    Box(
        modifier = modifier.pullToRefresh(
            isRefreshing = isRefreshing,
            state = state,
            threshold = WalletSyncPullThreshold,
            onRefresh = onRefresh,
        ),
    ) {
        content()
        PullToRefreshDefaults.Indicator(
            modifier = Modifier.align(Alignment.TopCenter),
            isRefreshing = isRefreshing,
            state = state,
        )
    }
}

internal enum class DashboardDenomination { SATS, FIAT }

internal val defaultDashboardDenomination = DashboardDenomination.SATS

internal data class DashboardChartSelection(
    val timestampSeconds: Long,
    val sats: Long,
    val fiatCents: Long?,
)

internal data class ChartPalette(val line: Color, val fill: Color)

internal fun chartPalette(denomination: DashboardDenomination): ChartPalette = when (denomination) {
    DashboardDenomination.SATS -> ChartPalette(GlanceMandarin, homeChartAreaFill)
    DashboardDenomination.FIAT -> ChartPalette(fiatChartGreen, fiatChartAreaFill)
}

internal enum class DashboardBalanceTextSize { LARGE, REGULAR }

internal fun dashboardBalanceTextSize(
    displayed: DashboardDenomination,
    active: DashboardDenomination,
): DashboardBalanceTextSize = if (displayed == active) DashboardBalanceTextSize.LARGE else DashboardBalanceTextSize.REGULAR

internal fun selectedDashboardDenomination(
    current: DashboardDenomination,
    tapped: DashboardDenomination,
): DashboardDenomination = if (current == tapped) current else tapped

internal fun dashboardSatsForChartSelection(currentBalance: Long, selected: ChartPoint?): Long =
    selected?.value ?: currentBalance

internal fun dashboardChartSelection(
    timestampSeconds: Long?,
    balancePoints: List<ChartPoint>,
    fiatPoints: List<ChartPoint>,
): DashboardChartSelection? {
    val timestamp = timestampSeconds ?: return null
    val sats = balancePoints.firstOrNull { it.timestampSeconds == timestamp }?.value ?: return null
    return DashboardChartSelection(
        timestampSeconds = timestamp,
        sats = sats,
        fiatCents = fiatPoints.firstOrNull { it.timestampSeconds == timestamp }?.value,
    )
}

@Composable internal fun WalletSyncStatus(state: WalletSyncState, onRetry: () -> Unit) {
    when (state) {
        WalletSyncState.Idle -> Unit
        WalletSyncState.Succeeded -> Unit
        WalletSyncState.ConnectingToTor -> SyncStatusContent("Connecting to Tor…", "wallet_connecting")
        // The pull-to-refresh indicator already communicates wallet-sync progress.
        WalletSyncState.Syncing -> Unit
        WalletSyncState.TorUnavailable -> SyncFailureContent(
            "Tor is unavailable. Retry Tor before syncing wallets.",
            "Retry Tor",
            onRetry,
            "wallet_sync_tor_unavailable",
        )
        WalletSyncState.Failed -> SyncFailureContent(
            "Sync paused. Your cached wallet data remains available.",
            "Retry sync",
            onRetry,
            "wallet_sync_failed",
        )
    }
}

@Composable internal fun SyncStatusContent(text: String, tag: String) = Row(
    Modifier.fillMaxWidth().testTag(tag),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
) {
    CircularProgressIndicator(Modifier.size(20.dp), color = GlanceMandarin, strokeWidth = 2.dp)
    Text(text, color = GlanceMuted)
}

@Composable internal fun SyncFailureContent(message: String, action: String, onRetry: () -> Unit, tag: String) = Column(
    Modifier.fillMaxWidth().testTag(tag),
    verticalArrangement = Arrangement.spacedBy(8.dp),
) {
    Text(message, color = GlanceMuted)
    OutlinedButton(onClick = onRetry) { Text(action) }
}

