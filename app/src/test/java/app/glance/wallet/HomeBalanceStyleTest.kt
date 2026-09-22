package app.glance.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeBalanceStyleTest {
    @Test
    fun `bitcoin glyph uses the mandarin accent`() {
        assertEquals(GlanceMandarin, bitcoinGlyphColor)
    }

    @Test
    fun `bitcoin glyph is slightly smaller than the inline balance text`() {
        assertEquals(0.85f, bitcoinGlyphTextScale)
    }
}
