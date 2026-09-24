package app.glance.wallet

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.glance.wallet.core.data.db.AddressChain
import app.glance.wallet.core.data.db.AddressHistoryEntity
import app.glance.wallet.core.data.db.BlockTimestampCacheEntity
import app.glance.wallet.core.data.db.DerivedAddressEntity
import app.glance.wallet.core.data.db.DecoyProfileEntity
import app.glance.wallet.core.data.db.FiatPriceCacheEntity
import app.glance.wallet.core.data.db.GlanceDatabase
import app.glance.wallet.core.data.db.LabelEntity
import app.glance.wallet.core.data.db.LabelReferenceType
import app.glance.wallet.core.data.db.ScriptType
import app.glance.wallet.core.data.db.ServerConfigEntity
import app.glance.wallet.core.data.db.UtxoEntity
import app.glance.wallet.core.data.db.TransactionInputEntity
import app.glance.wallet.core.data.db.TransactionOutputEntity
import app.glance.wallet.core.data.db.WatchedKeyEntity
import app.glance.wallet.core.data.db.WalletGroupEntity
import app.glance.wallet.core.data.sync.RoomWalletSyncStore
import app.glance.wallet.core.data.security.SqlCipherDatabaseFactory
import app.glance.wallet.core.security.AndroidKeystoreDatabaseKeyProvider
import app.glance.wallet.core.security.UtxoView
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistenceOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun roomCrudCascadesAndPersistsEveryPhaseTwoCache() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val first = watchedKey("first")
        val second = watchedKey("second")
        database.watchedKeyDao().upsert(first)
        database.watchedKeyDao().upsert(second)
        val firstAddressId = database.derivedAddressDao().upsert(address(first.id, 0))
        database.derivedAddressDao().upsert(address(second.id, 0))
        database.addressHistoryDao().upsertAll(listOf(AddressHistoryEntity(addressId = firstAddressId, txid = "history", confirmations = 1, blockHeight = 1, valueSats = 100)))
        database.utxoDao().upsertAll(listOf(UtxoEntity(addressId = firstAddressId, txid = "utxo", vout = 0, valueSats = 100, confirmations = 1)))
        database.blockTimestampCacheDao().upsert(BlockTimestampCacheEntity(1, 1000))
        database.labelDao().upsert(LabelEntity(LabelReferenceType.TRANSACTION, "history", "Coffee"))
        database.serverConfigDao().upsert(ServerConfigEntity("server", "electrum", "example.invalid", 50002, true, true))
        database.fiatPriceCacheDao().upsert(FiatPriceCacheEntity(provider = "mempool_space", currency = "EUR", timestamp = 1000, price = 1.0))
        database.decoyProfileDao().upsert(DecoyProfileEntity("decoy", "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", "redacted-zpub"))

        assertEquals(1, database.addressHistoryDao().forAddress(firstAddressId).first().size)
        assertEquals(1, database.utxoDao().forAddress(firstAddressId).first().size)
        assertEquals(1000L, database.blockTimestampCacheDao().find(1)?.timestamp)
        assertEquals("Coffee", database.labelDao().find(LabelReferenceType.TRANSACTION, "history")?.text)
        assertEquals(1, database.serverConfigDao().observeAll().first().size)
        assertEquals(1, database.fiatPriceCacheDao().forProviderAndCurrency("mempool_space", "EUR").size)
        assertEquals("redacted-zpub", database.decoyProfileDao().findById("decoy")?.accountExtendedPublicKey)

        database.watchedKeyDao().deleteById(first.id)
        assertEquals(0, database.derivedAddressDao().forKey(first.id).first().size)
        assertEquals(1, database.derivedAddressDao().forKey(second.id).first().size)
        database.close()
    }

    @Test
    fun transactionIoReplacementIsAtomicAndMissingDetectionIsLimitedToSyncedKeys() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java).allowMainThreadQueries().build()
        val first = watchedKey("io-first")
        val second = watchedKey("io-second")
        database.watchedKeyDao().upsert(first)
        database.watchedKeyDao().upsert(second)
        val firstAddress = database.derivedAddressDao().upsert(address(first.id, 0))
        val secondAddress = database.derivedAddressDao().upsert(address(second.id, 0))
        database.addressHistoryDao().upsertAll(listOf(
            AddressHistoryEntity(addressId = firstAddress, txid = "first-tx", confirmations = 1, blockHeight = 1, valueSats = 1),
            AddressHistoryEntity(addressId = secondAddress, txid = "second-tx", confirmations = 1, blockHeight = 1, valueSats = 1),
        ))
        val store = RoomWalletSyncStore(database)

        assertEquals(listOf("first-tx"), store.transactionsMissingIo(listOf(first.id)))
        store.replaceTransactionIo(
            "first-tx",
            listOf(TransactionInputEntity("first-tx", 0, null, 2, true)),
            listOf(TransactionOutputEntity("first-tx", 0, "bc1qoutput", 1)),
        )

        assertEquals(emptyList<String>(), store.transactionsMissingIo(listOf(first.id)))
        assertEquals(listOf("second-tx"), store.transactionsMissingIo(listOf(second.id)))
        assertEquals(1, database.transactionDetailDao().observeInputs("first-tx").first().size)
        assertEquals(1, database.transactionDetailDao().observeOutputs("first-tx").first().size)
        database.close()
    }

    @Test
    fun transactionDetailWatchedAddressLookupIsTargetAndGroupScoped() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java).allowMainThreadQueries().build()
        database.walletGroupDao().insert(WalletGroupEntity("group", "Group", 1L, UtxoView.BUBBLES.name, preferredReceiveScriptType = ScriptType.NATIVE_SEGWIT))
        val target = watchedKey("target")
        val grouped = watchedKey("grouped").copy(walletGroupId = "group")
        val otherGrouped = watchedKey("other-grouped").copy(walletGroupId = "other-group")
        database.watchedKeyDao().upsert(target)
        database.watchedKeyDao().upsert(grouped)
        database.watchedKeyDao().upsert(otherGrouped)
        database.derivedAddressDao().upsert(address(target.id, 0).copy(address = "target-address"))
        database.derivedAddressDao().upsert(address(grouped.id, 0).copy(address = "grouped-address"))
        database.derivedAddressDao().upsert(address(otherGrouped.id, 0).copy(address = "other-group-address"))

        assertEquals(listOf("target-address"), database.transactionDetailDao().observeWatchedAddressesForKey(target.id).first())
        assertEquals(listOf("grouped-address"), database.transactionDetailDao().observeWatchedAddressesForGroup("group").first())
        database.close()
    }

    @Test
    fun transactionDetailIncludesTheSpecificAddressTimestampAndReactiveLabel() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val key = watchedKey("detail")
        database.watchedKeyDao().upsert(key)
        val firstAddressId = database.derivedAddressDao().upsert(address(key.id, 0))
        val secondAddressId = database.derivedAddressDao().upsert(address(key.id, 1))
        database.addressHistoryDao().upsertAll(
            listOf(
                AddressHistoryEntity(addressId = firstAddressId, txid = "same-tx", confirmations = 2, blockHeight = 42, valueSats = 100),
                AddressHistoryEntity(addressId = secondAddressId, txid = "same-tx", confirmations = 2, blockHeight = 42, valueSats = 200),
            ),
        )
        database.blockTimestampCacheDao().upsert(BlockTimestampCacheEntity(42, 1_700_000_000))
        database.labelDao().upsert(LabelEntity(LabelReferenceType.TRANSACTION, "same-tx", "Lunch"))

        val rows = database.walletScreenDao().observeTransactionPage(key.id, limit = 20, offset = 0).first()
        val detail = database.walletScreenDao().observeTransactionDetail(rows.first { it.addressId == secondAddressId }.historyId).first()
        assertEquals(secondAddressId, detail?.addressId)
        assertEquals("bc1qtestdetail1", detail?.address)
        assertEquals(1_700_000_000L, detail?.timestamp)
        assertEquals("Lunch", detail?.label)

        database.labelDao().delete(LabelReferenceType.TRANSACTION, "same-tx")
        assertEquals(null, database.walletScreenDao().observeTransactionDetail(detail!!.historyId).first()?.label)
        database.close()
    }

    @Test
    fun pendingTransactionsAppearBeforeAllConfirmedTransactions() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val key = watchedKey("ordering")
        database.watchedKeyDao().upsert(key)
        val addressId = database.derivedAddressDao().upsert(address(key.id, 0))
        database.addressHistoryDao().upsertAll(
            listOf(
                AddressHistoryEntity(addressId = addressId, txid = "confirmed-newest", confirmations = 3, blockHeight = 500, valueSats = 25_000),
                AddressHistoryEntity(addressId = addressId, txid = "pending-outgoing", confirmations = 0, blockHeight = null, valueSats = -5_000),
                AddressHistoryEntity(addressId = addressId, txid = "pending-incoming-old", confirmations = 0, blockHeight = null, valueSats = 1_000),
                AddressHistoryEntity(addressId = addressId, txid = "pending-incoming-new", confirmations = 0, blockHeight = null, valueSats = 2_000),
            ),
        )

        assertEquals(
            listOf("pending-incoming-new", "pending-incoming-old", "pending-outgoing", "confirmed-newest"),
            database.walletScreenDao().observeTransactionPage(key.id, limit = 20, offset = 0).first().map { it.txid },
        )
        database.close()
    }

    @Test
    fun pendingUtxosAreExcludedFromDashboardKeyAndGroupBalances() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.walletGroupDao().insert(
            WalletGroupEntity("group", "Group", 1L, UtxoView.BUBBLES.name, preferredReceiveScriptType = ScriptType.NATIVE_SEGWIT),
        )
        val standalone = watchedKey("standalone")
        val grouped = watchedKey("grouped").copy(walletGroupId = "group")
        database.watchedKeyDao().upsert(standalone)
        database.watchedKeyDao().upsert(grouped)
        val standaloneAddress = database.derivedAddressDao().upsert(address(standalone.id, 0))
        val groupedAddress = database.derivedAddressDao().upsert(address(grouped.id, 0))
        database.utxoDao().upsertAll(
            listOf(
                UtxoEntity(addressId = standaloneAddress, txid = "confirmed-standalone", vout = 0, valueSats = 50_000, confirmations = 1),
                UtxoEntity(addressId = standaloneAddress, txid = "pending-standalone", vout = 1, valueSats = 10_000, confirmations = 0),
                UtxoEntity(addressId = groupedAddress, txid = "confirmed-grouped", vout = 0, valueSats = 25_000, confirmations = 3),
                UtxoEntity(addressId = groupedAddress, txid = "pending-grouped", vout = 1, valueSats = 5_000, confirmations = 0),
            ),
        )

        assertEquals(75_000L, database.utxoDao().observeConfirmedBalance().first())
        assertEquals(15_000L, database.utxoDao().observeUnconfirmedBalance().first())
        assertEquals(50_000L, database.walletScreenDao().observeKeyBalances().first().single { it.id == standalone.id }.balanceSats)
        assertEquals(25_000L, database.walletScreenDao().observeGroupedBalances().first().single().balanceSats)
        assertEquals(2, database.utxoDao().forAddress(standaloneAddress).first().size)
        database.close()
    }

    @Test
    fun transactionPagesReturnTwentyRowsWithTheExistingChronologicalOrdering() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val key = watchedKey("paged")
        database.watchedKeyDao().upsert(key)
        val addressId = database.derivedAddressDao().upsert(address(key.id, 0))
        database.addressHistoryDao().upsertAll(
            (1..25).map { height ->
                AddressHistoryEntity(
                    addressId = addressId,
                    txid = "transaction-$height",
                    confirmations = 1,
                    blockHeight = height,
                    valueSats = height.toLong(),
                )
            },
        )

        assertEquals(25, database.walletScreenDao().observeTransactionCount(key.id).first())
        assertEquals(
            (25 downTo 6).map { "transaction-$it" },
            database.walletScreenDao().observeTransactionPage(key.id, limit = 20, offset = 0).first().map { it.txid },
        )
        assertEquals(
            (5 downTo 1).map { "transaction-$it" },
            database.walletScreenDao().observeTransactionPage(key.id, limit = 20, offset = 20).first().map { it.txid },
        )
        database.close()
    }

    @Test
    fun groupedTransactionPagesCountDistinctTransactionsAndAggregateTheirAmounts() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.walletGroupDao().insert(
            WalletGroupEntity("group", "Group", 1L, UtxoView.BUBBLES.name, preferredReceiveScriptType = ScriptType.NATIVE_SEGWIT),
        )
        val first = watchedKey("first").copy(walletGroupId = "group")
        val second = watchedKey("second").copy(walletGroupId = "group")
        database.watchedKeyDao().upsert(first)
        database.watchedKeyDao().upsert(second)
        val firstAddress = database.derivedAddressDao().upsert(address(first.id, 0))
        val secondAddress = database.derivedAddressDao().upsert(address(second.id, 0))
        database.addressHistoryDao().upsertAll(
            listOf(
                AddressHistoryEntity(addressId = firstAddress, txid = "shared", confirmations = 1, blockHeight = 2, valueSats = 100),
                AddressHistoryEntity(addressId = secondAddress, txid = "shared", confirmations = 1, blockHeight = 2, valueSats = 200),
                AddressHistoryEntity(addressId = firstAddress, txid = "older", confirmations = 1, blockHeight = 1, valueSats = 50),
            ),
        )

        assertEquals(2, database.walletScreenDao().observeGroupTransactionCount("group").first())
        val page = database.walletScreenDao().observeGroupTransactionPage("group", limit = 20, offset = 0).first()
        assertEquals(listOf("shared", "older"), page.map { it.txid })
        assertEquals(300L, page.first().valueSats)
        database.close()
    }

    @Test
    fun standaloneTransactionPagesGroupSharedTxidsBeforeCountAndPagination() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java).allowMainThreadQueries().build()
        val key = watchedKey("standalone")
        database.watchedKeyDao().upsert(key)
        val firstAddress = database.derivedAddressDao().upsert(address(key.id, 0))
        val secondAddress = database.derivedAddressDao().upsert(address(key.id, 1))
        database.addressHistoryDao().upsertAll(listOf(
            AddressHistoryEntity(addressId = firstAddress, txid = "shared", confirmations = 1, blockHeight = 3, valueSats = 100),
            AddressHistoryEntity(addressId = secondAddress, txid = "shared", confirmations = 1, blockHeight = 3, valueSats = -40),
            AddressHistoryEntity(addressId = firstAddress, txid = "older", confirmations = 1, blockHeight = 2, valueSats = 25),
        ))
        val dao = database.walletScreenDao()
        assertEquals(2, dao.observeTransactionCount(key.id).first())
        assertEquals(listOf("shared"), dao.observeTransactionPage(key.id, 1, 0).first().map { it.txid })
        assertEquals(60L, dao.observeTransactionPage(key.id, 1, 0).first().single().valueSats)
        assertEquals(listOf("older"), dao.observeTransactionPage(key.id, 1, 1).first().map { it.txid })
        database.close()
    }

    @Test
    fun watchedKeyRenamePersistsAndDeletionOnlyRemovesItsOwnedLabelsAndCache() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val first = watchedKey("first")
        val second = watchedKey("second")
        database.watchedKeyDao().upsert(first)
        database.watchedKeyDao().upsert(second)
        val firstAddressId = database.derivedAddressDao().upsert(address(first.id, 0))
        val secondAddressId = database.derivedAddressDao().upsert(address(second.id, 0))
        database.addressHistoryDao().upsertAll(
            listOf(
                AddressHistoryEntity(addressId = firstAddressId, txid = "orphaned", confirmations = 1, blockHeight = 1, valueSats = 100),
                AddressHistoryEntity(addressId = firstAddressId, txid = "shared", confirmations = 1, blockHeight = 1, valueSats = 100),
                AddressHistoryEntity(addressId = secondAddressId, txid = "shared", confirmations = 1, blockHeight = 1, valueSats = 100),
            ),
        )
        database.utxoDao().upsertAll(listOf(UtxoEntity(addressId = firstAddressId, txid = "first-utxo", vout = 0, valueSats = 100, confirmations = 1)))
        database.labelDao().upsert(LabelEntity(LabelReferenceType.ADDRESS, "bc1qtestfirst0", "First address"))
        database.labelDao().upsert(LabelEntity(LabelReferenceType.TRANSACTION, "orphaned", "Orphaned transaction"))
        database.labelDao().upsert(LabelEntity(LabelReferenceType.TRANSACTION, "shared", "Shared transaction"))

        database.watchedKeyDao().update(first.copy(label = "Renamed wallet"))
        assertEquals("Renamed wallet", database.watchedKeyDao().findById(first.id)?.label)

        database.watchedKeyDao().deleteWithOwnedData(first.id)

        assertEquals(null, database.watchedKeyDao().findById(first.id))
        assertEquals(0, database.derivedAddressDao().forKey(first.id).first().size)
        assertEquals(1, database.derivedAddressDao().forKey(second.id).first().size)
        assertEquals(null, database.labelDao().find(LabelReferenceType.ADDRESS, "bc1qtestfirst0"))
        assertEquals(null, database.labelDao().find(LabelReferenceType.TRANSACTION, "orphaned"))
        assertEquals("Shared transaction", database.labelDao().find(LabelReferenceType.TRANSACTION, "shared")?.text)
        database.close()
    }

    @Test
    fun keystoreWrappedKeyOpensAnEncryptedSqlCipherDatabase() = runBlocking {
        val name = "phase2-device-test"
        val databaseName = "$name.db"
        val provider = AndroidKeystoreDatabaseKeyProvider(context, name)
        context.deleteDatabase(databaseName)
        val firstKey = provider.getOrCreate()
        assertEquals(firstKey.toList(), AndroidKeystoreDatabaseKeyProvider(context, name).getOrCreate().toList())
        assertNotEquals(firstKey.toList(), File(context.noBackupFilesDir, "$name.key").readBytes().toList())

        val database = SqlCipherDatabaseFactory(context, provider).open(databaseName)
        database.watchedKeyDao().upsert(watchedKey("encrypted"))
        database.close()
        val header = context.getDatabasePath(databaseName).inputStream().use { input ->
            val bytes = ByteArray("SQLite format 3\u0000".length)
            input.read(bytes)
            String(bytes, Charsets.US_ASCII)
        }
        assertNotEquals("SQLite format 3\u0000", header)
        assertThrows(SQLiteException::class.java) {
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqliteDatabase ->
                sqliteDatabase.rawQuery("SELECT count(*) FROM sqlite_master", null).use { cursor ->
                    cursor.moveToFirst()
                }
            }
        }
        context.deleteDatabase(databaseName)
        provider.delete()
    }

    private fun watchedKey(id: String) = WatchedKeyEntity(id, "Test", "redacted", ScriptType.NATIVE_SEGWIT, 1)

    private fun address(keyId: String, index: Int) = DerivedAddressEntity(
        keyId = keyId,
        chain = AddressChain.EXTERNAL,
        derivationIndex = index,
        address = "bc1qtest$keyId$index",
        isUsed = false,
        isConfirmedUnused = false,
    )
}
