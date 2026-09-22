package app.glance.wallet.core.data.db

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ScriptType { LEGACY, SEGWIT_COMPAT, NATIVE_SEGWIT, TAPROOT }

/** The source of a watched balance: a derivable HD account or one immutable address. */
enum class WatchTargetType { HD_KEY, SINGLE_ADDRESS }

enum class AddressChain { EXTERNAL, INTERNAL }

enum class LabelReferenceType { ADDRESS, TRANSACTION }

/** A user-visible wallet which may contain several independently-derived HD formats. */
@Entity(tableName = "wallet_groups")
data class WalletGroupEntity(
    @PrimaryKey val id: String,
    val label: String,
    val dateAdded: Long,
    val utxoView: String,
    @ColumnInfo(defaultValue = "5000") val dustThresholdSats: Long = 5_000L,
    val preferredReceiveScriptType: ScriptType,
)

@Entity(tableName = "watched_keys")
data class WatchedKeyEntity(
    @PrimaryKey val id: String,
    val label: String,
    val keyMaterial: String,
    /** Meaningful only for HD_KEY; SINGLE_ADDRESS retains a harmless native-SegWit placeholder. */
    val scriptType: ScriptType,
    val dateAdded: Long,
    val externalHighestUsedIndex: Int = -1,
    val internalHighestUsedIndex: Int = -1,
    val externalScannedThroughIndex: Int = -1,
    val internalScannedThroughIndex: Int = -1,
    val targetType: WatchTargetType = WatchTargetType.HD_KEY,
    /** Null only for pre-v8 rows until their former global default is materialized. */
    val utxoView: String? = null,
    /** Per-wallet cutoff for visually de-emphasized UTXOs; zero disables dust treatment. */
    @ColumnInfo(defaultValue = "5000") val dustThresholdSats: Long = 5_000L,
    /** Null preserves all pre-group imports as standalone wallets. */
    val walletGroupId: String? = null,
)

@Entity(
    tableName = "derived_addresses",
    foreignKeys = [
        ForeignKey(
            entity = WatchedKeyEntity::class,
            parentColumns = ["id"],
            childColumns = ["keyId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["keyId", "chain", "derivationIndex"], unique = true),
        Index(value = ["address"], unique = true),
    ],
)
data class DerivedAddressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyId: String,
    val chain: AddressChain,
    val derivationIndex: Int,
    val address: String,
    val isUsed: Boolean,
    val isConfirmedUnused: Boolean,
    val lastStatus: String? = null,
    /** Fixed-address history paging only. Null count/cursor means pre-v11 cache state is unknown. */
    val historyRemoteCount: Int? = null,
    val historyNextCursor: String? = null,
    @ColumnInfo(defaultValue = "0") val historyComplete: Boolean = false,
    /** Oversized fixed-address targets retain a summary balance rather than a full UTXO snapshot. */
    @ColumnInfo(defaultValue = "0") val isUtxoSnapshotSuppressed: Boolean = false,
    @ColumnInfo(defaultValue = "0") val cachedConfirmedBalanceSats: Long = 0L,
    @ColumnInfo(defaultValue = "0") val cachedUnconfirmedBalanceSats: Long = 0L,
    val unspentOutputCount: Int? = null,
)

@Entity(
    tableName = "address_history",
    foreignKeys = [
        ForeignKey(
            entity = DerivedAddressEntity::class,
            parentColumns = ["id"],
            childColumns = ["addressId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["addressId", "txid"], unique = true),
        Index(value = ["blockHeight"]),
    ],
)
data class AddressHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val addressId: Long,
    val txid: String,
    val confirmations: Int,
    val blockHeight: Int?,
    val valueSats: Long,
)

@Entity(tableName = "block_timestamp_cache")
data class BlockTimestampCacheEntity(
    @PrimaryKey val blockHeight: Int,
    val timestamp: Long,
)

@Entity(
    tableName = "utxos",
    foreignKeys = [
        ForeignKey(
            entity = DerivedAddressEntity::class,
            parentColumns = ["id"],
            childColumns = ["addressId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["addressId", "txid", "vout"], unique = true),
    ],
)
data class UtxoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val addressId: Long,
    val txid: String,
    val vout: Int,
    val valueSats: Long,
    val confirmations: Int,
    val blockHeight: Int? = null,
)

@Entity(
    tableName = "labels",
    primaryKeys = ["referenceType", "referenceId"],
)
data class LabelEntity(
    val referenceType: LabelReferenceType,
    val referenceId: String,
    val text: String,
)

@Entity(tableName = "server_configs")
data class ServerConfigEntity(
    @PrimaryKey val id: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val useTls: Boolean,
    val isCustom: Boolean,
)

@Entity(
    tableName = "fiat_price_cache",
    indices = [Index(value = ["provider", "currency", "timestamp"], unique = true)],
)
data class FiatPriceCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val provider: String,
    val currency: String,
    val timestamp: Long,
    val price: Double,
)

/** The configured display balance for the separately encrypted decoy profile. */
@Entity(tableName = "decoy_profiles")
data class DecoyProfileEntity(
    @PrimaryKey val id: String,
    val fakeBalanceSats: Long,
)
