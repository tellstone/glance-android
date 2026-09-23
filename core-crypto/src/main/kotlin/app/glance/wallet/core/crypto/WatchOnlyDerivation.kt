package app.glance.wallet.core.crypto

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.io.ByteArrayOutput

/** Mainnet single-sig address types supported by Glance. */
enum class ScriptType(
    internal val purpose: Long,
    internal val extendedKeyVersion: Int,
) {
    LEGACY(44, DeterministicWallet.xpub),
    SEGWIT_COMPAT(49, DeterministicWallet.ypub),
    NATIVE_SEGWIT(84, DeterministicWallet.zpub),
    TAPROOT(86, DeterministicWallet.xpub),
}

data class DerivedAddress(
    val chain: Int,
    val index: Long,
    val address: String,
)

/**
 * The sole private-key-adjacent primitive in Glance: it converts the isolated duress profile's
 * generated BIP39 mnemonic into the public BIP84 account that the regular watch-only sync engine
 * consumes. It does not expose private keys or signing operations.
 */
data class DuressBip84Wallet(
    val accountExtendedPublicKey: String,
    val watchOnlyKey: WatchOnlyKey,
)

fun duressBip84WalletFromEntropy(entropy: ByteArray): Pair<String, DuressBip84Wallet> {
    require(entropy.size == BIP39_TWELVE_WORD_ENTROPY_BYTES) { "Duress entropy must be 128 bits" }
    val mnemonic = MnemonicCode.toMnemonics(entropy).joinToString(" ")
    return mnemonic to duressBip84Wallet(mnemonic)
}

fun duressBip84Wallet(mnemonic: String): DuressBip84Wallet {
    val normalizedMnemonic = mnemonic.trim().lowercase()
    require(normalizedMnemonic.split(Regex("\\s+")).size == BIP39_TWELVE_WORD_COUNT) {
        "Duress mnemonic must contain twelve words"
    }
    MnemonicCode.validate(normalizedMnemonic)
    val seed = MnemonicCode.toSeed(normalizedMnemonic, "")
    val account = DeterministicWallet.generate(seed).derivePrivateKey(
        listOf(
            DeterministicWallet.hardened(ScriptType.NATIVE_SEGWIT.purpose),
            DeterministicWallet.hardened(0),
            DeterministicWallet.hardened(0),
        ),
    ).extendedPublicKey
    val encoded = account.encode(DeterministicWallet.zpub)
    return DuressBip84Wallet(encoded, WatchOnlyKey.fromSerialized(encoded, ScriptType.NATIVE_SEGWIT))
}

private const val BIP39_TWELVE_WORD_COUNT = 12
private const val BIP39_TWELVE_WORD_ENTROPY_BYTES = 16

/**
 * Parses an Add Watched Key import.
 *
 * Plain xpubs are intentionally ambiguous; callers must either supply a script type or use
 * [classifyUnifiedImport] and its discovery flow. Supported single-sig descriptors are always
 * unambiguous.
 */
fun parseWatchedKey(input: String, scriptType: ScriptType? = null): WatchOnlyKey {
    val normalizedInput = normalizeWatchedKeyInput(input)
    val descriptor = supportedDescriptorOrNull(normalizedInput)
    if (descriptor != null) {
        val (serializedKey, descriptorType) = descriptor
        require(scriptType == null || scriptType == descriptorType) {
            "Output descriptor conflicts with selected script type"
        }
        return WatchOnlyKey.fromSerialized(serializedKey, descriptorType)
    }

    require(!normalizedInput.contains('(')) { "Unsupported output descriptor" }
    requireNotNull(scriptType) { "A script type is required for a plain extended public key" }
    return WatchOnlyKey.fromSerialized(normalizedInput, scriptType)
}

/**
 * Produces the canonical import value used for parsing and persistence.
 *
 * Extended public keys are Base58Check strings, so whitespace and Unicode formatting markers can
 * never be meaningful. Removing copy/paste separators allows an otherwise valid key copied from a
 * wrapped source to be imported and subsequently re-parsed by the sync engine exactly as it was
 * initially validated.
 */
fun normalizeWatchedKeyInput(input: String): String = input.filterNot {
    it.isWhitespace() || it in COPY_PASTE_FORMAT_MARKERS
}

/** Result of locally classifying the one-field Add Watch Target input. */
sealed interface UnifiedImport {
    data class Address(val address: String) : UnifiedImport
    data class Key(val source: String, val scriptType: ScriptType) : UnifiedImport
    data class AmbiguousXpub(val source: String) : UnifiedImport
}

/**
 * Classifies only unambiguous input without network access. A bare xpub deliberately remains
 * unresolved because its version bytes do not encode a BIP purpose.
 */
fun classifyUnifiedImport(input: String): UnifiedImport {
    val normalized = normalizeWatchedKeyInput(input)
    val addressCandidate = if (normalized.startsWith("bitcoin:", ignoreCase = true)) {
        normalized.substringAfter(':').substringBefore('?')
    } else normalized
    runCatching { parseSingleAddress(addressCandidate) }.getOrNull()?.let { return UnifiedImport.Address(it) }

    supportedDescriptorOrNull(normalized)?.let { descriptor ->
        return UnifiedImport.Key(normalized, descriptor.scriptType)
    }
    require(!normalized.contains('(')) { "Unsupported output descriptor" }
    return when {
        normalized.startsWith("ypub") -> UnifiedImport.Key(normalized, WatchOnlyKey.fromSerialized(normalized, ScriptType.SEGWIT_COMPAT).scriptType)
        normalized.startsWith("zpub") -> UnifiedImport.Key(normalized, WatchOnlyKey.fromSerialized(normalized, ScriptType.NATIVE_SEGWIT).scriptType)
        normalized.startsWith("xpub") -> {
            WatchOnlyKey.fromSerialized(normalized, ScriptType.LEGACY)
            UnifiedImport.AmbiguousXpub(normalized)
        }
        else -> throw IllegalArgumentException("Invalid extended public key")
    }
}

/**
 * Validates and normalizes a mainnet Bitcoin address for fixed-address tracking.
 *
 * No script-family restriction is applied: a fixed address has no derivation path, so all
 * address forms understood by the Bitcoin primitive are valid watch-only inputs.  Address text
 * is intentionally retained verbatim (apart from surrounding whitespace) because Base58 is
 * case-sensitive; parsing against the mainnet genesis block rejects test networks and bad
 * checksums.
 */
fun parseSingleAddress(input: String): String {
    val normalized = input.trim()
    require(normalized.isNotEmpty()) { "A Bitcoin address is required" }
    Bitcoin.addressToPublicKeyScript(Block.LivenetGenesisBlock.hash, normalized)
        .fold(
            { throw IllegalArgumentException("Invalid mainnet Bitcoin address") },
            { Unit },
        )
    return normalized
}

private val COPY_PASTE_FORMAT_MARKERS = setOf(
    '\u200B', // zero-width space
    '\u200C', // zero-width non-joiner
    '\u200D', // zero-width joiner
    '\u2060', // word joiner
    '\uFEFF', // byte-order mark / zero-width no-break space
)

private val DESCRIPTOR = Regex(
    """(?:pkh\($ORIGIN([^()/\s#]+)/<0;1>/\*\)|sh\(wpkh\($ORIGIN([^()/\s#]+)/<0;1>/\*\)\)|wpkh\($ORIGIN([^()/\s#]+)/<0;1>/\*\)|tr\($ORIGIN([^()/\s#]+)/<0;1>/\*\))""",
)

private const val ORIGIN = "(?:\\[[0-9a-fA-F]{8}(?:/(?:0|[1-9][0-9]*)(?:['hH])?)*\\])?"

private data class SupportedDescriptor(val serializedKey: String, val scriptType: ScriptType)

private fun supportedDescriptorOrNull(input: String): SupportedDescriptor? {
    val checksumIndex = input.indexOf('#')
    val body = if (checksumIndex >= 0) input.substring(0, checksumIndex) else input
    if (checksumIndex >= 0) {
        val checksum = input.substring(checksumIndex + 1)
        if (input.indexOf('#', checksumIndex + 1) >= 0 || checksum != descriptorChecksum(body)) return null
    }
    return DESCRIPTOR.matchEntire(body)?.descriptorKeyAndType()
}

private fun MatchResult.descriptorKeyAndType(): SupportedDescriptor =
    when {
        groupValues[1].isNotEmpty() -> SupportedDescriptor(groupValues[1], ScriptType.LEGACY)
        groupValues[2].isNotEmpty() -> SupportedDescriptor(groupValues[2], ScriptType.SEGWIT_COMPAT)
        groupValues[3].isNotEmpty() -> SupportedDescriptor(groupValues[3], ScriptType.NATIVE_SEGWIT)
        else -> SupportedDescriptor(groupValues[4], ScriptType.TAPROOT)
    }

/** BIP380 checksum for standard descriptor text. */
internal fun descriptorChecksum(descriptor: String): String {
    var checksum = 1L
    var classes = 0
    var classCount = 0
    descriptor.forEach { character ->
        val position = DESCRIPTOR_INPUT_CHARSET.indexOf(character)
        require(position >= 0) { "Invalid descriptor character" }
        checksum = descriptorPolymod(checksum, position and 31)
        classes = classes * 3 + (position shr 5)
        classCount++
        if (classCount == 3) {
            checksum = descriptorPolymod(checksum, classes)
            classes = 0
            classCount = 0
        }
    }
    if (classCount > 0) checksum = descriptorPolymod(checksum, classes)
    repeat(8) { checksum = descriptorPolymod(checksum, 0) }
    checksum = checksum xor 1
    return buildString(8) {
        for (index in 0 until 8) append(DESCRIPTOR_CHECKSUM_CHARSET[((checksum shr (5 * (7 - index))) and 31).toInt()])
    }
}

private fun descriptorPolymod(checksum: Long, value: Int): Long {
    val top = checksum shr 35
    var result = ((checksum and 0x7ffffffffL) shl 5) xor value.toLong()
    DESCRIPTOR_CHECKSUM_GENERATORS.forEachIndexed { index, generator ->
        if (((top shr index) and 1L) != 0L) result = result xor generator
    }
    return result
}

private const val DESCRIPTOR_INPUT_CHARSET = "0123456789()[],'/*abcdefgh@:$%{}IJKLMNOPQRSTUVWXYZ&+-.;<=>?!^_|~ijklmnopqrstuvwxyzABCDEFGH`#\"\\ "
private const val DESCRIPTOR_CHECKSUM_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
private val DESCRIPTOR_CHECKSUM_GENERATORS = longArrayOf(
    0xf5dee51989L, 0xa9fdca3312L, 0x1bab10e32dL, 0x3706b1677aL, 0x644d626ffdL,
)

/** Returns the Electrum scripthash for a supported mainnet address. */
fun electrumScriptHash(address: String): String {
    val script = Bitcoin.addressToPublicKeyScript(Block.LivenetGenesisBlock.hash, address)
        .fold(
            { throw IllegalArgumentException("Invalid mainnet address") },
            { it },
        )
    val output = ByteArrayOutput()
    Script.write(script, output)
    return Crypto.sha256(output.toByteArray())
        .reversedArray()
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

/**
 * A validated extended public key that can only derive non-hardened receiving or change children.
 *
 * This type intentionally never exposes a private key API.
 */
class WatchOnlyKey private constructor(
    private val extendedPublicKey: DeterministicWallet.ExtendedPublicKey,
    val scriptType: ScriptType,
) {
    fun derive(chain: Int, index: Long): DerivedAddress {
        require(chain == EXTERNAL_CHAIN || chain == INTERNAL_CHAIN) { "chain must be external (0) or internal (1)" }
        require(index in 0 until HARDENED_KEY_INDEX) { "watch-only derivation index must be non-hardened" }

        val child = extendedPublicKey.derivePublicKey(listOf(chain.toLong(), index)).publicKey
        val address = when (scriptType) {
            ScriptType.LEGACY -> Bitcoin.computeBIP44Address(child, Block.LivenetGenesisBlock.hash)
            ScriptType.SEGWIT_COMPAT -> Bitcoin.computeBIP49Address(child, Block.LivenetGenesisBlock.hash)
            ScriptType.NATIVE_SEGWIT -> Bitcoin.computeBIP84Address(child, Block.LivenetGenesisBlock.hash)
            ScriptType.TAPROOT -> Bitcoin.computeBIP86Address(child, Block.LivenetGenesisBlock.hash)
        }
        return DerivedAddress(chain, index, address)
    }

    companion object {
        private const val EXTERNAL_CHAIN = 0
        private const val INTERNAL_CHAIN = 1
        private const val HARDENED_KEY_INDEX = 0x80000000L

        fun fromSerialized(serialized: String, scriptType: ScriptType): WatchOnlyKey {
            val (version, key) = try {
                DeterministicWallet.ExtendedPublicKey.decode(serialized)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid extended public key")
            }
            require(version in supportedVersions) { "Only mainnet extended public keys are supported" }
            require(isCompatible(version, scriptType)) { "Extended public key version does not match script type" }
            return WatchOnlyKey(key, scriptType)
        }

        private val supportedVersions = setOf(
            DeterministicWallet.xpub,
            DeterministicWallet.ypub,
            DeterministicWallet.zpub,
        )

        private fun isCompatible(version: Int, scriptType: ScriptType): Boolean = when (version) {
            // Version bytes name an export convention, not a cryptographic restriction. Some
            // wallets serialize BIP49/84 account keys as xpub, so discovery and Expert mode must
            // be able to derive every supported single-sig address type from one.
            DeterministicWallet.xpub -> true
            DeterministicWallet.ypub -> scriptType == ScriptType.SEGWIT_COMPAT
            DeterministicWallet.zpub -> scriptType == ScriptType.NATIVE_SEGWIT
            else -> false
        }
    }
}
