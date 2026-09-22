package app.glance.wallet

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class SupportScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun supportScreenShowsOneDonationMethodAndUpdatesItsQr() {
        composeRule.setContent { GlanceTheme { SupportScreen(onBack = {}) } }

        composeRule.onNodeWithContentDescription("Glance donation heart").assertIsDisplayed()
        composeRule.onNodeWithTag("support_method_selector").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("On-chain donation QR code").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("On-chain donation address").assertIsDisplayed()
        composeRule.onNodeWithText("Copy address").assertIsDisplayed()

        composeRule.onNodeWithText("Lightning").performClick()
        composeRule.onNodeWithContentDescription("Lightning donation QR code").assertIsDisplayed()
        composeRule.onNodeWithTag("support_payload").assertIsDisplayed()
    }
}
