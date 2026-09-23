package app.glance.wallet

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.unit.dp

class PinSetupTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun setupUsesVisibleIndividualScreens() {
        composeRule.setContent { GlanceTheme { PinSetupContent { } } }

        composeRule.onNodeWithText("Create your PIN").assertIsDisplayed()
        composeRule.onAllNodesWithText("Enter").assertCountEquals(0)
        composeRule.onNodeWithText("1").performClick()
        composeRule.onNodeWithText("2").performClick()
        composeRule.onNodeWithText("3").performClick()
        composeRule.onNodeWithText("4").performClick()
        composeRule.onNodeWithText("5").performClick()
        composeRule.onNodeWithText("6").performClick()

        composeRule.onNodeWithText("Confirm your PIN").assertIsDisplayed()
        composeRule.onAllNodesWithText("Enter").assertCountEquals(0)
    }

    @Test
    fun unlockKeypadShufflesDigitsButKeepsBiometricAndBackspaceInPlace() {
        assertEquals(
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("Biometric", "0", "⌫")),
            keypadRows(scramble = false),
        )
        val scrambled = keypadRows(scramble = true, random = Random(7))
        assertNotEquals((1..9).map(Int::toString), scrambled.take(3).flatten())
        assertEquals(listOf("Biometric", "0", "⌫"), scrambled.last())
        assertEquals((1..9).map(Int::toString).toSet(), scrambled.take(3).flatten().toSet())
    }

    @Test
    fun setupPinUsesTheSameFullWidthKeypadGridAsUnlock() {
        composeRule.setContent { GlanceTheme { PinSetupContent { } } }

        composeRule.onNodeWithText("Create your PIN").assertIsDisplayed()
        composeRule.onNodeWithTag("pin_keypad").assertWidthIsEqualTo(280.dp)
        composeRule.onAllNodesWithText("Enter").assertCountEquals(0)
    }

    @Test
    fun unlockKeypadSubmitsImmediatelyAfterTheSixthDigitWithoutAnEnterKey() {
        var pin by mutableStateOf("")
        var submittedPin: String? = null
        composeRule.setContent {
            GlanceTheme {
                PinKeypad(
                    value = pin,
                    scramble = false,
                    haptics = false,
                    onAutoSubmit = { submittedPin = it },
                    onChange = { pin = it },
                )
            }
        }

        "123456".forEach { digit -> composeRule.onNodeWithText(digit.toString()).performClick() }

        assertEquals("123456", submittedPin)
        composeRule.onAllNodesWithText("Enter").assertCountEquals(0)
    }

    @Test
    fun unlockKeypadUsesTheReferenceThreeColumnWidthAndExposesBothActions() {
        composeRule.setContent {
            GlanceTheme {
                PinKeypad(
                    value = "",
                    scramble = false,
                    haptics = false,
                    onAutoSubmit = {},
                    onBiometric = {},
                    onChange = {},
                )
            }
        }

        composeRule.onNodeWithTag("pin_keypad").assertWidthIsEqualTo(280.dp)
        composeRule.onNodeWithContentDescription("Unlock with biometrics").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Delete PIN digit").assertIsDisplayed()
    }

    @Test
    fun unlockScreenLeavesDeliberateSpaceBetweenSubtitleDotsAndKeypad() {
        composeRule.setContent {
            GlanceTheme {
                PinUnlockPage { PinKeypad("", false, false, onAutoSubmit = {}, onBiometric = {}, onChange = {}) }
            }
        }

        val subtitleBottom = composeRule.onNodeWithText("Enter your PIN").getUnclippedBoundsInRoot().bottom
        val dotsBottom = composeRule.onNodeWithTag("pin_digits").getUnclippedBoundsInRoot().bottom
        val keypadTop = composeRule.onNodeWithTag("pin_keypad").getUnclippedBoundsInRoot().top

        assertTrue("Subtitle and PIN dots need visual separation", dotsBottom - subtitleBottom >= 26.dp)
        assertTrue("PIN dots and keypad need visual separation", keypadTop - dotsBottom >= 23.dp)
    }

    @Test
    fun duressSetupBlocksDuplicateSubmissionWhileCreationRuns() {
        val creationStarted = CompletableDeferred<Unit>()
        val allowCreationToFinish = CompletableDeferred<Unit>()
        var submissions = 0
        composeRule.setContent {
            GlanceTheme {
                DuressSetupDialog(onDismiss = {}) { _ ->
                    submissions += 1
                    creationStarted.complete(Unit)
                    allowCreationToFinish.await()
                }
            }
        }

        composeRule.onNodeWithTag("pin_keypad").assertWidthIsEqualTo(280.dp)
        enterPin("654321")
        enterPin("654321")
        composeRule.onNodeWithTag("duress_create_profile").performClick()

        runBlocking { creationStarted.await() }
        composeRule.onNodeWithText("Creating duress profile…").assertIsDisplayed()
        composeRule.onNodeWithTag("duress_create_profile").assertIsNotEnabled()
        assertEquals(1, submissions)

        runBlocking { allowCreationToFinish.complete(Unit) }
    }

    @Test
    fun duressSetupShowsRetryableErrorWhenCreationFails() {
        composeRule.setContent {
            GlanceTheme {
                DuressSetupDialog(onDismiss = {}) { _ -> error("creation interrupted") }
            }
        }

        enterPin("654321")
        enterPin("654321")
        composeRule.onNodeWithTag("duress_create_profile").performClick()

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Unable to create the duress profile. Try again.").assertIsDisplayed()
        composeRule.onNodeWithTag("duress_create_profile").assertIsEnabled()
    }

    @Test
    fun initialSetupCompletesAfterConfirmingTheRealPinWithoutOfferingDuressSetup() {
        var configuredPin: String? = null
        composeRule.setContent {
            GlanceTheme {
                PinSetupContent { configuredPin = it }
            }
        }

        enterPin("123456")
        enterPin("123456")

        composeRule.waitForIdle()
        assertEquals("123456", configuredPin)
        composeRule.onAllNodesWithText("Duress PIN (optional)").assertCountEquals(0)
    }

    @Test
    fun initialSetupShowsRetryableErrorWhenSecureCreationFails() {
        composeRule.setContent {
            GlanceTheme {
                PinSetupContent { error("creation interrupted") }
            }
        }

        enterPin("123456")
        enterPin("123456")

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Unable to secure wallet. Try again.").assertIsDisplayed()
        composeRule.onNodeWithText("Confirm your PIN").assertIsDisplayed()
    }

    private fun enterPin(pin: String) {
        pin.forEach { digit -> composeRule.onNodeWithText(digit.toString()).performClick() }
    }
}
