package app.glance.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

class TorConnectionStyleTest {
    @Test
    fun connectionSheetUsesTheEstablishedDarkCardAndActionLanguage() {
        assertEquals(GlanceBackground, torConnectionSurfaceColor)
        assertEquals(GlanceCardShape, torConnectionCardShape)
        assertEquals(GlanceMandarin, torConnectionPrimaryActionColor)
        assertEquals(GlancePillShape, torConnectionActionShape)
    }
}
