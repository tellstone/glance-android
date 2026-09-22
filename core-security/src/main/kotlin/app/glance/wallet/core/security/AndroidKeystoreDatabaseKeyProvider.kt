package app.glance.wallet.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.glance.wallet.core.common.security.DatabaseKeyProvider
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps each profile's SQLCipher passphrase encrypted by a distinct Android Keystore key. */
class AndroidKeystoreDatabaseKeyProvider(
    private val context: Context,
    private val storageName: String,
) : DatabaseKeyProvider {
    override fun getOrCreate(): ByteArray = if (wrappedKeyFile.exists()) {
        decrypt(wrappedKeyFile)
    } else {
        createAndWrap()
    }

    override fun delete() {
        wrappedKeyFile.delete()
        keyStore.deleteEntry(keyAlias)
    }

    private fun createAndWrap(): ByteArray = ByteArray(DATABASE_KEY_LENGTH).also { key ->
        SecureRandom().nextBytes(key)
        val encrypted = encrypt(key)
        wrappedKeyFile.parentFile?.mkdirs()
        wrappedKeyFile.writeBytes(encrypted)
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        return byteArrayOf(FORMAT_VERSION.toByte(), cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(plaintext)
    }

    private fun decrypt(file: File): ByteArray {
        try {
            val bytes = file.readBytes()
            require(bytes.size > HEADER_SIZE && bytes[0].toInt() == FORMAT_VERSION)
            val ivLength = bytes[1].toInt() and 0xff
            require(ivLength > 0 && bytes.size > HEADER_SIZE + ivLength)
            return Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, existingWrappingKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + ivLength)))
                doFinal(bytes.copyOfRange(HEADER_SIZE + ivLength, bytes.size))
            }
        } catch (_: java.security.GeneralSecurityException) {
            throw DatabaseKeyRecoveryRequiredException()
        }
    }

    private fun wrappingKey(): SecretKey = (keyStore.getKey(keyAlias, null) as? SecretKey) ?: KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        .run {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }

    private fun existingWrappingKey(): SecretKey = keyStore.getKey(keyAlias, null) as? SecretKey
        ?: throw DatabaseKeyRecoveryRequiredException()

    private val keyStore: KeyStore get() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private val keyAlias get() = "app.glance.wallet.db.$storageName"
    private val wrappedKeyFile get() = File(context.noBackupFilesDir, "$storageName.key")

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DATABASE_KEY_LENGTH = 32
        const val FORMAT_VERSION = 1
        const val HEADER_SIZE = 2
        const val GCM_TAG_LENGTH_BITS = 128
    }
}

/** The encrypted database exists but its device-bound wrapping key cannot be recovered. */
class DatabaseKeyRecoveryRequiredException : IllegalStateException("Database key recovery is required.")
