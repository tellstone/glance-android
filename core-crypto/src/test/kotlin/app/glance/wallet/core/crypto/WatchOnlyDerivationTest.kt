package app.glance.wallet.core.crypto

import fr.acinq.bitcoin.Bech32
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.MnemonicCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchOnlyDerivationTest {

    @Test
    fun `validates standard mainnet single addresses without treating testnet as mainnet`() {
        assertEquals(
            "1BoatSLRHtKNngkdXEeobR76b53LETtpyT",
            parseSingleAddress(" 1BoatSLRHtKNngkdXEeobR76b53LETtpyT "),
        )
        assertEquals(
            "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa",
            parseSingleAddress("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"),
        )
        assertEquals(
            "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu",
            parseSingleAddress("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"),
        )
        assertEquals(
            "37VucYSaXLCAsxYyAPfbSi9eh4iEcbShgf",
            parseSingleAddress("37VucYSaXLCAsxYyAPfbSi9eh4iEcbShgf"),
        )
        assertEquals(
            "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr",
            parseSingleAddress("bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr"),
        )
        assertTrue(runCatching { parseSingleAddress("tb1qfm2hnh8k7l46n8f6xya8htv8e3ck7h4k7l3g3m") }.isFailure)
        assertTrue(runCatching { parseSingleAddress("not-a-bitcoin-address") }.isFailure)
    }

    @Test
    fun `classifies clipboard-marked bare and BIP21 addresses as addresses`() {
        val address = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"

        assertEquals(UnifiedImport.Address(address), classifyUnifiedImport("\u200B$address\uFEFF"))
        assertEquals(UnifiedImport.Address(address), classifyUnifiedImport("bitcoin:\u200B$address\uFEFF?amount=1"))
    }
    private val seed = MnemonicCode.toSeed(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        "",
    )

    @Test
    fun `derives the BIP44 receiving address from the official vector`() {
        assertEquals("1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA", derive(ScriptType.LEGACY))
    }

    @Test
    fun `derives the BIP49 receiving address from the official vector`() {
        assertEquals("37VucYSaXLCAsxYyAPfbSi9eh4iEcbShgf", derive(ScriptType.SEGWIT_COMPAT))
    }

    @Test
    fun `derives the BIP84 receiving address from the official vector`() {
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", derive(ScriptType.NATIVE_SEGWIT))
    }

    @Test
    fun `derives the BIP86 receiving address from the official vector`() {
        assertEquals("bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr", derive(ScriptType.TAPROOT))
    }

    @Test
    fun `accepts the published BIP32 xpub vector after clipboard line wrapping`() {
        val wrapped = """
            xpub6CUGRUonZSQ4TWtTMmzXdrXDtypWKiKrhko4egpiMZbpiaQL2jkwSB1icqYh2cfDfVxdx4df189oLKnC5fSwqPfgyP3hooxujYzAu3f
            ​DVmz
        """.trimIndent()

        val normalized = normalizeWatchedKeyInput(wrapped)
        val key = parseWatchedKey(wrapped, ScriptType.LEGACY)

        assertEquals("xpub6CUGRUonZSQ4TWtTMmzXdrXDtypWKiKrhko4egpiMZbpiaQL2jkwSB1icqYh2cfDfVxdx4df189oLKnC5fSwqPfgyP3hooxujYzAu3fDVmz", normalized)
        assertEquals(ScriptType.LEGACY, key.scriptType)
        assertEquals("1EfgV2Hr5CDjXPavHDpDMjmU33BA2veHy6", key.derive(chain = 0, index = 0).address)
    }

    @Test
    fun `parses a BIP86 tr descriptor with the standard external and internal chains`() {
        val account = DeterministicWallet.generate(seed).derivePrivateKey(
            listOf(
                DeterministicWallet.hardened(ScriptType.TAPROOT.purpose),
                DeterministicWallet.hardened(0),
                DeterministicWallet.hardened(0),
            ),
        ).extendedPublicKey
        val descriptor = "tr(${account.encode(DeterministicWallet.xpub)}/<0;1>/*)"

        val key = parseWatchedKey(descriptor)

        assertEquals(ScriptType.TAPROOT, key.scriptType)
        assertEquals("bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr", key.derive(0, 0).address)
    }

    @Test
    fun `classifies addresses prefixed keys descriptors and ambiguous xpubs for unified import`() {
        val account = DeterministicWallet.generate(seed).derivePrivateKey(
            listOf(
                DeterministicWallet.hardened(ScriptType.NATIVE_SEGWIT.purpose),
                DeterministicWallet.hardened(0),
                DeterministicWallet.hardened(0),
            ),
        ).extendedPublicKey
        val xpub = account.encode(DeterministicWallet.xpub)
        val zpub = account.encode(DeterministicWallet.zpub)

        assertEquals(
            UnifiedImport.Address("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"),
            classifyUnifiedImport("bitcoin:bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu?amount=1"),
        )
        assertEquals(UnifiedImport.AmbiguousXpub(xpub), classifyUnifiedImport(xpub))
        assertEquals(ScriptType.NATIVE_SEGWIT, (classifyUnifiedImport(zpub) as UnifiedImport.Key).scriptType)
        assertEquals(
            ScriptType.NATIVE_SEGWIT,
            (classifyUnifiedImport("wpkh($xpub/<0;1>/*)") as UnifiedImport.Key).scriptType,
        )
    }

    @Test
    fun `parses all supported single sig descriptors`() {
        val account = DeterministicWallet.generate(seed).derivePrivateKey(
            listOf(DeterministicWallet.hardened(84), DeterministicWallet.hardened(0), DeterministicWallet.hardened(0)),
        ).extendedPublicKey.encode(DeterministicWallet.xpub)

        assertEquals(ScriptType.LEGACY, parseWatchedKey("pkh($account/<0;1>/*)").scriptType)
        assertEquals(ScriptType.SEGWIT_COMPAT, parseWatchedKey("sh(wpkh($account/<0;1>/*))").scriptType)
        assertEquals(ScriptType.NATIVE_SEGWIT, parseWatchedKey("wpkh($account/<0;1>/*)").scriptType)
        assertEquals(ScriptType.TAPROOT, parseWatchedKey("tr($account/<0;1>/*)").scriptType)
    }

    @Test
    fun `accepts supported descriptors with origin metadata and a valid BIP380 checksum`() {
        val account = DeterministicWallet.generate(seed).derivePrivateKey(
            listOf(DeterministicWallet.hardened(84), DeterministicWallet.hardened(0), DeterministicWallet.hardened(0)),
        ).extendedPublicKey.encode(DeterministicWallet.xpub)
        val descriptor = "wpkh([d34db33f/84h/0h/0h]$account/<0;1>/*)"

        assertEquals("8lvh9jxk", descriptorChecksum("raw(51)"))
        assertEquals(ScriptType.NATIVE_SEGWIT, parseWatchedKey("$descriptor#${descriptorChecksum(descriptor)}").scriptType)
        assertTrue(runCatching { parseWatchedKey("$descriptor#aaaaaaaa") }.isFailure)
        assertTrue(runCatching { parseWatchedKey("wpkh([bad-origin]$account/<0;1>/*)") }.isFailure)
    }

    @Test
    fun `rejects descriptors outside the supported single key Taproot form`() {
        assertTrue(runCatching { parseWatchedKey("tr(not-a-key/<0;1>/*)") }.isFailure)
        assertTrue(runCatching { parseWatchedKey("wpkh(xpub/<0;1>/*)") }.isFailure)
        assertTrue(runCatching { parseWatchedKey("tr(xpub/*)") }.isFailure)
    }

    @Test
    fun `rejects descriptors with unbalanced nested wrapper parentheses`() {
        val account = DeterministicWallet.generate(seed).derivePrivateKey(
            listOf(DeterministicWallet.hardened(49), DeterministicWallet.hardened(0), DeterministicWallet.hardened(0)),
        ).extendedPublicKey.encode(DeterministicWallet.xpub)

        assertTrue(runCatching { parseWatchedKey("sh(wpkh($account/<0;1>/*)") }.isFailure)
        assertTrue(runCatching { parseWatchedKey("sh(wpkh($account/<0;1>/*)))") }.isFailure)
    }

    @Test
    fun `rejects hardened children from a watch-only key`() {
        val key = watchOnlyKey(ScriptType.NATIVE_SEGWIT)

        val result = runCatching { key.derive(chain = 0, index = 0x80000000L) }

        assertTrue(result.isFailure)
    }

    @Test
    fun `rejects a script type that conflicts with the extended public key version`() {
        val account = DeterministicWallet.generate(seed).derivePrivateKey(
            listOf(
                DeterministicWallet.hardened(ScriptType.NATIVE_SEGWIT.purpose),
                DeterministicWallet.hardened(0),
                DeterministicWallet.hardened(0),
            ),
        ).extendedPublicKey

        val result = runCatching {
            WatchOnlyKey.fromSerialized(account.encode(DeterministicWallet.zpub), ScriptType.TAPROOT)
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun `accepts a native SegWit zpub with the native SegWit script type`() {
        val account = DeterministicWallet.generate(seed).derivePrivateKey(
            listOf(
                DeterministicWallet.hardened(ScriptType.NATIVE_SEGWIT.purpose),
                DeterministicWallet.hardened(0),
                DeterministicWallet.hardened(0),
            ),
        ).extendedPublicKey

        val key = WatchOnlyKey.fromSerialized(account.encode(DeterministicWallet.zpub), ScriptType.NATIVE_SEGWIT)

        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", key.derive(0, 0).address)
    }

    @Test
    fun `BIP32 public derivation matches the neutered private derivation`() {
        val master = DeterministicWallet.generate(hexToBytes("000102030405060708090a0b0c0d0e0f"))
        val publicParent = master.derivePrivateKey(DeterministicWallet.hardened(0)).extendedPublicKey

        val fromPublicKey = publicParent.derivePublicKey(1)
        val fromPrivateKey = master.derivePrivateKey(
            listOf(DeterministicWallet.hardened(0), 1),
        ).extendedPublicKey

        assertEquals(fromPrivateKey, fromPublicKey)
    }

    @Test
    fun `accepts BIP173 Bech32 and BIP350 Bech32m witness addresses`() {
        val bip173 = derive(ScriptType.NATIVE_SEGWIT)
        val bip350 = derive(ScriptType.TAPROOT)

        assertEquals(0, Bech32.decodeWitnessAddress(bip173).second.toInt())
        assertEquals(1, Bech32.decodeWitnessAddress(bip350).second.toInt())
    }

    @Test
    fun `rejects witness addresses with the wrong BIP350 checksum encoding`() {
        val program = ByteArray(20) { it.toByte() }
        val versionZeroWithBech32m = Bech32.encode(
            hrp = "bc",
            int5s = arrayOf(0.toByte()) + Bech32.eight2five(program),
            encoding = Bech32.Encoding.Bech32m,
        )
        val versionOneWithBech32 = Bech32.encode(
            hrp = "bc",
            int5s = arrayOf(1.toByte()) + Bech32.eight2five(program),
            encoding = Bech32.Encoding.Bech32,
        )

        assertTrue(runCatching { Bech32.decodeWitnessAddress(versionZeroWithBech32m) }.isFailure)
        assertTrue(runCatching { Bech32.decodeWitnessAddress(versionOneWithBech32) }.isFailure)
    }

    @Test
    fun `rejects malformed private and testnet extended key imports`() {
        val account = DeterministicWallet.generate(seed).derivePrivateKey(
            listOf(
                DeterministicWallet.hardened(ScriptType.LEGACY.purpose),
                DeterministicWallet.hardened(0),
                DeterministicWallet.hardened(0),
            ),
        )

        assertTrue(runCatching { WatchOnlyKey.fromSerialized("not-an-extended-key", ScriptType.LEGACY) }.isFailure)
        assertTrue(runCatching { WatchOnlyKey.fromSerialized(account.encode(DeterministicWallet.xprv), ScriptType.LEGACY) }.isFailure)
        assertTrue(runCatching { WatchOnlyKey.fromSerialized(account.extendedPublicKey.encode(true), ScriptType.LEGACY) }.isFailure)
    }

    private fun derive(scriptType: ScriptType): String =
        watchOnlyKey(scriptType).derive(chain = 0, index = 0).address

    private fun watchOnlyKey(scriptType: ScriptType): WatchOnlyKey {
        val account = DeterministicWallet.generate(seed).derivePrivateKey(
            listOf(
                DeterministicWallet.hardened(scriptType.purpose),
                DeterministicWallet.hardened(0),
                DeterministicWallet.hardened(0),
            ),
        ).extendedPublicKey
        return WatchOnlyKey.fromSerialized(account.encode(scriptType.extendedKeyVersion), scriptType)
    }

    private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
