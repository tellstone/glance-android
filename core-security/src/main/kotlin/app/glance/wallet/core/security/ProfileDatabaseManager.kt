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

data class ProfileSession(val type: ProfileType, val database: GlanceDatabase)

/** Opens exactly one SQLCipher profile at a time, with independent Keystore-backed keys. */
class ProfileDatabaseManager(
    private val context: Context,
    private val duressWalletMaterialGenerator: DuressWalletMaterialGenerator = DuressWalletMaterialGenerator(),
) {
    suspend fun open(type: ProfileType): ProfileSession {
        val name = if (type == ProfileType.REAL) REAL_DATABASE else DECOY_DATABASE
        val provider = AndroidKeystoreDatabaseKeyProvider(context, name.removeSuffix(".db"))
        val database = SqlCipherDatabaseFactory(context, provider).open(name)
        return ProfileSession(type, database)
    }

    internal suspend fun createDecoyWallet() {
        val material = duressWalletMaterialGenerator.generate()
        val session = open(ProfileType.DECOY)
        try {
            session.database.withTransaction {
                session.database.decoyProfileDao().upsert(
                    DecoyProfileEntity(DECOY_PROFILE_ID, material.mnemonic, material.accountExtendedPublicKey),
                )
                val keyId = DURESS_WALLET_KEY_ID
                session.database.watchedKeyDao().upsert(
                    WatchedKeyEntity(keyId, "Savings", material.accountExtendedPublicKey, ScriptType.NATIVE_SEGWIT, System.currentTimeMillis()),
                )
                session.database.derivedAddressDao().upsert(
                    DerivedAddressEntity(keyId = keyId, chain = AddressChain.EXTERNAL, derivationIndex = 0,
                        address = material.firstReceiveAddress, isUsed = false, isConfirmedUnused = false),
                )
            }
        } finally {
            session.database.close()
        }
    }

    internal suspend fun decoyMnemonic(): String? {
        val session = open(ProfileType.DECOY)
        return try {
            session.database.decoyProfileDao().findById(DECOY_PROFILE_ID)?.mnemonic
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
        const val DURESS_WALLET_KEY_ID = "duress-bip84-wallet"
    }
}
