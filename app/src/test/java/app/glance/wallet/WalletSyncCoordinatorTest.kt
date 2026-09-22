package app.glance.wallet

import app.glance.wallet.core.network.NetworkRoute
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class WalletSyncCoordinatorTest {
    @Test fun `offline mode never starts a requested sync`() = runBlocking {
        var requests = 0
        val coordinator = WalletSyncCoordinator { requests++ }

        coordinator.requestSync(torEnabled = false, torState = TorState.Disabled, offlineMode = true)

        assertEquals(0, requests)
        assertEquals(WalletSyncState.Idle, coordinator.state.value)
    }

    @Test
    fun `wallet refresh requires a deliberate 160 dp pull`() {
        assertEquals(160.dp, WalletSyncPullThreshold)
    }

    @Test
    fun `home refresh requests wallet sync and a fresh fiat quote`() = runBlocking {
        var walletRefreshes = 0
        var fiatRefreshes = 0
        var historicalRefreshes = 0

        refreshHomeData(
            refreshWallet = { walletRefreshes++ },
            refreshFiat = { fiatRefreshes++ },
            refreshHistoricalFiat = { historicalRefreshes++ },
        )

        assertEquals(1, walletRefreshes)
        assertEquals(1, fiatRefreshes)
        assertEquals(1, historicalRefreshes)
    }

    @Test
    fun `historical prices wait for Tor readiness when Tor is enabled`() {
        assertFalse(isHistoricalFiatRouteReady(torEnabled = true, torState = TorState.Starting))
        assertTrue(isHistoricalFiatRouteReady(torEnabled = false, torState = TorState.Disabled))
    }

    @Test
    fun `historical price request includes a prior quote buffer`() {
        assertEquals(913_600L, historicalPriceRequestStart(1_000_000L))
    }

    private val ready = TorState.Ready(NetworkRoute.Socks(InetSocketAddress("127.0.0.1", 19050)))

    @Test
    fun `does not sync on startup without an explicit request`() = runBlocking {
        var requests = 0
        val coordinator = WalletSyncCoordinator { requests++ }

        coordinator.reconcile(torEnabled = false, torState = TorState.Disabled)

        assertEquals(0, requests)
    }

    @Test
    fun `waits for Tor readiness before first sync`() = runBlocking {
        var requests = 0
        val coordinator = WalletSyncCoordinator { requests++ }

        coordinator.requestSync(torEnabled = true, torState = TorState.Starting)
        assertEquals(0, requests)
        assertEquals(WalletSyncState.ConnectingToTor, coordinator.state.value)

        coordinator.reconcile(torEnabled = true, torState = ready)
        assertEquals(1, requests)
        assertEquals(WalletSyncState.Succeeded, coordinator.state.value)
    }

    @Test
    fun `imports request one additional sync`() = runBlocking {
        var requests = 0
        val coordinator = WalletSyncCoordinator { requests++ }

        coordinator.requestSync(torEnabled = false, torState = TorState.Disabled)
        coordinator.requestSync(torEnabled = false, torState = TorState.Disabled)

        assertEquals(2, requests)
        assertEquals(WalletSyncState.Succeeded, coordinator.state.value)
    }

    @Test
    fun `network failure is visible and retryable`() = runBlocking {
        var requests = 0
        val coordinator = WalletSyncCoordinator {
            requests++
            if (requests == 1) error("endpoint failed")
        }

        coordinator.requestSync(torEnabled = false, torState = TorState.Disabled)
        assertEquals(WalletSyncState.Failed, coordinator.state.value)

        coordinator.requestSync(torEnabled = false, torState = TorState.Disabled)
        assertEquals(2, requests)
        assertEquals(WalletSyncState.Succeeded, coordinator.state.value)
    }

    @Test
    fun `Tor failure remains pending until a successful retry`() = runBlocking {
        var requests = 0
        val coordinator = WalletSyncCoordinator { requests++ }

        coordinator.requestSync(torEnabled = true, torState = TorState.Unavailable(IllegalStateException()))
        assertEquals(0, requests)
        assertEquals(WalletSyncState.TorUnavailable, coordinator.state.value)

        coordinator.reconcile(torEnabled = true, torState = ready)
        assertEquals(1, requests)
        assertTrue(coordinator.state.value is WalletSyncState.Succeeded)
    }

    @Test
    fun `request made during sync is queued after the current sync`() = runBlocking {
        val firstRequestStarted = CompletableDeferred<Unit>()
        val finishFirstRequest = CompletableDeferred<Unit>()
        var requests = 0
        val coordinator = WalletSyncCoordinator {
            requests++
            if (requests == 1) {
                firstRequestStarted.complete(Unit)
                finishFirstRequest.await()
            }
        }

        val firstSync = async { coordinator.requestSync(torEnabled = false, torState = TorState.Disabled) }
        firstRequestStarted.await()
        coordinator.requestSync(torEnabled = false, torState = TorState.Disabled)
        finishFirstRequest.complete(Unit)
        firstSync.await()

        assertEquals(2, requests)
        assertEquals(WalletSyncState.Succeeded, coordinator.state.value)
    }

    @Test
    fun `cancelling an active refresh leaves it pending for a later retry`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<Unit>()
        val coordinator = WalletSyncCoordinator {
            started.complete(Unit)
            neverCompletes.await()
        }

        val refresh = async { coordinator.requestSync(torEnabled = false, torState = TorState.Disabled) }
        started.await()
        coordinator.cancel()

        assertThrows(kotlinx.coroutines.CancellationException::class.java) { runBlocking { refresh.await() } }
        assertEquals(WalletSyncState.Idle, coordinator.state.value)
    }

    @Test
    fun `sync work is dispatched away from the caller thread`() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val callerThread = Thread.currentThread().name
            var syncThread = ""
            val coordinator = WalletSyncCoordinator(ioDispatcher = dispatcher) {
                syncThread = Thread.currentThread().name
            }

            coordinator.reconcile(torEnabled = false, torState = TorState.Disabled)

            assertNotEquals(callerThread, syncThread)
        } finally {
            dispatcher.close()
        }
    }
}
