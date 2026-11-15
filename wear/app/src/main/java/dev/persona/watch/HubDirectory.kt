package dev.persona.watch

import android.content.Context
import dev.persona.watch.data.HubConfig
import org.json.JSONArray
import org.json.JSONObject

/** Names and IDs are metadata; every credential stays in its own Keystore-encrypted envelope. */
data class SavedHub(val id: String, val name: String, val config: HubConfig?)
class HubDirectory(private val context: Context) {
    private val preferences = context.getSharedPreferences("hub_directory", Context.MODE_PRIVATE)
    private val registration = HubRegistration(object : HubRegistrationStore {
        override fun groups() = additional()
        override fun primaryUrl() = CredentialStore(context).load()?.baseUrl
        override fun pending(): PendingHubRegistration? {
            val value = preferences.getString("pending", null) ?: return null
            val row = JSONObject(value)
            val id = row.getString("id")
            require(id.matches(Regex("[a-f0-9-]{36}")))
            return PendingHubRegistration(id, row.getString("name"), row.getString("baseUrl"))
        }
        override fun writePending(pending: PendingHubRegistration?) {
            val editor = preferences.edit()
            if (pending == null) editor.remove("pending") else editor.putString("pending", JSONObject()
                .put("id", pending.id).put("name", pending.name).put("baseUrl", pending.baseUrl).toString())
            check(editor.commit()) { "Could not save pending connection" }
        }
        override fun credential(id: String): HubConfig? {
            val store = CredentialStore(context, "group_$id")
            val config = store.load()
            // A temporarily unavailable Keystore must not make us abandon an issued credential.
            check(config != null || !store.exists()) { "Saved connection is unavailable" }
            return config
        }
        override fun saveCredential(id: String, config: HubConfig) = CredentialStore(context, "group_$id").save(config)
        override fun writeGroups(groups: List<SavedHub>) = write(groups)
    })
    fun additional(): List<SavedHub> = synchronized(lock) {
        val rows = runCatching { JSONArray(preferences.getString("groups", "[]")) }.getOrElse { JSONArray() }
        (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optString("id")
            if (!id.matches(Regex("[a-f0-9-]{36}"))) return@mapNotNull null
            SavedHub(id, row.optString("name").take(80), CredentialStore(context, "group_$id").load())
        }.take(3)
    }
    fun add(name: String, config: HubConfig, pair: (HubConfig) -> HubConfig) = synchronized(lock) {
        registration.add(name, config, pair)
        WidgetUpdates.request(context)
    }
    fun recoverPending(): Boolean = synchronized(lock) {
        registration.recover().also { if (it) WidgetUpdates.request(context) }
    }
    fun remove(id: String) = synchronized(lock) {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        val current = additional()
        require(current.any { it.id == id })
        write(current.filterNot { it.id == id })
        CredentialStore(context, "group_$id").clear()
        WidgetUpdates.request(context)
    }
    private fun write(groups: List<SavedHub>) {
        val rows = JSONArray()
        groups.forEach { rows.put(JSONObject().put("id", it.id).put("name", it.name)) }
        check(preferences.edit().putString("groups", rows.toString()).commit())
    }
    companion object { private val lock = Any() }
}
