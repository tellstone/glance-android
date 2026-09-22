package app.glance.wallet

import android.os.Bundle
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.glance.wallet.core.security.*
import java.util.concurrent.Executor
import java.io.File
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlinx.coroutines.flow.first

class MainActivity : FragmentActivity() {
    private lateinit var preferences: SecurityPreferencesStore
    private lateinit var torController: TorController
    private lateinit var profiles: ProfileDatabaseManager
    private lateinit var authentication: AuthenticationCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = (application as GlanceApplication).securityPreferences
        torController = (application as GlanceApplication).torController
        profiles = ProfileDatabaseManager(applicationContext)
        authentication = AuthenticationCoordinator(
            preferences,
            profiles,
            torStateCleaner = torController,
            eraseStateStore = FileEraseStateStore(File(applicationContext.noBackupFilesDir, "erase-incomplete.marker")),
        )
        lifecycleScope.launch { authentication.initialize() }
        setContent {
            val authState by authentication.state.collectAsState()
            val settings by preferences.data.collectAsState<SecurityPreferences, SecurityPreferences?>(initial = null)
            val torState by torController.state.collectAsState()
            var initialTorBootstrap by remember { mutableStateOf(true) }
            LaunchedEffect(settings?.torEnabled, settings?.offlineMode) {
                val loadedSettings = settings ?: return@LaunchedEffect
                torController.setOffline(loadedSettings.offlineMode)
                if (!loadedSettings.offlineMode) torController.setEnabled(loadedSettings.torEnabled)
            }
            LaunchedEffect(settings, torState) {
                val loadedSettings = settings ?: return@LaunchedEffect
                if (loadedSettings.offlineMode || !loadedSettings.torEnabled || torState is TorState.Ready || torState is TorState.Unavailable) {
                    initialTorBootstrap = false
                }
            }
            LaunchedEffect(settings?.torEnabled, settings?.offlineMode) {
                val loadedSettings = settings ?: return@LaunchedEffect
                if (loadedSettings.torEnabled && !loadedSettings.offlineMode) {
                    kotlinx.coroutines.delay(TOR_BOOTSTRAP_GATE_TIMEOUT_MILLIS)
                    initialTorBootstrap = false
                }
            }
            LaunchedEffect(authState, settings?.torEnabled, settings?.offlineMode) {
                val loadedSettings = settings ?: return@LaunchedEffect
                when ((authState as? AuthenticationState.Unlocked)?.session?.type) {
                    ProfileType.REAL -> (application as GlanceApplication).setNetworkSessionActive(true, loadedSettings.torEnabled, loadedSettings.offlineMode)
                    ProfileType.DECOY -> (application as GlanceApplication).setNetworkSessionActive(false, loadedSettings.torEnabled, loadedSettings.offlineMode)
                    null -> Unit // Pre-PIN Tor bootstrap is intentionally kept alive; no network work is scheduled.
                }
            }
            ApplyScreenshotBlocking(settings?.screenshotBlocking ?: true)
            GlanceTheme { Surface(Modifier.fillMaxSize(), color = GlanceBackground) {
                val loadedSettings = settings
                if (loadedSettings == null) Box(Modifier.fillMaxSize())
                else if (torGate(loadedSettings.torEnabled, torState, loadedSettings.offlineMode, initialTorBootstrap) == TorGate.PENDING) TorBootstrapScreen()
                else GlanceApp(authState, loadedSettings, authentication, preferences, torController, ::requestBiometricUnlock)
            } }
        }
    }

    override fun onStop() {
        super.onStop()
        authentication.lock()
        (application as GlanceApplication).setNetworkSessionActive(false, torEnabled = true)
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { preferences.data.first().let { settings -> torController.setOffline(settings.offlineMode); if (!settings.offlineMode) torController.setEnabled(settings.torEnabled) } }
    }

    private fun requestBiometricUnlock() {
        if (!BiometricUnlocker(BiometricManager.from(this)).isAvailable()) return
        BiometricPrompt(this, Executor { runOnUiThread(it) }, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                lifecycleScope.launch { authentication.unlockWithBiometric() }
            }
        }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Glance")
            .setSubtitle("Authenticate to open your wallet")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Use PIN").build())
    }
}

@Composable
internal fun TorBootstrapScreen() = Box(
    modifier = Modifier.fillMaxSize().background(GlanceBackground),
    contentAlignment = Alignment.Center,
) {
    Column(
        modifier = Modifier.width(280.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(96.dp).testTag("tor_bootstrap_logo"),
        )
        Text("Glance", color = GlanceText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(24.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("tor_bootstrap_progress"), color = GlanceMandarin)
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Connecting to Tor",
            color = GlanceMuted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("tor_bootstrap_status"),
        )
    }
}

@Composable private fun ApplyScreenshotBlocking(enabled: Boolean) {
    val view = LocalView.current
    LaunchedEffect(enabled) {
        val window = (view.context as? android.app.Activity)?.window ?: return@LaunchedEffect
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

@Composable private fun GlanceApp(state: AuthenticationState, settings: SecurityPreferences, authentication: AuthenticationCoordinator, preferences: SecurityPreferencesStore, torController: TorController, biometricUnlock: () -> Unit) = when (state) {
    AuthenticationState.Initializing -> Box(Modifier.fillMaxSize())
    AuthenticationState.SetupRequired -> PinSetup(authentication)
    AuthenticationState.Locked -> PinUnlock(authentication, settings, biometricUnlock)
    is AuthenticationState.Throttled -> PinUnlock(authentication, settings, biometricUnlock, state.nextAllowedAtMillis)
    AuthenticationState.RecoveryRequired -> DatabaseRecovery(authentication)
    AuthenticationState.EraseIncomplete -> DatabaseRecovery(authentication, eraseIncomplete = true)
    is AuthenticationState.Unlocked -> if (state.session.type == ProfileType.DECOY) {
        DecoyPhase7Wallet(state.session.database, state.session.fakeBalanceSats ?: 0L, authentication)
    } else {
        Phase7Wallet(state.session, settings, preferences, authentication, torController)
    }
}

@Composable private fun PinSetup(authentication: AuthenticationCoordinator) {
    PinSetupContent { realPin ->
        authentication.configure(realPin)
    }
}

@Composable
internal fun PinSetupContent(onConfigured: suspend (realPin: String) -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(0) }
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    if (saving) {
        PinPage("Securing wallet", "This may take a few seconds.") {
            CircularProgressIndicator(color = GlanceMandarin)
        }
        return
    }

    when (step) {
        0 -> PinPage("Create your PIN", "Choose a six-digit PIN.") {
            PinKeypad(pin, false, true, onAutoSubmit = { step = 1 }) { pin = it }
        }
        1 -> PinPage("Confirm your PIN", "Enter the same six digits again.") {
            PinKeypad(confirmation, false, true, onAutoSubmit = { submittedPin ->
                    if (submittedPin == pin) {
                        error = null
                        saving = true
                        scope.launch {
                            try {
                                onConfigured(pin)
                            } catch (failure: Throwable) {
                                if (failure is kotlinx.coroutines.CancellationException) throw failure
                                error = "Unable to secure wallet. Try again."
                            } finally {
                                saving = false
                            }
                        }
                    } else {
                        error = "PINs do not match."
                        confirmation = ""
                    }
                }) { confirmation = it }
            error?.let { Text(it, color = GlanceWarning, modifier = Modifier.padding(top = 12.dp)) }
        }
    }
}

@Composable private fun PinUnlock(authentication: AuthenticationCoordinator, settings: SecurityPreferences, biometricUnlock: () -> Unit, throttledUntil: Long? = null) {
    var pin by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }; var now by remember { mutableStateOf(System.currentTimeMillis()) }; var checking by remember { mutableStateOf(false) }; var submitting by remember { mutableStateOf(false) }; val scope = rememberCoroutineScope()
    LaunchedEffect(throttledUntil) {
        while (throttledUntil != null && now < throttledUntil) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(250)
        }
    }
    val remainingSeconds = throttledUntil?.let { ((it - now).coerceAtLeast(0L) + 999L) / 1000L }
    val isThrottled = remainingSeconds != null && remainingSeconds > 0
    val status = when {
        isThrottled -> "Try again in ${remainingSeconds}s."
        settings.retryState.failedAttempts > 0 -> "Wrong PIN — ${5 - settings.retryState.failedAttempts.coerceAtMost(5)} attempts left before timeout."
        else -> null
    }
    PinUnlockPage {
        val biometricAvailable = settings.biometricEnabled && BiometricUnlocker(BiometricManager.from(androidx.compose.ui.platform.LocalContext.current)).isAvailable()
        PinKeypad(
            value = pin,
            scramble = settings.scrambleKeypad,
            haptics = settings.hapticKeypad,
            inputEnabled = !isThrottled && !checking && !submitting,
            onAutoSubmit = { submittedPin ->
                if (!isThrottled && !checking && !submitting) {
                    submitting = true
                    scope.launch {
                        checking = true
                        try {
                            when (val result = authentication.unlockWithPin(submittedPin, System.currentTimeMillis())) {
                                is AuthenticationState.Unlocked -> Unit
                                is AuthenticationState.Throttled -> { pin = ""; error = null }
                                else -> { pin = ""; error = "Wrong PIN." }
                            }
                        } finally {
                            checking = false
                            submitting = false
                        }
                    }
                }
            },
            onBiometric = if (biometricAvailable) biometricUnlock else null,
            onChange = { pin = it },
        )
        if (checking) Text("Checking PIN…", color = GlanceMuted, modifier = Modifier.padding(top = 12.dp))
        (error ?: status)?.let { Text(it, color = if (isThrottled) GlanceMuted else GlanceWarning, modifier = Modifier.padding(top = 12.dp)) }
    }
}

@Composable
private fun DatabaseRecovery(authentication: AuthenticationCoordinator, eraseIncomplete: Boolean = false) {
    val scope = rememberCoroutineScope()
    var confirmation by remember { mutableStateOf(false) }
    PinPage(
        if (eraseIncomplete) "Erase incomplete" else "Wallet unavailable",
        if (eraseIncomplete) "Some local data could not be erased." else "This device can no longer open the encrypted wallet data.",
    ) {
        Text(if (eraseIncomplete) "Retry before setting up a new wallet." else "Restore from a backup after erasing local data.", color = GlanceMuted)
        Spacer(Modifier.height(20.dp))
        Button(onClick = { confirmation = true }, colors = ButtonDefaults.buttonColors(containerColor = GlanceWarning)) { Text(if (eraseIncomplete) "Retry erase" else "Erase all data") }
    }
    if (confirmation) AlertDialog(
        onDismissRequest = { confirmation = false },
        title = { Text("Erase all data?") },
        text = { Text("This permanently deletes encrypted wallet data and security settings from this device.") },
        confirmButton = { Button(onClick = { scope.launch { authentication.eraseAllData() } }, colors = ButtonDefaults.buttonColors(containerColor = GlanceWarning)) { Text("Erase permanently") } },
        dismissButton = { OutlinedButton(onClick = { confirmation = false }) { Text("Cancel") } },
    )
}

@Composable private fun PinPage(title: String, subtitle: String, content: @Composable () -> Unit) = PinEntryPage(title, subtitle, content)

@Composable internal fun PinUnlockPage(content: @Composable () -> Unit) = PinEntryPage("Glance", "Enter your PIN", content)

@Composable private fun PinEntryPage(title: String, subtitle: String, content: @Composable () -> Unit) = Box(
    Modifier.fillMaxSize().background(GlanceBackground),
    Alignment.Center,
) {
    Column(modifier = Modifier.width(280.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = GlanceText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = GlanceMuted, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(26.dp))
        content()
    }
}

@Composable internal fun PinKeypad(
    value: String,
    scramble: Boolean,
    haptics: Boolean,
    inputEnabled: Boolean = true,
    onConfirm: (() -> Unit)? = null,
    onAutoSubmit: ((String) -> Unit)? = null,
    onBiometric: (() -> Unit)? = null,
    onChange: (String) -> Unit,
) {
    val view = LocalView.current
    val unlockLayout = onAutoSubmit != null || onBiometric != null
    val keys = remember(scramble, unlockLayout) { keypadRows(scramble, unlockLayout) }
    PinDots(value.length)
    Spacer(Modifier.height(24.dp))
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.width(280.dp).testTag("pin_keypad"),
    ) {
        keys.forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { key ->
                if (key == "Enter") {
                    Button(
                        onClick = { onConfirm?.invoke() },
                        enabled = value.length == 6 && onConfirm != null && inputEnabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GlanceMandarin,
                            disabledContainerColor = GlanceMuted,
                        ),
                        modifier = Modifier.size(86.dp, 52.dp).testTag("setup_confirm"),
                    ) { Text("Enter") }
                } else if (key == "Biometric") {
                    if (onBiometric == null) Spacer(Modifier.size(86.dp, 52.dp)) else {
                        PinActionKey(onClick = onBiometric, enabled = inputEnabled, contentDescription = "Unlock with biometrics", contentColor = GlanceMandarin) {
                            Icon(Icons.Filled.Fingerprint, contentDescription = null)
                        }
                    }
                } else if (key == "⌫") {
                    PinActionKey(onClick = {
                        if (haptics) performPinHaptic(view, view.context)
                        onChange(value.dropLast(1))
                    }, enabled = inputEnabled, contentDescription = "Delete PIN digit") {
                        Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = null)
                    }
                } else PinNumberKey(
                    digit = key,
                    enabled = inputEnabled,
                    onClick = {
                        if (haptics) performPinHaptic(view, view.context)
                        if (value.length < 6) {
                            val nextValue = value + key
                            onChange(nextValue)
                            if (nextValue.length == 6) onAutoSubmit?.invoke(nextValue)
                        }
                    },
                )
            }
        } }
    }
}

@Composable private fun PinDots(enteredDigits: Int) = Row(
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    modifier = Modifier.semantics { contentDescription = "$enteredDigits of 6 PIN digits entered" }.testTag("pin_digits"),
) {
    repeat(6) { index ->
        Box(
            Modifier.size(11.dp).background(
                if (index < enteredDigits) GlanceMandarin else GlanceMuted.copy(alpha = 0.55f),
                CircleShape,
            ),
        )
    }
}

@Composable private fun PinNumberKey(digit: String, enabled: Boolean, onClick: () -> Unit) = Button(
    onClick = onClick,
    enabled = enabled,
    shape = MaterialTheme.shapes.medium,
    colors = ButtonDefaults.buttonColors(
        containerColor = GlanceSurface,
        contentColor = GlanceText,
        disabledContainerColor = GlanceSurface,
        disabledContentColor = GlanceMuted,
    ),
    modifier = Modifier.size(86.dp, 52.dp).semantics { contentDescription = "PIN digit $digit" },
) { Text(digit, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp)) }

@Composable private fun PinActionKey(onClick: () -> Unit, enabled: Boolean, contentDescription: String, contentColor: Color = GlanceText, content: @Composable () -> Unit) = Button(
    onClick = onClick,
    enabled = enabled,
    shape = MaterialTheme.shapes.medium,
    colors = ButtonDefaults.buttonColors(
        containerColor = GlanceSurface,
        contentColor = contentColor,
        disabledContainerColor = GlanceSurface,
        disabledContentColor = GlanceMuted.copy(alpha = 0.45f),
    ),
    modifier = Modifier.size(86.dp, 52.dp).semantics { this.contentDescription = contentDescription },
    content = { content() },
)

private fun performPinHaptic(view: android.view.View, context: Context) {
    if (view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)) return
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
    if (!vibrator.hasVibrator()) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(12L, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION") vibrator.vibrate(12L)
    }
}

internal fun keypadRows(scramble: Boolean, unlockLayout: Boolean = true, random: Random = Random.Default): List<List<String>> {
    val digits = (1..9).map(Int::toString).let { if (scramble) it.shuffled(random) else it }
    return listOf(
        digits.take(3),
        digits.drop(3).take(3),
        digits.drop(6),
        if (unlockLayout) listOf("Biometric", "0", "⌫") else listOf("⌫", "0", "Enter"),
    )
}

@Composable internal fun DecoyWalletContent(fakeBalanceSats: Long, authentication: AuthenticationCoordinator) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Glance", style = MaterialTheme.typography.headlineLarge)
        Text("Wallet balance", color = GlanceMuted)
        Text("$fakeBalanceSats sats", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("decoy_balance"))
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = authentication::lock, modifier = Modifier.fillMaxWidth()) { Text("Lock now") }
    }
}

@Composable internal fun DuressForensicLimitationNotice() {
    Text(
        "The separate encrypted decoy database may be detectable during forensic device inspection, even though its contents remain unreadable.",
        color = GlanceMuted,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable internal fun WalletContent(settings: SecurityPreferences, preferences: SecurityPreferencesStore, authentication: AuthenticationCoordinator, torController: TorController) {
    var eraseConfirmation by remember { mutableStateOf(false) }; var removeDuressConfirmation by remember { mutableStateOf(false) }; var torOffWarning by remember { mutableStateOf(false) }; var setupDuress by remember { mutableStateOf(false) }; var decoyBalance by remember { mutableStateOf("") }; val scope = rememberCoroutineScope(); val activity = LocalActivity.current
    LaunchedEffect(Unit) { decoyBalance = authentication.currentDecoyBalance()?.toString().orEmpty() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("wallet_settings_scroll").padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Glance", style = MaterialTheme.typography.headlineLarge); Text("Wallet unlocked", color = GlanceMuted); HorizontalDivider(); Text("Wallet", style = MaterialTheme.typography.titleMedium)
        SettingSwitch("Scramble PIN keypad", settings.scrambleKeypad) { scope.launch { preferences.update { old -> old.copy(scrambleKeypad = !old.scrambleKeypad) } } }
        SettingSwitch("Haptic PIN feedback", settings.hapticKeypad) { scope.launch { preferences.update { old -> old.copy(hapticKeypad = !old.hapticKeypad) } } }
        SettingSwitch("Biometric unlock", settings.biometricEnabled) { scope.launch { preferences.update { old -> old.copy(biometricEnabled = !old.biometricEnabled) } } }
        Text("Duress profile", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text("Set the static decoy balance shown after the duress PIN unlocks. This profile remains separate from your wallet.", color = GlanceMuted, style = MaterialTheme.typography.bodySmall)
        DuressForensicLimitationNotice()
        if (settings.credentials?.duressPinVerifier == null) {
            Text("Not activated", color = GlanceMuted, modifier = Modifier.testTag("duress_not_activated"))
            Button(onClick = { setupDuress = true }, colors = ButtonDefaults.buttonColors(containerColor = GlanceMandarin), modifier = Modifier.fillMaxWidth()) { Text("Set up duress profile") }
        } else {
            OutlinedTextField(value = decoyBalance, onValueChange = { decoyBalance = it.filter(Char::isDigit) }, label = { Text("Decoy balance (sats)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val decoySats = decoyBalance.toLongOrNull()
            Button(onClick = { decoySats?.let { sats -> scope.launch { authentication.configureDecoyBalance(sats) } } }, enabled = decoySats != null, colors = ButtonDefaults.buttonColors(containerColor = GlanceMandarin, disabledContainerColor = GlanceMuted), modifier = Modifier.fillMaxWidth().testTag("save_decoy_balance")) { Text("Save decoy balance") }
            OutlinedButton(onClick = { removeDuressConfirmation = true }, modifier = Modifier.fillMaxWidth().testTag("remove_duress_profile")) { Text("Remove duress profile") }
        }
        Text("App behavior", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        SettingSwitch("Use Tor", settings.torEnabled) { enabled ->
            if (enabled) scope.launch { preferences.update { it.copy(torEnabled = true) } } else torOffWarning = true
        }
        val torState by torController.state.collectAsState()
        TorStatusRow(if (settings.torEnabled && torState == TorState.Disabled) TorState.Starting else torState) {
            scope.launch { torController.retry() }
        }
        SettingSwitch("Block screenshots", settings.screenshotBlocking) { scope.launch { preferences.update { old -> old.copy(screenshotBlocking = !old.screenshotBlocking) } } }
        Spacer(Modifier.height(24.dp)); Button(onClick = { eraseConfirmation = true }, colors = ButtonDefaults.buttonColors(containerColor = GlanceWarning), modifier = Modifier.fillMaxWidth()) { Text("Erase all data") }; OutlinedButton(onClick = authentication::lock, modifier = Modifier.fillMaxWidth()) { Text("Lock now") }
    }
    if (eraseConfirmation) AlertDialog(onDismissRequest = { eraseConfirmation = false }, title = { Text("Erase all data?") }, text = { Text("This permanently deletes both encrypted wallet profiles, security settings, local cached data, and cached Tor state. This cannot be undone.") }, confirmButton = { Button(onClick = { scope.launch { authentication.eraseAllData() } }, colors = ButtonDefaults.buttonColors(containerColor = GlanceWarning)) { Text("Erase permanently") } }, dismissButton = { OutlinedButton(onClick = { eraseConfirmation = false }) { Text("Cancel") } })
    if (torOffWarning) AlertDialog(onDismissRequest = { torOffWarning = false }, title = { Text("Turn off Tor?") }, text = { Text("The querying server will see your device's real IP address. Continue only if you accept this privacy risk.") }, confirmButton = { Button(onClick = { torOffWarning = false; scope.launch { preferences.update { it.copy(torEnabled = false) } } }, colors = ButtonDefaults.buttonColors(containerColor = GlanceWarning)) { Text("Turn off Tor") } }, dismissButton = { OutlinedButton(onClick = { torOffWarning = false }) { Text("Keep Tor on") } })
    if (removeDuressConfirmation) AlertDialog(onDismissRequest = { removeDuressConfirmation = false }, title = { Text("Remove duress profile?") }, text = { Text("This permanently deletes the duress PIN, decoy database, and fake balance. Your real wallet remains unchanged.") }, confirmButton = { Button(onClick = { removeDuressConfirmation = false; scope.launch { authentication.removeDuressProfile(); decoyBalance = "" } }, colors = ButtonDefaults.buttonColors(containerColor = GlanceWarning)) { Text("Remove permanently") } }, dismissButton = { OutlinedButton(onClick = { removeDuressConfirmation = false }) { Text("Cancel") } })
    if (setupDuress) DuressSetupDialog(onDismiss = { setupDuress = false }) { pin, balance ->
        authentication.configureDuress(pin, balance)
        setupDuress = false
        decoyBalance = balance.toString()
    }
}

@Composable
private fun LegacyDuressSetupDialog(onDismiss: () -> Unit, onConfigured: suspend (String, Long) -> Unit) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var balance by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!creating) onDismiss() }, title = { Text("Set up duress profile") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Choose a new six-digit PIN and a static decoy balance.", color = GlanceMuted)
        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(6) }, label = { Text("Duress PIN") }, singleLine = true, enabled = !creating, modifier = Modifier.testTag("duress_pin"))
        OutlinedTextField(confirmation, { confirmation = it.filter(Char::isDigit).take(6) }, label = { Text("Confirm PIN") }, singleLine = true, enabled = !creating, modifier = Modifier.testTag("duress_pin_confirmation"))
        OutlinedTextField(balance, { balance = it.filter(Char::isDigit) }, label = { Text("Balance (sats)") }, singleLine = true, enabled = !creating, modifier = Modifier.testTag("duress_balance"))
        if (creating) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = GlanceMandarin, strokeWidth = 2.dp)
            Text("Creating duress profile…", color = GlanceMuted)
        }
        error?.let { Text(it, color = GlanceWarning) }
    } }, confirmButton = { Button(onClick = {
        val sats = balance.toLongOrNull()
        when {
            pin.length != 6 || confirmation != pin -> error = "PINs must match and contain six digits."
            sats == null -> error = "Enter a valid balance."
            else -> {
                creating = true
                error = null
                scope.launch {
                    try {
                        onConfigured(pin, sats)
                    } catch (failure: Throwable) {
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        error = "Unable to create the duress profile. Try again."
                    } finally {
                        creating = false
                    }
                }
            }
        }
    }, enabled = !creating && pin.length == 6 && confirmation.length == 6 && balance.isNotEmpty(), modifier = Modifier.testTag("duress_create_profile")) { Text("Create profile") } }, dismissButton = { OutlinedButton(onClick = onDismiss, enabled = !creating) { Text("Cancel") } })
}

@Composable
internal fun DuressSetupDialog(onDismiss: () -> Unit, onConfigured: suspend (String, Long) -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(0) }
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var balance by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    when (step) {
        0 -> PinEntryPage("Set up duress PIN", "Choose a separate six-digit PIN.") {
            PinKeypad(pin, false, true, onAutoSubmit = { error = null; step = 1 }) { pin = it }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("Cancel") }
        }
        1 -> PinEntryPage("Confirm duress PIN", "Enter the same six digits again.") {
            PinKeypad(confirmation, false, true, onAutoSubmit = { submittedPin ->
                if (submittedPin == pin) { error = null; step = 2 } else { confirmation = ""; error = "PINs do not match." }
            }) { confirmation = it }
            error?.let { Text(it, color = GlanceWarning, modifier = Modifier.padding(top = 12.dp)) }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("Cancel") }
        }
        else -> PinEntryPage("Duress balance", "Set the static decoy balance in sats.") {
            OutlinedTextField(
                value = balance,
                onValueChange = { balance = it.filter(Char::isDigit) },
                label = { Text("Balance (sats)") },
                singleLine = true,
                enabled = !creating,
                modifier = Modifier.fillMaxWidth().testTag("duress_balance"),
            )
            Button(
                onClick = {
                    val sats = balance.toLongOrNull()
                    if (sats == null) error = "Enter a valid balance." else {
                        creating = true
                        error = null
                        scope.launch {
                            try { onConfigured(pin, sats) } catch (failure: Throwable) {
                                if (failure is kotlinx.coroutines.CancellationException) throw failure
                                error = "Unable to create the duress profile. Try again."
                            } finally { creating = false }
                        }
                    }
                },
                enabled = !creating && balance.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = GlanceMandarin),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag("duress_create_profile"),
            ) { Text("Create profile") }
            if (creating) Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = GlanceMandarin, strokeWidth = 2.dp)
                Text("Creating duress profile…", color = GlanceMuted)
            }
            error?.let { Text(it, color = GlanceWarning, modifier = Modifier.padding(top = 12.dp)) }
            OutlinedButton(onClick = onDismiss, enabled = !creating, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("Cancel") }
        }
    }
}

@Composable private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(checked, onCheckedChange) }

@Composable internal fun TorStatusRow(state: TorState, onRetry: () -> Unit) {
    val (label, color, tag, retry) = when (state) {
        TorState.Disabled -> Quad("Tor off", GlanceMuted, "tor_status_off", false)
        TorState.Starting -> Quad("Connecting to Tor", GlanceMandarin, "tor_status_connecting", false)
        is TorState.Ready -> Quad("Tor connected", GlanceMandarin, "tor_status_connected", false)
        is TorState.Unavailable -> Quad("Tor unavailable", GlanceWarning, "tor_status_unavailable", true)
    }
    Row(Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = label }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(12.dp).background(color, CircleShape))
        Text(label, color = color, modifier = Modifier.weight(1f))
        if (retry) OutlinedButton(onClick = onRetry, modifier = Modifier.testTag("tor_retry")) { Text("Retry") }
    }
}

private data class Quad(val label: String, val color: Color, val tag: String, val retry: Boolean)

@Composable fun GlanceTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = darkColorScheme(background = GlanceBackground, surface = GlanceSurface, onBackground = GlanceText, onSurface = GlanceText, primary = GlanceMandarin), content = content)
internal val GlanceBackground = Color(0xFF0B0B0B); internal val GlanceSurface = Color(0xFF1C1C1F); internal val GlanceText = Color(0xFFF6F4EF); internal val GlanceMuted = Color(0xFF6E6B66); internal val GlanceMandarin = Color(0xFFE16D3E); internal val GlanceWarning = Color(0xFFD9534F)
/** Status dots only; connection state is always also named in text. */
internal val TorConnectedDot = Color(0xFF5CB85C)
internal val TorConnectingDot = Color(0xFFF2C94C)
internal val TorUnavailableDot = GlanceWarning
