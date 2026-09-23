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
@Composable internal fun HomeScreen(database: GlanceDatabase, fiatStore: FiatPriceStore, currency: String, torEnabled: Boolean, offlineMode: Boolean, torState: TorState, torReadySinceMillis: Long?, syncState: WalletSyncState, showChart: Boolean, historicalFiatRefreshRequest: Long, onRetrySync: () -> Unit, onAdd: () -> Unit, onSettings: () -> Unit, onSupport: () -> Unit, onKey: (String) -> Unit, onGroup: (String) -> Unit, onTorEnabled: (Boolean) -> Unit, onOfflineMode: (Boolean) -> Unit, onRenewTor: () -> Unit) {
    val rawKeys by database.walletScreenDao().observeKeyBalances().collectAsState(initial = emptyList())
    val groupedKeys by database.walletScreenDao().observeGroupedBalances().collectAsState(initial = emptyList())
    val keys = rawKeys.filter { it.walletGroupId == null } + groupedKeys
    val contentState = homeContentState(keys.size)
    var connectionSheet by remember { mutableStateOf(false) }
    if (contentState == HomeContentState.ONBOARDING) {
        Scaffold(containerColor = GlanceBackground) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                HomeOnboarding(onAdd = onAdd)
                HomeStatusActions(
                    torEnabled = torEnabled,
                    offlineMode = offlineMode,
                    torState = torState,
                    onConnectionClick = { connectionSheet = true },
                    onSettings = onSettings,
                    modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = HomeScreenGutter, vertical = 4.dp),
                )
            }
        }
        if (connectionSheet) TorConnectionSheet(torEnabled, offlineMode, torState, torReadySinceMillis, onDismiss = { connectionSheet = false }, onTorEnabled = onTorEnabled, onOfflineMode = onOfflineMode, onRenew = onRenewTor)
        return
    }
    val balance by database.utxoDao().observeConfirmedBalance().collectAsState(initial = 0L)
    val quotes by fiatStore.observe(currency).collectAsState(emptyList())
    val latestQuote = quotes.latestPrice()
    var liveChartTimestamp by remember(currency) { mutableLongStateOf(System.currentTimeMillis() / 1_000) }
    LaunchedEffect(balance, latestQuote?.timestamp) {
        liveChartTimestamp = maxOf(System.currentTimeMillis() / 1_000, latestQuote?.timestamp ?: 0L)
    }
    var denomination by remember { mutableStateOf(defaultDashboardDenomination) }
    var selectedChartPoint by remember { mutableStateOf<DashboardChartSelection?>(null) }
    fun selectDenomination(target: DashboardDenomination) {
        denomination = selectedDashboardDenomination(denomination, target)
        selectedChartPoint = null
    }
    var initialFiatRefresh by remember(fiatStore, currency) { mutableStateOf(true) }
    LaunchedEffect(fiatStore, currency, torEnabled, offlineMode, torState, latestQuote?.timestamp) {
        while (isActive) {
            val routeReady = !offlineMode && (!torEnabled || torState is TorState.Ready)
            if (routeReady && isFiatRefreshDue(latestQuote?.timestamp, System.currentTimeMillis() / 1_000, initialFiatRefresh)) {
                fiatStore.refreshCurrent(currency, useOnion = torEnabled)
                initialFiatRefresh = false
            }
            delay(FIAT_REFRESH_SECONDS * 1_000)
        }
    }
    Scaffold(containerColor = GlanceBackground) { padding ->
        DeliberatePullToRefresh(
            isRefreshing = syncState == WalletSyncState.Syncing,
            onRefresh = onRetrySync,
            modifier = Modifier.padding(padding),
        ) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = HomeScreenGutter),
            contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(onClick = onSupport)
                            .semantics { contentDescription = "Open Support / Donate" }
                            .testTag("support_logo"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_donate_heart),
                            contentDescription = null,
                            modifier = Modifier.size(homeSupportHeartIconSize),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Glance", color = GlanceText, style = MaterialTheme.typography.titleLarge)
                    }
                    HomeStatusActions(torEnabled, offlineMode, torState, onConnectionClick = { connectionSheet = true }, onSettings = onSettings)
                }
            }
            if (contentState == HomeContentState.ONBOARDING) {
                item { HomeOnboarding(onAdd) }
            } else {
                item {
                    val satsTextSize = dashboardBalanceTextSize(DashboardDenomination.SATS, denomination)
                    val fiatTextSize = dashboardBalanceTextSize(DashboardDenomination.FIAT, denomination)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (denomination == DashboardDenomination.SATS) {
                            AmountText(selectedChartPoint?.sats ?: balance, Modifier.testTag("dashboard_balance"), large = satsTextSize == DashboardBalanceTextSize.LARGE)
                            selectedChartPoint?.fiatCents?.let { cents ->
                                FiatCentsAmountText(cents, currency, Modifier.testTag("dashboard_fiat_balance").clickable { selectDenomination(DashboardDenomination.FIAT) }, regularStyle = MaterialTheme.typography.bodyLarge)
                            } ?: FiatAmountText(if (selectedChartPoint == null) balance else 0L, if (selectedChartPoint == null) latestQuote?.price else null, currency, Modifier.testTag("dashboard_fiat_balance").clickable { selectDenomination(DashboardDenomination.FIAT) }, regularStyle = MaterialTheme.typography.bodyLarge)
                        } else {
                            selectedChartPoint?.fiatCents?.let { FiatCentsAmountText(it, currency, Modifier.testTag("dashboard_fiat_balance"), large = fiatTextSize == DashboardBalanceTextSize.LARGE) }
                                ?: FiatAmountText(balance, latestQuote?.price, currency, Modifier.testTag("dashboard_fiat_balance"), large = fiatTextSize == DashboardBalanceTextSize.LARGE)
                            AmountText(selectedChartPoint?.sats ?: balance, Modifier.testTag("dashboard_balance").clickable { selectDenomination(DashboardDenomination.SATS) })
                        }
                    }
                }
                if (syncState != WalletSyncState.Succeeded) item { WalletSyncStatus(syncState, onRetrySync) }
                if (showChart) item {
                    BalanceChart(
                        database = database,
                        fiatStore = fiatStore,
                        currency = currency,
                        torEnabled = torEnabled,
                        torState = torState,
                        historicalFiatRefreshRequest = historicalFiatRefreshRequest,
                        fiat = denomination == DashboardDenomination.FIAT,
                        liveEndpointSeconds = liveChartTimestamp,
                        liveFiatPrice = latestQuote?.price,
                        onSelectionChange = { selectedChartPoint = it },
                        offlineMode = offlineMode,
                    )
                }
                items(keys, key = { it.id }) { key ->
                val kind = watchedKeyMetadata(key)
                Surface(
                    color = GlanceSurface,
                    shape = GlanceCardShape,
                    modifier = Modifier.fillMaxWidth().clickable { key.walletGroupId?.let(onGroup) ?: onKey(key.id) }.testTag("watched_key_${key.id}"),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(key.label.ifBlank { kind }, color = GlanceText, style = MaterialTheme.typography.titleMedium)
                            Text(kind, color = GlanceMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            AmountText(key.balanceSats)
                            FiatAmountText(key.balanceSats, latestQuote?.price, currency)
                        }
                    }
                }
                }
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Button(
                            onClick = onAdd,
                            shape = GlancePillShape,
                            colors = ButtonDefaults.buttonColors(containerColor = GlanceMandarin, contentColor = GlanceBackground),
                            modifier = Modifier.heightIn(min = 48.dp).testTag("add_key"),
                        ) { Text("+  Add watch target") }
                    }
                }
            }
        }
        }
    }
    if (connectionSheet) TorConnectionSheet(torEnabled, offlineMode, torState, torReadySinceMillis, onDismiss = { connectionSheet = false }, onTorEnabled = onTorEnabled, onOfflineMode = onOfflineMode, onRenew = onRenewTor)
}

@Composable
internal fun HomeStatusActions(torEnabled: Boolean, offlineMode: Boolean, torState: TorState, onConnectionClick: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier) {
        IconButton(onClick = onConnectionClick, modifier = Modifier.testTag("connection_menu")) {
            val icon = when (torHeaderIcon(torConnectionIndicator(torEnabled, torState, offlineMode))) {
                TorHeaderIcon.FILLED_SHIELD -> Icons.Filled.Shield
                TorHeaderIcon.OUTLINED_SHIELD -> Icons.Outlined.Shield
                TorHeaderIcon.OFFLINE -> Icons.Outlined.CloudOff
            }
            Icon(icon, contentDescription = "Connection and privacy status", tint = GlanceText)
        }
        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = GlanceText) }
    }
}

@Composable
internal fun HomeOnboarding(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val content = homeOnboardingContent()
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 28.dp)
            .testTag("home_onboarding"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = "Glance logo",
            modifier = Modifier.size(92.dp).testTag("home_onboarding_logo"),
        )
        Spacer(Modifier.height(14.dp))
        Text(content.title, color = GlanceText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            content.tagline,
            color = GlanceMuted,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(26.dp))
        content.privacyStatements.forEachIndexed { index, statement ->
            val (icon, iconDescription) = when (index) {
                0 -> Icons.Filled.KeyOff to "Public-key-only privacy"
                1 -> Icons.Filled.Wifi to "Tor routing"
                else -> Icons.Filled.Shield to "PIN and biometric security"
            }
            Row(
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = iconDescription },
                verticalAlignment = Alignment.Top,
            ) {
                Icon(icon, contentDescription = null, tint = GlanceMandarin, modifier = Modifier.size(20.dp).padding(top = 2.dp))
                Spacer(Modifier.width(12.dp))
                Text(statement, color = GlanceText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            if (index != content.privacyStatements.lastIndex) Spacer(Modifier.height(16.dp))
        }
        Spacer(Modifier.height(34.dp))
        Button(
            onClick = onAdd,
            shape = GlancePillShape,
            colors = ButtonDefaults.buttonColors(containerColor = GlanceMandarin, contentColor = GlanceBackground),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("add_key"),
        ) { Text("+  ${content.primaryActionLabel}") }
        Spacer(Modifier.height(6.dp))
        Text(
            content.backupPrompt,
            color = GlanceMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("restore_backup_prompt"),
        )
    }
}

internal fun watchedKeyMetadata(key: KeyBalanceRow): String = when (key.targetType) {
    WatchTargetType.SINGLE_ADDRESS -> "Single address"
    WatchTargetType.HD_KEY -> key.scriptType?.name?.replace('_', ' ') ?: if (key.walletGroupId != null) "Multi-format wallet" else "Watched key"
}

