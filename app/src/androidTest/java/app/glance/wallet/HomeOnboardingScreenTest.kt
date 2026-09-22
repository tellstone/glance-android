package app.glance.wallet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeOnboardingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun onboardingUsesTheOfficialLogoAndRoutesTheFirstKeyAction() {
        var addRequested by mutableStateOf(false)

        composeRule.setContent {
            GlanceTheme { HomeOnboarding(onAdd = { addRequested = true }) }
        }

        composeRule.onNodeWithContentDescription("Glance logo").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Tor routing").assertIsDisplayed()
        composeRule.onNodeWithText("Restoring from a backup?").assertIsDisplayed()
        composeRule.onNodeWithText("+  Add your first key").performClick()

        assertTrue(addRequested)
    }
}
