package dev.persona.watch

import android.content.Context
import dev.persona.watch.data.HubConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Names and IDs are metadata; every credential stays in its own Keystore-encrypted envelope. */
data class SavedHub(val id: String, val name: String, val config: HubConfig?)
class HubDirectory(private val context: Context) {
    private val preferences = context.getSharedPreferences("hub_directory", Context.MODE_PRIVATE)
    fun additional(): List<SavedHub> = synchronized(lock) {
        val rows = runCatching { JSONArray(preferences.getString("groups", "[]")) }.getOrElse { JSONArray() }
        (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optString("id")
            if (!id.matches(Regex("[a-f0-9-]{36}"))) return@mapNotNull null
            SavedHub(id, row.optString("name").take(80), CredentialStore(context, "group_$id").load())
        }.take(3)
    }
    fun add(name: String, config: HubConfig) = synchronized(lock) {
        val current = additional()
        require(current.size < 3)
        require(current.none { it.config?.baseUrl == config.baseUrl } && CredentialStore(context).load()?.baseUrl != config.baseUrl)
        require(name.trim().isNotEmpty() && name.length <= 80)
        val id = UUID.randomUUID().toString()
        CredentialStore(context, "group_$id").save(config.validated())
        write(current + SavedHub(id, name.trim(), config))
        WidgetUpdates.request(context)
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
