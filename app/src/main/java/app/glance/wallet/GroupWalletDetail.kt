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

