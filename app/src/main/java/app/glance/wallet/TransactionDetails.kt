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
import androidx.compose.ui.text.style.TextOverflow
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
internal fun TransactionDetailScreen(database: GlanceDatabase, keyId: String, txid: String, explorerPreset: ExplorerPreset, onBack: () -> Unit) {
    val transaction by database.walletScreenDao().observeTransactionDetail(keyId, txid).collectAsState(initial = null)
    val inputs by database.transactionDetailDao().observeInputs(txid).collectAsState(emptyList())
    val outputs by database.transactionDetailDao().observeOutputs(txid).collectAsState(emptyList())
    val watchedAddresses by database.transactionDetailDao().observeWatchedAddressesForKey(keyId).collectAsState(emptyList())
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
                    TransactionFactsCard(row = row, onCopyTransactionId = {
                            copy(context, row.txid)
                            copiedField = "Transaction ID copied"
                        })
                }
                item { TransactionIoSections(inputs, outputs, watchedAddresses.toSet(), onCopyAddress = { copy(context, it); copiedField = "Address copied" }) }
                if (inputs.isEmpty() && outputs.isEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Transaction inputs and outputs are not cached yet. Pull to refresh the wallet to load them.", color = GlanceMuted)
                    }
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
internal fun TransactionFactsCard(
    row: TransactionDetailRow,
    onCopyTransactionId: () -> Unit,
) = Surface(color = GlanceSurface, shape = GlanceCardShape, modifier = Modifier.fillMaxWidth().testTag("transaction_facts_card")) {
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

/** Compatibility route for persisted pre-v14 navigation; new routes always use key and txid. */
@Composable
internal fun TransactionDetailScreen(database: GlanceDatabase, historyId: Long, explorerPreset: ExplorerPreset, onBack: () -> Unit) {
    val scope by database.walletScreenDao().observeTransactionScopeForHistory(historyId).collectAsState(null)
    val current = scope
    if (current == null) Scaffold(topBar = { BackBar("Transaction", onBack) }) { Text("Transaction unavailable", color = GlanceMuted, modifier = Modifier.padding(it).padding(20.dp)) }
    else TransactionDetailScreen(database, current.keyId, current.txid, explorerPreset, onBack = onBack)
}

@Composable
internal fun TransactionIoSections(inputs: List<TransactionInputEntity>, outputs: List<TransactionOutputEntity>, watchedAddresses: Set<String> = emptySet(), onCopyAddress: (String) -> Unit) {
    var inputsExpanded by remember { mutableStateOf(false) }
    var outputsExpanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TransactionIoSection("Inputs", inputs.size, inputsExpanded, { inputsExpanded = !inputsExpanded }) { inputs.forEach { entry ->
            TransactionIoEntry(entry.entryIndex, entry.address, entry.isCoinbase, watchedAddresses, onCopyAddress)
        } }
        TransactionIoSection("Outputs", outputs.size, outputsExpanded, { outputsExpanded = !outputsExpanded }) { outputs.forEach { entry ->
            TransactionIoEntry(entry.entryIndex, entry.address, false, watchedAddresses, onCopyAddress)
        } }
    }
}

@Composable private fun TransactionIoSection(title: String, count: Int, expanded: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) =
    Surface(color = GlanceSurface, shape = GlanceCardShape, modifier = Modifier.fillMaxWidth().testTag("transaction_${title.lowercase()}")) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onToggle).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$title ($count)", color = GlanceText, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward, contentDescription = if (expanded) "Collapse $title" else "Expand $title", tint = GlanceText)
            }
            if (expanded) content()
        }
    }

internal fun transactionEntryIndexLabel(index: Int): String = "#$index"

@Composable private fun TransactionIoEntry(index: Int, address: String?, coinbase: Boolean, watchedAddresses: Set<String>, onCopyAddress: (String) -> Unit) =
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(transactionEntryIndexLabel(index), color = GlanceMuted, style = MaterialTheme.typography.labelSmall)
        when {
            coinbase -> Text("Coinbase input", color = GlanceMuted)
            address == null -> Text("Address unavailable (script output)", color = GlanceMuted)
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    address,
                    color = GlanceText,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (address in watchedAddresses) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag(if (address in watchedAddresses) "transaction_watched_address" else "transaction_external_address"),
                )
                CompactCopyButton(onClick = { onCopyAddress(address) }, description = "Copy address")
            }
        }
    }

@Composable
internal fun GroupTransactionFactsCard(
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
internal fun TransactionFactRow(label: String, value: String, onCopy: (() -> Unit)? = null, copyDescription: String? = null) = Row(
    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = if (onCopy == null) 16.dp else 4.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(label, color = GlanceMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    Text(value, color = GlanceText, style = MaterialTheme.typography.bodyMedium)
    onCopy?.let {
        CompactCopyButton(onClick = it, description = requireNotNull(copyDescription))
    }
}

@Composable
private fun CompactCopyButton(onClick: () -> Unit, description: String) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(Icons.Filled.ContentCopy, contentDescription = description, tint = GlanceText, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun TransactionFactDivider() = HorizontalDivider(color = GlanceBackground.copy(alpha = 0.7f), thickness = 1.dp)

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

internal fun explorerName(preset: ExplorerPreset): String = when (preset) {
    ExplorerPreset.MEMPOOL_SPACE -> "mempool.space"
    ExplorerPreset.BLOCKSTREAM -> "Blockstream"
}

internal fun utxoViewLabel(view: UtxoView): String = when (view) {
    UtxoView.BUBBLES -> "Bubbles"
    UtxoView.LIST -> "List"
}

internal fun openExplorer(context: Context, preset: ExplorerPreset, txid: String) {
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

