package app.glance.wallet.core.security

data class RetryState(
    val failedAttempts: Int = 0,
    val nextAllowedAtMillis: Long = 0,
)

class AuthenticationPolicy(initialState: RetryState = RetryState()) {
    private var state = initialState

    fun restore(state: RetryState) {
        this.state = state
    }

    fun recordFailure(nowMillis: Long): RetryState {
        val failures = state.failedAttempts + 1
        val delayMillis = if (failures < FIRST_THROTTLED_ATTEMPT) 0 else {
            (FIRST_DELAY_MILLIS * (1L shl (failures - FIRST_THROTTLED_ATTEMPT).coerceAtMost(MAX_DELAY_SHIFT)))
                .coerceAtMost(MAX_DELAY_MILLIS)
        }
        return RetryState(failures, nowMillis + delayMillis).also { state = it }
    }

    fun recordSuccess(): RetryState = RetryState().also { state = it }

    companion object {
        private const val FIRST_THROTTLED_ATTEMPT = 5
        private const val FIRST_DELAY_MILLIS = 30_000L
        private const val MAX_DELAY_MILLIS = 300_000L
        private const val MAX_DELAY_SHIFT = 4
    }
}
