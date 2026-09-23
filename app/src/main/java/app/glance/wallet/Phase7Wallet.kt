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


internal object Routes { const val HOME = "home"; const val ADD = "add"; const val SETTINGS = "settings"; const val SUPPORT = "support"; const val DETAIL = "detail/{id}"; const val GROUP_DETAIL = "group-detail/{id}"; const val GROUP_SETTINGS = "group-settings/{id}"; const val GROUP_TRANSACTION = "group-transaction/{groupId}/{txid}"; const val RECEIVE = "receive/{id}"; const val WALLET_SETTINGS = "wallet-settings/{id}"; const val TRANSACTION = "transaction/{historyId}" }

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
