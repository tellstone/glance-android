package app.glance.wallet.core.security

import android.util.AtomicFile
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Durable, non-secret fail-closed state for an erase that did not complete. */
interface EraseStateStore {
    suspend fun isPending(): Boolean
    suspend fun markPending()
    suspend fun clear()
}

/** Production marker stored outside the encrypted preferences that Erase All Data removes. */
class FileEraseStateStore(private val file: File) : EraseStateStore {
    private val atomicFile = AtomicFile(file)

    override suspend fun isPending(): Boolean = runCatching {
        atomicFile.openRead().use { true }
    }.getOrDefault(false)

    override suspend fun markPending() {
        file.parentFile?.mkdirs()
        val output = atomicFile.startWrite()
        try {
            output.write(byteArrayOf(1))
            atomicFile.finishWrite(output)
        } catch (failure: Throwable) {
            atomicFile.failWrite(output)
            throw failure
        }
    }

    override suspend fun clear() {
        atomicFile.delete()
        check(!file.exists()) { "Unable to clear incomplete erase marker." }
    }
}

/** Test-only convenience store; production composition must supply [FileEraseStateStore]. */
class InMemoryEraseStateStore : EraseStateStore {
    private val pending = AtomicBoolean(false)

    override suspend fun isPending(): Boolean = pending.get()
    override suspend fun markPending() { pending.set(true) }
    override suspend fun clear() { pending.set(false) }
}
