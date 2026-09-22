package app.glance.wallet.core.common.security

/** Supplies a SQLCipher passphrase without persisting its plaintext representation. */
interface DatabaseKeyProvider {
    fun getOrCreate(): ByteArray
    fun delete()
}
