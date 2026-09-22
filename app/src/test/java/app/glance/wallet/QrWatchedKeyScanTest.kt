package app.glance.wallet

import fr.acinq.bitcoin.DeterministicWallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrWatchedKeyScanTest {
    @Test fun `scanner extracts a mainnet address from a BIP21 payload`() {
        assertEquals(
            "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu",
            singleAddressInputFromQr("bitcoin:bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu?amount=0.01&label=ignored"),
        )
    }

    @Test fun `scanner extracts clipboard-marked bare and BIP21 addresses`() {
        val address = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"

        assertEquals(address, singleAddressInputFromQr("\u200B$address\uFEFF"))
        assertEquals(address, singleAddressInputFromQr("bitcoin:\u200B$address\uFEFF?amount=0.01"))
    }

    @Test fun `scanner normalizes a supported extended-public-key payload`() {
        val xpub = generatedXpub()
        assertEquals(
            xpub,
            watchedKeyInputFromQr(" $xpub\n"),
        )
    }

    @Test fun `scanner accepts supported single sig descriptor forms`() {
        val xpub = generatedXpub()
        assertEquals(
            "tr($xpub/<0;1>/*)",
            watchedKeyInputFromQr("tr($xpub/<0;1>/*)"),
        )
        assertEquals(
            "wpkh($xpub/<0;1>/*)",
            watchedKeyInputFromQr("wpkh($xpub/<0;1>/*)"),
        )
        assertEquals(
            "wpkh([d34db33f/84h/0h/0h]$xpub/<0;1>/*)",
            watchedKeyInputFromQr("wpkh([d34db33f/84h/0h/0h]$xpub/<0;1>/*)"),
        )
    }

    @Test fun `scanner rejects non-watch-only QR payloads`() {
        assertNull(watchedKeyInputFromQr("bitcoin:bc1qexample"))
        assertNull(watchedKeyInputFromQr("seed words must never be imported"))
        assertNull(watchedKeyInputFromQr("wsh(xpubAbc123/<0;1>/*)"))
    }

    @Test fun `unified scanner accepts an address without choosing a mode`() {
        assertEquals(
            "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu",
            unifiedWatchTargetInputFromQr("bitcoin:bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu?label=ignored"),
        )
    }

    private fun generatedXpub(): String = DeterministicWallet.generate(ByteArray(32) { it.toByte() })
        .derivePrivateKey(
            listOf(
                DeterministicWallet.hardened(84),
                DeterministicWallet.hardened(0),
                DeterministicWallet.hardened(0),
            ),
        )
        .extendedPublicKey
        .encode(DeterministicWallet.xpub)
}
