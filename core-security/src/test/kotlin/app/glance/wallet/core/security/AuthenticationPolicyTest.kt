package app.glance.wallet.core.security

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthenticationPolicyTest {
    @Test
    fun `fifth failed attempt applies the first retry delay`() {
        val policy = AuthenticationPolicy()

        repeat(4) { policy.recordFailure(nowMillis = 1_000L) }
        val result = policy.recordFailure(nowMillis = 1_000L)

        assertEquals(31_000L, result.nextAllowedAtMillis)
    }

    @Test
    fun `a success clears retry state`() {
        val policy = AuthenticationPolicy()
        repeat(5) { policy.recordFailure(nowMillis = 1_000L) }

        assertEquals(RetryState(), policy.recordSuccess())
    }

    @Test
    fun `restored failures continue toward the retry delay`() {
        val policy = AuthenticationPolicy(RetryState(failedAttempts = 4))

        assertEquals(RetryState(failedAttempts = 5, nextAllowedAtMillis = 31_000L), policy.recordFailure(1_000L))
    }

    @Test
    fun `very high failure counts retain the maximum delay`() {
        val policy = AuthenticationPolicy(RetryState(failedAttempts = 67))

        val result = policy.recordFailure(nowMillis = 1_000L)

        assertEquals(68, result.failedAttempts)
        assertEquals(301_000L, result.nextAllowedAtMillis)
    }
}
