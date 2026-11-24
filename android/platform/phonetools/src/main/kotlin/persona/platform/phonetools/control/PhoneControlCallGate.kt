package persona.platform.phonetools.control

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * Bounded TTL idempotency gate for remote phone control calls (`action`, `otp_consume`).
 * Prevents network retries from double-tapping UI controls or double-consuming OTPs.
 * Kept strictly in memory (max 64 entries, TTL 60s) so sensitive payloads never touch disk.
 */
class PhoneControlCallGate(
    private val capacity: Int = MAX_CAPACITY,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(
        val value: JsonObject,
        val storedAtMs: Long,
    )

    sealed interface Begin {
        data class Cached(val value: JsonObject) : Begin
        data object Leader : Begin
        data class Follower(val deferred: CompletableDeferred<JsonObject>) : Begin
    }

    private val lock = Mutex()
    private val completed = LinkedHashMap<String, Entry>()
    private val inFlight = mutableMapOf<String, CompletableDeferred<JsonObject>>()

    suspend fun begin(key: String): Begin = lock.withLock {
        pruneExpiredLocked()
        completed[key]?.let { return@withLock Begin.Cached(it.value) }
        inFlight[key]?.let { return@withLock Begin.Follower(it) }
        val deferred = CompletableDeferred<JsonObject>()
        val _ = inFlight.put(key, deferred)
        Begin.Leader
    }

    suspend fun complete(key: String, value: JsonObject) = lock.withLock {
        pruneExpiredLocked()
        val _ = completed.put(key, Entry(value, clock()))
        while (completed.size > capacity) {
            val oldest = completed.keys.first()
            val _ = completed.remove(oldest)
        }
        val _ = inFlight.remove(key)?.complete(value)
    }

    suspend fun fail(key: String, cause: Throwable) = lock.withLock {
        val deferred = inFlight.remove(key) ?: return@withLock
        if (cause is CancellationException) {
            deferred.cancel(cause)
        } else {
            val _ = deferred.completeExceptionally(cause)
        }
    }

    suspend fun clear() = lock.withLock {
        completed.clear()
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
    }

    internal suspend fun cachedCount(): Int = lock.withLock {
        pruneExpiredLocked()
        completed.size
    }

    private fun pruneExpiredLocked() {
        val cutoff = clock() - ttlMs
        val it = completed.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.storedAtMs < cutoff) {
                it.remove()
            }
        }
    }

    private companion object {
        const val MAX_CAPACITY = 64
        const val DEFAULT_TTL_MS = 60_000L
    }
}
