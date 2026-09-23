package app.glance.wallet.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuressWalletMaterialTest {
    @Test
    fun `generates a twelve word BIP84 decoy watch target from secure entropy`() {
        val material = DuressWalletMaterialGenerator { ByteArray(16) { it.toByte() } }.generate()

        assertEquals(12, material.mnemonic.split(' ').size)
        assertTrue(material.accountExtendedPublicKey.startsWith("zpub"))
        assertTrue(material.firstReceiveAddress.startsWith("bc1q"))
    }
}
