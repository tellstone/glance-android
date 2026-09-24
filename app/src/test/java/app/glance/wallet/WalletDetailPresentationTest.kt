package app.glance.wallet

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WalletDetailPresentationTest {
    @Test
    fun oversizedSingleAddressExplainsThatOnlyItsBalanceAndRecentHistoryAreAvailable() {
        assertEquals(
            "UTXO details are unavailable because this address has more than 1,000 unspent outputs. Its balance and recent transactions remain available.",
            utxoSnapshotUnavailableMessage,
        )
    }

    @Test
    fun transactionRowsUsePendingSpecificDirectionsAndExplicitConfirmationText() {
        assertEquals("Received", transactionDirection(valueSats = 42L, confirmations = 1))
        assertEquals("Sent", transactionDirection(valueSats = -42L, confirmations = 1))
        assertEquals("Incoming", transactionDirection(valueSats = 42L, confirmations = 0))
        assertEquals("Outgoing", transactionDirection(valueSats = -42L, confirmations = 0))
        assertEquals("0 confirmations", transactionConfirmationText(0))
        assertEquals("1 confirmation", transactionConfirmationText(1))
        assertEquals("12 confirmations", transactionConfirmationText(12))
        assertEquals(fiatChartGreen, transactionDirectionColor(42L))
        assertEquals(GlanceWarning, transactionDirectionColor(-42L))
    }

    @Test
    fun missingCachedTransactionDateIsPresentedExplicitly() {
        assertEquals("Date unavailable", transactionDateText(null))
    }

    @Test
    fun transactionPaginationUsesTwentyFiveRowsAndInclusiveRanges() {
        assertEquals(1, transactionPageCount(0))
        assertEquals(1, transactionPageCount(25))
        assertEquals(2, transactionPageCount(26))
        assertEquals("1–25 of 26", transactionPageRangeText(page = 0, totalCount = 26))
        assertEquals("26–26 of 26", transactionPageRangeText(page = 1, totalCount = 26))
    }

    @Test
    fun utxoPresentationUsesExplicitConfirmationStatusAndAbbreviatedOutput() {
        assertEquals("Pending", utxoConfirmationText(0))
        assertEquals("1 confirmation", utxoConfirmationText(1))
        assertEquals("12 confirmations", utxoConfirmationText(12))
        assertEquals("a3f9…02c1:4", utxoOutputText("a3f9123402c1", 4))
    }

    @Test
    fun pendingUtxosUseTheDedicatedAmberTreatment() {
        assertEquals(Color(0xFFF0B35C), pendingUtxoColor)
    }

    @Test
    fun utxoListAndDetailAmountsUseTheDefaultFormatColorForEveryStatus() {
        assertNull(utxoAmountTextColor(confirmations = 1, valueSats = 5_000L, dustThresholdSats = 5_000L))
        assertNull(utxoAmountTextColor(confirmations = 0, valueSats = 5_000L, dustThresholdSats = 5_000L))
        assertNull(utxoAmountTextColor(confirmations = 1, valueSats = 42L, dustThresholdSats = 5_000L))
    }

    @Test
    fun receivePresentationDistinguishesReusableAndFixedAddresses() {
        assertEquals("Receive", receiveActionLabel(isSingleAddress = false))
        assertEquals("Address", receiveActionLabel(isSingleAddress = true))
        assertEquals("Next unused address · Savings", receiveContextCaption("Savings", isSingleAddress = false))
        assertEquals("Address · Savings", receiveContextCaption("Savings", isSingleAddress = true))
    }

    @Test
    fun addressDisplayUsesAlternatingPrimaryAndMandarinAccentsWithoutChangingCase() {
        assertEquals(
            listOf(
                AddressDisplaySpan("bc1q", AddressTextTone.PRIMARY),
                AddressDisplaySpan("fp99", AddressTextTone.MANDARIN),
                AddressDisplaySpan("qf7q", AddressTextTone.PRIMARY),
                AddressDisplaySpan("8trx", AddressTextTone.MANDARIN),
            ),
            addressDisplaySpans("bc1qfp99qf7q8trx"),
        )
        assertEquals(
            listOf(
                AddressDisplaySpan("1A2b", AddressTextTone.MANDARIN),
                AddressDisplaySpan("3C4d", AddressTextTone.PRIMARY),
                AddressDisplaySpan("5E", AddressTextTone.MANDARIN),
            ),
            addressDisplaySpans("1A2b3C4d5E"),
        )
    }
}
