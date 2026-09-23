package app.glance.wallet

import android.content.Context
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.glance.wallet.core.data.db.WatchedKeyEntity
import app.glance.wallet.core.data.db.DecoyProfileEntity
import app.glance.wallet.core.data.db.ScriptType
import app.glance.wallet.core.security.ProfileDatabaseManager
import app.glance.wallet.core.security.ProfileType
import app.glance.wallet.core.security.SecurityPreferencesStore
import app.glance.wallet.core.security.ExplorerPreset
import app.glance.wallet.core.security.UtxoView
import app.glance.wallet.core.security.AndroidKeystoreDatabaseKeyProvider
import app.glance.wallet.core.security.DatabaseKeyRecoveryRequiredException
import app.glance.wallet.core.security.AuthenticationCoordinator
import app.glance.wallet.core.security.AuthenticationState
import app.glance.wallet.core.security.FileEraseStateStore
import app.glance.wallet.core.security.SecurityPreferences
import app.glance.wallet.core.security.TorStateCleaner
import org.junit.Assert.assertNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SecurityOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun explorerPreferenceDefaultsToMempoolAndPersistsBlockstream() = runBlocking {
        val store = SecurityPreferencesStore.forTesting(context, "explorer-preference")
        assertEquals(ExplorerPreset.MEMPOOL_SPACE, store.data.first().explorerPreset)
        store.update { it.copy(explorerPreset = ExplorerPreset.BLOCKSTREAM) }
        assertEquals(ExplorerPreset.BLOCKSTREAM, store.data.first().explorerPreset)
        store.wipe()
    }

    @Test
    fun latestUtxoViewDefaultStartsAsBubblesAndPersistsList() = runBlocking {
        val store = SecurityPreferencesStore.forTesting(context, "utxo-view-preference")
        assertEquals(UtxoView.BUBBLES, store.data.first().utxoView)
        store.update { it.copy(utxoView = UtxoView.LIST) }
        assertEquals(UtxoView.LIST, store.data.first().utxoView)
        store.wipe()
    }
    private val profiles = ProfileDatabaseManager(context)
    private var preferences: SecurityPreferencesStore? = null

    @After
    fun tearDown() {
        runBlocking {
            profiles.deleteAll()
            preferences?.wipe()
        }
    }

    @Test
    fun torRoutingIsEnabledByDefaultAndPersistsWhenChanged() = runBlocking {
        val store = testPreferences("tor-routing")
        store.wipe()

        assertTrue(store.data.first().torEnabled)
        assertFalse(store.data.first().offlineMode)
        store.update { it.copy(torEnabled = false) }

        assertFalse(store.data.first().torEnabled)
    }

    @Test
    fun realAndDecoyProfilesAreSeparateEncryptedDatabases() = runBlocking {
        profiles.deleteAll()
        val real = profiles.open(ProfileType.REAL).database
        try {
            real.watchedKeyDao().upsert(WatchedKeyEntity("real", "Real", "redacted", ScriptType.NATIVE_SEGWIT, 0L))
        } finally {
            real.close()
        }

        val decoy = profiles.open(ProfileType.DECOY).database
        try {
            decoy.decoyProfileDao().upsert(DecoyProfileEntity(ProfileDatabaseManager.DECOY_PROFILE_ID, "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", "redacted-zpub"))
            assertEquals(null, decoy.watchedKeyDao().findById("real"))
            assertEquals("redacted-zpub", decoy.decoyProfileDao().findById(ProfileDatabaseManager.DECOY_PROFILE_ID)?.accountExtendedPublicKey)
        } finally {
            decoy.close()
        }
    }

    @Test
    fun onlyTheRealProfileCanConfigureTheStaticDecoyBalance() = runBlocking {
        val store = testPreferences("decoy-balance-access")
        store.wipe()
        profiles.deleteAll()
        val authentication = AuthenticationCoordinator(store, profiles)
        authentication.initialize()

        authentication.configure(realPin = "123456")
        authentication.configureDuress("654321", 50_000L)
        authentication.lock()
        authentication.unlockWithPin("654321", nowMillis = 0L)

        val failure = runCatching { authentication.configureDecoyBalance(100_000L) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure is SecurityException)
        val decoy = profiles.open(ProfileType.DECOY)
        try {
            assertNotNull(decoy.database.decoyProfileDao().findById(ProfileDatabaseManager.DECOY_PROFILE_ID)?.mnemonic)
        } finally {
            decoy.database.close()
            authentication.lock()
        }
    }

    @Test
    fun savingDecoyBalanceTwiceReplacesTheSyntheticSnapshotAtomically() = runBlocking {
        val store = testPreferences("decoy-balance-repeat")
        store.wipe()
        profiles.deleteAll()
        val authentication = AuthenticationCoordinator(store, profiles)
        authentication.initialize()
        authentication.configure(realPin = "123456")
        authentication.configureDuress("654321", 50_000L)
        authentication.configureDecoyBalance(125_000L)

        val decoy = profiles.open(ProfileType.DECOY).database
        try {
            val key = requireNotNull(decoy.watchedKeyDao().findById("synthetic-decoy-wallet"))
            val addresses = decoy.derivedAddressDao().forKey(key.id).first()
            assertEquals(1, addresses.size)
            assertNotNull(decoy.decoyProfileDao().findById(ProfileDatabaseManager.DECOY_PROFILE_ID)?.mnemonic)
            assertEquals(125_000L, decoy.utxoDao().forAddress(addresses.single().id).first().single().valueSats)
            assertEquals(125_000L, decoy.addressHistoryDao().forAddress(addresses.single().id).first().single().valueSats)
        } finally {
            decoy.close()
            authentication.lock()
        }
    }

    @Test
    fun switchingToTheDuressProfileClosesTheRealDatabaseSession() = runBlocking {
        val startedAtMillis = SystemClock.elapsedRealtime()
        val store = testPreferences("profile-session-switch")
        store.wipe()
        profiles.deleteAll()
        val authentication = AuthenticationCoordinator(store, profiles)
        authentication.initialize()

        authentication.configure(realPin = "123456")
        authentication.configureDuress("654321", 0L)
        val realDatabase = (authentication.state.value as AuthenticationState.Unlocked).session.database

        authentication.unlockWithPin("654321", nowMillis = 0L)

        assertFalse("The real database must close before the decoy profile opens.", realDatabase.isOpen)
        authentication.lock()
        assertTrue(
            "PIN setup and one duress unlock must not stall the instrumentation runner.",
            SystemClock.elapsedRealtime() - startedAtMillis < 30_000L,
        )
    }

    @Test
    fun lockingInvalidatesAnInFlightPinUnlock() = runBlocking {
        val store = testPreferences("in-flight-unlock-lock")
        store.wipe()
        profiles.deleteAll()
        val verificationStarted = CountDownLatch(1)
        val releaseVerification = CountDownLatch(1)
        val authenticator = app.glance.wallet.core.security.PinAuthenticator(
            object : app.glance.wallet.core.security.PinHasher() {
                override fun matches(pin: String, verifier: app.glance.wallet.core.security.PinVerifier): Boolean {
                    verificationStarted.countDown()
                    check(releaseVerification.await(10, TimeUnit.SECONDS)) { "PIN verification did not resume." }
                    return super.matches(pin, verifier)
                }
            },
        )
        val authentication = AuthenticationCoordinator(store, profiles, authenticator)
        authentication.initialize()
        authentication.configure("123456")
        authentication.lock()

        try {
            val pendingUnlock = async(Dispatchers.Default) {
                authentication.unlockWithPin("123456", nowMillis = 0L)
            }
            assertTrue("PIN verification never began.", verificationStarted.await(10, TimeUnit.SECONDS))

            authentication.lock()
            releaseVerification.countDown()

            assertEquals(AuthenticationState.Locked, pendingUnlock.await())
            assertEquals(AuthenticationState.Locked, authentication.state.value)
        } finally {
            releaseVerification.countDown()
            authentication.lock()
        }
    }

    @Test
    fun removingDuressPreservesRealProfileAndAllowsFreshSetup() = runBlocking {
        val store = testPreferences("duress-lifecycle")
        store.wipe()
        profiles.deleteAll()
        val authentication = AuthenticationCoordinator(store, profiles)
        authentication.initialize()

        authentication.configure("123456")
        authentication.configureDuress("654321", 50_000L)
        authentication.removeDuressProfile()

        assertTrue(store.data.first().credentials?.duressPinVerifier == null)
        assertTrue(store.data.first().credentials?.realPinVerifier != null)
        assertEquals(AuthenticationState.Unlocked::class, authentication.state.value::class)

        authentication.lock()
        assertTrue(authentication.unlockWithPin("123456", 0L) is AuthenticationState.Unlocked)
        authentication.removeDuressProfile()

        authentication.configureDuress("111222", 75_000L)
        authentication.lock()
        assertTrue(authentication.unlockWithPin("111222", 0L) is AuthenticationState.Unlocked)
        assertNotNull((authentication.state.value as AuthenticationState.Unlocked).session.database.decoyProfileDao().findById(ProfileDatabaseManager.DECOY_PROFILE_ID)?.mnemonic)
        authentication.lock()
        assertTrue(authentication.unlockWithPin("654321", 0L) is AuthenticationState.Throttled)
    }

    @Test
    fun decoyRemovalIsRejectedOutsideTheRealProfile() {
        runBlocking {
            val store = testPreferences("duress-removal-access")
            store.wipe()
            profiles.deleteAll()
            val authentication = AuthenticationCoordinator(store, profiles)
            authentication.initialize()
            authentication.configure("123456")
            authentication.configureDuress("654321", 0L)
            authentication.lock()
            assertThrows(SecurityException::class.java) { runBlocking { authentication.removeDuressProfile() } }
            authentication.unlockWithPin("654321", 0L)
            assertThrows(SecurityException::class.java) { runBlocking { authentication.removeDuressProfile() } }
        }
    }

    @Test
    fun credentialsCannotBeReconfiguredBeforeInitializationCompletes(): Unit = runBlocking {
        val store = testPreferences("initialization-gate")
        store.wipe()
        val existingCredentials = app.glance.wallet.core.security.PinAuthenticator().configure("123456")
        store.update {
            it.copy(credentials = existingCredentials)
        }
        val authentication = AuthenticationCoordinator(store, profiles)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { authentication.configure("654321") }
        }
    }

    @Test
    fun encryptedPreferencesPersistSecuritySettings() = runBlocking {
        val namespace = "preferences-persist"
        val store = testPreferences(namespace)
        store.wipe()
        store.update { it.copy(scrambleKeypad = true, screenshotBlocking = false, torEnabled = false, offlineMode = true) }
        val restored = SecurityPreferencesStore.forTesting(context, namespace).data.first()
        assertTrue(restored.scrambleKeypad)
        assertEquals(false, restored.screenshotBlocking)
        assertFalse(restored.torEnabled)
        assertTrue(restored.offlineMode)
    }

    @Test
    fun wipeReplacesTheActiveStoreAndLeavesItUsable() = runBlocking {
        val store = testPreferences("preferences-wipe")
        store.update { it.copy(scrambleKeypad = true) }

        store.wipe()

        assertEquals(false, store.data.first().scrambleKeypad)
        store.update { it.copy(screenshotBlocking = false) }
        assertEquals(false, store.data.first().screenshotBlocking)
    }

    @Test
    fun eraseAllDataDeletesEverySecurityStoreAndReturnsToSetup() = runBlocking {
        val namespace = "erase-all"
        val store = testPreferences(namespace)
        store.wipe()
        profiles.deleteAll()
        var torStateCleared = false
        val authentication = AuthenticationCoordinator(store, profiles, torStateCleaner = TorStateCleaner { torStateCleared = true })
        authentication.initialize()
        authentication.configure("123456")
        authentication.configureDuress("654321", 50_000L)

        assertTrue(databaseKeyFile(ProfileDatabaseManager.REAL_DATABASE).isFile)
        assertTrue(databaseKeyFile(ProfileDatabaseManager.DECOY_DATABASE).isFile)
        assertTrue(keystoreContains("app.glance.wallet.db.glance-wallet"))
        assertTrue(keystoreContains("app.glance.wallet.db.glance-decoy"))
        assertTrue(keystoreContains("app.glance.wallet.security.preferences.test.$namespace"))

        authentication.eraseAllData()

        assertTrue(torStateCleared)
        assertEquals(AuthenticationState.SetupRequired, authentication.state.value)
        assertFalse(databaseKeyFile(ProfileDatabaseManager.REAL_DATABASE).exists())
        assertFalse(databaseKeyFile(ProfileDatabaseManager.DECOY_DATABASE).exists())
        assertFalse(context.getDatabasePath(ProfileDatabaseManager.REAL_DATABASE).exists())
        assertFalse(context.getDatabasePath(ProfileDatabaseManager.DECOY_DATABASE).exists())
        assertFalse(keystoreContains("app.glance.wallet.db.glance-wallet"))
        assertFalse(keystoreContains("app.glance.wallet.db.glance-decoy"))
        // wipe() removes the old Tink keyset and alias, then eagerly creates a fresh empty store.
        assertTrue(keystoreContains("app.glance.wallet.security.preferences.test.$namespace"))
        assertEquals(null, store.data.first().credentials)
        assertTrue(authentication.unlockWithPin("123456", 0L) is AuthenticationState.SetupRequired)

        authentication.initialize()
        assertEquals(AuthenticationState.SetupRequired, authentication.state.value)
        authentication.configure("111222")
        assertTrue(authentication.state.value is AuthenticationState.Unlocked)
        authentication.lock()
    }

    @Test
    fun incompleteEraseRemainsBlockedAfterCoordinatorRecreationUntilRetrySucceeds() = runBlocking {
        val namespace = "erase-retry-persistence"
        val markerFile = File(context.noBackupFilesDir, "$namespace.marker")
        val marker = FileEraseStateStore(markerFile)
        marker.clear()
        val store = testPreferences(namespace)
        store.wipe()
        profiles.deleteAll()
        var failTorCleanup = true

        try {
            val first = AuthenticationCoordinator(
                store,
                profiles,
                torStateCleaner = TorStateCleaner {
                    if (failTorCleanup) error("Tor cleanup failed")
                },
                eraseStateStore = marker,
            )
            first.initialize()
            first.eraseAllData()

            assertEquals(AuthenticationState.EraseIncomplete, first.state.value)
            assertTrue(marker.isPending())

            val recreated = AuthenticationCoordinator(
                store,
                profiles,
                torStateCleaner = TorStateCleaner { },
                eraseStateStore = FileEraseStateStore(markerFile),
            )
            recreated.initialize()
            assertEquals(AuthenticationState.EraseIncomplete, recreated.state.value)

            failTorCleanup = false
            recreated.eraseAllData()
            assertEquals(AuthenticationState.SetupRequired, recreated.state.value)
            assertFalse(marker.isPending())
        } finally {
            marker.clear()
        }
    }

    @Test
    fun databaseKeyIsKeystoreWrappedAndNeverWrittenAsPlaintext() {
        val provider = AndroidKeystoreDatabaseKeyProvider(context, "instrumented-key")
        provider.delete()
        val key = provider.getOrCreate()
        val wrapped = java.io.File(context.noBackupFilesDir, "instrumented-key.key").readBytes()
        assertTrue(wrapped.isNotEmpty())
        assertTrue(!wrapped.contentEquals(key))
        assertTrue(provider.getOrCreate().contentEquals(key))
        provider.delete()
    }

    @Test
    fun missingKeystoreAliasForExistingWrapperFailsWithoutReplacement() {
        val storageName = "missing-alias-key"
        val provider = AndroidKeystoreDatabaseKeyProvider(context, storageName)
        provider.delete()
        provider.getOrCreate()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("app.glance.wallet.db.$storageName") }

        val failure = runCatching { provider.getOrCreate() }.exceptionOrNull()

        assertTrue(failure is DatabaseKeyRecoveryRequiredException)
        assertFalse(keystoreContains("app.glance.wallet.db.$storageName"))
        provider.delete()
    }

    @Test
    fun activityBlocksScreenshotsByDefault() {
        testPreferences("preferences-activity").also { store ->
            runBlocking { store.update { it.copy(scrambleKeypad = true) } }
        }
        val activity = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        activity.onActivity { host ->
            assertTrue(host.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
        activity.close()
    }

    private fun testPreferences(namespace: String): SecurityPreferencesStore =
        SecurityPreferencesStore.forTesting(context, namespace).also { preferences = it }

    private fun databaseKeyFile(databaseName: String) =
        java.io.File(context.noBackupFilesDir, "${databaseName.removeSuffix(".db")}.key")

    private fun keystoreContains(alias: String): Boolean =
        KeyStore.getInstance("AndroidKeyStore").run {
            load(null)
            containsAlias(alias)
        }
}
