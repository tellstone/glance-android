package app.glance.wallet

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.glance.wallet.core.crypto.ScriptType as CryptoScriptType
import app.glance.wallet.core.crypto.parseWatchedKey
import app.glance.wallet.core.data.db.GlanceDatabase
import app.glance.wallet.core.data.db.ScriptType as DataScriptType
import app.glance.wallet.core.security.UtxoView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddWatchTargetPersistenceTest {
    @Test
    fun reimportingTheSameFormatsDoesNotCreateDuplicates() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            GlanceDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            persistWatchTargets(database, "hd", XPUB, listOf(DataScriptType.LEGACY, DataScriptType.NATIVE_SEGWIT))
            persistWatchTargets(database, "again", XPUB, listOf(DataScriptType.LEGACY, DataScriptType.NATIVE_SEGWIT))

            val keys = database.watchedKeyDao().observeAll().first()
            assertEquals(2, keys.size)
            assertEquals(
                setOf(DataScriptType.LEGACY, DataScriptType.NATIVE_SEGWIT),
                keys.map { it.scriptType }.toSet(),
            )
            assertFalse(keys.any { it.label == "again" })
        } finally {
            database.close()
        }
    }

    @Test
    fun aGroupedWalletCannotGainAnotherFormatAfterImport() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            GlanceDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            persistWatchTargets(database, "Savings", XPUB, listOf(DataScriptType.LEGACY, DataScriptType.NATIVE_SEGWIT))

            val failure = runCatching {
                persistWatchTargets(database, "Savings", XPUB, listOf(DataScriptType.SEGWIT_COMPAT))
            }.exceptionOrNull()

            assertTrue(failure?.message?.contains("formats can only be chosen during import") == true)
            assertEquals(2, database.watchedKeyDao().observeAll().first().size)
        } finally {
            database.close()
        }
    }

    @Test
    fun importedWalletsUseTheLatestChosenUtxoViewIndividually() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            GlanceDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            persistWatchTargets(database, "hd", XPUB, listOf(DataScriptType.LEGACY, DataScriptType.NATIVE_SEGWIT), UtxoView.LIST)

            val keys = database.watchedKeyDao().observeAll().first()
            assertTrue(keys.all { it.utxoView == UtxoView.LIST.name })
            database.watchedKeyDao().setUtxoView(keys.first().id, UtxoView.BUBBLES.name)
            assertEquals(UtxoView.BUBBLES.name, database.watchedKeyDao().findById(keys.first().id)?.utxoView)
            assertEquals(UtxoView.LIST.name, database.watchedKeyDao().findById(keys.last().id)?.utxoView)
        } finally {
            database.close()
        }
    }

    @Test
    fun importingMultipleDiscoveredFormatsCreatesOneSharedWalletGroup() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            GlanceDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            persistWatchTargets(
                database,
                "Savings",
                XPUB,
                listOf(DataScriptType.LEGACY, DataScriptType.NATIVE_SEGWIT),
            )

            val groups = database.walletGroupDao().observeAll().first()
            val keys = database.watchedKeyDao().observeAll().first()
            assertEquals(1, groups.size)
            assertEquals("Savings", groups.single().label)
            assertEquals(DataScriptType.NATIVE_SEGWIT, groups.single().preferredReceiveScriptType)
            assertEquals(setOf(groups.single().id), keys.mapNotNull { it.walletGroupId }.toSet())
        } finally {
            database.close()
        }
    }

    @Test
    fun groupPreferencesAndFormatRemovalDoNotAffectRemainingFormats() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            GlanceDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            persistWatchTargets(database, "Savings", XPUB, listOf(DataScriptType.LEGACY, DataScriptType.NATIVE_SEGWIT))
            val group = database.walletGroupDao().observeAll().first().single()
            val keys = database.watchedKeyDao().forWalletGroup(group.id)

            database.walletGroupDao().setPreferredReceiveScriptType(group.id, DataScriptType.LEGACY)
            database.watchedKeyDao().deleteWithOwnedData(keys.first { it.scriptType == DataScriptType.NATIVE_SEGWIT }.id)

            assertEquals(DataScriptType.LEGACY, database.walletGroupDao().observeAll().first().single().preferredReceiveScriptType)
            assertEquals(listOf(DataScriptType.LEGACY), database.watchedKeyDao().forWalletGroup(group.id).map { it.scriptType })
        } finally {
            database.close()
        }
    }

    @Test
    fun importingAnHdKeyRejectsAnAddressAlreadyTrackedInItsInitialWindow() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            GlanceDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val existingAddress = parseWatchedKey(XPUB, CryptoScriptType.NATIVE_SEGWIT).derive(0, 0).address
            persistWatchTarget(database, "fixed", existingAddress, null)

            val failure = runCatching {
                persistWatchTarget(database, "hd", XPUB, DataScriptType.NATIVE_SEGWIT)
            }.exceptionOrNull()

            assertTrue(failure?.message?.contains("already being tracked") == true)
            assertEquals(1, database.watchedKeyDao().observeAll().first().size)
        } finally {
            database.close()
        }
    }

    private companion object {
        const val XPUB = "xpub6CUGRUonZSQ4TWtTMmzXdrXDtypWKiKrhko4egpiMZbpiaQL2jkwSB1icqYh2cfDfVxdx4df189oLKnC5fSwqPfgyP3hooxujYzAu3fDVmz"
    }
}
