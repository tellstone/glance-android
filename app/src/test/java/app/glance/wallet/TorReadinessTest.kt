package app.glance.wallet

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TorReadinessTest {
    @Test
    fun `waits for runtime readiness before returning the SOCKS port`() = runBlocking {
        var readyChecks = 0

        val port = awaitTorSocksPort(
            isReady = { ++readyChecks >= 2 },
            socksPort = { 19050 },
            timeoutMillis = 1_000L,
            pollMillis = 1L,
        )

        assertEquals(19050, port)
        assertEquals(2, readyChecks)
    }

    @Test
    fun `startup cancellation stops Tor and resets state before propagation`() = runBlocking {
        var unavailable: Throwable? = null
        var stopped = false
        var state: TorState = TorState.Starting

        assertThrows(CancellationException::class.java) {
            runBlocking {
                runTorStartup(
                    startDaemon = {},
                    awaitSocksPort = { throw CancellationException("cancelled") },
                    onReady = { error("Tor must not become ready") },
                    onUnavailable = { unavailable = it },
                    onCancellation = {
                        try {
                            stopped = true
                        } finally {
                            state = TorState.Disabled
                        }
                    },
                )
            }
        }

        assertEquals(null, unavailable)
        assertEquals(true, stopped)
        assertEquals(TorState.Disabled, state)
    }

    @Test
    fun `startup waiting for a SOCKS port is preemptible`() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        var stopped = false
        var state: TorState = TorState.Starting
        val startup = launch {
            runTorStartup(
                startDaemon = {},
                awaitSocksPort = {
                    waiting.complete(Unit)
                    awaitCancellation()
                },
                onReady = { error("Tor must not become ready") },
                onUnavailable = { error("Tor must not become unavailable") },
                onCancellation = {
                    stopped = true
                    state = TorState.Disabled
                },
            )
        }

        waiting.await()
        startup.cancelAndJoin()

        assertEquals(true, stopped)
        assertEquals(TorState.Disabled, state)
    }

    @Test
    fun `shutdown cancellation stops Tor and clears ready state before propagation`() = runBlocking {
        var stopped = false
        var state: TorState = TorState.Ready(app.glance.wallet.core.network.NetworkRoute.Socks(java.net.InetSocketAddress("127.0.0.1", 19050)))

        assertThrows(CancellationException::class.java) {
            runBlocking {
                runTorShutdown(
                    stopDaemon = { throw CancellationException("cancelled") },
                    onDisabled = { state = TorState.Disabled },
                    onCancellation = {
                        try {
                            stopped = true
                        } finally {
                            state = TorState.Disabled
                        }
                    },
                )
            }
        }

        assertEquals(true, stopped)
        assertEquals(TorState.Disabled, state)
    }

    @Test
    fun `cancellation remains visible when Tor cleanup fails`() = runBlocking {
        var state: TorState = TorState.Starting

        assertThrows(CancellationException::class.java) {
            runBlocking {
                runTorStartup(
                    startDaemon = {},
                    awaitSocksPort = { throw CancellationException("cancelled") },
                    onReady = { error("Tor must not become ready") },
                    onUnavailable = { error("Tor must not become unavailable") },
                    onCancellation = {
                        try {
                            throw IllegalStateException("cleanup failed")
                        } finally {
                            state = TorState.Disabled
                        }
                    },
                )
            }
        }

        assertEquals(TorState.Disabled, state)
    }

    @Test
    fun `renewal stop failure publishes unavailable instead of leaving startup pending`() = runBlocking {
        val failure = IllegalStateException("stop failed")
        var unavailable: Throwable? = null
        var state: TorState = TorState.Starting

        runTorStartup(
            beforeStart = { throw failure },
            startDaemon = { error("Tor must not start after a failed renewal stop") },
            awaitSocksPort = { error("Tor must not await a port") },
            onReady = { error("Tor must not become ready") },
            onUnavailable = {
                unavailable = it
                state = TorState.Unavailable(it)
            },
            onCancellation = { error("A daemon failure is not cancellation") },
        )

        assertEquals(failure, unavailable)
        assertEquals(TorState.Unavailable(failure), state)
    }

    @Test
    fun `exclusive cleanup prevents concurrent lifecycle work until all cleanup completes`() = runBlocking {
        val lifecycleMutex = Mutex()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var competingLifecycleWorkEntered = false

        val cleanup = launch {
            runExclusiveTorCleanup(
                withLifecycleLock = { block -> lifecycleMutex.withLock { block() } },
                disable = {},
                stopDaemon = {},
                cleanup = {
                    cleanupEntered.complete(Unit)
                    releaseCleanup.await()
                },
            )
        }
        cleanupEntered.await()
        val competingLifecycleWork = launch {
            lifecycleMutex.withLock { competingLifecycleWorkEntered = true }
        }

        yield()
        assertEquals(false, competingLifecycleWorkEntered)

        releaseCleanup.complete(Unit)
        cleanup.join()
        competingLifecycleWork.join()
        assertEquals(true, competingLifecycleWorkEntered)
    }
}
