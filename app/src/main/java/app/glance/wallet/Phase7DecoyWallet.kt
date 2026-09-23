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
@Composable
internal fun DecoyPhase7Wallet(session: ProfileSession, authentication: AuthenticationCoordinator, torController: TorController) {
    val database = session.database
    val app = LocalContext.current.applicationContext as GlanceApplication
    val scope = rememberCoroutineScope()
    val torState by torController.state.collectAsState()
    val syncCoordinator = remember(database) { WalletSyncCoordinator { app.networkClients.syncEngine(RoomWalletSyncStore(database)).syncAll() } }
    DisposableEffect(syncCoordinator) {
        app.registerSyncCoordinator(syncCoordinator)
        onDispose { app.unregisterSyncCoordinator(syncCoordinator) }
    }
    LaunchedEffect(torState) { syncCoordinator.requestSync(torEnabled = true, torState = torState, offlineMode = false) }
    val syncState by syncCoordinator.state.collectAsState()
    val keys by database.walletScreenDao().observeKeyBalances().collectAsState(emptyList())
    val balance by database.utxoDao().observeConfirmedBalance().collectAsState(0L)
    var detail by remember { mutableStateOf(false) }
    var transactionDetailId by remember { mutableStateOf<Long?>(null) }
    var receiveKeyId by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(false) }
    var revealPhrase by remember { mutableStateOf(false) }
    val mnemonic by produceState<String?>(null, settings, revealPhrase) {
        value = if (settings && revealPhrase) authentication.currentDecoyMnemonic() else null
    }
    transactionDetailId?.let { historyId ->
        TransactionDetailScreen(database, historyId, ExplorerPreset.MEMPOOL_SPACE) { transactionDetailId = null }
        return
    }
    receiveKeyId?.let { keyId ->
        ReceiveScreen(database, keyId) { receiveKeyId = null }
        return
    }
    if (settings) {
        DecoySettingsContent(
            revealPhrase = revealPhrase,
            mnemonic = mnemonic,
            onBack = { settings = false; revealPhrase = false },
            onRevealPhrase = { revealPhrase = true },
        )
        return
    }
    if (detail) {
        val key = keys.firstOrNull()
        if (key != null) KeyDetailScreen(database, key.id, UtxoView.BUBBLES, syncState, onRefresh = { scope.launch { syncCoordinator.requestSync(true, torState) } }, onBack = { detail = false }, onTransaction = { transactionDetailId = it }, onReceive = { receiveKeyId = key.id }) else detail = false
        return
    }
    Scaffold(containerColor = GlanceBackground) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Glance", color = GlanceText, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { settings = true }) { Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = GlanceText) }
                }
            }
            item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { AmountText(balance, Modifier.testTag("decoy_balance"), large = true) } }
            if (syncState != WalletSyncState.Succeeded) item { WalletSyncStatus(syncState) { scope.launch { syncCoordinator.requestSync(true, torState) } } }
            item { Text("Watched keys", color = GlanceMuted, style = MaterialTheme.typography.labelMedium) }
            items(keys, key = { it.id }) { key ->
                Surface(
                    color = GlanceSurface,
                    shape = GlanceCardShape,
                    modifier = Modifier.fillMaxWidth().clickable { detail = true }.testTag("decoy_watched_key_${key.id}"),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(key.label.ifBlank { "Native SegWit" }, color = GlanceText, style = MaterialTheme.typography.titleMedium)
                            Text("Native SegWit", color = GlanceMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        AmountText(key.balanceSats)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DecoySettingsContent(
    revealPhrase: Boolean,
    mnemonic: String?,
    onBack: () -> Unit,
    onRevealPhrase: () -> Unit,
) {
    Scaffold(containerColor = GlanceBackground, topBar = { BackBar("Settings", onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = HomeScreenGutter),
            contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SettingsGroup("Wallet", "decoy_settings_group_wallet") {
                    SettingsDisclosureRow(
                        label = "Recovery phrase",
                        value = if (revealPhrase) "Visible" else null,
                        onClick = onRevealPhrase,
                    )
                    if (revealPhrase) {
                        SettingsDivider()
                        Text(
                            mnemonic ?: "Phrase unavailable",
                            color = GlanceText,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp).testTag("duress_recovery_phrase"),
                        )
                    }
                }
            }
            item { SettingsGroup("About", "decoy_settings_group_about") { SettingsValueRow("Version", BuildConfig.VERSION_NAME) } }
        }
    }
}

