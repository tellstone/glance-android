package app.glance.wallet

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchTargetStyleTest {
    @Test
    fun addTargetAndWalletManagementUseTheFilledDarkFormLanguage() {
        assertEquals(GlanceSurface, watchTargetFieldColor)
        assertEquals(GlanceCardShape, watchTargetFieldShape)
        assertEquals(GlanceMandarin, watchTargetPrimaryActionColor)
        assertEquals(GlancePillShape, watchTargetPrimaryActionShape)
        assertEquals(GlanceSurface, walletSettingsDialogColor)
    }

    @Test
    fun walletManagementReservesWarningForTheDestructiveAction() {
        assertEquals(GlanceWarning, walletSettingsDeleteActionColor)
        assertEquals(GlancePillShape, walletSettingsDeleteActionShape)
    }

    @Test
    fun qrScannerUsesTheSharedGlanceCardAndFocusedScanTreatment() {
        assertEquals(GlanceSurface, qrScannerCardColor)
        assertEquals(GlanceCardShape, qrScannerCardShape)
        assertEquals(RoundedCornerShape(12.dp), qrScannerPreviewShape)
        assertEquals(GlanceMandarin, qrScannerFrameColor)
    }
}
