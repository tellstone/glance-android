package app.glance.wallet

import app.glance.wallet.core.crypto.ScriptType
import app.glance.wallet.core.data.db.ScriptType as DataScriptType
import app.glance.wallet.core.data.sync.XpubDiscoveryResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddWatchTargetImportTest {
    @Test
    fun `Tor discovery failure keeps its actionable message`() {
        assertEquals(
            "Connect to Tor before checking this xpub's address format.",
            importErrorMessage(TorDiscoveryUnavailableException()),
        )
    }

    @Test
    fun `prefix mismatch explains that xpub supports every format`() {
        assertEquals(
            "This key is valid, but its prefix does not match the selected address format. A plain xpub can use any supported format; ypub is only Nested SegWit and zpub is only Native SegWit.",
            importErrorMessage(IllegalArgumentException("Extended public key version does not match script type")),
        )
    }

    @Test
    fun `multiple discovery imports every active format without a choice dialog`() {
        assertEquals(
            listOf(DataScriptType.LEGACY, DataScriptType.NATIVE_SEGWIT),
            discoveredFormatsToImport(
                XpubDiscoveryResult.Multiple(setOf(ScriptType.NATIVE_SEGWIT, ScriptType.LEGACY)),
            ),
        )
        assertNull(formatChoiceDialogCopyOrNull(XpubDiscoveryResult.Multiple(ScriptType.entries.toSet())))
    }

    @Test
    fun `no match explains why every format is available`() {
        val copy = requireNotNull(formatChoiceDialogCopyOrNull(XpubDiscoveryResult.NoMatch))

        assertEquals("Choose address format", copy.title)
        assertEquals("This xpub has no activity yet, so its format cannot be detected.", copy.message)
    }
}
