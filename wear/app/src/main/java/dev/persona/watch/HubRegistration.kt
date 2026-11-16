package dev.persona.watch

import dev.persona.watch.data.HubConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** The journal contains metadata only; issued credentials stay in encrypted CredentialStore files. */
internal data class PendingHubRegistration(val id: String, val name: String, val baseUrl: String)

internal object PendingHubJournal {
    fun decode(value: String?): List<PendingHubRegistration> {
        if (value == null) return emptyList()
        val rows = if (value.trimStart().startsWith("[")) JSONArray(value) else JSONArray().put(JSONObject(value))
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val id = row.getString("id")
            require(id.matches(Regex("[a-f0-9-]{36}")))
            PendingHubRegistration(id, row.getString("name"), row.getString("baseUrl"))
        }
    }
    fun encode(pending: List<PendingHubRegistration>): String = JSONArray().also { rows ->
        pending.forEach { rows.put(JSONObject().put("id", it.id).put("name", it.name).put("baseUrl", it.baseUrl)) }
    }.toString()
}

internal interface HubRegistrationStore {
    fun groups(): List<SavedHub>
    fun primaryUrl(): String?
    fun pending(): List<PendingHubRegistration>
    fun writePending(pending: List<PendingHubRegistration>)
    fun credential(id: String): HubConfig?
    fun saveCredential(id: String, config: HubConfig)
    fun writeGroups(groups: List<SavedHub>)
}

/** Caller serializes operations. Publication may be retried without another token exchange. */
internal class HubRegistration(private val store: HubRegistrationStore,
                               private val newId: () -> String = { UUID.randomUUID().toString() }) {
    fun add(name: String, input: HubConfig, pair: (HubConfig) -> HubConfig) {
        val config = input.validated()
        require(name.trim().isNotEmpty() && name.length <= 80)
        // Unrelated journals recover independently; a failed old Hub must not block a new Hub.
        // Keep unreadable issued credentials, but discard metadata whose exchange never became durable.
        val pending = store.pending()
        val retained = pending.filter { runCatching { store.credential(it.id) != null }.getOrDefault(true) }
        if (retained != pending) store.writePending(retained)
        val matching = retained.firstOrNull { it.baseUrl == config.baseUrl }
        if (matching != null) {
            val issued = checkNotNull(store.credential(matching.id))
            publish(matching, issued)
            return
        }
        val current = store.groups()
        require(current.size < 3)
        require(current.none { it.config?.baseUrl == config.baseUrl } && store.primaryUrl() != config.baseUrl)
        val registration = PendingHubRegistration(newId(), name.trim(), config.baseUrl)
        // This must succeed before the network can consume a one-time pairing token.
        store.writePending(store.pending() + registration)
        val paired = pair(config).validated()
        require(paired.baseUrl == config.baseUrl && paired.fingerprint == config.fingerprint)
        store.saveCredential(registration.id, paired)
        publish(registration, paired)
    }

    /** Never contacts the Hub. A process restart can finish a saved exchange using only this journal. */
    fun recover(): Boolean {
        var recovered = false
        var failure: Exception? = null
        for (pending in store.pending()) {
            try {
                val issued = store.credential(pending.id)
                if (issued == null) forget(pending.id)
                else { publish(pending, issued); recovered = true }
            } catch (problem: Exception) {
                // One unreadable/invalid Hub cannot prevent another saved registration from recovering.
                if (failure == null) failure = problem else failure.addSuppressed(problem)
            }
        }
        failure?.let { throw it }
        return recovered
    }

    private fun publish(pending: PendingHubRegistration, issued: HubConfig) {
        val config = issued.validated()
        require(config.baseUrl == pending.baseUrl)
        val other = store.groups().filterNot { it.id == pending.id }
        require(other.size < 3)
        require(other.none { it.config?.baseUrl == config.baseUrl } && store.primaryUrl() != config.baseUrl)
        // Always commit, even if a failed SharedPreferences commit already changed its in-memory view.
        store.writeGroups(other + SavedHub(pending.id, pending.name, config))
        // A failed cleanup is harmless: replay rewrites the same ID, never another credential or row.
        forget(pending.id)
    }

    private fun forget(id: String) = store.writePending(store.pending().filterNot { it.id == id })
}
