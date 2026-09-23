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
internal fun UtxoConfirmationSummary(confirmedCount: Int, pendingCount: Int) {
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

