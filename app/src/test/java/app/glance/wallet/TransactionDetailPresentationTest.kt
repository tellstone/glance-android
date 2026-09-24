package app.glance.wallet

import app.glance.wallet.core.security.ExplorerPreset
import org.junit.Assert.assertEquals
import org.junit.Test

class TransactionDetailPresentationTest {
    @Test
    fun transactionIdentifiersAreAbbreviatedForDisplay() {
        assertEquals("a3f9…02c1", abbreviateTransactionIdentifier("a3f9123402c1"))
        assertEquals("bc1q…k3f7", abbreviateTransactionIdentifier("bc1q9h2k3f7"))
        assertEquals("short", abbreviateTransactionIdentifier("short"))
    }

    @Test
    fun pendingTransactionDoesNotInventConfirmationData() {
        assertEquals("Pending", transactionStatus(confirmations = 0, blockHeight = null, timestamp = null))
    }

    @Test
    fun confirmedTransactionWithoutCachedTimestampStatesThatClearly() {
        assertEquals(
            "3 confirmations; date unavailable",
            transactionStatus(confirmations = 3, blockHeight = 800_000, timestamp = null),
        )
    }

    @Test
    fun explorerUrlsUseTheSelectedMainnetPreset() {
        assertEquals("https://mempool.space/tx/redacted", explorerUrl(ExplorerPreset.MEMPOOL_SPACE, "redacted"))
        assertEquals("https://blockstream.info/tx/redacted", explorerUrl(ExplorerPreset.BLOCKSTREAM, "redacted"))
    }

    @Test
    fun transactionRowsUseStoredZeroBasedIndices() {
        assertEquals("#0", transactionEntryIndexLabel(0))
        assertEquals("#1", transactionEntryIndexLabel(1))
    }
}
