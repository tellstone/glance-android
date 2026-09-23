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

