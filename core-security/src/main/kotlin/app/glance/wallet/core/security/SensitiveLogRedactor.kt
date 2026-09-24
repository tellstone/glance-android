package app.glance.wallet.core.security

import timber.log.Timber

object SensitiveLogRedactor {
    private val extendedKey = Regex("\\b(?:xpub|ypub|zpub|tpub|upub|vpub)[1-9A-HJ-NP-Za-km-z]{20,}\\b")
    private val bech32Address = Regex("\\b(?:bc1|tb1)[ac-hj-np-z02-9]{11,90}\\b", RegexOption.IGNORE_CASE)
    private val legacyAddress = Regex("\\b[13][1-9A-HJ-NP-Za-km-z]{25,34}\\b")
    private val txid = Regex("\\b[a-fA-F0-9]{64}\\b")

    fun redact(value: String): String = value
        .replace(extendedKey, "[redacted-xpub]")
        .replace(bech32Address, "[redacted-address]")
        .replace(legacyAddress, "[redacted-address]")
        .replace(txid, "[redacted-txid]")
}

/** Debug-only tree; release builds deliberately do not plant a logging tree. */
class RedactingDebugTree : Timber.DebugTree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val safeMessage = SensitiveLogRedactor.redact(message)
        val safeThrowable = t?.let { IllegalStateException(SensitiveLogRedactor.redact(it.message.orEmpty())) }
        super.log(priority, tag, safeMessage, safeThrowable)
    }
}

object SecurityLogging {
    /** Installs the only permitted debug logger; release builds deliberately install none. */
    fun plantRedactingDebugTree() {
        Timber.plant(RedactingDebugTree())
    }
}
