package app.glance.wallet

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import app.glance.wallet.presentation.ChartRange
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChartRangeSelectorLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rangeSelectorIsCenteredInItsAvailableWidth() {
        composeRule.setContent {
            GlanceTheme {
                ChartRangeSelector(selected = ChartRange.DAY, onSelect = {})
            }
        }

        val selector = composeRule.onNodeWithTag("chart_range_selector").getUnclippedBoundsInRoot()
        val root = composeRule.onNodeWithTag("chart_range_selector_root").getUnclippedBoundsInRoot()

        assertEquals(
            (((root.right - root.left) - (selector.right - selector.left)) / 2f).value,
            (selector.left - root.left).value,
            1.dp.value,
        )
    }

    @Test
    fun selectedRangeHasAnExplicitAccessibleSelectionState() {
        composeRule.setContent {
            GlanceTheme {
                ChartRangeSelector(selected = ChartRange.DAY, onSelect = {})
            }
        }

        composeRule.onNodeWithContentDescription("Selected chart range 1D").assertExists()
        composeRule.onNodeWithContentDescription("Chart range 1W").assertExists()
    }
}
