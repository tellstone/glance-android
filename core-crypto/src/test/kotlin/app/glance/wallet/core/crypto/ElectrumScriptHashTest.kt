package app.glance.wallet.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class ElectrumScriptHashTest {
    @Test
    fun `computes reversed sha256 of a mainnet P2PKH output script`() {
        assertEquals(
            "ce9302be003e28b6a7b711c4694263d88bfacf576fed1c663149b75b00016e3b",
            electrumScriptHash("1BoatSLRHtKNngkdXEeobR76b53LETtpyT"),
        )
    }
}
