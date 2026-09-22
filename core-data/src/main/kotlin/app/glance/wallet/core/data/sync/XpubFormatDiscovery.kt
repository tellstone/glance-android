package app.glance.wallet.core.data.sync

import app.glance.wallet.core.crypto.ScriptType
import app.glance.wallet.core.crypto.parseWatchedKey
import app.glance.wallet.core.network.ChainDataProvider

/** The deliberately non-persistent result of checking an otherwise ambiguous plain xpub. */
sealed interface XpubDiscoveryResult {
    data class Match(val scriptType: ScriptType) : XpubDiscoveryResult
    data class Multiple(val scriptTypes: Set<ScriptType>) : XpubDiscoveryResult
    data object NoMatch : XpubDiscoveryResult
}

/**
 * Checks the initial external and internal gap windows for every supported address encoding.
 * All candidates for a chain are batched in one status request. This does not write a watched
 * key or any derived address: import remains atomic once the UI has a safe resolution.
 */
class XpubFormatDiscovery(
    private val chainData: ChainDataProvider,
    private val config: SyncConfig = SyncConfig(),
) {
    fun discover(xpub: String): XpubDiscoveryResult {
        try {
            val keys = displayOrder.associateWith { parseWatchedKey(xpub, it) }
            val activeTypes = mutableSetOf<ScriptType>()
            listOf(0, 1).forEach { chain ->
                val candidates = keys.flatMap { (type, key) ->
                    (0 until config.gapLimit).map { index -> Candidate(type, key.derive(chain, index.toLong()).address) }
                }
                val statuses = chainData.fetchAddressStatuses(candidates.map { it.address })
                candidates.forEach { candidate ->
                    requireNotNull(statuses[candidate.address]) { "Missing address status" }
                        .takeIf { it.hasActivity }
                        ?.let { activeTypes += candidate.scriptType }
                }
            }
            return when (activeTypes.size) {
                0 -> XpubDiscoveryResult.NoMatch
                1 -> XpubDiscoveryResult.Match(activeTypes.single())
                else -> XpubDiscoveryResult.Multiple(activeTypes)
            }
        } finally {
            chainData.close()
        }
    }

    private data class Candidate(val scriptType: ScriptType, val address: String)

    companion object {
        /** Display priority only; it must never resolve an empty or multi-match key by itself. */
        val displayOrder = listOf(
            ScriptType.NATIVE_SEGWIT,
            ScriptType.LEGACY,
            ScriptType.TAPROOT,
            ScriptType.SEGWIT_COMPAT,
        )
    }
}
