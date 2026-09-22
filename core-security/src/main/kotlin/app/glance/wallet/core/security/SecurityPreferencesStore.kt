package app.glance.wallet.core.security

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import androidx.datastore.tink.AeadSerializer
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplate
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ExplorerPreset { MEMPOOL_SPACE, BLOCKSTREAM }

enum class UtxoView { BUBBLES, LIST }

data class SecurityPreferences(
    val credentials: PinCredentials? = null,
    val scrambleKeypad: Boolean = false,
    val hapticKeypad: Boolean = true,
    val biometricEnabled: Boolean = false,
    val screenshotBlocking: Boolean = true,
    val torEnabled: Boolean = true,
    val offlineMode: Boolean = false,
    val showBalanceChart: Boolean = true,
    val utxoView: UtxoView = UtxoView.BUBBLES,
    val fiatCurrency: String = "USD",
    val explorerPreset: ExplorerPreset = ExplorerPreset.MEMPOOL_SPACE,
    /** True only while an old serialized CoinGecko key still needs to be overwritten. */
    internal val legacyCoinGeckoKeyPresent: Boolean = false,
    val retryState: RetryState = RetryState(),
    val duressRemovalPending: Boolean = false,
)

/** Encrypted DataStore for security settings; SQLCipher keys never enter this store. */
class SecurityPreferencesStore private constructor(
    private val context: Context,
    private val identity: StorageIdentity,
) {
    private val sharedStore = acquire(context.applicationContext, identity)

    @OptIn(ExperimentalCoroutinesApi::class)
    val data: Flow<SecurityPreferences> = sharedStore.holder.flatMapLatest { it.dataStore.data }

    suspend fun update(transform: (SecurityPreferences) -> SecurityPreferences): SecurityPreferences =
        sharedStore.mutex.withLock { sharedStore.holder.value.dataStore.updateData(transform) }

    suspend fun clear() {
        update { SecurityPreferences() }
    }

    /** Rewrites an old encrypted payload without the retired user-supplied CoinGecko key. */
    suspend fun removeLegacyCoinGeckoKey() {
        if (data.first().legacyCoinGeckoKeyPresent) update { it.copy(legacyCoinGeckoKeyPresent = false) }
    }

    /** Removes encrypted preference payload, Tink keyset, and its Keystore wrapping key. */
    suspend fun wipe() {
        sharedStore.mutex.withLock {
            sharedStore.holder.value.scope.coroutineContext[Job]?.cancelAndJoin()
            context.applicationContext.dataStoreFile(identity.fileName).delete()
            context.applicationContext
                .getSharedPreferences(identity.keysetPreferenceFile, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(identity.masterKeyAlias)
            }
            sharedStore.holder.value = createHolder(context.applicationContext, identity)
        }
    }

    companion object {
        private val productionIdentity = StorageIdentity(
            fileName = "glance-security.preferences",
            keysetName = "glance-security-keyset",
            keysetPreferenceFile = "glance-security-keyset-prefs",
            masterKeyAlias = "app.glance.wallet.security.preferences",
        )
        private val activeStores = mutableMapOf<StorageIdentity, SharedStore>()

        @Synchronized
        private fun acquire(context: Context, identity: StorageIdentity): SharedStore =
            activeStores.getOrPut(identity) { SharedStore(context, identity) }

        private fun createHolder(context: Context, identity: StorageIdentity): StoreHolder = StoreHolder(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ).also { holder ->
            holder.dataStore = DataStoreFactory.create(
                serializer = AeadSerializer(aead(context, identity), SecurityPreferencesSerializer, identity.fileName.encodeToByteArray()),
                scope = holder.scope,
                produceFile = { context.dataStoreFile(identity.fileName) },
            )
        }

        private fun aead(context: Context, identity: StorageIdentity): Aead {
            // AndroidKeysetManager creates the first keyset eagerly, so register before it builds.
            AeadConfig.register()
            val handle = AndroidKeysetManager.Builder()
                .withSharedPref(context.applicationContext, identity.keysetName, identity.keysetPreferenceFile)
                .withKeyTemplate(KeyTemplate.createFrom(PredefinedAeadParameters.AES256_GCM))
                .withMasterKeyUri("android-keystore://${identity.masterKeyAlias}")
                .build()
                .keysetHandle
            return handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        }

        fun create(context: Context): SecurityPreferencesStore =
            SecurityPreferencesStore(context.applicationContext, productionIdentity)

        /** Creates an isolated encrypted store for instrumentation tests only. */
        fun forTesting(context: Context, namespace: String): SecurityPreferencesStore {
            require(namespace.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid test storage namespace." }
            return SecurityPreferencesStore(
                context.applicationContext,
                StorageIdentity(
                    fileName = "glance-security-test-$namespace.preferences",
                    keysetName = "glance-security-test-$namespace-keyset",
                    keysetPreferenceFile = "glance-security-test-$namespace-keyset-prefs",
                    masterKeyAlias = "app.glance.wallet.security.preferences.test.$namespace",
                ),
            )
        }
    }

    private data class StorageIdentity(
        val fileName: String,
        val keysetName: String,
        val keysetPreferenceFile: String,
        val masterKeyAlias: String,
    )

    private class SharedStore(context: Context, identity: StorageIdentity) {
        val mutex = Mutex()
        val holder = MutableStateFlow(createHolder(context, identity))
    }

    private class StoreHolder(val scope: CoroutineScope) {
        lateinit var dataStore: DataStore<SecurityPreferences>
    }
}

private object SecurityPreferencesSerializer : Serializer<SecurityPreferences> {
    override val defaultValue = SecurityPreferences()

    override suspend fun readFrom(input: InputStream): SecurityPreferences {
        val fields = input.readBytes().decodeToString().lineSequence()
            .mapNotNull { line -> line.substringBefore('=', "").takeIf { '=' in line }?.let { it to line.substringAfter('=') } }
            .toMap()
        val real = fields["real"]?.let { PinVerifier(Base64.decode(it, Base64.NO_WRAP)) }
        val decoy = fields["decoy"]?.takeIf(String::isNotEmpty)?.let { PinVerifier(Base64.decode(it, Base64.NO_WRAP)) }
        return SecurityPreferences(
            credentials = real?.let { PinCredentials(it, decoy) },
            scrambleKeypad = fields["scramble"].toBoolean(),
            hapticKeypad = fields["haptic"]?.toBoolean() ?: true,
            biometricEnabled = fields["biometric"].toBoolean(),
            screenshotBlocking = fields["screenshots"]?.toBoolean() ?: true,
            torEnabled = fields["tor"]?.toBoolean() ?: true,
            offlineMode = fields["offline"].toBoolean(),
            showBalanceChart = fields["showChart"]?.toBoolean() ?: true,
            utxoView = fields["utxoView"]
                ?.let { runCatching { UtxoView.valueOf(it) }.getOrNull() }
                ?: UtxoView.BUBBLES,
            fiatCurrency = fields["fiatCurrency"]?.uppercase()?.takeIf { it in FIAT_CURRENCIES } ?: "USD",
            explorerPreset = fields["explorerPreset"]
                ?.let { runCatching { ExplorerPreset.valueOf(it) }.getOrNull() }
                ?: ExplorerPreset.MEMPOOL_SPACE,
            legacyCoinGeckoKeyPresent = fields.containsKey("coinGeckoDemoApiKey"),
            retryState = RetryState(fields["failures"]?.toIntOrNull() ?: 0, fields["next"]?.toLongOrNull() ?: 0L),
            duressRemovalPending = fields["duressRemovalPending"].toBoolean(),
        )
    }

    override suspend fun writeTo(t: SecurityPreferences, output: OutputStream) {
        val credentials = t.credentials
        output.write(buildString {
            appendLine("real=${credentials?.realPinVerifier?.encoded?.let { Base64.encodeToString(it, Base64.NO_WRAP) }.orEmpty()}")
            appendLine("decoy=${credentials?.duressPinVerifier?.encoded?.let { Base64.encodeToString(it, Base64.NO_WRAP) }.orEmpty()}")
            appendLine("scramble=${t.scrambleKeypad}")
            appendLine("haptic=${t.hapticKeypad}")
            appendLine("biometric=${t.biometricEnabled}")
            appendLine("screenshots=${t.screenshotBlocking}")
            appendLine("tor=${t.torEnabled}")
            appendLine("offline=${t.offlineMode}")
            appendLine("showChart=${t.showBalanceChart}")
            appendLine("utxoView=${t.utxoView.name}")
            appendLine("fiatCurrency=${t.fiatCurrency}")
            appendLine("explorerPreset=${t.explorerPreset.name}")
            appendLine("failures=${t.retryState.failedAttempts}")
            appendLine("next=${t.retryState.nextAllowedAtMillis}")
            appendLine("duressRemovalPending=${t.duressRemovalPending}")
        }.encodeToByteArray())
    }
}

val FIAT_CURRENCIES = setOf("USD", "EUR", "GBP", "CAD", "CHF", "AUD", "JPY")
