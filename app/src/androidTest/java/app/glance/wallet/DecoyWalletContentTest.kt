package app.glance.wallet

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import app.glance.wallet.core.security.AuthenticationCoordinator
import app.glance.wallet.core.security.ProfileDatabaseManager
import app.glance.wallet.core.security.SecurityPreferencesStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Rule
import org.junit.Test

class DecoyWalletContentTest {
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
    fun decoySurfaceShowsOnlyItsStaticBalanceAndNoSecuritySettings() {
        composeRule.setContent { GlanceTheme { DecoyWalletContent(50_000L, AuthenticationCoordinator(preferences, profiles)) } }

        composeRule.onNodeWithTag("decoy_balance").assertTextEquals("50000 sats")
        composeRule.onAllNodesWithText("Scramble PIN keypad").assertCountEquals(0)
        composeRule.onAllNodesWithText("Save decoy balance").assertCountEquals(0)
    }

    @Test
    fun duressSettingsDiscloseTheForensicDatabaseLimitation() {
        composeRule.setContent { GlanceTheme { DuressForensicLimitationNotice() } }

        composeRule.onAllNodesWithText("The separate encrypted decoy database may be detectable during forensic device inspection, even though its contents remain unreadable.")
            .assertCountEquals(1)
    }
}
