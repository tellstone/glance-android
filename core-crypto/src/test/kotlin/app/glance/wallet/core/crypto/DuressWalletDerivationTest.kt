package app.glance.wallet.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuressWalletDerivationTest {
    @Test
    fun `derives a BIP84 watch target from a no-passphrase twelve-word mnemonic`() {
        val wallet = duressBip84Wallet(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        )

        assertEquals(ScriptType.NATIVE_SEGWIT, wallet.watchOnlyKey.scriptType)
        assertEquals(
            "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu",
            wallet.watchOnlyKey.derive(chain = 0, index = 0).address,
        )
        assertTrue(wallet.accountExtendedPublicKey.startsWith("zpub"))
    }
}
