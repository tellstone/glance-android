package app.glance.wallet

import app.glance.wallet.core.network.NetworkRoute
import java.net.InetSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class TorConnectionUiPolicyTest {
    @Test fun `enabled Tor gates PIN only while bootstrap is pending`() {
        assertEquals(TorGate.PENDING, torGate(torEnabled = true, state = TorState.Starting))
        assertEquals(TorGate.OPEN, torGate(torEnabled = true, state = readyTorState()))
        assertEquals(TorGate.OPEN, torGate(torEnabled = true, state = TorState.Unavailable(IllegalStateException())))
        assertEquals(TorGate.OPEN, torGate(torEnabled = false, state = TorState.Disabled))
    }

    @Test fun `only the initial bootstrap uses the blocking Tor gate`() {
        assertEquals(TorGate.PENDING, torGate(torEnabled = true, state = TorState.Starting, initialBootstrap = true))
        assertEquals(TorGate.OPEN, torGate(torEnabled = true, state = TorState.Starting, initialBootstrap = false))
        assertEquals(TorGate.OPEN, torGate(torEnabled = true, state = TorState.Disabled, initialBootstrap = false))
    }

    @Test fun `initial Tor bootstrap gate opens when its readiness deadline expires`() {
        assertEquals(
            TorGate.OPEN,
            torGate(
                torEnabled = true,
                state = TorState.Starting,
                bootstrapElapsedMillis = TOR_BOOTSTRAP_GATE_TIMEOUT_MILLIS,
            ),
        )
    }

    @Test fun `connection indicator uses semantic status and no route detail`() {
        assertEquals(TorConnectionIndicator.CONNECTED, torConnectionIndicator(true, readyTorState()))
        assertEquals(TorConnectionIndicator.CONNECTING, torConnectionIndicator(true, TorState.Starting))
        assertEquals(TorConnectionIndicator.NO_TOR, torConnectionIndicator(true, TorState.Unavailable(IllegalStateException())))
        assertEquals(TorConnectionIndicator.NO_TOR, torConnectionIndicator(false, TorState.Disabled))
    }

    @Test fun `offline mode bypasses Tor bootstrap and owns the connection status`() {
        assertEquals(TorGate.OPEN, torGate(torEnabled = true, offlineMode = true, state = TorState.Starting))
        assertEquals(TorConnectionIndicator.OFFLINE, torConnectionIndicator(true, offlineMode = true, state = readyTorState()))
    }

    @Test fun `header icon distinguishes offline from Tor connection states`() {
        assertEquals(TorHeaderIcon.FILLED_SHIELD, torHeaderIcon(TorConnectionIndicator.CONNECTED))
        assertEquals(TorHeaderIcon.OUTLINED_SHIELD, torHeaderIcon(TorConnectionIndicator.CONNECTING))
        assertEquals(TorHeaderIcon.OUTLINED_SHIELD, torHeaderIcon(TorConnectionIndicator.NO_TOR))
        assertEquals(TorHeaderIcon.OFFLINE, torHeaderIcon(TorConnectionIndicator.OFFLINE))
    }
}

private fun readyTorState() = TorState.Ready(NetworkRoute.Socks(InetSocketAddress("127.0.0.1", 19050)))
