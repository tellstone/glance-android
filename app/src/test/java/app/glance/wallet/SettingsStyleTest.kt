package app.glance.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsStyleTest {
    @Test
    fun settingsVisualTokensUseTheDarkMandarinPalette() {
        assertEquals(GlanceBackground, settingsTopBarColor)
        assertEquals(GlanceMandarin, settingsSwitchCheckedTrackColor)
        assertEquals(GlanceText, settingsSwitchCheckedThumbColor)
        assertEquals(GlancePillShape, settingsActionButtonShape)
    }

    @Test
    fun settingsControlsUseTheMockCompactScale() {
        assertEquals(0.82f, settingsSwitchScale)
        assertEquals(16f, settingsHeaderTextSize.value)
    }
}
