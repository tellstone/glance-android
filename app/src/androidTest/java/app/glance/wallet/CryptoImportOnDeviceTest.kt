package app.glance.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.glance.wallet.core.crypto.ScriptType
import app.glance.wallet.core.crypto.parseWatchedKey
import fr.acinq.bitcoin.DeterministicWallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Ensures the APK includes the secp256k1 backend required by public-key imports and derivation. */
@RunWith(AndroidJUnit4::class)
class CryptoImportOnDeviceTest {
    @Test
    fun parsesAndDerivesFromAnExtendedPublicKey() {
        val account = DeterministicWallet.generate(ByteArray(32) { (it + 1).toByte() })
            .derivePrivateKey(
                listOf(
                    DeterministicWallet.hardened(84),
                    DeterministicWallet.hardened(0),
                    DeterministicWallet.hardened(0),
                ),
            )
            .extendedPublicKey
        val serialized = account.encode(DeterministicWallet.zpub)

        val imported = parseWatchedKey(serialized, ScriptType.NATIVE_SEGWIT)

        assertEquals(ScriptType.NATIVE_SEGWIT, imported.scriptType)
        assertTrue(imported.derive(chain = 0, index = 0).address.startsWith("bc1q"))
    }
}
