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
internal fun SettingsScreen(
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
    var currencyExpanded by remember { mutableStateOf(false) }
    var explorerExpanded by remember { mutableStateOf(false) }
    var duressExpanded by remember { mutableStateOf(false) }

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
                    SettingsDisclosureRow("Block explorer", modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).testTag("setting_block_explorer"), value = explorerName(settings.explorerPreset)) { explorerExpanded = true }
                    ExposedDropdownMenu(explorerExpanded, { explorerExpanded = false }) { ExplorerPreset.entries.forEach { preset -> DropdownMenuItem({ Text(explorerName(preset)) }, { scope.launch { preferences.update { it.copy(explorerPreset = preset) } }; explorerExpanded = false }) } }
                }
                SettingsDivider()
                ExposedDropdownMenuBox(currencyExpanded, { currencyExpanded = !currencyExpanded }) {
                    SettingsDisclosureRow("Currency", modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).testTag("setting_fiat_currency"), value = settings.fiatCurrency) { currencyExpanded = true }
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
            item { Text("Creates an isolated, automatically generated Native SegWit decoy wallet. Its real balance, history, and UTXOs sync through Tor only.", color = GlanceMuted, style = MaterialTheme.typography.bodySmall) }
            item { DuressForensicLimitationNotice() }
            if (settings.credentials?.duressPinVerifier == null) {
                item { Text("Not activated", color = GlanceMuted, modifier = Modifier.testTag("duress_not_activated")) }
                item { Button(onClick = { setupDuress = true }, shape = settingsActionButtonShape, colors = ButtonDefaults.buttonColors(containerColor = GlanceMandarin, contentColor = GlanceBackground), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Set up duress profile") } }
            } else {
                item { OutlinedButton(onClick = { removeDuressConfirmation = true }, modifier = Modifier.fillMaxWidth().testTag("remove_duress_profile")) { Text("Remove duress profile") } }
            }
            }
            item { SettingsGroup("Support", "settings_group_support") { SettingsDisclosureRow("Support Glance", icon = { Icon(Icons.Filled.Favorite, contentDescription = null, tint = GlanceMandarin, modifier = Modifier.size(14.dp)) }, onClick = onSupport) } }
            item { SettingsGroup("About", "settings_group_about") { SettingsValueRow("Version", BuildConfig.VERSION_NAME) } }
            item { SettingsGroup("Troubleshooting", "settings_group_troubleshooting") { SettingsDisclosureRow("Erase all data", value = null, warning = true) { eraseConfirmation = true } } }
        }
    }
    if (eraseConfirmation) AlertDialog(onDismissRequest = { eraseConfirmation = false }, containerColor = GlanceSurface, titleContentColor = GlanceText, textContentColor = GlanceMuted, title = { Text("Erase all data?") }, text = { Text("This permanently deletes both encrypted wallet profiles, security settings, local cached data, and cached Tor state. This cannot be undone.") }, confirmButton = { Button(onClick = { scope.launch { authentication.eraseAllData() } }, shape = settingsActionButtonShape, colors = ButtonDefaults.buttonColors(containerColor = GlanceWarning, contentColor = GlanceText)) { Text("Erase permanently") } }, dismissButton = { TextButton(onClick = { eraseConfirmation = false }, colors = ButtonDefaults.textButtonColors(contentColor = GlanceText)) { Text("Cancel") } })
    if (removeDuressConfirmation) AlertDialog(onDismissRequest = { removeDuressConfirmation = false }, containerColor = GlanceSurface, titleContentColor = GlanceText, textContentColor = GlanceMuted, title = { Text("Remove duress profile?") }, text = { Text("This permanently deletes the duress PIN, generated recovery phrase, encrypted decoy database, and wallet history. Your real wallet remains unchanged.") }, confirmButton = { Button(onClick = { removeDuressConfirmation = false; scope.launch { authentication.removeDuressProfile() } }, shape = settingsActionButtonShape, colors = ButtonDefaults.buttonColors(containerColor = GlanceWarning, contentColor = GlanceText)) { Text("Remove permanently") } }, dismissButton = { TextButton(onClick = { removeDuressConfirmation = false }, colors = ButtonDefaults.textButtonColors(contentColor = GlanceText)) { Text("Cancel") } })
    if (setupDuress) DuressSetupDialog(onDismiss = { setupDuress = false }) { pin ->
        authentication.configureDuress(pin)
        setupDuress = false
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
internal fun DonationMethodToggle(selected: DonationMethod, onMethodSelected: (DonationMethod) -> Unit) = Surface(
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

