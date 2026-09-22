package app.glance.wallet

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class QrScannerDialogTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun scannerDialogExposesTheGlanceCardPreviewAndDismissAction() {
        composeRule.setContent {
            MaterialTheme {
                QrScannerDialog(
                    onDismiss = {},
                    onPayload = {},
                    onUnavailable = {},
                    cameraPreview = { modifier -> Box(modifier.testTag("scanner_fake_camera")) },
                )
            }
        }

        composeRule.onNodeWithTag("qr_scanner_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Scan watch target").assertIsDisplayed()
        composeRule.onNodeWithTag("qr_scanner_preview").assertIsDisplayed()
        composeRule.onNodeWithTag("scanner_fake_camera").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close QR scanner").assertIsDisplayed()
    }
}
