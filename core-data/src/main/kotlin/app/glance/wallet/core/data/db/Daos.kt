package app.glance.wallet.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchedKeyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(key: WatchedKeyEntity)

    @Update
    suspend fun update(key: WatchedKeyEntity)

    @Query("SELECT * FROM watched_keys ORDER BY dateAdded")
    fun observeAll(): Flow<List<WatchedKeyEntity>>

    @Query("SELECT * FROM watched_keys WHERE id = :id")
    suspend fun findById(id: String): WatchedKeyEntity?

    @Query("SELECT * FROM watched_keys WHERE id = :id")
    fun observeById(id: String): Flow<WatchedKeyEntity?>

    @Query("SELECT * FROM watched_keys WHERE walletGroupId = :groupId ORDER BY dateAdded")
    suspend fun forWalletGroup(groupId: String): List<WatchedKeyEntity>

    @Query("SELECT * FROM watched_keys WHERE walletGroupId = :groupId ORDER BY dateAdded")
    fun observeForWalletGroup(groupId: String): Flow<List<WatchedKeyEntity>>

    @Query("""
        SELECT walletGroupId FROM watched_keys
        WHERE targetType = 'HD_KEY' AND keyMaterial = :keyMaterial AND walletGroupId IS NOT NULL
        LIMIT 1
    """)
    suspend fun walletGroupIdForHdKeyMaterial(keyMaterial: String): String?

    @Query("""
        SELECT scriptType FROM watched_keys
        WHERE targetType = 'HD_KEY' AND keyMaterial = :keyMaterial AND scriptType IN (:scriptTypes)
    """)
    suspend fun existingHdScriptTypes(keyMaterial: String, scriptTypes: List<ScriptType>): List<ScriptType>

    @Query("UPDATE watched_keys SET label = :label WHERE id = :id")
    suspend fun rename(id: String, label: String)

    @Query("UPDATE watched_keys SET utxoView = :view WHERE id = :id")
    suspend fun setUtxoView(id: String, view: String)

    @Query("UPDATE watched_keys SET utxoView = :view WHERE utxoView IS NULL")
    suspend fun initializeMissingUtxoViews(view: String)

    @Query("UPDATE watched_keys SET dustThresholdSats = :thresholdSats WHERE id = :id")
    suspend fun setDustThresholdSats(id: String, thresholdSats: Long)

    @Query("DELETE FROM labels WHERE referenceType = 'ADDRESS' AND referenceId IN (SELECT address FROM derived_addresses WHERE keyId = :id)")
    suspend fun deleteAddressLabelsForKey(id: String)

    @Query("""
        DELETE FROM labels
        WHERE referenceType = 'TRANSACTION'
          AND referenceId IN (
              SELECT h.txid
              FROM address_history h
              JOIN derived_addresses a ON a.id = h.addressId
              WHERE a.keyId = :id
          )
          AND NOT EXISTS (
              SELECT 1
              FROM address_history remainingHistory
              JOIN derived_addresses remainingAddress ON remainingAddress.id = remainingHistory.addressId
              WHERE remainingHistory.txid = labels.referenceId
                AND remainingAddress.keyId != :id
          )
    """)
    suspend fun deleteUnsharedTransactionLabelsForKey(id: String)

    @Query("DELETE FROM watched_keys WHERE id = :id")
    suspend fun deleteById(id: String)

    @Transaction
    suspend fun deleteWithOwnedData(id: String) {
        deleteAddressLabelsForKey(id)
        deleteUnsharedTransactionLabelsForKey(id)
        deleteById(id)
    }
}

@Dao
interface WalletGroupDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(group: WalletGroupEntity)

    @Query("SELECT * FROM wallet_groups ORDER BY dateAdded")
    fun observeAll(): Flow<List<WalletGroupEntity>>

    @Query("SELECT * FROM wallet_groups WHERE id = :id")
    fun observeById(id: String): Flow<WalletGroupEntity?>

    @Query("UPDATE wallet_groups SET label = :label WHERE id = :id")
    suspend fun rename(id: String, label: String)

    @Query("UPDATE wallet_groups SET utxoView = :view WHERE id = :id")
    suspend fun setUtxoView(id: String, view: String)

    @Query("UPDATE wallet_groups SET dustThresholdSats = :thresholdSats WHERE id = :id")
    suspend fun setDustThresholdSats(id: String, thresholdSats: Long)

    @Query("UPDATE wallet_groups SET preferredReceiveScriptType = :scriptType WHERE id = :id")
    suspend fun setPreferredReceiveScriptType(id: String, scriptType: ScriptType)

    @Query("DELETE FROM wallet_groups WHERE id = :id")
    suspend fun deleteById(id: String)

    @Transaction
    suspend fun deleteWithOwnedData(id: String, watchedKeyDao: WatchedKeyDao) {
        watchedKeyDao.forWalletGroup(id).forEach { watchedKeyDao.deleteWithOwnedData(it.id) }
        deleteById(id)
    }
}

@Dao
interface DerivedAddressDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun upsert(address: DerivedAddressEntity): Long

    @Update
    suspend fun update(address: DerivedAddressEntity)

    @Query("SELECT * FROM derived_addresses WHERE keyId = :keyId ORDER BY chain, derivationIndex")
    fun forKey(keyId: String): Flow<List<DerivedAddressEntity>>

    @Query("SELECT * FROM derived_addresses WHERE keyId = :keyId AND chain = :chain ORDER BY derivationIndex")
    suspend fun forChain(keyId: String, chain: AddressChain): List<DerivedAddressEntity>

    @Query("SELECT * FROM derived_addresses WHERE keyId = :keyId AND chain = :chain AND derivationIndex = :index LIMIT 1")
    suspend fun find(keyId: String, chain: AddressChain, index: Int): DerivedAddressEntity?

    @Query("SELECT * FROM derived_addresses WHERE address = :address LIMIT 1")
    suspend fun findByAddress(address: String): DerivedAddressEntity?

    @Query("SELECT address FROM derived_addresses WHERE address IN (:addresses)")
    suspend fun existingAddresses(addresses: List<String>): List<String>
}

@Dao
interface AddressHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(history: List<AddressHistoryEntity>)

    @Query("DELETE FROM address_history WHERE addressId = :addressId")
    suspend fun deleteForAddress(addressId: Long)

    @Query("SELECT * FROM address_history WHERE addressId = :addressId ORDER BY blockHeight DESC")
    fun forAddress(addressId: Long): Flow<List<AddressHistoryEntity>>

    @Query("""
        UPDATE address_history SET confirmations = CASE
            WHEN blockHeight IS NULL THEN 0
            ELSE MAX(:tipHeight - blockHeight + 1, 0)
        END
        WHERE addressId IN (SELECT id FROM derived_addresses WHERE keyId IN (:keyIds))
    """)
    suspend fun refreshConfirmations(keyIds: List<String>, tipHeight: Int)
}

@Dao
interface UtxoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(utxos: List<UtxoEntity>)

    @Query("DELETE FROM utxos WHERE addressId = :addressId")
    suspend fun deleteForAddress(addressId: Long)

    @Query("""
        SELECT COALESCE(SUM(CASE WHEN a.isUtxoSnapshotSuppressed = 1 THEN a.cachedConfirmedBalanceSats
            ELSE COALESCE(u.valueSats, 0) END), 0)
        FROM derived_addresses a LEFT JOIN utxos u ON u.addressId = a.id AND u.confirmations > 0 AND a.isUtxoSnapshotSuppressed = 0
    """)
    fun observeConfirmedBalance(): Flow<Long>

    /** Sats charts use confirmation time, so their live endpoint excludes pending UTXOs. */
    @Query("""
        SELECT COALESCE(SUM(CASE WHEN a.isUtxoSnapshotSuppressed = 1 THEN a.cachedConfirmedBalanceSats
            ELSE COALESCE(u.valueSats, 0) END), 0)
        FROM derived_addresses a LEFT JOIN utxos u ON u.addressId = a.id AND u.confirmations > 0 AND a.isUtxoSnapshotSuppressed = 0
    """)
    fun observeChartConfirmedBalance(): Flow<Long>

    @Query("""
        SELECT COALESCE(SUM(CASE WHEN a.isUtxoSnapshotSuppressed = 1 THEN a.cachedUnconfirmedBalanceSats
            ELSE COALESCE(u.valueSats, 0) END), 0)
        FROM derived_addresses a LEFT JOIN utxos u ON u.addressId = a.id AND u.confirmations = 0 AND a.isUtxoSnapshotSuppressed = 0
    """)
    fun observeUnconfirmedBalance(): Flow<Long>

    @Query("SELECT * FROM utxos WHERE addressId = :addressId")
    fun forAddress(addressId: Long): Flow<List<UtxoEntity>>

    @Query("""
        UPDATE utxos SET confirmations = CASE
            WHEN blockHeight IS NULL THEN 0
            ELSE MAX(:tipHeight - blockHeight + 1, 0)
        END
        WHERE addressId IN (SELECT id FROM derived_addresses WHERE keyId IN (:keyIds))
    """)
    suspend fun refreshConfirmations(keyIds: List<String>, tipHeight: Int)
}

@Dao
interface BlockTimestampCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cacheEntry: BlockTimestampCacheEntity)

    @Query("SELECT * FROM block_timestamp_cache WHERE blockHeight = :blockHeight")
    suspend fun find(blockHeight: Int): BlockTimestampCacheEntity?

    @Query("""
        SELECT DISTINCT h.blockHeight
        FROM address_history h
        JOIN derived_addresses a ON a.id = h.addressId
        LEFT JOIN block_timestamp_cache b ON b.blockHeight = h.blockHeight
        WHERE a.keyId IN (:keyIds)
          AND h.confirmations > 0
          AND h.blockHeight IS NOT NULL
          AND b.blockHeight IS NULL
        ORDER BY h.blockHeight
    """)
    suspend fun missingConfirmedHistoryHeights(keyIds: List<String>): List<Int>
}

@Dao
interface LabelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(label: LabelEntity)

    @Query("SELECT * FROM labels WHERE referenceType = :referenceType AND referenceId = :referenceId")
    suspend fun find(referenceType: LabelReferenceType, referenceId: String): LabelEntity?

    @Query("DELETE FROM labels WHERE referenceType = :referenceType AND referenceId = :referenceId")
    suspend fun delete(referenceType: LabelReferenceType, referenceId: String)
}

@Dao
interface ServerConfigDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: ServerConfigEntity)

    @Query("SELECT * FROM server_configs ORDER BY isCustom, host")
    fun observeAll(): Flow<List<ServerConfigEntity>>
}

@Dao
interface FiatPriceCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: FiatPriceCacheEntity)

    @Query("SELECT * FROM fiat_price_cache WHERE provider = :provider AND currency = :currency ORDER BY timestamp DESC")
    suspend fun forProviderAndCurrency(provider: String, currency: String): List<FiatPriceCacheEntity>

    @Query("SELECT timestamp FROM fiat_price_cache WHERE provider = :provider AND currency = :currency AND timestamp IN (:timestamps)")
    suspend fun cachedTimestamps(provider: String, currency: String, timestamps: List<Long>): List<Long>

    @Query("SELECT MIN(timestamp) FROM fiat_price_cache WHERE provider = :provider AND currency = :currency")
    suspend fun oldestTimestamp(provider: String, currency: String): Long?

    @Query("SELECT MAX(timestamp) FROM fiat_price_cache WHERE provider = :provider AND currency = :currency")
    suspend fun newestTimestamp(provider: String, currency: String): Long?

    @Query("SELECT * FROM fiat_price_cache WHERE provider = :provider AND currency = :currency ORDER BY timestamp ASC")
    fun observeForProviderAndCurrency(provider: String, currency: String): Flow<List<FiatPriceCacheEntity>>
}

@Dao
interface DecoyProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: DecoyProfileEntity)

    @Query("SELECT * FROM decoy_profiles WHERE id = :id")
    suspend fun findById(id: String): DecoyProfileEntity?
}
