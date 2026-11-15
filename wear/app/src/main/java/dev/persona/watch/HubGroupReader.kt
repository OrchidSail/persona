package dev.persona.watch

import dev.persona.watch.data.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

data class HubGroupRead(val snapshot: WatchSnapshot, val cache: Map<String, WatchSnapshot>, val failed: Set<String>)
object HubGroupReader {
    /** Blocking: call on a worker; use a separate bounded executor for concurrent connections. */
    fun read(primary: HubConfig?, extras: List<SavedHub>, cached: Map<String, WatchSnapshot>, locale: String,
             executor: ExecutorService): HubGroupRead {
        val groups = (if (primary != null) listOf(SavedHub("", "", primary)) else emptyList()) + extras
        val requests = groups.associate { group -> group.id to executor.submit<WatchSnapshot> {
            (group.config?.let { HubClient(it, locale) } ?: throw HubException("authentication_failed")).refresh()
        } }
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
        val next = cached.filterKeys { key -> groups.any { it.id == key } }.toMutableMap()
        val failed = mutableSetOf<String>()
        groups.forEach { group ->
            val request = requests.getValue(group.id)
            runCatching { request.get((end - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS) }
                .onSuccess { fresh -> next[group.id] = if (fresh.inboxUnavailable) fresh.copy(inbox = cached[group.id]?.inbox.orEmpty()) else fresh }
                .onFailure { failed += group.id; request.cancel(true) }
        }
        val updatedAt = if (failed.size < groups.size) System.currentTimeMillis()
            else next.values.maxOfOrNull { it.updatedAt } ?: 0
        return HubGroupRead(HubGroups.merge(groups.map { HubGroupSnapshot(it.id, it.name, next[it.id], it.id !in failed) },
            updatedAt), next, failed)
    }
}
