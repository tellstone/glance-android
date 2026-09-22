package app.glance.wallet.core.data.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class KeyBalanceRow(val id: String, val label: String, val scriptType: ScriptType?, val targetType: WatchTargetType, val balanceSats: Long, val walletGroupId: String? = null)
data class TransactionRow(
    val historyId: Long,
    val addressId: Long,
    val txid: String,
    val valueSats: Long,
    val confirmations: Int,
    val blockHeight: Int?,
    val timestamp: Long?,
    val address: String,
    val label: String?,
)
data class TransactionDetailRow(
    val historyId: Long,
    val addressId: Long,
    val txid: String,
    val valueSats: Long,
    val confirmations: Int,
    val blockHeight: Int?,
    val timestamp: Long?,
    val address: String,
    val label: String?,
)
data class UtxoRow(
    val id: Long,
    val txid: String,
    val vout: Int,
    val valueSats: Long,
    val confirmations: Int,
    val address: String,
    val derivationIndex: Int,
    val label: String?,
)
data class ChartHistoryRow(val txid: String, val valueSats: Long, val confirmations: Int, val timestamp: Long?)
data class TransactionHistoryPagingRow(
    val remoteCount: Int?,
    val isComplete: Boolean,
    val isUtxoSnapshotSuppressed: Boolean,
)
data class GroupTransactionRow(
    val txid: String,
    val valueSats: Long,
    val confirmations: Int,
    val blockHeight: Int?,
    val timestamp: Long?,
    val label: String?,
)
data class GroupTransactionPartRow(
    val address: String,
    val scriptType: ScriptType,
    val valueSats: Long,
)
data class GroupTransactionDetailRow(
    val txid: String,
    val valueSats: Long,
    val confirmations: Int,
    val blockHeight: Int?,
    val timestamp: Long?,
    val label: String?,
)

@Dao
interface WalletScreenDao {
    @Query("""
        SELECT g.id, g.label, NULL AS scriptType, 'HD_KEY' AS targetType,
          COALESCE(SUM(CASE WHEN a.isUtxoSnapshotSuppressed = 1 THEN a.cachedConfirmedBalanceSats ELSE COALESCE(u.valueSats, 0) END), 0) AS balanceSats, g.id AS walletGroupId
        FROM wallet_groups g
        JOIN watched_keys k ON k.walletGroupId = g.id
        LEFT JOIN derived_addresses a ON a.keyId = k.id
        LEFT JOIN utxos u ON u.addressId = a.id AND u.confirmations > 0 AND a.isUtxoSnapshotSuppressed = 0
        GROUP BY g.id ORDER BY g.dateAdded
    """)
    fun observeGroupedBalances(): Flow<List<KeyBalanceRow>>
    @Query("""
        SELECT k.id, k.label, k.scriptType, k.targetType, COALESCE(SUM(CASE WHEN a.isUtxoSnapshotSuppressed = 1 THEN a.cachedConfirmedBalanceSats ELSE COALESCE(u.valueSats, 0) END), 0) AS balanceSats, k.walletGroupId
        FROM watched_keys k
        LEFT JOIN derived_addresses a ON a.keyId = k.id
        LEFT JOIN utxos u ON u.addressId = a.id AND u.confirmations > 0 AND a.isUtxoSnapshotSuppressed = 0
        GROUP BY k.id ORDER BY k.dateAdded
    """)
    fun observeKeyBalances(): Flow<List<KeyBalanceRow>>

    @Query("""
        SELECT COUNT(*)
        FROM address_history h JOIN derived_addresses a ON a.id = h.addressId
        WHERE a.keyId = :keyId
    """)
    fun observeTransactionCount(keyId: String): Flow<Int>

    @Query("""
        SELECT a.historyRemoteCount AS remoteCount, a.historyComplete AS isComplete,
          a.isUtxoSnapshotSuppressed AS isUtxoSnapshotSuppressed
        FROM derived_addresses a JOIN watched_keys k ON k.id = a.keyId
        WHERE a.keyId = :keyId AND k.targetType = 'SINGLE_ADDRESS' AND a.chain = 'EXTERNAL'
        LIMIT 1
    """)
    fun observeSingleAddressHistoryPaging(keyId: String): Flow<TransactionHistoryPagingRow?>

    @Query("""
        SELECT h.id AS historyId, h.addressId, h.txid, h.valueSats, h.confirmations, h.blockHeight, b.timestamp, a.address,
          l.text AS label
        FROM address_history h JOIN derived_addresses a ON a.id = h.addressId
            LEFT JOIN block_timestamp_cache b ON b.blockHeight = h.blockHeight
        LEFT JOIN labels l ON l.referenceType = 'TRANSACTION' AND l.referenceId = h.txid
        WHERE a.keyId = :keyId
        ORDER BY CASE WHEN h.confirmations = 0 THEN 0 ELSE 1 END,
          h.blockHeight DESC, h.id DESC
        LIMIT :limit OFFSET :offset
    """)
    fun observeTransactionPage(keyId: String, limit: Int, offset: Int): Flow<List<TransactionRow>>

    @Query("""
        SELECT h.id AS historyId, h.addressId, h.txid, h.valueSats, h.confirmations, h.blockHeight,
          b.timestamp, a.address, l.text AS label
        FROM address_history h
        JOIN derived_addresses a ON a.id = h.addressId
        LEFT JOIN block_timestamp_cache b ON b.blockHeight = h.blockHeight
        LEFT JOIN labels l ON l.referenceType = 'TRANSACTION' AND l.referenceId = h.txid
        WHERE h.id = :historyId
    """)
    fun observeTransactionDetail(historyId: Long): Flow<TransactionDetailRow?>

    @Query("""
        SELECT u.id, u.txid, u.vout, u.valueSats, u.confirmations, a.address, a.derivationIndex,
          l.text AS label
        FROM utxos u JOIN derived_addresses a ON a.id = u.addressId
        LEFT JOIN labels l ON l.referenceType = 'ADDRESS' AND l.referenceId = a.address
        WHERE a.keyId = :keyId ORDER BY u.valueSats DESC
    """)
    fun observeUtxos(keyId: String): Flow<List<UtxoRow>>

    @Query("""
        SELECT COUNT(DISTINCT h.txid)
        FROM address_history h
        JOIN derived_addresses a ON a.id = h.addressId
        JOIN watched_keys k ON k.id = a.keyId
        WHERE k.walletGroupId = :groupId
    """)
    fun observeGroupTransactionCount(groupId: String): Flow<Int>

    @Query("""
        SELECT h.txid, SUM(h.valueSats) AS valueSats, MAX(h.confirmations) AS confirmations,
          MAX(h.blockHeight) AS blockHeight, MAX(b.timestamp) AS timestamp, l.text AS label
        FROM address_history h
        JOIN derived_addresses a ON a.id = h.addressId
        JOIN watched_keys k ON k.id = a.keyId
        LEFT JOIN block_timestamp_cache b ON b.blockHeight = h.blockHeight
        LEFT JOIN labels l ON l.referenceType = 'TRANSACTION' AND l.referenceId = h.txid
        WHERE k.walletGroupId = :groupId
        GROUP BY h.txid
        ORDER BY CASE WHEN MAX(h.confirmations) = 0 THEN 0 ELSE 1 END, MAX(h.blockHeight) DESC, h.txid DESC
        LIMIT :limit OFFSET :offset
    """)
    fun observeGroupTransactionPage(groupId: String, limit: Int, offset: Int): Flow<List<GroupTransactionRow>>

    @Query("""
        SELECT a.address, k.scriptType, h.valueSats
        FROM address_history h
        JOIN derived_addresses a ON a.id = h.addressId
        JOIN watched_keys k ON k.id = a.keyId
        WHERE k.walletGroupId = :groupId AND h.txid = :txid
        ORDER BY k.scriptType, a.address
    """)
    fun observeGroupTransactionParts(groupId: String, txid: String): Flow<List<GroupTransactionPartRow>>

    @Query("""
        SELECT h.txid, SUM(h.valueSats) AS valueSats, MAX(h.confirmations) AS confirmations,
          MAX(h.blockHeight) AS blockHeight, MAX(b.timestamp) AS timestamp, l.text AS label
        FROM address_history h
        JOIN derived_addresses a ON a.id = h.addressId
        JOIN watched_keys k ON k.id = a.keyId
        LEFT JOIN block_timestamp_cache b ON b.blockHeight = h.blockHeight
        LEFT JOIN labels l ON l.referenceType = 'TRANSACTION' AND l.referenceId = h.txid
        WHERE k.walletGroupId = :groupId AND h.txid = :txid
        GROUP BY h.txid
    """)
    fun observeGroupTransactionDetail(groupId: String, txid: String): Flow<GroupTransactionDetailRow?>

    @Query("""
        SELECT u.id, u.txid, u.vout, u.valueSats, u.confirmations, a.address, a.derivationIndex,
          l.text AS label
        FROM utxos u JOIN derived_addresses a ON a.id = u.addressId
        JOIN watched_keys k ON k.id = a.keyId
        LEFT JOIN labels l ON l.referenceType = 'ADDRESS' AND l.referenceId = a.address
        WHERE k.walletGroupId = :groupId ORDER BY u.valueSats DESC
    """)
    fun observeGroupUtxos(groupId: String): Flow<List<UtxoRow>>

    @Query("""
        SELECT h.txid, h.valueSats, h.confirmations, b.timestamp
        FROM address_history h JOIN derived_addresses a ON a.id = h.addressId
        LEFT JOIN block_timestamp_cache b ON b.blockHeight = h.blockHeight
        WHERE h.confirmations > 0
        ORDER BY b.timestamp, h.id
    """)
    fun observeChartHistory(): Flow<List<ChartHistoryRow>>

    @Query("""
        SELECT * FROM derived_addresses
        WHERE keyId = :keyId AND chain = 'EXTERNAL' AND isUsed = 0
        ORDER BY derivationIndex LIMIT 1
    """)
    suspend fun currentReceiveAddress(keyId: String): DerivedAddressEntity?
}
