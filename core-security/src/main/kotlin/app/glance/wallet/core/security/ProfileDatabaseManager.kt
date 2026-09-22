package app.glance.wallet.core.security

import android.content.Context
import androidx.room.withTransaction
import app.glance.wallet.core.data.db.DecoyProfileEntity
import app.glance.wallet.core.data.db.AddressChain
import app.glance.wallet.core.data.db.AddressHistoryEntity
import app.glance.wallet.core.data.db.DerivedAddressEntity
import app.glance.wallet.core.data.db.GlanceDatabase
import app.glance.wallet.core.data.db.ScriptType
import app.glance.wallet.core.data.db.UtxoEntity
import app.glance.wallet.core.data.db.WatchedKeyEntity
import app.glance.wallet.core.data.security.SqlCipherDatabaseFactory

data class ProfileSession(val type: ProfileType, val database: GlanceDatabase, val fakeBalanceSats: Long? = null)

/** Opens exactly one SQLCipher profile at a time, with independent Keystore-backed keys. */
class ProfileDatabaseManager(private val context: Context) {
    suspend fun open(type: ProfileType): ProfileSession {
        val name = if (type == ProfileType.REAL) REAL_DATABASE else DECOY_DATABASE
        val provider = AndroidKeystoreDatabaseKeyProvider(context, name.removeSuffix(".db"))
        val database = SqlCipherDatabaseFactory(context, provider).open(name)
        val fakeBalance = if (type == ProfileType.DECOY) {
            database.decoyProfileDao().findById(DECOY_PROFILE_ID)?.fakeBalanceSats ?: 0L
        } else {
            null
        }
        return ProfileSession(type, database, fakeBalance)
    }

    internal suspend fun configureDecoyBalance(sats: Long) {
        require(sats >= 0)
        val session = open(ProfileType.DECOY)
        try {
            session.database.withTransaction {
                session.database.decoyProfileDao().upsert(DecoyProfileEntity(DECOY_PROFILE_ID, sats))
                // A fresh duress store contains only synthetic records. Their values deliberately
                // derive from the configured decoy total, never from the real profile.
                val keyId = "synthetic-decoy-wallet"
                if (session.database.watchedKeyDao().findById(keyId) == null) {
                    session.database.watchedKeyDao().upsert(
                        WatchedKeyEntity(keyId, "Travel savings", "synthetic", ScriptType.NATIVE_SEGWIT, 0L),
                    )
                }
                val address = DerivedAddressEntity(keyId = keyId, chain = AddressChain.EXTERNAL, derivationIndex = 0,
                    address = "bc1qsyntheticdecoywallet000000000000000000000", isUsed = true, isConfirmedUnused = false)
                val addressId = session.database.derivedAddressDao().findByAddress(address.address)?.let { existing ->
                    session.database.derivedAddressDao().update(address.copy(id = existing.id))
                    existing.id
                } ?: session.database.derivedAddressDao().upsert(address)
                session.database.utxoDao().deleteForAddress(addressId)
                session.database.addressHistoryDao().deleteForAddress(addressId)
                session.database.utxoDao().upsertAll(listOf(UtxoEntity(addressId = addressId, txid = "synthetic-decoy-transaction", vout = 0, valueSats = sats, confirmations = 12)))
                session.database.addressHistoryDao().upsertAll(listOf(AddressHistoryEntity(addressId = addressId, txid = "synthetic-decoy-transaction", confirmations = 12, blockHeight = null, valueSats = sats)))
            }
        } finally {
            session.database.close()
        }
    }

    internal suspend fun currentDecoyBalance(): Long? {
        val session = open(ProfileType.DECOY)
        return try {
            session.database.decoyProfileDao().findById(DECOY_PROFILE_ID)?.fakeBalanceSats
        } finally {
            session.database.close()
        }
    }

    fun deleteAll() {
        val failures = mutableListOf<Throwable>()
        listOf(REAL_DATABASE, DECOY_DATABASE).forEach { name ->
            runCatching { AndroidKeystoreDatabaseKeyProvider(context, name.removeSuffix(".db")).delete() }
                .exceptionOrNull()?.let(failures::add)
            runCatching {
                check(!context.getDatabasePath(name).exists() || context.deleteDatabase(name)) {
                    "Unable to delete encrypted database."
                }
            }.exceptionOrNull()?.let(failures::add)
        }
        if (failures.isNotEmpty()) throw IllegalStateException("Unable to erase all encrypted profiles.", failures.first())
    }

    fun deleteDecoy() {
        AndroidKeystoreDatabaseKeyProvider(context, DECOY_DATABASE.removeSuffix(".db")).delete()
        context.deleteDatabase(DECOY_DATABASE)
    }

    companion object {
        const val REAL_DATABASE = "glance-wallet.db"
        const val DECOY_DATABASE = "glance-decoy.db"
        const val DECOY_PROFILE_ID = "configured"
    }
}
