package app.glance.wallet.core.security

import androidx.biometric.BiometricManager

/** Platform-independent eligibility check; the app supplies BiometricPrompt UI in its host activity. */
class BiometricUnlocker(private val biometricManager: BiometricManager) {
    fun isAvailable(): Boolean = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
        BiometricManager.BIOMETRIC_SUCCESS
}
