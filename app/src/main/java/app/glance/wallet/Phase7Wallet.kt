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

private object Routes { const val HOME = "home"; const val ADD = "add"; const val SETTINGS = "settings"; const val SUPPORT = "support"; const val DETAIL = "detail/{id}"; const val GROUP_DETAIL = "group-detail/{id}"; const val GROUP_SETTINGS = "group-settings/{id}"; const val GROUP_TRANSACTION = "group-transaction/{groupId}/{txid}"; const val RECEIVE = "receive/{id}"; const val WALLET_SETTINGS = "wallet-settings/{id}"; const val TRANSACTION = "transaction/{historyId}" }

/** Prevent rapid taps on an outgoing back arrow from removing the Home root destination. */
internal fun canPopPhase7BackStack(hasPreviousDestination: Boolean): Boolean = hasPreviousDestination

internal fun NavController.popPhase7BackStackSafely(): Boolean =
    if (canPopPhase7BackStack(previousBackStackEntry != null)) popBackStack() else false

/** Wallet reconciliation makes network requests, so require a deliberate pull rather than a small scroll. */
internal val WalletSyncPullThreshold = 160.dp
internal const val TRANSACTION_PAGE_SIZE = 25
internal val HomeScreenGutter = 16.dp
internal enum class HomeContentState { ONBOARDING, DASHBOARD }

internal fun homeContentState(watchTargetCount: Int): HomeContentState =
    if (watchTargetCount == 0) HomeContentState.ONBOARDING else HomeContentState.DASHBOARD

internal data class HomeOnboardingContent(
    val title: String,
    val tagline: String,
    val privacyStatements: List<String>,
    val primaryActionLabel: String,
    val backupPrompt: String,
)

internal fun homeOnboardingContent() = HomeOnboardingContent(
    title = "Glance",
    tagline = "See your bitcoin. Nothing else.",
    privacyStatements = listOf(
        "Public keys only — your private keys never touch this app",
        "Routed through Tor by default, no accounts, no servers of ours",
        "PIN, biometric, and duress protection built in from the start",
    ),
    primaryActionLabel = "Add your first key",
    backupPrompt = "Restoring from a backup?",
)

internal val GlanceCardShape = RoundedCornerShape(16.dp)
internal val GlancePillShape = RoundedCornerShape(50)
internal val settingsTopBarColor = GlanceBackground
internal val settingsSwitchCheckedTrackColor = GlanceMandarin
internal val settingsSwitchCheckedThumbColor = GlanceText
internal val settingsActionButtonShape = GlancePillShape
internal const val settingsSwitchScale = 0.82f
internal val settingsHeaderTextSize = 16.sp
internal val torConnectionSurfaceColor = GlanceBackground
internal val torConnectionCardShape = GlanceCardShape
internal val torConnectionPrimaryActionColor = GlanceMandarin
internal val torConnectionActionShape = GlancePillShape
internal val watchTargetFieldColor = GlanceSurface
internal val watchTargetFieldShape = GlanceCardShape
internal val watchTargetPrimaryActionColor = GlanceMandarin
internal val watchTargetPrimaryActionShape = GlancePillShape
internal val qrScannerCardColor = GlanceSurface
internal val qrScannerCardShape = GlanceCardShape
internal val qrScannerPreviewShape = RoundedCornerShape(12.dp)
internal val qrScannerFrameColor = GlanceMandarin
internal val qrScannerPreviewInset = 20.dp
internal val walletSettingsDialogColor = GlanceSurface
internal val walletSettingsDeleteActionColor = GlanceWarning
internal val walletSettingsDeleteActionShape = GlancePillShape
internal val bitcoinGlyphColor = GlanceMandarin
internal const val bitcoinGlyphTextScale = 0.85f
internal val homeSupportHeartIconSize = 28.dp
/** The Home reference uses a quiet solid area beneath its single chart path. */
internal val homeChartAreaFill = GlanceMandarin.copy(alpha = 0.12f)
internal val fiatChartGreen = Color(0xFF85BB65)
internal val fiatChartAreaFill = fiatChartGreen.copy(alpha = 0.16f)
internal val homeChartMutedAreaFill = GlanceMuted.copy(alpha = 0.12f)
internal const val homeChartLineStrokeWidth = 5f
internal const val chartCursorShowsMarker = true
internal const val chartCursorShowsVerticalGuide = true
internal const val chartCursorShowsHorizontalGuide = false
internal val walletDetailTabShape = RoundedCornerShape(12.dp)
internal val utxoBubbleFill = GlanceMandarin.copy(alpha = 0.16f)
internal val dustBubbleFill = GlanceMuted.copy(alpha = 0.16f)
internal val pendingUtxoColor = Color(0xFFF0B35C)
internal val pendingUtxoBubbleFill = pendingUtxoColor.copy(alpha = 0.16f)
internal val utxoBubbleLabelFontWeight = FontWeight.Normal
internal const val UTXO_BUBBLE_LABEL_MAX_FONT_SIZE = 18
internal val supportDonationToggleShape = walletDetailTabShape
internal val supportDonationHeartSize = 112.dp
internal val supportDonationHeartTopPadding = 4.dp
internal val supportDonationSectionSpacing = 12.dp
internal val supportDonationTightSpacing = 8.dp
internal val supportDonationBottomPadding = 12.dp
internal val supportDonationQrCodeSize = 180.dp
internal val receiveQrCodeSize = 232.dp
internal val qrCardShape = GlanceCardShape
internal val qrCardInset = 10.dp
internal val qrBackgroundColor = GlanceText
internal const val qrModuleColor = android.graphics.Color.BLACK

internal enum class DonationMethod(val label: String, val payload: String) {
    ON_CHAIN("On-chain", BuildConfig.DONATION_ON_CHAIN),
    LIGHTNING("Lightning", BuildConfig.DONATION_LIGHTNING),
    ;

    companion object {
        val default = ON_CHAIN
    }
}

internal fun transactionDirection(valueSats: Long, confirmations: Int): String = when {
    confirmations == 0 && valueSats < 0 -> "Outgoing"
    confirmations == 0 -> "Incoming"
    valueSats < 0 -> "Sent"
    else -> "Received"
}
internal fun transactionDateText(timestampSeconds: Long?): String = timestampSeconds?.let(::formatChartTimestamp) ?: "Date unavailable"
internal fun transactionConfirmationText(confirmations: Int): String =
    if (confirmations == 1) "1 confirmation" else "$confirmations confirmations"
internal fun transactionDirectionColor(valueSats: Long): Color = if (valueSats < 0) GlanceWarning else fiatChartGreen
internal fun abbreviateTransactionIdentifier(value: String): String =
    if (value.length <= 8) value else "${value.take(4)}…${value.takeLast(4)}"
internal fun transactionPageCount(totalCount: Int): Int =
    ((totalCount.coerceAtLeast(1) - 1) / TRANSACTION_PAGE_SIZE) + 1
internal fun transactionPageRangeText(page: Int, totalCount: Int): String {
    val start = (page * TRANSACTION_PAGE_SIZE) + 1
    val end = minOf(start + TRANSACTION_PAGE_SIZE - 1, totalCount)
    return "$start–$end of $totalCount"
}

internal fun utxoConfirmationText(confirmations: Int): String = when (confirmations) {
    0 -> "Pending"
    1 -> "1 confirmation"
    else -> "$confirmations confirmations"
}
internal fun utxoOutputText(txid: String, vout: Int): String = "${abbreviateTransactionIdentifier(txid)}:$vout"
internal fun receiveActionLabel(isSingleAddress: Boolean): String = if (isSingleAddress) "Address" else "Receive"
internal fun receiveContextCaption(walletLabel: String, isSingleAddress: Boolean): String =
    "${if (isSingleAddress) "Address" else "Next unused address"} · $walletLabel"
internal enum class AddressTextTone { PRIMARY, MUTED, MANDARIN }
internal data class AddressDisplaySpan(val text: String, val tone: AddressTextTone)

internal fun addressDisplaySpans(address: String): List<AddressDisplaySpan> {
    val groups = address.chunked(4)
    val mandarinGroup = if (address.startsWith("bc1q") || address.startsWith("bc1p")) 1 else 0
    return buildList {
        groups.forEachIndexed { index, group ->
            val baseTone = when {
                index == mandarinGroup -> AddressTextTone.MANDARIN
                index % 2 == 0 -> AddressTextTone.PRIMARY
                else -> AddressTextTone.MUTED
            }
            if (index == groups.lastIndex && group.length > 2) {
                add(AddressDisplaySpan(group.dropLast(2), baseTone))
                add(AddressDisplaySpan(group.takeLast(2), AddressTextTone.MANDARIN))
            } else {
                add(AddressDisplaySpan(group, if (index == groups.lastIndex) AddressTextTone.MANDARIN else baseTone))
            }
        }
    }
}

@Composable
internal fun Phase7Wallet(
    session: ProfileSession,
    settings: SecurityPreferences,
    preferences: SecurityPreferencesStore,
    authentication: AuthenticationCoordinator,
    torController: TorController,
) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val app = LocalContext.current.applicationContext as GlanceApplication
    var historicalFiatRefreshRequest by remember { mutableLongStateOf(0L) }
    val syncCoordinator = remember(session.database) {
        WalletSyncCoordinator {
            app.networkClients.syncEngine(RoomWalletSyncStore(session.database)).syncAll()
        }
    }
    DisposableEffect(syncCoordinator) {
        app.registerSyncCoordinator(syncCoordinator)
        onDispose { app.unregisterSyncCoordinator(syncCoordinator) }
    }
    val fiatStore = remember(session.database) {
        FiatPriceStore(
            session.database,
            mempoolClearnetClient = app.networkClients.pooledMempool(),
            mempoolOnionClient = MempoolFiatPriceClient(MEMPOOL_ONION_API, torController),
        )
    }
    val torState by torController.state.collectAsState()
    val syncState by syncCoordinator.state.collectAsState()
    LaunchedEffect(settings.torEnabled, settings.offlineMode, torState) {
        syncCoordinator.reconcile(settings.torEnabled, torState, settings.offlineMode)
    }
    val refreshHome: () -> Unit = {
        scope.launch {
            if (!settings.offlineMode && settings.torEnabled && syncState == WalletSyncState.TorUnavailable) {
                torController.retry()
            } else {
                refreshHomeData(
                    refreshWallet = { syncCoordinator.requestSync(settings.torEnabled, torState, settings.offlineMode) },
                    refreshFiat = {
                        if (!settings.offlineMode && (!settings.torEnabled || torState is TorState.Ready)) {
                            fiatStore.refreshCurrent(settings.fiatCurrency, useOnion = settings.torEnabled)
                        }
                    },
                    refreshHistoricalFiat = { historicalFiatRefreshRequest++ },
                )
            }
        }
        Unit
    }
    LaunchedEffect(session.database, settings.utxoView) {
        session.database.watchedKeyDao().initializeMissingUtxoViews(settings.utxoView.name)
    }
    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(session.database, fiatStore, settings.fiatCurrency, settings.torEnabled, settings.offlineMode, torState, torController.readySinceMillis.collectAsState().value, syncState, settings.showBalanceChart, historicalFiatRefreshRequest, refreshHome, onAdd = { nav.navigate(Routes.ADD) }, onSettings = { nav.navigate(Routes.SETTINGS) }, onSupport = { nav.navigate(Routes.SUPPORT) }, onKey = { nav.navigate("detail/$it") }, onGroup = { nav.navigate("group-detail/$it") }, onTorEnabled = { enabled -> scope.launch { preferences.update { it.copy(torEnabled = enabled) } } }, onOfflineMode = { enabled -> scope.launch { preferences.update { it.copy(offlineMode = enabled) } } }, onRenewTor = app::renewTorConnection) }
        composable(Routes.ADD) {
            AddWatchTargetScreen(
                database = session.database,
                discoverXpub = { xpub -> app.networkClients.xpubFormatDiscovery().discover(xpub) },
                torReady = !settings.offlineMode && (!settings.torEnabled || torState is TorState.Ready),
                defaultUtxoView = settings.utxoView,
                onImported = refreshHome,
                onBack = { nav.popPhase7BackStackSafely() },
            )
        }
        composable(Routes.SETTINGS) { SettingsScreen(settings, preferences, authentication, torController, onBack = { nav.popPhase7BackStackSafely() }, onSupport = { nav.navigate(Routes.SUPPORT) }) }
        composable(Routes.SUPPORT) { SupportScreen(onBack = { nav.popPhase7BackStackSafely() }) }
        composable(Routes.DETAIL, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val keyId = requireNotNull(entry.arguments?.getString("id"))
            val keySyncCoordinator = remember(session.database, keyId) {
                WalletSyncCoordinator {
                    app.networkClients.syncEngine(RoomWalletSyncStore(session.database)).syncKey(keyId)
                }
            }
            DisposableEffect(keySyncCoordinator) {
                app.registerSyncCoordinator(keySyncCoordinator)
                onDispose { app.unregisterSyncCoordinator(keySyncCoordinator) }
            }
            val keySyncState by keySyncCoordinator.state.collectAsState()
            LaunchedEffect(settings.torEnabled, settings.offlineMode, torState) {
                keySyncCoordinator.reconcile(settings.torEnabled, torState, settings.offlineMode)
            }
            KeyDetailScreen(
                database = session.database,
                keyId = keyId,
                defaultUtxoView = settings.utxoView,
                syncState = keySyncState,
                onRefresh = { scope.launch { keySyncCoordinator.requestSync(settings.torEnabled, torState, settings.offlineMode) } },
                onBack = { nav.popPhase7BackStackSafely() },
                onTransaction = { nav.navigate("transaction/$it") },
                onReceive = { nav.navigate("receive/$keyId") },
                onWalletSettings = { nav.navigate("wallet-settings/$keyId") },
                onLoadMoreHistory = {
                    if (settings.offlineMode || (settings.torEnabled && torState !is TorState.Ready)) false
                    else app.networkClients.syncEngine(RoomWalletSyncStore(session.database)).loadMoreSingleAddressHistory(keyId)
                },
            )
        }
        composable(Routes.GROUP_DETAIL, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val groupId = requireNotNull(entry.arguments?.getString("id"))
            val groupSyncCoordinator = remember(session.database, groupId) {
                WalletSyncCoordinator {
                    val keyIds = session.database.watchedKeyDao().forWalletGroup(groupId).map { it.id }
                    app.networkClients.syncEngine(RoomWalletSyncStore(session.database)).syncKeys(keyIds)
                }
            }
            DisposableEffect(groupSyncCoordinator) { app.registerSyncCoordinator(groupSyncCoordinator); onDispose { app.unregisterSyncCoordinator(groupSyncCoordinator) } }
            val groupSyncState by groupSyncCoordinator.state.collectAsState()
            LaunchedEffect(settings.torEnabled, settings.offlineMode, torState) {
                groupSyncCoordinator.reconcile(settings.torEnabled, torState, settings.offlineMode)
            }
            GroupDetailScreen(
                database = session.database,
                groupId = groupId,
                syncState = groupSyncState,
                onRefresh = { scope.launch { groupSyncCoordinator.requestSync(settings.torEnabled, torState, settings.offlineMode) } },
                onBack = { nav.popPhase7BackStackSafely() },
                onReceive = { nav.navigate("receive/$it") },
                onWalletSettings = { nav.navigate("group-settings/$groupId") },
                onTransaction = { txid -> nav.navigate("group-transaction/$groupId/$txid") },
            )
        }
        composable(Routes.GROUP_SETTINGS, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val groupId = requireNotNull(entry.arguments?.getString("id"))
            GroupWalletSettingsScreen(
                database = session.database,
                groupId = groupId,
                onBack = { nav.popPhase7BackStackSafely() },
                onDeleted = { nav.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
        composable(Routes.GROUP_TRANSACTION, arguments = listOf(navArgument("groupId") { type = NavType.StringType }, navArgument("txid") { type = NavType.StringType })) { entry ->
            GroupTransactionDetailScreen(
                database = session.database,
                groupId = requireNotNull(entry.arguments?.getString("groupId")),
                txid = requireNotNull(entry.arguments?.getString("txid")),
                explorerPreset = settings.explorerPreset,
                onBack = { nav.popPhase7BackStackSafely() },
            )
        }
        composable(Routes.RECEIVE, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            ReceiveScreen(
                database = session.database,
                keyId = requireNotNull(entry.arguments?.getString("id")),
                onBack = { nav.popPhase7BackStackSafely() },
            )
        }
        composable(Routes.WALLET_SETTINGS, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val keyId = requireNotNull(entry.arguments?.getString("id"))
            WalletSettingsScreen(
                database = session.database,
                keyId = keyId,
                preferences = preferences,
                defaultUtxoView = settings.utxoView,
                onBack = { nav.popPhase7BackStackSafely() },
                onDeleted = { nav.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
        composable(Routes.TRANSACTION, arguments = listOf(navArgument("historyId") { type = NavType.LongType })) { entry ->
            TransactionDetailScreen(
                database = session.database,
                historyId = requireNotNull(entry.arguments?.getLong("historyId")),
                explorerPreset = settings.explorerPreset,
                onBack = { nav.popPhase7BackStackSafely() },
            )
        }
    }
}

/** Deliberately offline: this surface reads only the decoy SQLCipher database. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DecoyPhase7Wallet(database: GlanceDatabase, fakeBalance: Long, authentication: AuthenticationCoordinator) {
    val keys by database.walletScreenDao().observeKeyBalances().collectAsState(emptyList())
    var detail by remember { mutableStateOf(false) }
    var transactionDetailId by remember { mutableStateOf<Long?>(null) }
    var receiveKeyId by remember { mutableStateOf<String?>(null) }
    transactionDetailId?.let { historyId ->
        TransactionDetailScreen(database, historyId, ExplorerPreset.MEMPOOL_SPACE) { transactionDetailId = null }
        return
    }
    receiveKeyId?.let { keyId ->
        ReceiveScreen(database, keyId) { receiveKeyId = null }
        return
    }
    if (detail) {
        val key = keys.firstOrNull()
        if (key != null) KeyDetailScreen(database, key.id, UtxoView.BUBBLES, WalletSyncState.Idle, onRefresh = {}, onBack = { detail = false }, onTransaction = { transactionDetailId = it }, onReceive = { receiveKeyId = key.id }) else detail = false
        return
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Glance") }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { AmountText(fakeBalance, Modifier.testTag("decoy_balance"), large = true) }
            item { Text("Watch targets", style = MaterialTheme.typography.titleMedium) }
            items(keys, key = { it.id }) { key -> ListItem(headlineContent = { Text(key.label) }, supportingContent = { Text(if (key.targetType == WatchTargetType.SINGLE_ADDRESS) "Single address" else "Native SegWit", color = GlanceMuted) }, trailingContent = { AmountText(key.balanceSats) }, modifier = Modifier.clickable { detail = true }) }
            item { OutlinedButton(onClick = authentication::lock, modifier = Modifier.fillMaxWidth()) { Text("Lock") } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun HomeScreen(database: GlanceDatabase, fiatStore: FiatPriceStore, currency: String, torEnabled: Boolean, offlineMode: Boolean, torState: TorState, torReadySinceMillis: Long?, syncState: WalletSyncState, showChart: Boolean, historicalFiatRefreshRequest: Long, onRetrySync: () -> Unit, onAdd: () -> Unit, onSettings: () -> Unit, onSupport: () -> Unit, onKey: (String) -> Unit, onGroup: (String) -> Unit, onTorEnabled: (Boolean) -> Unit, onOfflineMode: (Boolean) -> Unit, onRenewTor: () -> Unit) {
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
private fun HomeStatusActions(torEnabled: Boolean, offlineMode: Boolean, torState: TorState, onConnectionClick: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
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

private fun watchedKeyMetadata(key: KeyBalanceRow): String = when (key.targetType) {
    WatchTargetType.SINGLE_ADDRESS -> "Single address"
    WatchTargetType.HD_KEY -> key.scriptType?.name?.replace('_', ' ') ?: if (key.walletGroupId != null) "Multi-format wallet" else "Watched key"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TorConnectionSheet(torEnabled: Boolean, offlineMode: Boolean, torState: TorState, readySinceMillis: Long?, onDismiss: () -> Unit, onTorEnabled: (Boolean) -> Unit, onOfflineMode: (Boolean) -> Unit, onRenew: () -> Unit) {
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
private fun DeliberatePullToRefresh(
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

@Composable private fun SyncStatusContent(text: String, tag: String) = Row(
    Modifier.fillMaxWidth().testTag(tag),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
) {
    CircularProgressIndicator(Modifier.size(20.dp), color = GlanceMandarin, strokeWidth = 2.dp)
    Text(text, color = GlanceMuted)
}

@Composable private fun SyncFailureContent(message: String, action: String, onRetry: () -> Unit, tag: String) = Column(
    Modifier.fillMaxWidth().testTag(tag),
    verticalArrangement = Arrangement.spacedBy(8.dp),
) {
    Text(message, color = GlanceMuted)
    OutlinedButton(onClick = onRetry) { Text(action) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AddWatchTargetScreen(
    database: GlanceDatabase,
    discoverXpub: (String) -> XpubDiscoveryResult,
    torReady: Boolean,
    defaultUtxoView: UtxoView = UtxoView.BUBBLES,
    onImported: () -> Unit,
    onBack: () -> Unit,
) {
    var input by remember { mutableStateOf("") }; var label by remember { mutableStateOf("") }; var type by remember { mutableStateOf(ScriptType.NATIVE_SEGWIT) }; var expertMode by remember { mutableStateOf(false) }; var expanded by remember { mutableStateOf(false) }; var choices by remember { mutableStateOf<FormatChoiceDialog?>(null) }; var error by remember { mutableStateOf<String?>(null) }; var scannerVisible by remember { mutableStateOf(false) }; var cameraDenied by remember { mutableStateOf(false) }; var discovering by remember { mutableStateOf(false) }; val scope = rememberCoroutineScope(); val context = LocalContext.current
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraDenied = !granted
        scannerVisible = granted
    }
    fun openScanner() {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scannerVisible = true
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    suspend fun persist(source: String, scriptTypes: Collection<ScriptType?>) {
        if (scriptTypes.size == 1) persistWatchTarget(database, label, source, scriptTypes.single(), defaultUtxoView)
        else persistWatchTargets(database, label, source, scriptTypes.filterNotNull(), defaultUtxoView)
        onImported(); onBack()
    }
    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val pasted = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (pasted.isNotBlank()) {
            input = pasted
            error = null
        }
    }
    Scaffold(containerColor = GlanceBackground, topBar = { BackBar("Add watch target", onBack) }) { padding -> Column(
        Modifier.fillMaxSize().padding(padding).padding(horizontal = HomeScreenGutter),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        FormFieldLabel("Bitcoin address, public key, or descriptor")
        GlanceFilledTextField(
            value = input,
            onValueChange = { input = it; error = null },
            placeholder = "bc1q..., zpub..., or descriptor",
            minLines = 2,
            modifier = Modifier.fillMaxWidth().testTag("watch_target_input"),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::pasteFromClipboard) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = "Paste watch target", tint = GlanceText)
                    }
                    IconButton(onClick = ::openScanner) {
                        Icon(painterResource(R.drawable.ic_qr_scanner), contentDescription = "Scan watch target QR code", tint = GlanceText)
                    }
                }
            },
        )
        Text(
            "Paste or scan a mainnet Bitcoin address, extended public key, or supported descriptor.",
            color = GlanceMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        if (cameraDenied) Text("Camera access is needed to scan a watch-target QR code.", color = GlanceMuted, style = MaterialTheme.typography.bodySmall)
        FormFieldLabel("Label (optional)")
        GlanceFilledTextField(
            value = label,
            onValueChange = { label = it },
            placeholder = "e.g. Savings",
            modifier = Modifier.fillMaxWidth().testTag("watch_target_label"),
            singleLine = true,
        )
        TextButton(
            onClick = { expertMode = !expertMode },
            colors = ButtonDefaults.textButtonColors(contentColor = GlanceMandarin),
        ) { Text(if (expertMode) "Hide expert options" else "Expert options") }
        if (expertMode) {
            FormFieldLabel("Address format for public keys")
            ExposedDropdownMenuBox(expanded, { expanded = !expanded }) {
                GlanceFilledTextField(
                    value = type.name.replace('_', ' '),
                    onValueChange = {},
                    placeholder = "",
                    readOnly = true,
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                )
                ExposedDropdownMenu(expanded, { expanded = false }) {
                    ScriptType.entries.forEach { choice -> DropdownMenuItem({ Text(choice.name.replace('_', ' ')) }, { type = choice; expanded = false }) }
                }
            }
            Text("Only needed for a plain xpub; addresses and descriptors resolve automatically.", color = GlanceMuted, style = MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it, color = GlanceWarning) }
        Button(onClick = { scope.launch {
            error = try {
                when (val target = classifyUnifiedImport(input)) {
                    is UnifiedImport.Address -> persist(target.address, listOf(null))
                    is UnifiedImport.Key -> persist(target.source, listOf(target.scriptType.toDataType()))
                    is UnifiedImport.AmbiguousXpub -> if (expertMode) persist(target.source, listOf(type)) else {
                        if (!torReady) throw TorDiscoveryUnavailableException()
                        discovering = true
                        when (val result = withContext(Dispatchers.IO) { discoverXpub(target.source) }) {
                            is XpubDiscoveryResult.Match -> persist(target.source, listOf(result.scriptType.toDataType()))
                            XpubDiscoveryResult.NoMatch -> choices = FormatChoiceDialog(result, ScriptType.entries.toList())
                            is XpubDiscoveryResult.Multiple -> persist(target.source, discoveredFormatsToImport(result))
                        }
                    }
                }
                null
            } catch (exception: TorDiscoveryUnavailableException) {
                importErrorMessage(exception)
            } catch (exception: IllegalArgumentException) {
                importErrorMessage(exception)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                "Couldn't check this xpub's address format. Try again or use Expert options."
            } finally { discovering = false }
        } }, enabled = !discovering, shape = watchTargetPrimaryActionShape, colors = ButtonDefaults.buttonColors(containerColor = watchTargetPrimaryActionColor, contentColor = GlanceBackground, disabledContainerColor = GlanceSurface, disabledContentColor = GlanceMuted), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("add_watch_target")) { Text(if (discovering) "Checking address format…" else "Add watch target") }
    } }
    choices?.let { available ->
        var fallbackSelection by remember(available) { mutableStateOf(FallbackFormatSelection(available.formats)) }
        val dialogCopy = requireNotNull(formatChoiceDialogCopyOrNull(available.result))
        AlertDialog(
            onDismissRequest = { choices = null },
            title = { Text(dialogCopy.title) },
            text = {
                Column {
                    Text(dialogCopy.message, color = GlanceMuted)
                    available.formats.forEach { choice ->
                        Row(Modifier.fillMaxWidth().clickable { fallbackSelection = fallbackSelection.select(choice) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(fallbackSelection.selected == choice, { fallbackSelection = fallbackSelection.select(choice) })
                            Text(choice.name.replace('_', ' '))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selected = fallbackSelection.selected ?: return@TextButton
                        scope.launch {
                            try {
                                persist(input, listOf(selected))
                                choices = null
                            } catch (exception: IllegalArgumentException) {
                                error = importErrorMessage(exception)
                            }
                        }
                    },
                    enabled = fallbackSelection.canConfirm,
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { choices = null }) { Text("Cancel") } },
        )
    }
    if (scannerVisible) QrScannerDialog(
        onDismiss = { scannerVisible = false },
        onPayload = { payload ->
            val scanned = unifiedWatchTargetInputFromQr(payload)
            scannerVisible = false
            if (scanned == null) error = "Scan a valid mainnet Bitcoin address, supported extended public key, or descriptor."
            else { input = scanned; error = null }
        },
        onUnavailable = {
            scannerVisible = false
            error = "The camera is unavailable. Paste the watch target instead."
        },
    )
}

internal class TorDiscoveryUnavailableException : RuntimeException("Connect to Tor before checking this xpub's address format.")

internal data class FormatChoiceDialog(
    val result: XpubDiscoveryResult,
    val formats: List<ScriptType>,
)

internal data class FormatChoiceDialogCopy(val title: String, val message: String)

internal fun formatChoiceDialogCopyOrNull(result: XpubDiscoveryResult): FormatChoiceDialogCopy? = when (result) {
    XpubDiscoveryResult.NoMatch -> FormatChoiceDialogCopy(
        title = "Choose address format",
        message = "This xpub has no activity yet, so its format cannot be detected.",
    )
    is XpubDiscoveryResult.Multiple, is XpubDiscoveryResult.Match -> null
}

internal fun discoveredFormatsToImport(result: XpubDiscoveryResult): List<ScriptType> = when (result) {
    is XpubDiscoveryResult.Match -> listOf(result.scriptType.toDataType())
    is XpubDiscoveryResult.Multiple -> result.scriptTypes
        .map { it.toDataType() }
        .sortedBy { ScriptType.entries.indexOf(it) }
    XpubDiscoveryResult.NoMatch -> emptyList()
}

internal suspend fun persistWatchTarget(
    database: GlanceDatabase,
    label: String,
    source: String,
    scriptType: ScriptType?,
    utxoView: UtxoView = UtxoView.BUBBLES,
) {
    val id = UUID.randomUUID().toString()
    if (scriptType == null) {
        val address = parseSingleAddress(source)
        database.withTransaction {
            require(database.derivedAddressDao().findByAddress(address) == null) { "This address is already being tracked" }
            database.watchedKeyDao().upsert(WatchedKeyEntity(id, label.trim(), address, ScriptType.NATIVE_SEGWIT, System.currentTimeMillis(), targetType = WatchTargetType.SINGLE_ADDRESS, utxoView = utxoView.name))
            database.derivedAddressDao().upsert(DerivedAddressEntity(keyId = id, chain = AddressChain.EXTERNAL, derivationIndex = 0, address = address, isUsed = false, isConfirmedUnused = false))
        }
    } else {
        persistWatchTargets(database, label, source, listOf(requireNotNull(scriptType)), utxoView)
    }
}

/** Adds every requested HD format atomically, retaining formats that this key already watches. */
internal suspend fun persistWatchTargets(
    database: GlanceDatabase,
    label: String,
    source: String,
    scriptTypes: Collection<ScriptType>,
    utxoView: UtxoView = UtxoView.BUBBLES,
) {
    val requestedTypes = scriptTypes.distinct().sortedBy { ScriptType.entries.indexOf(it) }
    require(requestedTypes.isNotEmpty()) { "At least one address format is required" }
    val normalized = normalizeWatchedKeyInput(source)
    val parsedByType = requestedTypes.associateWith { parseWatchedKey(normalized, it.toCryptoType()) }

    database.withTransaction {
        val existingTypes = database.watchedKeyDao().existingHdScriptTypes(normalized, requestedTypes).toSet()
        val typesToCreate = requestedTypes.filterNot { it in existingTypes }
        val existingGroupId = database.watchedKeyDao().walletGroupIdForHdKeyMaterial(normalized)
        require(existingGroupId == null || typesToCreate.isEmpty()) {
            "This multi-format wallet's formats can only be chosen during import."
        }
        val candidates = typesToCreate.flatMap { initialDerivedAddresses(requireNotNull(parsedByType[it])) }
        require(database.derivedAddressDao().existingAddresses(candidates).isEmpty()) { "This address is already being tracked" }

        val now = System.currentTimeMillis()
        val groupId = if (requestedTypes.size > 1 && existingTypes.isEmpty()) {
            UUID.randomUUID().toString().also { id ->
                database.walletGroupDao().insert(
                    WalletGroupEntity(
                        id = id,
                        label = label.trim(),
                        dateAdded = now,
                        utxoView = utxoView.name,
                        preferredReceiveScriptType = requestedTypes.preferredReceiveScriptType(),
                    ),
                )
            }
        } else null
        typesToCreate.forEach { scriptType ->
            database.watchedKeyDao().upsert(
                WatchedKeyEntity(
                    id = UUID.randomUUID().toString(),
                    label = if (groupId == null) label.trim() else "",
                    keyMaterial = normalized,
                    scriptType = scriptType,
                    dateAdded = now,
                    utxoView = utxoView.name,
                    walletGroupId = groupId,
                ),
            )
        }
    }
}

internal fun Collection<ScriptType>.preferredReceiveScriptType(): ScriptType =
    if (ScriptType.NATIVE_SEGWIT in this) ScriptType.NATIVE_SEGWIT
    else first { it in ScriptType.entries }

internal fun initialDerivedAddresses(
    key: app.glance.wallet.core.crypto.WatchOnlyKey,
    gapLimit: Int = SyncConfig.DEFAULT_GAP_LIMIT,
): List<String> = listOf(0, 1).flatMap { chain ->
    (0 until gapLimit).map { index -> key.derive(chain, index.toLong()).address }
}

@Composable
internal fun QrScannerDialog(
    onDismiss: () -> Unit,
    onPayload: (String) -> Unit,
    onUnavailable: () -> Unit,
    cameraPreview: @Composable (Modifier) -> Unit = { modifier ->
        QrCameraPreview(modifier, onPayload, onUnavailable)
    },
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = qrScannerCardColor,
            shape = qrScannerCardShape,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HomeScreenGutter)
                .testTag("qr_scanner_dialog"),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_qr_scanner),
                        contentDescription = null,
                        tint = GlanceMandarin,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Scan watch target",
                        color = GlanceText,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close QR scanner",
                            tint = GlanceText,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(qrScannerPreviewShape)
                        .background(GlanceBackground)
                        .testTag("qr_scanner_preview"),
                ) {
                    cameraPreview(Modifier.fillMaxSize())
                    QrScannerFrame(
                        Modifier
                            .matchParentSize()
                            .padding(qrScannerPreviewInset),
                    )
                }
                Text(
                    "Point the camera at a Bitcoin address, public key, or descriptor QR code.",
                    color = GlanceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun QrScannerFrame(modifier: Modifier = Modifier) = Canvas(modifier) {
    val cornerLength = 32.dp.toPx()
    val strokeWidth = 2.dp.toPx()
    val width = size.width
    val height = size.height
    fun corner(from: Offset, horizontal: Offset, vertical: Offset) {
        drawLine(qrScannerFrameColor, from, horizontal, strokeWidth)
        drawLine(qrScannerFrameColor, from, vertical, strokeWidth)
    }
    corner(Offset.Zero, Offset(cornerLength, 0f), Offset(0f, cornerLength))
    corner(Offset(width, 0f), Offset(width - cornerLength, 0f), Offset(width, cornerLength))
    corner(Offset(0f, height), Offset(cornerLength, height), Offset(0f, height - cornerLength))
    corner(Offset(width, height), Offset(width - cornerLength, height), Offset(width, height - cornerLength))
}

internal data class FallbackFormatSelection(
    val available: List<ScriptType>,
    val selected: ScriptType? = null,
) {
    init {
        require(available.isNotEmpty()) { "At least one fallback format must be available" }
    }

    val canConfirm: Boolean get() = selected in available

    fun select(format: ScriptType): FallbackFormatSelection =
        if (format in available) copy(selected = format) else this
}

internal fun importErrorMessage(exception: Exception): String =
    when {
        exception is TorDiscoveryUnavailableException -> exception.message!!
        exception.message?.contains("version does not match script type") == true ->
            "This key is valid, but its prefix does not match the selected address format. A plain xpub can use any supported format; ypub is only Nested SegWit and zpub is only Native SegWit."
        exception.message?.contains("script type is required") == true ->
            "Select a script type for this extended public key."
        exception.message?.contains("Invalid extended public key") == true ->
            "The key could not be decoded. Paste a complete mainnet xpub, ypub, or zpub without a derivation path or quotes."
        exception.message?.contains("Only mainnet") == true ->
            "Only mainnet xpub, ypub, and zpub keys are supported."
        exception.message?.contains("Bitcoin address") == true -> "Enter a valid mainnet Bitcoin address."
        exception.message?.contains("already being tracked") == true -> "This address is already being tracked."
        else -> "Enter a valid mainnet key or supported Taproot descriptor."
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun GroupDetailScreen(database: GlanceDatabase, groupId: String, syncState: WalletSyncState, onRefresh: () -> Unit, onBack: () -> Unit, onReceive: (String) -> Unit, onWalletSettings: () -> Unit, onTransaction: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState { 2 }
    val group by database.walletGroupDao().observeById(groupId).collectAsState(null)
    val keys by database.watchedKeyDao().observeForWalletGroup(groupId).collectAsState(emptyList())
    val transactionCount by database.walletScreenDao().observeGroupTransactionCount(groupId).collectAsState(0)
    var transactionPage by remember(groupId) { mutableStateOf(0) }
    val transactions by database.walletScreenDao().observeGroupTransactionPage(
        groupId = groupId,
        limit = TRANSACTION_PAGE_SIZE,
        offset = transactionPage * TRANSACTION_PAGE_SIZE,
    ).collectAsState(emptyList())
    val utxos by database.walletScreenDao().observeGroupUtxos(groupId).collectAsState(emptyList())
    val transactionListState = rememberLazyListState()
    var selectedUtxo by remember { mutableStateOf<UtxoRow?>(null) }
    val dustThreshold = group?.dustThresholdSats ?: DEFAULT_DUST_THRESHOLD_SATS
    val utxoView = group?.utxoView?.let { runCatching { UtxoView.valueOf(it) }.getOrNull() } ?: UtxoView.BUBBLES
    val preferredKey = keys.firstOrNull { it.scriptType == group?.preferredReceiveScriptType }
    val confirmedUtxoCount = utxos.count { it.confirmations > 0 }
    val pendingUtxoCount = utxos.count { it.confirmations == 0 }
    LaunchedEffect(transactionCount) {
        transactionPage = transactionPage.coerceAtMost(transactionPageCount(transactionCount) - 1)
    }
    Scaffold(
        topBar = { BackBar(group?.label?.ifBlank { "Multi-format wallet" } ?: "Multi-format wallet", onBack, actions = { IconButton(onClick = onWalletSettings) { Icon(Icons.Filled.MoreVert, contentDescription = "Wallet settings") } }) },
        bottomBar = {
            Surface(color = GlanceBackground) {
                Column {
                    if (pagerState.currentPage == 1) {
                        UtxoConfirmationSummary(confirmedUtxoCount, pendingUtxoCount)
                    }
                    Button(
                        onClick = { preferredKey?.let { onReceive(it.id) } },
                        enabled = preferredKey != null,
                        shape = GlancePillShape,
                        colors = ButtonDefaults.buttonColors(containerColor = GlanceMandarin, contentColor = GlanceBackground),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = HomeScreenGutter, vertical = 12.dp).heightIn(min = 48.dp).testTag("group_wallet_receive_action"),
                    ) { Icon(Icons.Filled.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Receive") }
                }
            }
        },
    ) { padding ->
        DeliberatePullToRefresh(syncState == WalletSyncState.Syncing, onRefresh, Modifier.padding(padding)) {
            Column(Modifier.fillMaxSize().padding(horizontal = HomeScreenGutter)) {
                WalletDetailTabs(pagerState.currentPage) { page -> scope.launch { pagerState.animateScrollToPage(page) } }
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().testTag("group_wallet_detail_pager")) { page ->
                    if (page == 0) LazyColumn(state = transactionListState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 18.dp, bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(transactions, key = { it.txid }) { tx ->
                            Surface(color = GlanceSurface, shape = GlanceCardShape, modifier = Modifier.fillMaxWidth().clickable { onTransaction(tx.txid) }.semantics { contentDescription = "${transactionDirection(tx.valueSats, tx.confirmations)} transaction" }) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TransactionRowContent(tx.valueSats, tx.confirmations, tx.timestamp, tx.label, Modifier.weight(1f))
                                    AmountText(tx.valueSats)
                                }
                            }
                        }
                        item {
                            TransactionPaginationControls(
                                page = transactionPage,
                                totalCount = transactionCount,
                                onPageSelected = { selectedPage ->
                                    transactionPage = selectedPage
                                    scope.launch { transactionListState.scrollToItem(0) }
                                },
                            )
                        }
                    } else Column(Modifier.fillMaxSize().padding(top = 10.dp)) {
                        if (utxoView == UtxoView.BUBBLES) BubbleGrid(utxos, dustThreshold, Modifier.testTag("group_utxo_bubble_view"), syncState == WalletSyncState.Syncing, onRefresh) { selectedUtxo = it }
                        else LazyColumn(Modifier.fillMaxSize().testTag("group_utxo_list_view"), contentPadding = PaddingValues(top = 14.dp, bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(utxos, key = { it.id }) { utxo -> UtxoCard(utxo, dustThreshold) { selectedUtxo = utxo } }
                        }
                    }
                }
            }
        }
    }
    selectedUtxo?.let { UtxoSheet(it, dustThreshold) { selectedUtxo = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupTransactionDetailScreen(database: GlanceDatabase, groupId: String, txid: String, explorerPreset: ExplorerPreset, onBack: () -> Unit) {
    val transaction by database.walletScreenDao().observeGroupTransactionDetail(groupId, txid).collectAsState(initial = null)
    val parts by database.walletScreenDao().observeGroupTransactionParts(groupId, txid).collectAsState(emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var explorerWarning by remember { mutableStateOf(false) }
    val row = transaction
    Scaffold(containerColor = GlanceBackground, topBar = { BackBar("Transaction", onBack) }) { padding ->
        if (row == null) {
            Text("Transaction unavailable", color = GlanceMuted, modifier = Modifier.padding(padding).padding(20.dp))
        } else {
            var label by remember(row.txid, row.label) { mutableStateOf(row.label.orEmpty()) }
            var labelSaving by remember(row.txid) { mutableStateOf(false) }
            var labelSaveError by remember(row.txid) { mutableStateOf<String?>(null) }
            var copiedField by remember(row.txid) { mutableStateOf<String?>(null) }
            val normalizedLabel = label.trim()
            val labelChanged = normalizedLabel != row.label.orEmpty()
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = HomeScreenGutter), contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TransactionDirectionHeading(row.valueSats, row.confirmations)
                        AmountText(row.valueSats, large = true)
                    }
                }
                item { GroupTransactionFactsCard(row, onCopyTransactionId = { copy(context, row.txid); copiedField = "Transaction ID copied" }) }
                copiedField?.let { message -> item { Text(message, color = GlanceMuted, style = MaterialTheme.typography.bodySmall) } }
                item {
                    Text("Addresses by format", color = GlanceMuted, style = MaterialTheme.typography.labelLarge)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        parts.forEach { part ->
                            Surface(color = GlanceSurface, shape = GlanceCardShape, modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(part.scriptType.displayName())
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(abbreviateTransactionIdentifier(part.address), color = GlanceMuted, style = MaterialTheme.typography.bodySmall)
                                            IconButton(onClick = { copy(context, part.address); copiedField = "Address copied" }) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy address", tint = GlanceText, modifier = Modifier.size(18.dp)) }
                                        }
                                    }
                                    AmountText(part.valueSats)
                                }
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FormFieldLabel("Label")
                        GlanceFilledTextField(value = label, onValueChange = { label = it; labelSaveError = null }, placeholder = "e.g. From exchange", modifier = Modifier.fillMaxWidth().testTag("group_transaction_label"), singleLine = true)
                    }
                }
                item {
                    Button(onClick = {
                        labelSaving = true
                        labelSaveError = null
                        scope.launch {
                            try {
                                if (normalizedLabel.isEmpty()) database.labelDao().delete(LabelReferenceType.TRANSACTION, row.txid)
                                else database.labelDao().upsert(LabelEntity(LabelReferenceType.TRANSACTION, row.txid, normalizedLabel))
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                labelSaveError = "Unable to save label. Try again."
                            } finally {
                                labelSaving = false
                            }
                        }
                    }, enabled = labelChanged && !labelSaving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("save_group_transaction_label"), shape = watchTargetPrimaryActionShape, colors = ButtonDefaults.buttonColors(containerColor = watchTargetPrimaryActionColor, contentColor = GlanceBackground, disabledContainerColor = GlanceSurface, disabledContentColor = GlanceMuted)) { Text(if (labelSaving) "Savingâ€¦" else "Save label") }
                }
                labelSaveError?.let { error -> item { Text(error, color = GlanceWarning, style = MaterialTheme.typography.bodySmall) } }
                item {
                    Surface(color = GlanceSurface, shape = GlancePillShape, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { explorerWarning = true }.semantics { contentDescription = "View in block explorer" }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = GlanceText, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("View in block explorer", color = GlanceText, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            if (explorerWarning) AlertDialog(
                onDismissRequest = { explorerWarning = false }, title = { Text("Open external browser?") },
                text = { Text("This opens an external browser outside Glance's Tor connection. It may expose this transaction ID and your device IP address.") },
                confirmButton = { Button(onClick = { explorerWarning = false; openExplorer(context, explorerPreset, row.txid) }, colors = ButtonDefaults.buttonColors(containerColor = GlanceWarning)) { Text("Open browser") } },
                dismissButton = { OutlinedButton(onClick = { explorerWarning = false }) { Text("Cancel") } },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable internal fun KeyDetailScreen(database: GlanceDatabase, keyId: String, defaultUtxoView: UtxoView, syncState: WalletSyncState, onRefresh: () -> Unit, onBack: () -> Unit, onTransaction: (Long) -> Unit, onReceive: () -> Unit, onWalletSettings: (() -> Unit)? = null, onLoadMoreHistory: suspend () -> Boolean = { false }) {
    val scope = rememberCoroutineScope(); val pagerState = rememberPagerState { 2 }; var selectedUtxo by remember { mutableStateOf<UtxoRow?>(null) }; val target by database.watchedKeyDao().observeById(keyId).collectAsState(null); val singleAddress = target?.targetType == WatchTargetType.SINGLE_ADDRESS
    val transactionCount by database.walletScreenDao().observeTransactionCount(keyId).collectAsState(0)
    val historyPaging by database.walletScreenDao().observeSingleAddressHistoryPaging(keyId).collectAsState(null)
    var transactionPage by remember(keyId) { mutableStateOf(0) }
    var loadingMoreHistory by remember(keyId) { mutableStateOf(false) }
    var historyLoadFailed by remember(keyId) { mutableStateOf(false) }
    val txs by database.walletScreenDao().observeTransactionPage(
        keyId = keyId,
        limit = TRANSACTION_PAGE_SIZE,
        offset = transactionPage * TRANSACTION_PAGE_SIZE,
    ).collectAsState(emptyList())
    val utxos by database.walletScreenDao().observeUtxos(keyId).collectAsState(emptyList())
    val transactionListState = rememberLazyListState()
    val walletTitle = target?.label?.takeIf(String::isNotBlank) ?: target?.scriptType?.name?.replace('_', ' ') ?: "Wallet"
    val utxoView = target?.utxoView?.let { runCatching { UtxoView.valueOf(it) }.getOrNull() } ?: defaultUtxoView
    val dustThresholdSats = target?.dustThresholdSats ?: DEFAULT_DUST_THRESHOLD_SATS
    val utxoSnapshotSuppressed = historyPaging?.isUtxoSnapshotSuppressed == true
    val confirmedUtxoCount = utxos.count { it.confirmations > 0 }
    val pendingUtxoCount = utxos.count { it.confirmations == 0 }
    val lastCachedTransactionPage = transactionPageCount(transactionCount) - 1
    val canLoadMoreHistory = singleAddress && transactionCount > 0 && historyPaging?.isComplete != true
    val isLoadMoreHistoryPage = canLoadMoreHistory && transactionPage == lastCachedTransactionPage + 1
    LaunchedEffect(transactionCount, canLoadMoreHistory) {
        transactionPage = transactionPage.coerceAtMost(lastCachedTransactionPage + if (canLoadMoreHistory) 1 else 0)
    }
    Scaffold(
        topBar = { BackBar(walletTitle, onBack, actions = { onWalletSettings?.let { openSettings -> IconButton(onClick = openSettings) { Icon(Icons.Filled.MoreVert, contentDescription = "Wallet settings") } } }) },
        bottomBar = {
            Surface(color = GlanceBackground) {
                Column {
                    if (pagerState.currentPage == 1 && !utxoSnapshotSuppressed) {
                        UtxoConfirmationSummary(confirmedUtxoCount, pendingUtxoCount)
                    }
                    Button(
                        onClick = onReceive,
                        shape = GlancePillShape,
                        colors = ButtonDefaults.buttonColors(containerColor = GlanceMandarin, contentColor = GlanceBackground),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = HomeScreenGutter, vertical = 12.dp).heightIn(min = 48.dp).testTag("wallet_receive_action"),
                    ) {
                        Icon(Icons.Filled.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(receiveActionLabel(singleAddress))
                    }
                }
            }
        },
    ) { padding -> DeliberatePullToRefresh(isRefreshing = syncState == WalletSyncState.Syncing, onRefresh = onRefresh, modifier = Modifier.padding(padding)) { Column(Modifier.fillMaxSize().padding(horizontal = HomeScreenGutter)) {
        WalletDetailTabs(selected = pagerState.currentPage, onSelected = { page -> scope.launch { pagerState.animateScrollToPage(page) } })
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().testTag("wallet_detail_pager")) { page ->
            if (page == 0) LazyColumn(state = transactionListState, modifier = Modifier.fillMaxSize().testTag("transaction_list"), contentPadding = PaddingValues(top = 18.dp, bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isLoadMoreHistoryPage && txs.isEmpty()) item {
                    Text("Load more transactions to see older history.", color = GlanceMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("transaction_empty_history_page"))
                }
                items(txs, key = { it.historyId }) { tx -> TransactionCard(tx) { onTransaction(tx.historyId) } }
                item {
                    TransactionPaginationControls(
                        page = transactionPage,
                        totalCount = transactionCount,
                        canAdvanceToLoadMore = canLoadMoreHistory && transactionPage == lastCachedTransactionPage,
                        onPageSelected = { selectedPage ->
                            transactionPage = selectedPage
                            scope.launch { transactionListState.scrollToItem(0) }
                        },
                    )
                    if (isLoadMoreHistoryPage) {
                        Button(
                            onClick = {
                                if (!loadingMoreHistory) scope.launch {
                                    loadingMoreHistory = true
                                    historyLoadFailed = false
                                    val loaded = runCatching { onLoadMoreHistory() }.getOrDefault(false)
                                    loadingMoreHistory = false
                                    historyLoadFailed = !loaded
                                }
                            },
                            enabled = !loadingMoreHistory,
                            shape = GlancePillShape,
                            colors = ButtonDefaults.buttonColors(containerColor = GlanceMandarin, contentColor = GlanceBackground),
                            modifier = Modifier.fillMaxWidth().testTag("transaction_history_load_more"),
                        ) { Text(if (loadingMoreHistory) "Loading more transactions…" else "Load more transactions") }
                        if (historyLoadFailed) Text("Could not load more history. Try again.", color = GlanceMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp).testTag("transaction_history_load_error"))
                    }
                }
            }
            else if (utxoSnapshotSuppressed) Box(Modifier.fillMaxSize().padding(24.dp).testTag("utxo_snapshot_unavailable"), contentAlignment = Alignment.Center) {
                Text(utxoSnapshotUnavailableMessage, color = GlanceMuted, style = MaterialTheme.typography.bodyMedium)
            } else Column(Modifier.fillMaxSize().padding(top = 10.dp)) { if (utxoView == UtxoView.BUBBLES) BubbleGrid(utxos, dustThresholdSats, Modifier.testTag("utxo_bubble_view"), syncState == WalletSyncState.Syncing, onRefresh) { selectedUtxo = it } else LazyColumn(Modifier.fillMaxSize().testTag("utxo_list_view"), contentPadding = PaddingValues(top = 14.dp, bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { items(utxos, key = { it.id }) { utxo -> UtxoCard(utxo, dustThresholdSats) { selectedUtxo = utxo } } } }
        }
    } } }
    selectedUtxo?.let { UtxoSheet(it, dustThresholdSats) { selectedUtxo = null } }
}

@Composable
private fun UtxoConfirmationSummary(confirmedCount: Int, pendingCount: Int) {
    Text(
        text = "$confirmedCount confirmed · $pendingCount pending",
        color = GlanceMuted,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.fillMaxWidth().padding(start = HomeScreenGutter, top = 8.dp, end = HomeScreenGutter).testTag("utxo_confirmation_summary"),
    )
}

internal const val utxoSnapshotUnavailableMessage = "UTXO details are unavailable because this address has more than 1,000 unspent outputs. Its balance and recent transactions remain available."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WalletSettingsScreen(database: GlanceDatabase, keyId: String, preferences: SecurityPreferencesStore, defaultUtxoView: UtxoView = UtxoView.BUBBLES, onBack: () -> Unit, onDeleted: () -> Unit) {
    val scope = rememberCoroutineScope()
    var watchedKey by remember(keyId) { mutableStateOf<WatchedKeyEntity?>(null) }
    var loaded by remember(keyId) { mutableStateOf(false) }
    LaunchedEffect(keyId) {
        database.watchedKeyDao().observeById(keyId).collect {
            watchedKey = it
            loaded = true
        }
    }
    var name by remember(watchedKey?.id, watchedKey?.label) { mutableStateOf(watchedKey?.label.orEmpty()) }
    var dustThreshold by remember(watchedKey?.id, watchedKey?.dustThresholdSats) { mutableStateOf(watchedKey?.dustThresholdSats?.toString().orEmpty()) }
    var dustThresholdError by remember { mutableStateOf<String?>(null) }
    var deleteConfirmation by remember { mutableStateOf(false) }
    var utxoViewExpanded by remember { mutableStateOf(false) }
    if (!loaded) return
    if (watchedKey == null) {
        LaunchedEffect(keyId) { onDeleted() }
        return
    }
    Scaffold(containerColor = GlanceBackground, topBar = { BackBar("Wallet settings", onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = HomeScreenGutter),
            contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SettingsGroup("Wallet", "wallet_settings_group_wallet") {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FormFieldLabel("Wallet name")
                        GlanceFilledTextField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = "e.g. Savings",
                            modifier = Modifier.fillMaxWidth().testTag("wallet_name"),
                            singleLine = true,
                        )
                        Button(
                            onClick = { scope.launch { database.watchedKeyDao().rename(keyId, name.trim()); onBack() } },
                            shape = watchTargetPrimaryActionShape,
                            colors = ButtonDefaults.buttonColors(containerColor = watchTargetPrimaryActionColor, contentColor = GlanceBackground),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("Save name") }
                        SettingsDivider()
                        ExposedDropdownMenuBox(utxoViewExpanded, { utxoViewExpanded = !utxoViewExpanded }) {
                            SettingsDisclosureRow("UTXO view", modifier = Modifier.menuAnchor().testTag("wallet_setting_utxo_view"), value = utxoViewLabel(watchedKey!!.utxoView?.let { runCatching { UtxoView.valueOf(it) }.getOrNull() } ?: defaultUtxoView)) { utxoViewExpanded = true }
                            ExposedDropdownMenu(utxoViewExpanded, { utxoViewExpanded = false }) {
                                UtxoView.entries.forEach { view ->
                                    DropdownMenuItem({ Text(utxoViewLabel(view)) }, {
                                        scope.launch {
                                            database.watchedKeyDao().setUtxoView(keyId, view.name)
                                            preferences.update { it.copy(utxoView = view) }
                                        }
                                        utxoViewExpanded = false
                                    })
                                }
                            }
                        }
                        SettingsDivider()
                        FormFieldLabel("Dust threshold (sats)")
                        GlanceFilledTextField(
                            value = dustThreshold,
                            onValueChange = { dustThreshold = it.filter(Char::isDigit); dustThresholdError = null },
                            placeholder = "5000",
                            modifier = Modifier.fillMaxWidth().testTag("wallet_setting_dust_threshold"),
                            singleLine = true,
                        )
                        Text("Amounts below this value use the dust style. Set 0 to disable.", color = GlanceMuted, style = MaterialTheme.typography.bodySmall)
                        Button(
                            onClick = {
                                val threshold = dustThreshold.toLongOrNull()
                                if (threshold == null) dustThresholdError = "Enter a non-negative whole number of sats."
                                else scope.launch { database.watchedKeyDao().setDustThresholdSats(keyId, threshold) }
                            },
                            shape = watchTargetPrimaryActionShape,
                            colors = ButtonDefaults.buttonColors(containerColor = watchTargetPrimaryActionColor, contentColor = GlanceBackground),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("save_wallet_dust_threshold"),
                        ) { Text("Save dust threshold") }
                        dustThresholdError?.let { Text(it, color = GlanceWarning, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item {
                SettingsGroup("Danger zone", "wallet_settings_group_danger") {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Deleting this watched key removes only its cached addresses, transactions, UTXOs, and labels.", color = GlanceMuted, style = MaterialTheme.typography.bodySmall)
                        Button(
                            onClick = { deleteConfirmation = true },
                            shape = walletSettingsDeleteActionShape,
                            colors = ButtonDefaults.buttonColors(containerColor = walletSettingsDeleteActionColor, contentColor = GlanceText),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("Delete watched key") }
                    }
                }
            }
        }
    }
    if (deleteConfirmation) AlertDialog(
        onDismissRequest = { deleteConfirmation = false },
        containerColor = walletSettingsDialogColor,
        titleContentColor = GlanceText,
        textContentColor = GlanceMuted,
        title = { Text("Delete watched key?") },
        text = { Text("Are you sure you want to delete? This removes only this key and its cached wallet data.") },
        confirmButton = { Button(onClick = { scope.launch { database.watchedKeyDao().deleteWithOwnedData(keyId); onDeleted() } }, shape = walletSettingsDeleteActionShape, colors = ButtonDefaults.buttonColors(containerColor = walletSettingsDeleteActionColor, contentColor = GlanceText)) { Text("Yes") } },
        dismissButton = { TextButton(onClick = { deleteConfirmation = false }, colors = ButtonDefaults.textButtonColors(contentColor = GlanceText)) { Text("No") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupWalletSettingsScreen(database: GlanceDatabase, groupId: String, onBack: () -> Unit, onDeleted: () -> Unit) {
    val scope = rememberCoroutineScope()
    var group by remember(groupId) { mutableStateOf<WalletGroupEntity?>(null) }
    var loaded by remember(groupId) { mutableStateOf(false) }
    LaunchedEffect(groupId) {
        database.walletGroupDao().observeById(groupId).collect {
            group = it
            loaded = true
        }
    }
    val keys by database.watchedKeyDao().observeForWalletGroup(groupId).collectAsState(emptyList())
    var name by remember(group?.id, group?.label) { mutableStateOf(group?.label.orEmpty()) }
    var dustThreshold by remember(group?.id, group?.dustThresholdSats) { mutableStateOf(group?.dustThresholdSats?.toString().orEmpty()) }
    var dustThresholdError by remember { mutableStateOf<String?>(null) }
    var viewExpanded by remember { mutableStateOf(false) }
    var receiveExpanded by remember { mutableStateOf(false) }
    var keyToRemove by remember { mutableStateOf<WatchedKeyEntity?>(null) }
    var deleteGroupConfirmation by remember { mutableStateOf(false) }
    if (!loaded) return
    if (group == null) {
        LaunchedEffect(groupId) { onDeleted() }
        return
    }
    Scaffold(containerColor = GlanceBackground, topBar = { BackBar("Wallet settings", onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = HomeScreenGutter),
            contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SettingsGroup("Wallet", "group_wallet_settings_group_wallet") {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FormFieldLabel("Wallet name")
                        GlanceFilledTextField(value = name, onValueChange = { name = it }, placeholder = "e.g. Savings", modifier = Modifier.fillMaxWidth().testTag("group_wallet_name"), singleLine = true)
                        Button(onClick = { scope.launch { database.walletGroupDao().rename(groupId, name.trim()); onBack() } }, shape = watchTargetPrimaryActionShape, colors = ButtonDefaults.buttonColors(containerColor = watchTargetPrimaryActionColor, contentColor = GlanceBackground), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save name") }
                        SettingsDivider()
                        ExposedDropdownMenuBox(viewExpanded, { viewExpanded = !viewExpanded }) {
                            SettingsDisclosureRow("UTXO view", modifier = Modifier.menuAnchor().testTag("group_wallet_setting_utxo_view"), value = utxoViewLabel(runCatching { UtxoView.valueOf(group!!.utxoView) }.getOrDefault(UtxoView.BUBBLES))) { viewExpanded = true }
                            ExposedDropdownMenu(viewExpanded, { viewExpanded = false }) {
                                UtxoView.entries.forEach { view -> DropdownMenuItem({ Text(utxoViewLabel(view)) }, { scope.launch { database.walletGroupDao().setUtxoView(groupId, view.name) }; viewExpanded = false }) }
                            }
                        }
                        SettingsDivider()
                        FormFieldLabel("Dust threshold (sats)")
                        GlanceFilledTextField(value = dustThreshold, onValueChange = { dustThreshold = it.filter(Char::isDigit); dustThresholdError = null }, placeholder = "5000", modifier = Modifier.fillMaxWidth().testTag("group_wallet_setting_dust_threshold"), singleLine = true)
                        Text("Amounts below this value use the dust style. Set 0 to disable.", color = GlanceMuted, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { val threshold = dustThreshold.toLongOrNull(); if (threshold == null) dustThresholdError = "Enter a non-negative whole number of sats." else scope.launch { database.walletGroupDao().setDustThresholdSats(groupId, threshold) } }, shape = watchTargetPrimaryActionShape, colors = ButtonDefaults.buttonColors(containerColor = watchTargetPrimaryActionColor, contentColor = GlanceBackground), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("save_group_wallet_dust_threshold")) { Text("Save dust threshold") }
                        dustThresholdError?.let { Text(it, color = GlanceWarning, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item {
                SettingsGroup("Formats", "group_wallet_settings_group_formats") {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Formats are chosen when this wallet is imported. They cannot be added later.", color = GlanceMuted, style = MaterialTheme.typography.bodySmall)
                        ExposedDropdownMenuBox(receiveExpanded, { receiveExpanded = !receiveExpanded }) {
                            SettingsDisclosureRow("Receive format", modifier = Modifier.menuAnchor().testTag("group_wallet_receive_format"), value = group!!.preferredReceiveScriptType.displayName()) { receiveExpanded = true }
                            ExposedDropdownMenu(receiveExpanded, { receiveExpanded = false }) {
                                keys.forEach { key -> DropdownMenuItem({ Text(key.scriptType.displayName()) }, { scope.launch { database.walletGroupDao().setPreferredReceiveScriptType(groupId, key.scriptType) }; receiveExpanded = false }) }
                            }
                        }
                        keys.forEach { key ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text(key.scriptType.displayName()); if (key.scriptType == group!!.preferredReceiveScriptType) Text("Used for Receive", color = GlanceMuted, style = MaterialTheme.typography.bodySmall) }
                                TextButton(onClick = { keyToRemove = key }, enabled = keys.size > 1, colors = ButtonDefaults.textButtonColors(contentColor = GlanceWarning), modifier = Modifier.testTag("remove_group_format_${key.id}")) { Text("Remove") }
                            }
                        }
                    }
                }
            }
            item {
                SettingsGroup("Danger zone", "group_wallet_settings_group_danger") {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Deleting this wallet removes every format and its cached addresses, transactions, UTXOs, and labels.", color = GlanceMuted, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { deleteGroupConfirmation = true }, shape = walletSettingsDeleteActionShape, colors = ButtonDefaults.buttonColors(containerColor = walletSettingsDeleteActionColor, contentColor = GlanceText), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Delete wallet") }
                    }
                }
            }
        }
    }
    keyToRemove?.let { key ->
        AlertDialog(
            onDismissRequest = { keyToRemove = null }, containerColor = walletSettingsDialogColor, titleContentColor = GlanceText, textContentColor = GlanceMuted,
            title = { Text("Remove ${key.scriptType.displayName()}?") },
            text = { Text("This removes only this format and its cached wallet data. Other formats remain in this wallet.") },
            confirmButton = { Button(onClick = { scope.launch {
                if (key.scriptType == group!!.preferredReceiveScriptType) keys.firstOrNull { it.id != key.id }?.let { database.walletGroupDao().setPreferredReceiveScriptType(groupId, it.scriptType) }
                database.watchedKeyDao().deleteWithOwnedData(key.id)
                keyToRemove = null
                onDeleted()
            } }, shape = walletSettingsDeleteActionShape, colors = ButtonDefaults.buttonColors(containerColor = walletSettingsDeleteActionColor, contentColor = GlanceText), modifier = Modifier.testTag("confirm_group_format_removal")) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { keyToRemove = null }, colors = ButtonDefaults.textButtonColors(contentColor = GlanceText)) { Text("Cancel") } },
        )
    }
    if (deleteGroupConfirmation) AlertDialog(
        onDismissRequest = { deleteGroupConfirmation = false }, containerColor = walletSettingsDialogColor, titleContentColor = GlanceText, textContentColor = GlanceMuted,
        title = { Text("Delete wallet?") }, text = { Text("Are you sure you want to delete every format in this wallet and its cached data?") },
        confirmButton = { Button(onClick = { scope.launch { database.walletGroupDao().deleteWithOwnedData(groupId, database.watchedKeyDao()); onDeleted() } }, shape = walletSettingsDeleteActionShape, colors = ButtonDefaults.buttonColors(containerColor = walletSettingsDeleteActionColor, contentColor = GlanceText)) { Text("Yes") } },
        dismissButton = { TextButton(onClick = { deleteGroupConfirmation = false }, colors = ButtonDefaults.textButtonColors(contentColor = GlanceText)) { Text("No") } },
    )
}

private fun ScriptType.displayName(): String = when (this) {
    ScriptType.LEGACY -> "Legacy"
    ScriptType.SEGWIT_COMPAT -> "SegWit compatible"
    ScriptType.NATIVE_SEGWIT -> "Native SegWit"
    ScriptType.TAPROOT -> "Taproot"
}

@Composable private fun WalletDetailTabs(selected: Int, onSelected: (Int) -> Unit) = Surface(color = GlanceSurface, shape = GlanceCardShape, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)) { Row(Modifier.padding(4.dp)) { listOf("Transactions", "UTXOs").forEachIndexed { index, title -> val active = selected == index; Surface(color = if (active) GlanceMandarin else GlanceSurface, shape = walletDetailTabShape, modifier = Modifier.weight(1f).heightIn(min = 40.dp).clickable { onSelected(index) }.semantics { contentDescription = "$title tab, ${if (active) "selected" else "not selected"}" }) { Box(contentAlignment = Alignment.Center) { Text(title, color = if (active) GlanceBackground else GlanceMuted, style = MaterialTheme.typography.labelLarge) } } } } }

@Composable
private fun TransactionPaginationControls(page: Int, totalCount: Int, canAdvanceToLoadMore: Boolean = false, onPageSelected: (Int) -> Unit) {
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

@Composable private fun TransactionCard(transaction: TransactionRow, onClick: () -> Unit) = Surface(color = GlanceSurface, shape = GlanceCardShape, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).semantics { contentDescription = "${transactionDirection(transaction.valueSats, transaction.confirmations)} transaction" }) { Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) { TransactionRowContent(transaction.valueSats, transaction.confirmations, transaction.timestamp, transaction.label, Modifier.weight(1f)); AmountText(transaction.valueSats) } }

@Composable
private fun TransactionRowContent(valueSats: Long, confirmations: Int, timestamp: Long?, label: String?, modifier: Modifier) {
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
private fun TransactionDirectionHeading(valueSats: Long, confirmations: Int) = Row(verticalAlignment = Alignment.CenterVertically) {
    TransactionDirectionIcon(valueSats)
    Spacer(Modifier.width(6.dp))
    Text(transactionDirection(valueSats, confirmations), color = GlanceMandarin, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun TransactionDirectionIcon(valueSats: Long, modifier: Modifier = Modifier) = Icon(
    imageVector = if (valueSats < 0) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
    contentDescription = null,
    tint = transactionDirectionColor(valueSats),
    modifier = modifier.size(16.dp),
)

@Composable
private fun UtxoCard(utxo: UtxoRow, dustThresholdSats: Long, onClick: () -> Unit) = Surface(
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
private fun BubbleGrid(utxos: List<UtxoRow>, dustThresholdSats: Long, modifier: Modifier, isRefreshing: Boolean, onRefresh: () -> Unit, onUtxo: (UtxoRow) -> Unit) {
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
private fun BubbleAmountText(sats: Long, diameter: Dp) {
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
private fun UtxoSheet(utxo: UtxoRow, dustThresholdSats: Long, dismiss: () -> Unit) {
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
private fun UtxoFactsCard(
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

private data class UtxoFact(
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

@Composable
internal fun ReceiveScreen(database: GlanceDatabase, keyId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val target by database.watchedKeyDao().observeById(keyId).collectAsState(null)
    val isSingleAddress = target?.targetType == WatchTargetType.SINGLE_ADDRESS
    var address by remember(keyId) { mutableStateOf<DerivedAddressEntity?>(null) }

    LaunchedEffect(keyId, target?.targetType) {
        address = when (target?.targetType) {
            WatchTargetType.SINGLE_ADDRESS -> database.derivedAddressDao().find(keyId, AddressChain.EXTERNAL, 0)
            WatchTargetType.HD_KEY -> database.walletScreenDao().currentReceiveAddress(keyId)
            null -> null
        }
    }

    val walletLabel = target?.label?.takeIf(String::isNotBlank)
        ?: target?.scriptType?.name?.replace('_', ' ')
        ?: "Wallet"
    Scaffold(containerColor = GlanceBackground, topBar = { BackBar(receiveActionLabel(isSingleAddress), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = HomeScreenGutter, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(receiveContextCaption(walletLabel, isSingleAddress), color = GlanceMuted, style = MaterialTheme.typography.bodySmall)
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                address?.let { derived ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        GlanceQrCode(
                            value = derived.address,
                            contentDescription = "Bitcoin receive QR code",
                            codeSize = receiveQrCodeSize,
                            modifier = Modifier.testTag("receive_qr"),
                        )
                        Spacer(Modifier.height(14.dp))
                        Surface(color = GlanceSurface, shape = GlanceCardShape, modifier = Modifier.fillMaxWidth().testTag("receive_address")) {
                            ClusteredBitcoinAddress(
                                address = derived.address,
                                accessibilityLabel = "Bitcoin receive address",
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { copy(context, derived.address) },
                                shape = GlancePillShape,
                                colors = ButtonDefaults.buttonColors(containerColor = GlanceSurface, contentColor = GlanceText),
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "Copy receive address" },
                            ) { Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Copy") }
                            Button(
                                onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, derived.address), "Share address")) },
                                shape = GlancePillShape,
                                colors = ButtonDefaults.buttonColors(containerColor = GlanceMandarin, contentColor = GlanceBackground),
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "Share receive address" },
                            ) { Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Share") }
                        }
                    }
                } ?: Text("Address unavailable", color = GlanceMuted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    settings: SecurityPreferences,
    preferences: SecurityPreferencesStore,
    authentication: AuthenticationCoordinator,
    tor: TorController,
    onBack: () -> Unit,
    onSupport: () -> Unit,
) = Phase7SettingsContent(
    settings = settings,
    preferences = preferences,
    authentication = authentication,
    tor = tor,
    biometricAvailable = BiometricUnlocker(BiometricManager.from(LocalContext.current)).isAvailable(),
    onBack = onBack,
    onSupport = onSupport,
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun Phase7SettingsContent(
    settings: SecurityPreferences,
    preferences: SecurityPreferencesStore,
    authentication: AuthenticationCoordinator,
    tor: TorController,
    biometricAvailable: Boolean,
    onBack: () -> Unit,
    onSupport: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var eraseConfirmation by remember { mutableStateOf(false) }
    var removeDuressConfirmation by remember { mutableStateOf(false) }
    var setupDuress by remember { mutableStateOf(false) }
    var decoyBalance by remember { mutableStateOf("") }
    var decoyBalanceSaving by remember { mutableStateOf(false) }
    var decoyBalanceError by remember { mutableStateOf<String?>(null) }
    var currencyExpanded by remember { mutableStateOf(false) }
    var explorerExpanded by remember { mutableStateOf(false) }
    var duressExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(settings.credentials?.duressPinVerifier) {
        decoyBalance = authentication.currentDecoyBalance()?.toString().orEmpty()
    }

    Scaffold(containerColor = GlanceBackground, topBar = { BackBar("Settings", onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = HomeScreenGutter).testTag("phase7_settings_scroll"),
            contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { SettingsGroup("Wallet", "settings_group_wallet") {
                P7SettingSwitch("Scramble PIN entry", settings.scrambleKeypad) { scope.launch { preferences.update { it.copy(scrambleKeypad = !it.scrambleKeypad) } } }
                SettingsDivider()
                P7SettingSwitch("Haptic PIN", settings.hapticKeypad) { scope.launch { preferences.update { it.copy(hapticKeypad = !it.hapticKeypad) } } }
                SettingsDivider()
                P7SettingSwitch("Biometric unlock", settings.biometricEnabled, enabled = biometricAvailable, tag = "setting_biometric_unlock") { scope.launch { preferences.update { it.copy(biometricEnabled = !it.biometricEnabled) } } }
            } }
            if (!biometricAvailable) item { Text("Device biometrics are not available or enrolled.", color = GlanceMuted, style = MaterialTheme.typography.bodySmall) }
            item { SettingsGroup("External settings", "settings_group_external") {
                ExposedDropdownMenuBox(explorerExpanded, { explorerExpanded = !explorerExpanded }) {
                    SettingsDisclosureRow("Block explorer", modifier = Modifier.menuAnchor().testTag("setting_block_explorer"), value = explorerName(settings.explorerPreset)) { explorerExpanded = true }
                    ExposedDropdownMenu(explorerExpanded, { explorerExpanded = false }) { ExplorerPreset.entries.forEach { preset -> DropdownMenuItem({ Text(explorerName(preset)) }, { scope.launch { preferences.update { it.copy(explorerPreset = preset) } }; explorerExpanded = false }) } }
                }
                SettingsDivider()
                ExposedDropdownMenuBox(currencyExpanded, { currencyExpanded = !currencyExpanded }) {
                    SettingsDisclosureRow("Currency", modifier = Modifier.menuAnchor().testTag("setting_fiat_currency"), value = settings.fiatCurrency) { currencyExpanded = true }
                    ExposedDropdownMenu(currencyExpanded, { currencyExpanded = false }) { FIAT_CURRENCIES.sorted().forEach { currency -> DropdownMenuItem({ Text(currency) }, { scope.launch { preferences.update { it.copy(fiatCurrency = currency) } }; currencyExpanded = false }) } }
                }
                SettingsDivider()
                P7SettingSwitch("Show balance chart", settings.showBalanceChart, tag = "setting_show_balance_chart") { scope.launch { preferences.update { it.copy(showBalanceChart = !it.showBalanceChart) } } }
            } }
            item { SettingsGroup("App behavior", "settings_group_app_behavior") {
                P7SettingSwitch("Screenshot block", settings.screenshotBlocking) { scope.launch { preferences.update { it.copy(screenshotBlocking = !it.screenshotBlocking) } } }
                SettingsDivider()
                SettingsDisclosureRow("Duress PIN", value = if (settings.credentials?.duressPinVerifier == null) "Not set" else "Configured") { duressExpanded = !duressExpanded }
                if (duressExpanded) SettingsDivider()
            } }
            if (duressExpanded) {
            item { Text("Set the static decoy balance shown after the duress PIN unlocks. This profile remains separate from your wallet.", color = GlanceMuted, style = MaterialTheme.typography.bodySmall) }
            item { DuressForensicLimitationNotice() }
            if (settings.credentials?.duressPinVerifier == null) {
                item { Text("Not activated", color = GlanceMuted, modifier = Modifier.testTag("duress_not_activated")) }
                item { Button(onClick = { setupDuress = true }, shape = settingsActionButtonShape, colors = ButtonDefaults.buttonColors(containerColor = GlanceMandarin, contentColor = GlanceBackground), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Set up duress profile") } }
            } else {
                item { OutlinedTextField(decoyBalance, { decoyBalance = it.filter(Char::isDigit) }, label = { Text("Decoy balance (sats)") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                val decoySats = decoyBalance.toLongOrNull()
                item {
                    Button(
                        onClick = { decoySats?.let { sats -> scope.launch {
                            decoyBalanceSaving = true
                            decoyBalanceError = null
                            try {
                                authentication.configureDecoyBalance(sats)
                            } catch (failure: Throwable) {
                                if (failure is kotlinx.coroutines.CancellationException) throw failure
                                decoyBalanceError = "Unable to save decoy balance. Try again."
                            } finally {
                                decoyBalanceSaving = false
                            }
                        } } },
                        enabled = decoySats != null && !decoyBalanceSaving,
                        shape = settingsActionButtonShape,
                        colors = ButtonDefaults.buttonColors(containerColor = GlanceMandarin, contentColor = GlanceBackground, disabledContainerColor = GlanceSurface),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("save_decoy_balance"),
                    ) { Text(if (decoyBalanceSaving) "Saving…" else "Save decoy balance") }
                }
                decoyBalanceError?.let { error -> item { Text(error, color = GlanceWarning) } }
                item { OutlinedButton(onClick = { removeDuressConfirmation = true }, modifier = Modifier.fillMaxWidth().testTag("remove_duress_profile")) { Text("Remove duress profile") } }
            }
            }
            item { SettingsGroup("Support", "settings_group_support") { SettingsDisclosureRow("Support Glance", icon = { Icon(Icons.Filled.Favorite, contentDescription = null, tint = GlanceMandarin, modifier = Modifier.size(14.dp)) }, onClick = onSupport) } }
            item { SettingsGroup("About", "settings_group_about") { SettingsValueRow("Version", BuildConfig.VERSION_NAME) } }
            item { SettingsGroup("Troubleshooting", "settings_group_troubleshooting") { SettingsDisclosureRow("Erase all data", value = null, warning = true) { eraseConfirmation = true } } }
        }
    }
    if (eraseConfirmation) AlertDialog(onDismissRequest = { eraseConfirmation = false }, containerColor = GlanceSurface, titleContentColor = GlanceText, textContentColor = GlanceMuted, title = { Text("Erase all data?") }, text = { Text("This permanently deletes both encrypted wallet profiles, security settings, local cached data, and cached Tor state. This cannot be undone.") }, confirmButton = { Button(onClick = { scope.launch { authentication.eraseAllData() } }, shape = settingsActionButtonShape, colors = ButtonDefaults.buttonColors(containerColor = GlanceWarning, contentColor = GlanceText)) { Text("Erase permanently") } }, dismissButton = { TextButton(onClick = { eraseConfirmation = false }, colors = ButtonDefaults.textButtonColors(contentColor = GlanceText)) { Text("Cancel") } })
    if (removeDuressConfirmation) AlertDialog(onDismissRequest = { removeDuressConfirmation = false }, containerColor = GlanceSurface, titleContentColor = GlanceText, textContentColor = GlanceMuted, title = { Text("Remove duress profile?") }, text = { Text("This permanently deletes the duress PIN, decoy database, and fake balance. Your real wallet remains unchanged.") }, confirmButton = { Button(onClick = { removeDuressConfirmation = false; scope.launch { authentication.removeDuressProfile(); decoyBalance = "" } }, shape = settingsActionButtonShape, colors = ButtonDefaults.buttonColors(containerColor = GlanceWarning, contentColor = GlanceText)) { Text("Remove permanently") } }, dismissButton = { TextButton(onClick = { removeDuressConfirmation = false }, colors = ButtonDefaults.textButtonColors(contentColor = GlanceText)) { Text("Cancel") } })
    if (setupDuress) DuressSetupDialog(onDismiss = { setupDuress = false }) { pin, balance ->
        authentication.configureDuress(pin, balance)
        setupDuress = false
        decoyBalance = balance.toString()
    }
}

@Composable
internal fun SupportScreen(onBack: () -> Unit) {
    var method by remember { mutableStateOf(DonationMethod.default) }
    var copied by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(topBar = { BackBar("Support Glance", onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .padding(bottom = supportDonationBottomPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_donate_heart),
                contentDescription = "Glance donation heart",
                modifier = Modifier.padding(top = supportDonationHeartTopPadding).size(supportDonationHeartSize),
            )
            Spacer(Modifier.height(supportDonationSectionSpacing))
            Text(
                "Glance is free and always will be.\nIf it’s useful to you, a tip helps\nkeep it that way.",
                color = GlanceMuted,
                style = MaterialTheme.typography.bodySmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(supportDonationSectionSpacing))
            DonationMethodToggle(method, onMethodSelected = { method = it; copied = false })
            Spacer(Modifier.height(supportDonationSectionSpacing))
            GlanceQrCode(
                value = method.payload,
                contentDescription = "${method.label} donation QR code",
                codeSize = supportDonationQrCodeSize,
                modifier = Modifier.testTag("support_qr"),
            )
            Spacer(Modifier.height(supportDonationTightSpacing))
            if (method == DonationMethod.ON_CHAIN) {
                ClusteredBitcoinAddress(
                    address = method.payload,
                    accessibilityLabel = "On-chain donation address",
                    modifier = Modifier.fillMaxWidth().testTag("support_payload"),
                )
            } else Text(
                method.payload,
                color = GlanceMuted,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("support_payload"),
            )
            Spacer(Modifier.height(supportDonationTightSpacing))
            Button(
                onClick = { copy(context, method.payload); copied = true },
                shape = GlancePillShape,
                colors = ButtonDefaults.buttonColors(containerColor = GlanceSurface, contentColor = GlanceText),
                modifier = Modifier.heightIn(min = 48.dp).testTag("copy_donation_address"),
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Copy address")
            }
            if (copied) {
                Spacer(Modifier.height(8.dp))
                Text("Address copied", color = GlanceMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun DonationMethodToggle(selected: DonationMethod, onMethodSelected: (DonationMethod) -> Unit) = Surface(
    color = GlanceSurface,
    shape = GlanceCardShape,
    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("support_method_selector"),
) {
    Row(Modifier.padding(4.dp)) {
        DonationMethod.entries.forEach { choice ->
            val active = selected == choice
            Surface(
                color = if (active) GlanceMandarin else GlanceSurface,
                shape = supportDonationToggleShape,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clickable { onMethodSelected(choice) }
                    .semantics { contentDescription = "${choice.label} donation method, ${if (active) "selected" else "not selected"}" },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(choice.label, color = if (active) GlanceBackground else GlanceMuted, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

internal fun chartRangeLabelColor(choice: ChartRange, selected: ChartRange) =
    if (choice == selected) GlanceMandarin else GlanceMuted

@Composable
private fun BalanceChart(database: GlanceDatabase, fiatStore: FiatPriceStore, currency: String, torEnabled: Boolean, torState: TorState, historicalFiatRefreshRequest: Long, fiat: Boolean, liveEndpointSeconds: Long, liveFiatPrice: Double?, onSelectionChange: (DashboardChartSelection?) -> Unit, offlineMode: Boolean) {
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

@Composable
internal fun TransactionDetailScreen(database: GlanceDatabase, historyId: Long, explorerPreset: ExplorerPreset, onBack: () -> Unit) {
    val transaction by database.walletScreenDao().observeTransactionDetail(historyId).collectAsState(initial = null)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var explorerWarning by remember { mutableStateOf(false) }
    val row = transaction
    Scaffold(topBar = { BackBar("Transaction", onBack) }) { padding ->
        if (row == null) {
            Text("Transaction unavailable", color = GlanceMuted, modifier = Modifier.padding(padding).padding(20.dp))
        } else {
            var label by remember(row.txid, row.label) { mutableStateOf(row.label.orEmpty()) }
            var labelSaving by remember(row.txid) { mutableStateOf(false) }
            var labelSaveError by remember(row.txid) { mutableStateOf<String?>(null) }
            var copiedField by remember(row.txid) { mutableStateOf<String?>(null) }
            val normalizedLabel = label.trim()
            val labelChanged = normalizedLabel != row.label.orEmpty()
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = HomeScreenGutter), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TransactionDirectionHeading(row.valueSats, row.confirmations)
                        AmountText(row.valueSats, large = true)
                    }
                }
                item {
                    TransactionFactsCard(
                        row = row,
                        onCopyTransactionId = {
                            copy(context, row.txid)
                            copiedField = "Transaction ID copied"
                        },
                        onCopyAddress = {
                            copy(context, row.address)
                            copiedField = "Address copied"
                        },
                    )
                }
                copiedField?.let { message ->
                    item { Text(message, color = GlanceMuted, style = MaterialTheme.typography.bodySmall) }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FormFieldLabel("Label")
                        GlanceFilledTextField(
                            value = label,
                            onValueChange = {
                                label = it
                                labelSaveError = null
                            },
                            placeholder = "e.g. From exchange",
                            modifier = Modifier.fillMaxWidth().testTag("transaction_label"),
                            singleLine = true,
                        )
                    }
                }
                item {
                    Button(
                        onClick = {
                            labelSaving = true
                            labelSaveError = null
                            scope.launch {
                                try {
                                    if (normalizedLabel.isEmpty()) database.labelDao().delete(LabelReferenceType.TRANSACTION, row.txid)
                                    else database.labelDao().upsert(LabelEntity(LabelReferenceType.TRANSACTION, row.txid, normalizedLabel))
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    labelSaveError = "Unable to save label. Try again."
                                } finally {
                                    labelSaving = false
                                }
                            }
                        },
                        enabled = labelChanged && !labelSaving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("save_transaction_label"),
                        shape = watchTargetPrimaryActionShape,
                        colors = ButtonDefaults.buttonColors(containerColor = watchTargetPrimaryActionColor, contentColor = GlanceBackground, disabledContainerColor = GlanceSurface, disabledContentColor = GlanceMuted),
                    ) { Text(if (labelSaving) "Saving…" else "Save label") }
                }
                labelSaveError?.let { error ->
                    item { Text(error, color = GlanceWarning, style = MaterialTheme.typography.bodySmall) }
                }
                item {
                    Surface(
                        color = GlanceSurface,
                        shape = GlancePillShape,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { explorerWarning = true }.semantics { contentDescription = "View in block explorer" },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = GlanceText, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("View in block explorer", color = GlanceText, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            if (explorerWarning) AlertDialog(
                onDismissRequest = { explorerWarning = false },
                title = { Text("Open external browser?") },
                text = { Text("This opens an external browser outside Glance's Tor connection. It may expose this transaction ID and your device IP address.") },
                confirmButton = { Button(onClick = { explorerWarning = false; openExplorer(context, explorerPreset, row.txid) }, colors = ButtonDefaults.buttonColors(containerColor = GlanceWarning)) { Text("Open browser") } },
                dismissButton = { OutlinedButton(onClick = { explorerWarning = false }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun TransactionFactsCard(
    row: TransactionDetailRow,
    onCopyTransactionId: () -> Unit,
    onCopyAddress: () -> Unit,
) = Surface(color = GlanceSurface, shape = GlanceCardShape, modifier = Modifier.fillMaxWidth().testTag("transaction_facts_card")) {
    Column {
        TransactionFactRow("Date", row.timestamp?.let(::formatChartTimestamp) ?: if (row.confirmations == 0) "Pending" else "Date unavailable")
        TransactionFactDivider()
        TransactionFactRow("Confirmations", row.confirmations.toString())
        TransactionFactDivider()
        TransactionFactRow("Block height", row.blockHeight?.toString() ?: "Unavailable")
        TransactionFactDivider()
        TransactionFactRow("Txid", abbreviateTransactionIdentifier(row.txid), onCopyTransactionId, "Copy transaction ID")
        TransactionFactDivider()
        TransactionFactRow("Address", abbreviateTransactionIdentifier(row.address), onCopyAddress, "Copy address")
    }
}

@Composable
private fun GroupTransactionFactsCard(
    row: GroupTransactionDetailRow,
    onCopyTransactionId: () -> Unit,
) = Surface(color = GlanceSurface, shape = GlanceCardShape, modifier = Modifier.fillMaxWidth().testTag("group_transaction_facts_card")) {
    Column {
        TransactionFactRow("Date", row.timestamp?.let(::formatChartTimestamp) ?: if (row.confirmations == 0) "Pending" else "Date unavailable")
        TransactionFactDivider()
        TransactionFactRow("Confirmations", row.confirmations.toString())
        TransactionFactDivider()
        TransactionFactRow("Block height", row.blockHeight?.toString() ?: "Unavailable")
        TransactionFactDivider()
        TransactionFactRow("Txid", abbreviateTransactionIdentifier(row.txid), onCopyTransactionId, "Copy transaction ID")
    }
}

@Composable
private fun TransactionFactRow(label: String, value: String, onCopy: (() -> Unit)? = null, copyDescription: String? = null) = Row(
    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = if (onCopy == null) 16.dp else 4.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(label, color = GlanceMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    Text(value, color = GlanceText, style = MaterialTheme.typography.bodyMedium)
    onCopy?.let {
        IconButton(onClick = it) {
            Icon(Icons.Filled.ContentCopy, contentDescription = requireNotNull(copyDescription), tint = GlanceText, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun TransactionFactDivider() = HorizontalDivider(color = GlanceBackground.copy(alpha = 0.7f), thickness = 1.dp)

internal fun transactionStatus(confirmations: Int, blockHeight: Int?, timestamp: Long?): String = when {
    confirmations == 0 -> "Pending"
    blockHeight == null -> "$confirmations confirmations"
    timestamp == null -> "$confirmations confirmations; date unavailable"
    else -> "$confirmations confirmations"
}

internal fun explorerUrl(preset: ExplorerPreset, txid: String): String = when (preset) {
    ExplorerPreset.MEMPOOL_SPACE -> "https://mempool.space/tx/$txid"
    ExplorerPreset.BLOCKSTREAM -> "https://blockstream.info/tx/$txid"
}

private fun explorerName(preset: ExplorerPreset): String = when (preset) {
    ExplorerPreset.MEMPOOL_SPACE -> "mempool.space"
    ExplorerPreset.BLOCKSTREAM -> "Blockstream"
}

private fun utxoViewLabel(view: UtxoView): String = when (view) {
    UtxoView.BUBBLES -> "Bubbles"
    UtxoView.LIST -> "List"
}

private fun openExplorer(context: Context, preset: ExplorerPreset, txid: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, explorerUrl(preset, txid).toUri())) }
}

internal fun historicalFiatRefreshMessage(status: HistoricalFiatStatus, torEnabled: Boolean): String? = when (status) {
    HistoricalFiatStatus.Loading, HistoricalFiatStatus.Complete, HistoricalFiatStatus.Partial -> null
    HistoricalFiatStatus.Unavailable -> if (torEnabled) {
        "Full history unavailable through the current Tor provider."
    } else {
        "Historical fiat prices are unavailable from the current provider."
    }
}

@Composable
private fun MandarinLine(
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

private fun utxoToneColor(tone: UtxoVisualTone): Color? = when (tone) {
    UtxoVisualTone.PENDING -> pendingUtxoColor
    UtxoVisualTone.DUST -> GlanceMuted
    UtxoVisualTone.STANDARD -> null
}

internal fun utxoAmountTextColor(confirmations: Int, valueSats: Long, dustThresholdSats: Long): Color? =
    when (utxoVisualTone(confirmations, valueSats, dustThresholdSats)) {
        UtxoVisualTone.PENDING, UtxoVisualTone.DUST, UtxoVisualTone.STANDARD -> null
    }

private fun utxoBubbleFill(tone: UtxoVisualTone): Color = when (tone) {
    UtxoVisualTone.PENDING -> pendingUtxoBubbleFill
    UtxoVisualTone.DUST -> dustBubbleFill
    UtxoVisualTone.STANDARD -> utxoBubbleFill
}

@Composable
private fun AmountText(sats: Long, modifier: Modifier = Modifier, large: Boolean = false, color: Color? = null) {
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
@Composable private fun FiatAmountText(sats: Long, price: Double?, currency: String, modifier: Modifier = Modifier, large: Boolean = false, regularStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodySmall) { val text = price?.let { NumberFormat.getCurrencyInstance().apply { this.currency = Currency.getInstance(currency) }.format(fiatValue(sats, it)) } ?: "Fiat price unavailable"; Text("≈ $text", color = if (large) GlanceText else GlanceMuted, style = if (large) MaterialTheme.typography.headlineMedium else regularStyle, modifier = modifier) }
@Composable
private fun FiatCentsAmountText(cents: Long, currency: String, modifier: Modifier = Modifier, large: Boolean = false, regularStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodySmall) {
    Text(
        "≈ ${formatFiatCents(cents, currency)}",
        color = if (large) GlanceText else GlanceMuted,
        style = if (large) MaterialTheme.typography.headlineMedium else regularStyle,
        modifier = modifier,
    )
}

private fun formatFiatCents(cents: Long, currency: String): String = NumberFormat.getCurrencyInstance().apply {
    this.currency = Currency.getInstance(currency)
}.format(cents / 100.0)
private fun formatChartTimestamp(timestampSeconds: Long): String = DateFormat.getDateTimeInstance(
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
private const val FIAT_REFRESH_SECONDS = 300L
private const val MEMPOOL_ONION_API = "http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion/api/"

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
@Composable private fun BackBar(title: String, back: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) = CenterAlignedTopAppBar(
    title = { Text(title, style = MaterialTheme.typography.labelLarge.copy(fontSize = settingsHeaderTextSize)) },
    navigationIcon = { IconButton(onClick = back) { Icon(Icons.Filled.ArrowBack, contentDescription = "Navigate back") } },
    actions = actions,
    colors = TopAppBarDefaults.topAppBarColors(
        containerColor = settingsTopBarColor,
        titleContentColor = GlanceText,
        navigationIconContentColor = GlanceText,
        actionIconContentColor = GlanceText,
    ),
)

@Composable
private fun SettingsGroup(label: String, tag: String, content: @Composable ColumnScope.() -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(label, color = GlanceMuted, style = MaterialTheme.typography.labelSmall)
    Surface(color = GlanceSurface, shape = GlanceCardShape, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(content = content)
    }
}

@Composable
private fun FormFieldLabel(text: String) = Text(
    text,
    color = GlanceMuted,
    style = MaterialTheme.typography.labelSmall,
)

@Composable
private fun GlanceFilledTextField(
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
private fun SettingsDivider() = HorizontalDivider(color = GlanceBackground.copy(alpha = 0.7f), thickness = 1.dp, modifier = Modifier.padding(start = 16.dp))

@Composable
private fun P7SettingSwitch(label: String, checked: Boolean, enabled: Boolean = true, tag: String? = null, change: (Boolean) -> Unit) = Row(
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
private fun SettingsDisclosureRow(
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
private fun SettingsValueRow(label: String, value: String) = Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(label, Modifier.weight(1f), color = GlanceText, style = MaterialTheme.typography.bodyMedium)
    Text(value, color = GlanceMuted, style = MaterialTheme.typography.bodySmall)
}
private fun copy(context: Context, value: String) {
    val clip = ClipData.newPlainText("Bitcoin value", value)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
}
@Composable
private fun GlanceQrCode(value: String, contentDescription: String, codeSize: Dp, modifier: Modifier = Modifier) {
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

private fun qr(value: String) = createBitmap(256, 256, Bitmap.Config.ARGB_8888).also { bitmap ->
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 256, 256)
    for (x in 0 until 256) for (y in 0 until 256) {
        bitmap[x, y] = if (matrix[x, y]) qrModuleColor else qrBackgroundColor.toArgb()
    }
}.asImageBitmap()
private fun ScriptType.toCryptoType() = app.glance.wallet.core.crypto.ScriptType.valueOf(name)
private fun app.glance.wallet.core.crypto.ScriptType.toDataType() = ScriptType.valueOf(name)
