package app.glance.wallet.core.security

import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

enum class ProfileType { REAL, DECOY }

data class PinVerifier(val encoded: ByteArray) {
    init {
        require(encoded.size == SALT_LENGTH + HASH_LENGTH)
    }

    companion object {
        const val SALT_LENGTH = 16
        const val HASH_LENGTH = 32
    }
}

data class PinCredentials(
    val realPinVerifier: PinVerifier,
    val duressPinVerifier: PinVerifier?,
)

/** Derives non-reversible PIN verifiers without retaining cleartext PIN material. */
open class PinHasher(
    private val saltSource: () -> ByteArray = { java.security.SecureRandom().generateSeed(PinVerifier.SALT_LENGTH) },
) {
    open fun create(pin: String): PinVerifier {
        val salt = saltSource()
        require(salt.size == PinVerifier.SALT_LENGTH)
        return PinVerifier(salt + derive(pin.encodeToByteArray(), salt))
    }

    open fun matches(pin: String, verifier: PinVerifier): Boolean {
        val salt = verifier.encoded.copyOfRange(0, PinVerifier.SALT_LENGTH)
        val expected = verifier.encoded.copyOfRange(PinVerifier.SALT_LENGTH, verifier.encoded.size)
        return MessageDigest.isEqual(expected, derive(pin.encodeToByteArray(), salt))
    }

    private fun derive(pin: ByteArray, salt: ByteArray): ByteArray {
        val characters = pin.decodeToString().toCharArray()
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(characters, salt, ITERATIONS, PinVerifier.HASH_LENGTH * 8))
                .encoded
        } finally {
            characters.fill('\u0000')
        }
    }

    private companion object {
        const val ITERATIONS = 600_000
    }
}

class PinAuthenticator(
    private val hasher: PinHasher = PinHasher(),
    private val derivationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun createDuressVerifier(duressPin: String): PinVerifier {
        validate(duressPin)
        return kotlinx.coroutines.withContext(derivationDispatcher) { hasher.create(duressPin) }
    }

    /** Initial setup creates only the real profile credential; duress is provisioned from Settings. */
    suspend fun configure(realPin: String): PinCredentials {
        validate(realPin)
        val real = kotlinx.coroutines.withContext(derivationDispatcher) { hasher.create(realPin) }
        return PinCredentials(realPinVerifier = real, duressPinVerifier = null)
    }

    /** Always completes both checks when a duress verifier is configured to avoid a match-order timing signal. */
    suspend fun verify(pin: String, credentials: PinCredentials): ProfileType? = coroutineScope {
        val real = async(derivationDispatcher) { hasher.matches(pin, credentials.realPinVerifier) }
        val duress = credentials.duressPinVerifier?.let { verifier ->
            async(derivationDispatcher) { hasher.matches(pin, verifier) }
        }
        val realMatches = real.await()
        val duressMatches = duress?.await()
        when {
            realMatches -> ProfileType.REAL
            duressMatches == true -> ProfileType.DECOY
            else -> null
        }
    }

    fun matches(pin: String, verifier: PinVerifier): Boolean = hasher.matches(pin, verifier)

    private fun validate(pin: String) {
        require(pin.length == PIN_LENGTH && pin.all(Char::isDigit)) { "PIN must be exactly six digits" }
    }

    private companion object {
        const val PIN_LENGTH = 6
    }
}
