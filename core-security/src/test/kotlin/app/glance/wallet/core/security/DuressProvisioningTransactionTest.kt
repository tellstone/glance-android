package app.glance.wallet.core.security

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class DuressProvisioningTransactionTest {
    @Test
    fun `failed provisioning rolls back the new decoy store`() = runBlocking {
        val events = mutableListOf<String>()

        assertFailure<IllegalStateException> {
            provisionDecoyAtomically(
                provision = {
                    events += "create"
                    throw IllegalStateException("preference persistence failed")
                },
                rollback = { events += "delete" },
            )
        }

        assertEquals(listOf("create", "delete"), events)
    }

    @Test
    fun `cancelled provisioning rolls back the new decoy store`() = runBlocking {
        val events = mutableListOf<String>()

        assertFailure<CancellationException> {
            provisionDecoyAtomically(
                provision = {
                    events += "create"
                    throw CancellationException("setup interrupted")
                },
                rollback = { events += "delete" },
            )
        }

        assertEquals(listOf("create", "delete"), events)
    }

    @Test
    fun `successful provisioning retains the new decoy store`() = runBlocking {
        val events = mutableListOf<String>()

        provisionDecoyAtomically(
            provision = { events += "create" },
            rollback = { events += "delete" },
        )

        assertEquals(listOf("create"), events)
    }

    private suspend inline fun <reified T : Throwable> assertFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.simpleName}")
        } catch (failure: Throwable) {
            if (failure !is T) throw failure
        }
    }
}
