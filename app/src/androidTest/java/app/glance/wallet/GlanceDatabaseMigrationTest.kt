package app.glance.wallet

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.glance.wallet.core.data.db.GlanceDatabaseMigrations
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlanceDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        "schemas",
    )

    @Test
    fun v1BaselineSchemaValidatesThroughLatestMigration() {
        helper.createDatabase(TEST_DATABASE, 1).close()

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            12,
            true,
            *GlanceDatabaseMigrations.ALL,
        ).close()
    }

    @Test
    fun v5WatchedKeysBecomeHdTargets() {
        helper.createDatabase(TEST_DATABASE_V5, 5).apply {
            execSQL("INSERT INTO watched_keys (id, label, keyMaterial, scriptType, dateAdded, externalHighestUsedIndex, internalHighestUsedIndex, externalScannedThroughIndex, internalScannedThroughIndex) VALUES ('id', 'label', 'redacted', 'NATIVE_SEGWIT', 1, -1, -1, -1, -1)")
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE_V5,
            6,
            true,
            GlanceDatabaseMigrations.MIGRATION_5_6,
        ).apply {
            query("SELECT targetType FROM watched_keys WHERE id = 'id'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("HD_KEY", cursor.getString(0))
            }
            close()
        }
    }

    @Test
    fun v6UtxosGainNullableBlockHeights() {
        helper.createDatabase(TEST_DATABASE_V6, 6).apply {
            execSQL("INSERT INTO watched_keys (id, label, keyMaterial, scriptType, dateAdded, externalHighestUsedIndex, internalHighestUsedIndex, externalScannedThroughIndex, internalScannedThroughIndex, targetType) VALUES ('key', 'label', 'redacted', 'NATIVE_SEGWIT', 1, -1, -1, -1, -1, 'HD_KEY')")
            execSQL("INSERT INTO derived_addresses (id, keyId, chain, derivationIndex, address, isUsed, isConfirmedUnused, lastStatus) VALUES (1, 'key', 'EXTERNAL', 0, 'redacted-address', 1, 0, 'status')")
            execSQL("INSERT INTO utxos (addressId, txid, vout, valueSats, confirmations) VALUES (1, 'redacted-txid', 0, 100, 1)")
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE_V6,
            7,
            true,
            GlanceDatabaseMigrations.MIGRATION_6_7,
        ).apply {
            query("SELECT blockHeight FROM utxos").use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.isNull(0))
            }
            close()
        }
    }

    @Test
    fun v7WatchedKeysGainAnUnsetPerWalletUtxoView() {
        helper.createDatabase(TEST_DATABASE_V7, 7).apply {
            execSQL("INSERT INTO watched_keys (id, label, keyMaterial, scriptType, dateAdded, externalHighestUsedIndex, internalHighestUsedIndex, externalScannedThroughIndex, internalScannedThroughIndex, targetType) VALUES ('key', 'label', 'redacted', 'NATIVE_SEGWIT', 1, -1, -1, -1, -1, 'HD_KEY')")
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE_V7,
            8,
            true,
            GlanceDatabaseMigrations.MIGRATION_7_8,
        ).apply {
            query("SELECT utxoView FROM watched_keys WHERE id = 'key'").use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.isNull(0))
            }
            close()
        }
    }

    @Test
    fun v8WatchedKeysReceiveTheDefaultDustThreshold() {
        helper.createDatabase(TEST_DATABASE_V8, 8).apply {
            execSQL("INSERT INTO watched_keys (id, label, keyMaterial, scriptType, dateAdded, externalHighestUsedIndex, internalHighestUsedIndex, externalScannedThroughIndex, internalScannedThroughIndex, targetType, utxoView) VALUES ('key', 'label', 'redacted', 'NATIVE_SEGWIT', 1, -1, -1, -1, -1, 'HD_KEY', 'BUBBLES')")
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE_V8,
            9,
            true,
            GlanceDatabaseMigrations.MIGRATION_8_9,
        ).apply {
            query("SELECT dustThresholdSats FROM watched_keys WHERE id = 'key'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(5_000L, cursor.getLong(0))
            }
            close()
        }
    }

    @Test
    fun v9WatchedKeysRemainStandaloneAfterWalletGroupsAreAdded() {
        helper.createDatabase("glance-v9-migration-test", 9).apply {
            execSQL("INSERT INTO watched_keys (id, label, keyMaterial, scriptType, dateAdded, externalHighestUsedIndex, internalHighestUsedIndex, externalScannedThroughIndex, internalScannedThroughIndex, targetType, utxoView, dustThresholdSats) VALUES ('key', 'label', 'redacted', 'NATIVE_SEGWIT', 1, -1, -1, -1, -1, 'HD_KEY', 'BUBBLES', 5000)")
            close()
        }
        helper.runMigrationsAndValidate("glance-v9-migration-test", 10, true, GlanceDatabaseMigrations.MIGRATION_9_10).apply {
            query("SELECT walletGroupId FROM watched_keys WHERE id = 'key'").use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.isNull(0))
            }
            close()
        }
    }

    private companion object {
        const val TEST_DATABASE = "glance-v1-migration-test"
        const val TEST_DATABASE_V5 = "glance-v5-migration-test"
        const val TEST_DATABASE_V6 = "glance-v6-migration-test"
        const val TEST_DATABASE_V7 = "glance-v7-migration-test"
        const val TEST_DATABASE_V8 = "glance-v8-migration-test"
    }

    @Test
    fun v10AddressesRetainHistoryAndStartWithUnknownRemotePagingState() {
        helper.createDatabase("glance-v10-history-page-migration-test", 10).apply {
            execSQL("INSERT INTO watched_keys (id, label, keyMaterial, scriptType, dateAdded, externalHighestUsedIndex, internalHighestUsedIndex, externalScannedThroughIndex, internalScannedThroughIndex, targetType, dustThresholdSats) VALUES ('key', 'label', 'redacted', 'NATIVE_SEGWIT', 1, -1, -1, -1, -1, 'SINGLE_ADDRESS', 5000)")
            execSQL("INSERT INTO derived_addresses (keyId, chain, derivationIndex, address, isUsed, isConfirmedUnused) VALUES ('key', 'EXTERNAL', 0, 'redacted-address', 1, 0)")
            close()
        }
        helper.runMigrationsAndValidate("glance-v10-history-page-migration-test", 11, true, GlanceDatabaseMigrations.MIGRATION_10_11).apply {
            query("SELECT historyRemoteCount, historyNextCursor, historyComplete FROM derived_addresses").use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.isNull(0))
                check(cursor.isNull(1))
                assertEquals(0, cursor.getInt(2))
            }
            close()
        }
    }

    @Test
    fun v11AddressesStartWithDetailedUtxoTracking() {
        helper.createDatabase("glance-v11-oversized-utxo-migration-test", 11).apply {
            execSQL("INSERT INTO watched_keys (id, label, keyMaterial, scriptType, dateAdded, externalHighestUsedIndex, internalHighestUsedIndex, externalScannedThroughIndex, internalScannedThroughIndex, targetType, dustThresholdSats) VALUES ('key', 'label', 'redacted', 'NATIVE_SEGWIT', 1, -1, -1, -1, -1, 'SINGLE_ADDRESS', 5000)")
            execSQL("INSERT INTO derived_addresses (keyId, chain, derivationIndex, address, isUsed, isConfirmedUnused) VALUES ('key', 'EXTERNAL', 0, 'redacted-address', 1, 0)")
            close()
        }
        helper.runMigrationsAndValidate("glance-v11-oversized-utxo-migration-test", 12, true, GlanceDatabaseMigrations.MIGRATION_11_12).apply {
            query("SELECT isUtxoSnapshotSuppressed, cachedConfirmedBalanceSats, cachedUnconfirmedBalanceSats, unspentOutputCount FROM derived_addresses").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals(0L, cursor.getLong(1))
                assertEquals(0L, cursor.getLong(2))
                check(cursor.isNull(3))
            }
            close()
        }
    }
}
