package app.glance.wallet.core.security

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinAuthenticatorTest {
    @Test
    fun `initial setup creates only a real pin verifier`() = runBlocking {
        val authenticator = PinAuthenticator(PinHasher(testSaltSource()))

        val credentials = authenticator.configure(realPin = "123456")

        assertEquals(ProfileType.REAL, authenticator.verify("123456", credentials))
        assertEquals(null, credentials.duressPinVerifier)
    }

    @Test
    fun `a configured real pin unlocks the real profile`() = runBlocking {
        val authenticator = PinAuthenticator(PinHasher(testSaltSource()))
        val credentials = authenticator.configure(realPin = "123456")

        assertEquals(ProfileType.REAL, authenticator.verify("123456", credentials))
    }

    @Test
    fun `a configured duress pin unlocks only the decoy profile`() = runBlocking {
        val authenticator = PinAuthenticator(PinHasher(testSaltSource()))
        val realCredentials = authenticator.configure(realPin = "123456")
        val credentials = realCredentials.copy(duressPinVerifier = authenticator.createDuressVerifier("654321"))

        assertEquals(ProfileType.DECOY, authenticator.verify("654321", credentials))
    }

    @Test
    fun `a new duress verifier can be created independently of the real verifier`() = runBlocking {
        val authenticator = PinAuthenticator(PinHasher(testSaltSource()))

        val verifier = authenticator.createDuressVerifier("654321")

        assertTrue(authenticator.matches("654321", verifier))
        assertFalse(authenticator.matches("123456", verifier))
    }

    @Test
    fun `pin verifiers are salted and do not retain plaintext`() = runBlocking {
        val authenticator = PinAuthenticator(PinHasher(testSaltSource()))
        val first = authenticator.configure(realPin = "123456")
        val second = authenticator.configure(realPin = "123456")

        assertFalse(first.realPinVerifier.encoded.contentEquals(second.realPinVerifier.encoded))
        assertFalse(first.realPinVerifier.encoded.decodeToString().contains("123456"))
        assertTrue(authenticator.matches("123456", first.realPinVerifier))
        assertFalse(authenticator.matches("654321", first.realPinVerifier))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `pins must be exactly six digits`() {
        runBlocking {
            PinAuthenticator(PinHasher(testSaltSource())).configure(realPin = "12345")
        }
    }

    private fun testSaltSource(): () -> ByteArray {
        var next = 0
        return { ByteArray(16) { (next++).toByte() } }
    }
}
