package dev.persona.watch

import dev.persona.watch.data.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class HubGroupRead(val snapshot: WatchSnapshot, val cache: Map<String, WatchSnapshot>, val failed: Set<String>)
object HubGroupReader {
    /** Blocking: call on a worker; use a separate bounded executor for concurrent connections. */
    fun read(primary: HubConfig?, extras: List<SavedHub>, cached: Map<String, WatchSnapshot>, locale: String,
             executor: ExecutorService): HubGroupRead {
        val groups = (if (primary != null) listOf(SavedHub("", "", primary)) else emptyList()) + extras
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
        val requests = groups.associate { group -> group.id to executor.submit<WatchSnapshot> {
            (group.config?.let { HubClient(it, locale) } ?: throw HubException("authentication_failed")).refresh()
        } }
        return collect(groups, requests, cached, deadline)
    }

    internal fun collect(groups: List<SavedHub>, requests: Map<String, Future<WatchSnapshot>>,
                         cached: Map<String, WatchSnapshot>, deadline: Long,
                         nanoTime: () -> Long = System::nanoTime): HubGroupRead {
        val next = cached.filterKeys { key -> groups.any { it.id == key } }.toMutableMap()
        val failed = mutableSetOf<String>()
        var expired = false
        var interrupted = false
        fun cancelPending() { requests.values.filterNot { it.isDone }.forEach { it.cancel(true) } }
        groups.forEach { group ->
            val request = requests.getValue(group.id)
            // Subtraction remains valid across nanoTime wraparound for this bounded interval.
            val remaining = deadline - nanoTime()
            if (!expired && remaining <= 0) { expired = true; cancelPending() }
            if (interrupted || (expired && (!request.isDone || request.isCancelled))) {
                failed += group.id
                return@forEach
            }
            try {
                // A later group may already have completed while an earlier one used the budget.
                val fresh = request.get(if (expired) 0 else remaining, TimeUnit.NANOSECONDS)
                next[group.id] = if (fresh.inboxUnavailable) fresh.copy(inbox = cached[group.id]?.inbox.orEmpty()) else fresh
            } catch (failure: Exception) {
                failed += group.id
                request.cancel(true)
                if (failure is TimeoutException) { expired = true; cancelPending() }
                if (failure is InterruptedException) {
                    interrupted = true
                    cancelPending()
                    Thread.currentThread().interrupt()
                }
            }
        }
        val updatedAt = if (failed.size < groups.size) System.currentTimeMillis()
            else next.values.maxOfOrNull { it.updatedAt } ?: 0
        return HubGroupRead(HubGroups.merge(groups.map { HubGroupSnapshot(it.id, it.name, next[it.id], it.id !in failed) },
            updatedAt), next, failed)
    }
}
