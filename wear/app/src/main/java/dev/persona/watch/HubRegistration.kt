package dev.persona.watch

import dev.persona.watch.data.HubConfig
import java.util.UUID

/** The journal contains metadata only; issued credentials stay in encrypted CredentialStore files. */
internal data class PendingHubRegistration(val id: String, val name: String, val baseUrl: String)

internal interface HubRegistrationStore {
    fun groups(): List<SavedHub>
    fun primaryUrl(): String?
    fun pending(): PendingHubRegistration?
    fun writePending(pending: PendingHubRegistration?)
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
        // Finish an earlier durable exchange before accepting another registration.
        val pending = store.pending()
        val issued = pending?.let { store.credential(it.id) }
        if (pending != null && issued != null) {
            publish(pending, issued)
            if (pending.baseUrl == config.baseUrl) return
        }
        val current = store.groups()
        require(current.size < 3)
        require(current.none { it.config?.baseUrl == config.baseUrl } && store.primaryUrl() != config.baseUrl)
        val registration = PendingHubRegistration(newId(), name.trim(), config.baseUrl)
        // This must succeed before the network can consume a one-time pairing token.
        store.writePending(registration)
        val paired = pair(config).validated()
        require(paired.baseUrl == config.baseUrl && paired.fingerprint == config.fingerprint)
        store.saveCredential(registration.id, paired)
        publish(registration, paired)
    }

    /** Never contacts the Hub. A process restart can finish a saved exchange using only this journal. */
    fun recover(): Boolean {
        val pending = store.pending() ?: return false
        val issued = store.credential(pending.id) ?: return false
        publish(pending, issued)
        return true
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
        store.writePending(null)
    }
}
