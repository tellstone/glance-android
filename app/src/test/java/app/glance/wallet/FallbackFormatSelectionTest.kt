package app.glance.wallet

import app.glance.wallet.core.data.db.ScriptType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackFormatSelectionTest {
    @Test
    fun `no match requires a fresh selection before it can be confirmed`() {
        val selection = FallbackFormatSelection(ScriptType.entries.toList())

        assertFalse(selection.canConfirm)
        assertNull(selection.selected)
    }

    @Test
    fun `multiple formats excluding native SegWit require a displayed selection`() {
        val selection = FallbackFormatSelection(listOf(ScriptType.LEGACY, ScriptType.TAPROOT))

        assertFalse(selection.canConfirm)
        assertFalse(selection.select(ScriptType.NATIVE_SEGWIT).canConfirm)
        assertEquals(ScriptType.TAPROOT, selection.select(ScriptType.TAPROOT).selected)
        assertTrue(selection.select(ScriptType.TAPROOT).canConfirm)
    }

    @Test
    fun `confirmation uses the dialog selection rather than an earlier expert default`() {
        val selection = FallbackFormatSelection(listOf(ScriptType.LEGACY, ScriptType.TAPROOT))
            .select(ScriptType.LEGACY)

        assertEquals(ScriptType.LEGACY, selection.selected)
    }
}
