package app.glance.wallet.core.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

sealed interface AuthenticationState {
    data object Initializing : AuthenticationState
    data object SetupRequired : AuthenticationState
    data object Locked : AuthenticationState
    data object RecoveryRequired : AuthenticationState
    data object EraseIncomplete : AuthenticationState
    data class Throttled(val nextAllowedAtMillis: Long) : AuthenticationState
    data class Unlocked(val session: ProfileSession) : AuthenticationState
}

/** Android-owned Tor state is erased alongside encrypted profiles and preferences. */
fun interface TorStateCleaner {
    suspend fun clear()
}

/** Coordinates persisted PIN credentials and ensures only one profile is opened per unlock. */
class AuthenticationCoordinator(
    private val preferences: SecurityPreferencesStore,
    private val profiles: ProfileDatabaseManager,
    private val authenticator: PinAuthenticator = PinAuthenticator(),
    private val torStateCleaner: TorStateCleaner = TorStateCleaner { },
    private val eraseStateStore: EraseStateStore = InMemoryEraseStateStore(),
) {
    private val policy = AuthenticationPolicy()
    private val mutableState = MutableStateFlow<AuthenticationState>(AuthenticationState.Initializing)
    private val unlockLock = Any()
    private var unlockGeneration = 0L
    val state: StateFlow<AuthenticationState> = mutableState

    suspend fun initialize() {
        if (eraseStateStore.isPending()) {
            mutableState.value = AuthenticationState.EraseIncomplete
            return
        }
        preferences.removeLegacyCoinGeckoKey()
        val settings = preferences.data.first()
        policy.restore(settings.retryState)
        if (settings.duressRemovalPending) {
            profiles.deleteDecoy()
            preferences.update { current ->
                current.copy(
                    credentials = current.credentials?.copy(duressPinVerifier = null),
                    duressRemovalPending = false,
                )
            }
        }
        mutableState.value = if (settings.credentials == null) AuthenticationState.SetupRequired else AuthenticationState.Locked
    }

    /** Completes initial install with a real profile only; duress provisioning is Settings-only. */
    suspend fun configure(realPin: String) {
        check(mutableState.value == AuthenticationState.SetupRequired) { "PIN setup is not available." }
        val generation = beginUnlock()
        val credentials = authenticator.configure(realPin)
        preferences.update { it.copy(credentials = credentials, retryState = RetryState()) }
        unlockOrRequireRecovery(ProfileType.REAL, generation)
    }

    suspend fun unlockWithPin(pin: String, nowMillis: Long): AuthenticationState {
        if (eraseStateStore.isPending()) {
            return AuthenticationState.EraseIncomplete.also { mutableState.value = it }
        }
        val generation = beginUnlock()
        val settings = preferences.data.first()
        val credentials = settings.credentials ?: return publishIfCurrent(generation, AuthenticationState.SetupRequired)
        if (nowMillis < settings.retryState.nextAllowedAtMillis) {
            return publishIfCurrent(generation, AuthenticationState.Throttled(settings.retryState.nextAllowedAtMillis))
        }
        return authenticator.verify(pin, credentials)?.let { profile ->
            policy.recordSuccess()
            preferences.update { it.copy(retryState = RetryState()) }
            unlockOrRequireRecovery(profile, generation)
        } ?: policy.recordFailure(nowMillis).let { retry ->
            preferences.update { it.copy(retryState = retry) }
            publishIfCurrent(generation, AuthenticationState.Throttled(retry.nextAllowedAtMillis))
        }
    }

    /** Biometrics always opens the real profile; duress remains PIN-only by design. */
    suspend fun unlockWithBiometric(): AuthenticationState {
        if (eraseStateStore.isPending()) {
            return AuthenticationState.EraseIncomplete.also { mutableState.value = it }
        }
        val generation = beginUnlock()
        val settings = preferences.data.first()
        if (settings.credentials == null) {
            return publishIfCurrent(generation, AuthenticationState.SetupRequired)
        }
        if (!settings.biometricEnabled) return publishIfCurrent(generation, AuthenticationState.Locked)
        policy.recordSuccess()
        preferences.update { it.copy(retryState = RetryState()) }
        return unlockOrRequireRecovery(ProfileType.REAL, generation)
    }

    /** Decoy presentation data is configurable only while the real profile is open. */
    suspend fun configureDecoyBalance(sats: Long) {
        val session = (mutableState.value as? AuthenticationState.Unlocked)?.session
            ?: throw SecurityException("Unlock the real profile before configuring the decoy balance.")
        if (session.type != ProfileType.REAL) {
            throw SecurityException("The decoy profile cannot configure its displayed balance.")
        }
        profiles.configureDecoyBalance(sats)
    }

    suspend fun configureDuress(duressPin: String, fakeBalanceSats: Long) {
        require(fakeBalanceSats >= 0) { "Decoy balance must not be negative." }
        val session = (mutableState.value as? AuthenticationState.Unlocked)?.session
            ?: throw SecurityException("Unlock the real profile before configuring the duress profile.")
        if (session.type != ProfileType.REAL) {
            throw SecurityException("The decoy profile cannot configure itself.")
        }
        val current = preferences.data.first()
        val credentials = current.credentials ?: throw SecurityException("Real credentials are not configured.")
        check(credentials.duressPinVerifier == null) { "Duress profile is already configured." }
        require(authenticator.verify(duressPin, PinCredentials(credentials.realPinVerifier, null)) != ProfileType.REAL) {
            "Duress PIN must differ from real PIN."
        }
        val verifier = authenticator.createDuressVerifier(duressPin)
        // A previous interrupted attempt has no verifier and therefore must never be reused.
        profiles.deleteDecoy()
        provisionDecoyAtomically(
            provision = {
                profiles.configureDecoyBalance(fakeBalanceSats)
                preferences.update { it.copy(credentials = credentials.copy(duressPinVerifier = verifier)) }
            },
            rollback = profiles::deleteDecoy,
        )
    }

    suspend fun removeDuressProfile() {
        val session = (mutableState.value as? AuthenticationState.Unlocked)?.session
            ?: throw SecurityException("Unlock the real profile before removing the duress profile.")
        if (session.type != ProfileType.REAL) {
            throw SecurityException("Only the real profile can remove the duress profile.")
        }
        val current = preferences.data.first()
        if (current.credentials?.duressPinVerifier == null) return
        preferences.update { it.copy(duressRemovalPending = true) }
        profiles.deleteDecoy()
        preferences.update { it.copy(credentials = it.credentials?.copy(duressPinVerifier = null), duressRemovalPending = false) }
    }

    suspend fun currentDecoyBalance(): Long? =
        if ((mutableState.value as? AuthenticationState.Unlocked)?.session?.type == ProfileType.REAL) {
            profiles.currentDecoyBalance()
        } else null

    fun lock() {
        synchronized(unlockLock) {
            unlockGeneration++
            (mutableState.value as? AuthenticationState.Unlocked)?.session?.database?.close()
            if (mutableState.value != AuthenticationState.EraseIncomplete) {
                mutableState.value = AuthenticationState.Locked
            }
        }
    }

    suspend fun eraseAllData() {
        synchronized(unlockLock) {
            unlockGeneration++
            (mutableState.value as? AuthenticationState.Unlocked)?.session?.database?.close()
            mutableState.value = AuthenticationState.Initializing
        }
        try {
            eraseStateStore.markPending()
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            mutableState.value = AuthenticationState.EraseIncomplete
            return
        }
        val failures = mutableListOf<Throwable>()
        suspend fun attempt(block: suspend () -> Unit) {
            try {
                block()
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                failures += failure
            }
        }
        attempt { profiles.deleteAll() }
        attempt { preferences.wipe() }
        attempt { torStateCleaner.clear() }
        if (failures.isEmpty()) attempt { eraseStateStore.clear() }
        mutableState.value = if (failures.isEmpty()) AuthenticationState.SetupRequired else AuthenticationState.EraseIncomplete
    }

    private fun beginUnlock(): Long = synchronized(unlockLock) { ++unlockGeneration }

    private fun publishIfCurrent(generation: Long, nextState: AuthenticationState): AuthenticationState = synchronized(unlockLock) {
        if (generation == unlockGeneration) {
            mutableState.value = nextState
            nextState
        } else {
            mutableState.value
        }
    }

    private suspend fun unlock(profile: ProfileType, generation: Long): AuthenticationState {
        val previousSession = synchronized(unlockLock) {
            if (generation != unlockGeneration) return mutableState.value
            (mutableState.value as? AuthenticationState.Unlocked)?.session
        }
        previousSession?.database?.close()
        if (synchronized(unlockLock) { generation != unlockGeneration }) return state.value

        val openedSession = profiles.open(profile)
        return synchronized(unlockLock) {
            if (generation != unlockGeneration) {
                openedSession.database.close()
                mutableState.value
            } else {
                AuthenticationState.Unlocked(openedSession).also { mutableState.value = it }
            }
        }
    }

    private suspend fun unlockOrRequireRecovery(profile: ProfileType, generation: Long): AuthenticationState = try {
        unlock(profile, generation)
    } catch (_: DatabaseKeyRecoveryRequiredException) {
        publishIfCurrent(generation, AuthenticationState.RecoveryRequired)
    }
}

/** Deletes an uncommitted decoy store if its verifier could not be persisted. */
internal suspend fun <T> provisionDecoyAtomically(
    provision: suspend () -> T,
    rollback: () -> Unit,
): T {
    var committed = false
    try {
        return provision().also { committed = true }
    } finally {
        if (!committed) rollback()
    }
}
