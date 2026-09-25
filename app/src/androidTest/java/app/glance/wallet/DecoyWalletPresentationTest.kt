package app.glance.wallet

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.glance.wallet.core.security.ProfileDatabaseManager
import app.glance.wallet.core.security.SecurityPreferencesStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Rule
import org.junit.Test

class DecoyWalletPresentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val profiles = ProfileDatabaseManager(context)
    private val preferences = SecurityPreferencesStore.forTesting(context, "decoy-ui")

    @After
    fun tearDown() {
        kotlinx.coroutines.runBlocking { preferences.wipe() }
        profiles.deleteAll()
    }

    @Test
    fun decoySettingsUseNeutralGroupsWithoutDuressDisclosure() {
        composeRule.setContent {
            GlanceTheme {
                DecoySettingsContent(
                    revealPhrase = false,
                    mnemonic = null,
                    onBack = {},
                    onRevealPhrase = {},
                )
            }
        }

        composeRule.onNodeWithTag("decoy_settings_group_wallet").assertIsDisplayed()
        composeRule.onNodeWithTag("decoy_settings_group_about").assertIsDisplayed()
        composeRule.onNodeWithText("Recovery phrase").assertIsDisplayed()
        composeRule.onAllNodesWithText("This automatically generated decoy wallet is not intended to receive funds.").assertCountEquals(0)
        composeRule.onAllNodesWithText("Duress PIN").assertCountEquals(0)
        composeRule.onAllNodesWithText("Biometric unlock").assertCountEquals(0)
    }

    @Test
    fun duressSettingsDiscloseTheForensicDatabaseLimitation() {
        composeRule.setContent { GlanceTheme { DuressForensicLimitationNotice() } }

        composeRule.onAllNodesWithText("The separate encrypted decoy database may be detectable during forensic device inspection, even though its contents remain unreadable.")
            .assertCountEquals(1)
    }
}
