package app.glance.wallet.core.security

import app.glance.wallet.core.crypto.duressBip84WalletFromEntropy
import java.security.SecureRandom

data class DuressWalletMaterial(
    val mnemonic: String,
    val accountExtendedPublicKey: String,
    val firstReceiveAddress: String,
)

/** Generates the only mnemonic Glance may ever create: the isolated duress wallet's decoy seed. */
class DuressWalletMaterialGenerator(
    private val entropy: () -> ByteArray = {
        ByteArray(BIP39_TWELVE_WORD_ENTROPY_BYTES).also(SecureRandom()::nextBytes)
    },
) {
    fun generate(): DuressWalletMaterial {
        val (mnemonic, wallet) = duressBip84WalletFromEntropy(entropy())
        return DuressWalletMaterial(
            mnemonic = mnemonic,
            accountExtendedPublicKey = wallet.accountExtendedPublicKey,
            firstReceiveAddress = wallet.watchOnlyKey.derive(chain = 0, index = 0).address,
        )
    }

    private companion object { const val BIP39_TWELVE_WORD_ENTROPY_BYTES = 16 }
}
