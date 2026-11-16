package dev.persona.watch.pet

import dev.persona.watch.data.HubConfig
import dev.persona.watch.data.WatchSnapshot
import java.time.LocalTime

/** Unlike immutable art, task-derived state belongs to the exact authenticated connection. */
internal fun petStatusScope(config: HubConfig): String = PetPack.hash(
    listOf(config.baseUrl, config.token, config.fingerprint.orEmpty(), config.deviceId.orEmpty())
        .joinToString("\u0000").toByteArray()).take(32)

/** Shared by the page and MainActivity's bridge, without advancing freshness on a failed read. */
internal object PetFreshness {
    fun next(previous: PetStoredStatus, snapshot: WatchSnapshot?, unreachable: Boolean,
             partlyUnreachable: Boolean = false, now: Long = System.currentTimeMillis(),
             hour: Int = LocalTime.now().hour): PetStoredStatus {
        // A failed read may still carry a previously cached snapshot; never stamp it with "now".
        val date = maxOf(previous.updatedAt.coerceAtLeast(0), snapshot?.updatedAt?.coerceAtLeast(0) ?: 0)
        val next = PetStoredStatus(PetStatus.from(snapshot, unreachable, hour, partlyUnreachable), date)
        return next.copy(status = next.visible(now))
    }
}

/** Connection-owned page data. A paused/replaced connection cannot accept an old worker completion. */
internal class PetPageState {
    var connection: HubConfig? = null
        private set
    @Volatile var generation = 0
        private set
    var manifest: PetManifest? = null
        private set
    var groups = emptyMap<String, WatchSnapshot>()
        private set
    var stored = PetStoredStatus(PetStatus(PetState.OFFLINE), 0)
        private set

    fun resume(next: HubConfig?, saved: PetStoredStatus, now: Long): Boolean {
        val changed = next != connection
        if (changed) {
            invalidate()
            connection = next
            manifest = null
            groups = emptyMap()
        }
        stored = if (next == null) PetStoredStatus(PetStatus(PetState.OFFLINE), 0) else saved
        revalidate(now)
        return changed
    }

    fun invalidate() { generation++ }
    fun accepts(ticket: Int) = ticket == generation && connection != null
    fun acceptManifest(ticket: Int, next: PetManifest): Boolean {
        if (!accepts(ticket)) return false
        manifest = next
        return true
    }

    fun acceptRefresh(ticket: Int, nextManifest: PetManifest?, nextGroups: Map<String, WatchSnapshot>,
                      snapshot: WatchSnapshot?, unreachable: Boolean, partlyUnreachable: Boolean,
                      now: Long): Boolean {
        if (!accepts(ticket)) return false
        manifest = nextManifest
        groups = nextGroups
        stored = PetFreshness.next(stored, snapshot, unreachable, partlyUnreachable, now)
        return true
    }

    fun revalidate(now: Long): Boolean {
        val visible = stored.visible(now)
        val changed = stored.status != visible
        stored = stored.copy(status = visible)
        return changed
    }

    fun expiryDelay(now: Long): Long? = stored.freshUntil(now)?.let { it - now }
}

/** Static mode never calls the clip decoder, including a tap requested while motion is disabled. */
internal data class PetPlayback(val state: String, val clip: String, val animated: Boolean) {
    data class Artwork<T>(val still: T?, val frames: List<T>)
    fun <T> load(still: (String) -> T?, frames: (String) -> List<T>): Artwork<T> =
        Artwork(still(state), if (animated) frames(clip) else emptyList())

    companion object {
        fun plan(state: PetState, requested: String, motion: Boolean, interactive: Boolean): PetPlayback {
            val animated = motion && interactive
            return PetPlayback(state.wire, if (animated) requested else state.wire, animated)
        }
    }
}

/** Transfer output ownership only on success, including Error/OOM failures after earlier frames. */
internal fun <T> collectPetFrames(capacity: Int, recycle: (T) -> Unit, decode: (MutableList<T>) -> Unit): List<T> {
    val output = ArrayList<T>(capacity)
    try {
        decode(output)
        return output
    } catch (failure: Throwable) {
        output.forEach { frame ->
            try { recycle(frame) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
        }
        throw failure
    }
}
