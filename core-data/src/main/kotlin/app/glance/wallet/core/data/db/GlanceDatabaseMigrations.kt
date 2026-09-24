package app.glance.wallet.core.data.db

import androidx.room.migration.Migration

/** The v1 baseline has no predecessor; all later schema changes must be added here and tested. */
object GlanceDatabaseMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE derived_addresses ADD COLUMN lastStatus TEXT")
        }
    }
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE watched_keys ADD COLUMN externalScannedThroughIndex INTEGER NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE watched_keys ADD COLUMN internalScannedThroughIndex INTEGER NOT NULL DEFAULT -1")
        }
    }
    /** Existing address snapshots predate signed chart deltas and must be refreshed once. */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("UPDATE derived_addresses SET lastStatus = NULL WHERE isUsed = 1")
        }
    }
    /** v4 could overwrite signed cached deltas with zero-value Electrum history; repair on next sync. */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("UPDATE derived_addresses SET lastStatus = NULL WHERE isUsed = 1")
        }
    }
    /** v6 adds fixed-address watch targets; existing rows remain HD targets. */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE watched_keys ADD COLUMN targetType TEXT NOT NULL DEFAULT 'HD_KEY'")
        }
    }
    /** v7 persists UTXO heights so confirmation counts can advance without re-downloading snapshots. */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE utxos ADD COLUMN blockHeight INTEGER")
        }
    }
    /** v8 moves the UTXO presentation choice onto each watched wallet. */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE watched_keys ADD COLUMN utxoView TEXT")
        }
    }
    /** v9 makes the dust cutoff independently configurable for every watched wallet. */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE watched_keys ADD COLUMN dustThresholdSats INTEGER NOT NULL DEFAULT 5000")
        }
    }
    /** v10 adds optional multi-format wallet parents without changing existing imports. */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `wallet_groups` (`id` TEXT NOT NULL, `label` TEXT NOT NULL, `dateAdded` INTEGER NOT NULL, `utxoView` TEXT NOT NULL, `dustThresholdSats` INTEGER NOT NULL DEFAULT 5000, `preferredReceiveScriptType` TEXT NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("ALTER TABLE watched_keys ADD COLUMN walletGroupId TEXT")
        }
    }
    /** v11 retains legacy history and marks its remote continuation unknown for on-demand paging. */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE derived_addresses ADD COLUMN historyRemoteCount INTEGER")
            db.execSQL("ALTER TABLE derived_addresses ADD COLUMN historyNextCursor TEXT")
            db.execSQL("ALTER TABLE derived_addresses ADD COLUMN historyComplete INTEGER NOT NULL DEFAULT 0")
        }
    }
    /** v12 stores a lightweight balance for fixed addresses whose UTXO sets are too large to cache safely. */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE derived_addresses ADD COLUMN isUtxoSnapshotSuppressed INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE derived_addresses ADD COLUMN cachedConfirmedBalanceSats INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE derived_addresses ADD COLUMN cachedUnconfirmedBalanceSats INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE derived_addresses ADD COLUMN unspentOutputCount INTEGER")
        }
    }
    /** Synthetic decoys cannot safely become real wallets, so their legacy metadata is discarded. */
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS decoy_profiles")
            db.execSQL("CREATE TABLE IF NOT EXISTS `decoy_profiles` (`id` TEXT NOT NULL, `mnemonic` TEXT NOT NULL, `accountExtendedPublicKey` TEXT NOT NULL, PRIMARY KEY(`id`))")
        }
    }
    /** v14 caches complete Esplora transaction I/O for offline transaction details. */
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `transaction_inputs` (`txid` TEXT NOT NULL, `entryIndex` INTEGER NOT NULL, `address` TEXT, `valueSats` INTEGER NOT NULL, `isCoinbase` INTEGER NOT NULL, PRIMARY KEY(`txid`, `entryIndex`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `transaction_outputs` (`txid` TEXT NOT NULL, `entryIndex` INTEGER NOT NULL, `address` TEXT, `valueSats` INTEGER NOT NULL, PRIMARY KEY(`txid`, `entryIndex`))")
        }
    }
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
}
