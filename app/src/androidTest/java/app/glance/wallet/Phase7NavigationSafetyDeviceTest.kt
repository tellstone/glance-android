package app.glance.wallet

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Rule
import org.junit.Test

class Phase7NavigationSafetyDeviceTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rapidRepeatedBackLeavesTheHomeDestinationVisible() {
        composeRule.setContent {
            val nav = rememberNavController()
            NavHost(navController = nav, startDestination = "home") {
                composable("home") { Text("Home") }
                composable("detail") { Text("Detail") }
            }
            LaunchedEffect(Unit) {
                nav.navigate("detail")
                nav.popPhase7BackStackSafely()
                nav.popPhase7BackStackSafely()
            }
        }

        composeRule.onNodeWithText("Home").assertExists()
    }
}
