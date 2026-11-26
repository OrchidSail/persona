package persona.feature.assistant.settings

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import persona.feature.assistant.R
import persona.core.contracts.Machine
import persona.core.contracts.api.AssistantConfig
import persona.core.contracts.api.AssistantConfigUpdate
import persona.core.contracts.api.AssistantGovernanceBounds
import persona.core.contracts.api.AssistantManagement
import persona.core.contracts.api.AssistantRefused
import persona.core.contracts.api.AssistantSkills
import persona.core.contracts.api.DefaultRepositoryProviders
import persona.core.contracts.api.DirectionLeader
import persona.core.contracts.api.DirectionNotesConflictException
import persona.core.contracts.api.DirectionNotesResult
import persona.core.contracts.api.MemoryFile
import persona.core.contracts.api.RepositoryBinding
import persona.core.contracts.api.RepositoryCatalog
import persona.core.contracts.api.RepositoryInput
import persona.core.contracts.api.RepositoryPreview
import persona.core.contracts.api.RepositoryProvider
import persona.core.contracts.api.RepositoryRemoval
import persona.core.contracts.api.SavedPlace
import persona.core.contracts.api.TaskTrustKind
import persona.core.contracts.parseTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/** The calls Settings › Assistant makes: narrower than [AssistantManagement], so it stays put while that one grows. */
internal interface AssistantSettingsApi {
    suspend fun config(): AssistantConfig
    suspend fun updateConfig(update: AssistantConfigUpdate): AssistantConfig
    suspend fun memory(): List<MemoryFile>
    suspend fun saveMemory(name: String, content: String)
    suspend fun deleteMemory(name: String)
    suspend fun places(): List<SavedPlace> = emptyList()
    suspend fun savePlace(place: SavedPlace): SavedPlace = throw UnsupportedOperationException("Saved places unavailable")
    suspend fun deletePlace(id: String): Unit = throw UnsupportedOperationException("Saved places unavailable")
    suspend fun skills(): AssistantSkills = throw UnsupportedOperationException("Skills unavailable")
    suspend fun taskTrust(): List<TaskTrustKind> = throw UnsupportedOperationException("Task trust unavailable")
    suspend fun revokeTaskTrust(key: String): Unit = throw UnsupportedOperationException("Task trust unavailable")
    suspend fun leaders(): List<DirectionLeader> = throw UnsupportedOperationException("Leaders unavailable")
    suspend fun updateDirectionNotes(id: String, notes: String, notesAt: String?): DirectionNotesResult =
        throw UnsupportedOperationException("Direction notes unavailable")
    suspend fun repositories(): RepositoryCatalog = throw UnsupportedOperationException("Repositories unavailable")
    suspend fun repositoryProviders(): List<RepositoryProvider> = DefaultRepositoryProviders
    suspend fun previewRepository(url: String, provider: String = ""): RepositoryPreview =
        throw UnsupportedOperationException("Repositories unavailable")
    suspend fun createRepository(input: RepositoryInput): RepositoryBinding =
        throw UnsupportedOperationException("Repositories unavailable")
    suspend fun updateRepository(id: String, input: RepositoryInput): RepositoryBinding =
        throw UnsupportedOperationException("Repositories unavailable")
    suspend fun deleteRepository(id: String): RepositoryRemoval =
        throw UnsupportedOperationException("Repositories unavailable")
    suspend fun restoreRepository(id: String, receipt: String): RepositoryBinding =
        throw UnsupportedOperationException("Repositories unavailable")
}

internal fun AssistantManagement.settingsApi(): AssistantSettingsApi {
    val management = this
    return object : AssistantSettingsApi {
        override suspend fun config() = management.config()
        override suspend fun updateConfig(update: AssistantConfigUpdate) = management.updateConfig(update)
        override suspend fun memory() = management.memory()
        override suspend fun saveMemory(name: String, content: String) = management.saveMemory(name, content)
        override suspend fun deleteMemory(name: String) = management.deleteMemory(name)
        override suspend fun places() = management.places()
        override suspend fun savePlace(place: SavedPlace) = management.savePlace(place)
        override suspend fun deletePlace(id: String) = management.deletePlace(id)
        override suspend fun skills() = management.skills()
        override suspend fun taskTrust() = management.taskTrust()
        override suspend fun revokeTaskTrust(key: String) = management.revokeTaskTrust(key)
        override suspend fun leaders() = management.leaders()
        override suspend fun updateDirectionNotes(id: String, notes: String, notesAt: String?) =
            management.updateDirectionNotes(id, notes, notesAt)
        override suspend fun repositories() = management.repositories()
        override suspend fun repositoryProviders() = management.repositoryProviders()
        override suspend fun previewRepository(url: String, provider: String) = management.previewRepository(url, provider)
        override suspend fun createRepository(input: RepositoryInput) = management.createRepository(input)
        override suspend fun updateRepository(id: String, input: RepositoryInput) = management.updateRepository(id, input)
        override suspend fun deleteRepository(id: String) = management.deleteRepository(id)
        override suspend fun restoreRepository(id: String, receipt: String) = management.restoreRepository(id, receipt)
    }
}

/**
 * Whether the hub's time zone follows this phone's (the default, as on iOS). The hub keeps the choice too (#1855,
 * `timezone_picked`), so every device leaves a picked zone alone; the phone's copy is what a hub from before goes by.
 */
internal interface TimezoneChoice {
    val phoneZone: String
    var followPhone: Boolean
}

internal class StoredTimezoneChoice(context: Context) : TimezoneChoice {
    private val preferences = context.applicationContext.getSharedPreferences("persona-assistant", Context.MODE_PRIVATE)
    override val phoneZone: String get() = TimeZone.getDefault().id
    override var followPhone: Boolean
        get() = preferences.getBoolean("timezone.follow_phone", true)
        set(value) { preferences.edit().putBoolean("timezone.follow_phone", value).apply() }
}

/** The zone to send so the hub follows the phone; null when it already does or the user picked one. */
internal fun zoneToSync(choice: TimezoneChoice, hubZone: String?): String? =
    choice.phoneZone.takeIf { choice.followPhone && it.isNotBlank() && it != hubZone }

/**
 * Routines and the assistant's sense of "today" follow the phone's zone unless the user picked another one. The hub's
 * word on that wins and is kept on the phone (a zone picked on another phone stays); a hub that has none yet is told
 * this phone's choice once a zone was picked here, so other devices stop changing it.
 */
internal suspend fun followPhoneZone(api: AssistantSettingsApi, choice: TimezoneChoice, known: AssistantConfig? = null): AssistantConfig {
    val config = known ?: api.config()
    config.timezonePicked?.let { choice.followPhone = !it }
    if (config.timezonePicked == null && !choice.followPhone && !config.timezone.isNullOrBlank()) {
        return api.updateConfig(AssistantConfigUpdate(timezone = config.timezone, timezonePicked = true))
    }
    val zone = zoneToSync(choice, config.timezone) ?: return config
    return api.updateConfig(AssistantConfigUpdate(timezone = zone))
}

/** Moving the assistant to another machine, waiting for the user to confirm. */
internal data class MachineMove(val fromName: SettingsText, val to: Machine) {
    fun title(resources: Resources): String = resources.getString(R.string.assistant_settings_move_title, to.displayName)
    fun text(resources: Resources): String = machineMoveWarning(resources, fromName.resolve(resources), to.displayName, to.online)
}

/** Conversations and memory are files on the machine the assistant runs on: they never move with it. */
internal fun machineMoveWarning(resources: Resources, from: String, to: String, online: Boolean): String = buildString {
    append(resources.getString(R.string.assistant_settings_move_text,
        resources.getString(R.string.assistant_settings_move_stays), resources.getString(R.string.assistant_settings_move_fresh, to, from)))
    if (!online) append("\n\n").append(resources.getString(R.string.assistant_settings_move_offline, to))
}

/**
 * Text the state hands the page: a string resource and its arguments, resolved in the page's language. An argument
 * may itself be a [SettingsText] (a fallback name such as "the original machine").
 */
internal class SettingsText(@StringRes val id: Int, vararg val args: Any) {
    fun resolve(resources: Resources): String =
        resources.getString(id, *args.map { if (it is SettingsText) it.resolve(resources) else it }.toTypedArray())

    override fun equals(other: Any?): Boolean = other is SettingsText && other.id == id && other.args.contentEquals(args)
    override fun hashCode(): Int = 31 * id + args.contentHashCode()

    companion object {
        /** A name the user or the hub gave: shown as it is in every language. */
        fun plain(text: String) = SettingsText(R.string.assistant_settings_plain, text)
    }
}

internal data class SettingsNotice(val text: SettingsText, val error: Boolean)

internal data class MemoryState(
    val entries: List<MemoryEntry>? = null,
    val index: MemoryFile? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
)

/** Saved places list (#9293); [error] is kept when a reload fails so the page can still show what it had. */
internal data class PlacesState(
    val entries: List<SavedPlace>? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val error: String? = null,
)

/**
 * The skills on the assistant's machine (Skills, like iOS): null until read. [failed] when they could not be read,
 * with the hub's own reason when it gave one ([reason], shown as it is).
 */
internal data class SkillsState(
    val list: AssistantSkills? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val reason: String? = null,
) {
    /** The page shows the empty state once it has an answer (or a failure) and no skill to list. */
    val empty: Boolean get() = !loading && (failed || list != null) && list?.skills.isNullOrEmpty()
}

internal data class TaskTrustState(
    val kinds: List<TaskTrustKind>? = null,
    val supported: Boolean = false,
    val loading: Boolean = false,
    val failed: Boolean = false,
)

internal data class DirectionNotesConflict(
    val leaderId: String,
    val currentNotes: String,
    val currentNotesAt: String?,
)

internal data class DirectionNotesState(
    val leaders: List<DirectionLeader>? = null,
    val supported: Boolean = false,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val conflict: DirectionNotesConflict? = null,
)

internal data class RemovedRepositoryUndo(
    val id: String,
    val displayName: String,
    val receipt: String,
)

internal data class RepositoriesState(
    val catalog: RepositoryCatalog? = null,
    val providers: List<RepositoryProvider> = DefaultRepositoryProviders,
    val supported: Boolean = false,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val error: String? = null,
    val undo: RemovedRepositoryUndo? = null,
)

internal data class AssistantSettingsUiState(
    val config: AssistantConfig? = null,
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val busy: Boolean = false,
    val notice: SettingsNotice? = null,
    val move: MachineMove? = null,
    val memory: MemoryState = MemoryState(),
    val places: PlacesState = PlacesState(),
    val skills: SkillsState = SkillsState(),
    val followPhone: Boolean = true,
    val taskTrust: TaskTrustState = TaskTrustState(),
    val directionNotes: DirectionNotesState = DirectionNotesState(),
    val repositories: RepositoriesState = RepositoriesState(),
) {
    val permissionSupported: Boolean get() = !loading && !loadFailed &&
        config?.permissionDefaults?.supports(config.manager) == true
}

/** Mutations run one at a time; every outcome lands in [state] (the page shows it, nothing is thrown). */
internal class AssistantSettingsState(
    private val api: AssistantSettingsApi,
    private val timezone: TimezoneChoice,
    /**
     * After a move, before [onMoved]: the hub has no main conversation now, so the outbox must forget the one it bound
     * on the old machine ([persona.core.contracts.api.MainConversationSender.mainChanged]); otherwise the next message
     * to the main conversation goes to that one, an ordinary conversation now.
     */
    private val mainChanged: suspend () -> Unit = {},
    private val recentTaskChatsChanged: suspend () -> suspend (Boolean?) -> Unit = { {} },
    /** After a move: the conversation list now comes from the other machine. */
    private val onMoved: suspend () -> Unit = {},
) {
    private val mutable = MutableStateFlow(AssistantSettingsUiState(followPhone = timezone.followPhone))
    val state = mutable.asStateFlow()
    val phoneZone: String get() = timezone.phoneZone

    private var configRevision = 0L
    private val recentTaskChatsLock = Mutex()

    private fun set(change: AssistantSettingsUiState.() -> AssistantSettingsUiState) = mutable.update(change)

    fun clearNotice() = set { copy(notice = null) }

    suspend fun load() = recentTaskChatsLock.withLock {
        val revision = ++configRevision
        set { copy(loading = true, loadFailed = false) }
        try {
            val config = api.config()
            currentCoroutineContext().ensureActive()
            if (revision != configRevision) return@withLock
            set { copy(config = config) }
            // Like iOS: opening the page brings the hub's zone along with the phone's.
            quietly {
                val synced = followPhoneZone(api, timezone, config)
                currentCoroutineContext().ensureActive()
                if (revision == configRevision) set { copy(config = synced, followPhone = timezone.followPhone) }
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { if (revision == configRevision) set { copy(loadFailed = true) } }
        finally { if (revision == configRevision) set { copy(loading = false) } }
    }

    fun requestMove(to: Machine, machines: List<Machine>) {
        val config = state.value.config ?: return
        if (state.value.busy || to.id == config.machineId) return
        val from = machines.firstOrNull { it.id == config.machineId }?.displayName ?: config.machineId.ifBlank { null }
        set { copy(move = MachineMove(from?.let(SettingsText::plain) ?: SettingsText(R.string.assistant_settings_move_from_unknown), to)) }
    }

    fun cancelMove() = set { copy(move = null) }

    suspend fun confirmMove() {
        val move = state.value.move ?: return
        set { copy(move = null) }
        val moved = perform(SettingsText(R.string.assistant_settings_move_failed, move.fromName)) {
            // The main conversation is a transcript on the old machine; the next message starts a new one here.
            val config = api.updateConfig(AssistantConfigUpdate(machineId = move.to.id, main = AssistantConfigUpdate.CLEAR_MAIN))
            set { copy(config = config, memory = MemoryState()) }
            done(SettingsText(R.string.assistant_settings_move_done, move.to.displayName))
        }
        if (!moved) return
        quietly { mainChanged() }
        quietly { onMoved() }
        loadMemory()
    }

    /** A default for future conversations; never writes the current conversation's mode. */
    suspend fun setDefaultPermission(mode: String) {
        val config = state.value.config ?: return
        val owner = config.manager?.takeIf(String::isNotBlank) ?: return
        if (!state.value.permissionSupported ||
            (config.defaultPermissionMode ?: "default") == mode) return
        require(mode in persona.core.contracts.api.AssistantPermissionDefaults.MODES)
        val revision = configRevision
        fun verify(value: AssistantConfig) {
            check(value.manager == owner) { "Assistant identity changed" }
            check(value.permissionDefaults?.supports(owner) == true) { "Assistant permission support changed" }
            check(value.defaultPermissionMode == mode || (mode == "default" && value.defaultPermissionMode.isNullOrBlank())) {
                "The hub did not save the permission default"
            }
        }
        perform(SettingsText(R.string.assistant_settings_permission_failed)) {
            val updated = api.updateConfig(AssistantConfigUpdate(defaultPermissionMode = mode))
            currentCoroutineContext().ensureActive()
            if (revision != configRevision) return@perform
            verify(updated)
            val persisted = api.config()
            currentCoroutineContext().ensureActive()
            if (revision != configRevision) return@perform
            verify(persisted)
            set { copy(config = persisted) }
        }
    }

    /**
     * Match phone: on, the hub takes this phone's zone and follows the devices again; off, the zone in effect becomes the
     * picked one, which no device changes (#1855). Either way the hub is told, so every device goes by it; when it can't
     * be, the switch goes back and says so.
     */
    suspend fun setFollowPhone(follow: Boolean) {
        if (state.value.busy || follow == state.value.followPhone) return
        timezone.followPhone = follow
        set { copy(followPhone = follow) }
        val failed = if (follow) R.string.assistant_settings_zone_sync_failed else R.string.assistant_settings_save_failed
        val saved = perform(SettingsText(failed)) {
            val update = if (follow) {
                AssistantConfigUpdate(timezone = timezone.phoneZone.takeIf { it.isNotBlank() }, timezonePicked = false)
            } else {
                AssistantConfigUpdate(timezone = state.value.config?.timezone, timezonePicked = true)
            }
            val updated = api.updateConfig(update)
            set { copy(config = updated) }
        }
        if (!saved) {
            timezone.followPhone = !follow
            set { copy(followPhone = !follow) }
        }
    }

    suspend fun chooseZone(zone: String) {
        if (state.value.followPhone || zone.isBlank() || zone == state.value.config?.timezone) return
        perform(SettingsText(R.string.assistant_settings_zone_failed)) {
            val updated = api.updateConfig(AssistantConfigUpdate(timezone = zone, timezonePicked = true))
            set { copy(config = updated) }
            done(SettingsText(R.string.assistant_settings_zone_done, zone))
        }
    }

    /** "Notify only in main chat": moves at once; a failure puts it back and says so. */
    suspend fun setMainTasksQuiet(on: Boolean) {
        val config = state.value.config ?: return
        if (config.mainTasksQuiet == null || config.mainTasksQuiet == on) return
        set { copy(config = config.copy(mainTasksQuiet = on)) }
        val ok = perform(SettingsText(R.string.assistant_settings_save_failed)) {
            val updated = api.updateConfig(AssistantConfigUpdate(mainTasksQuiet = on))
            set { copy(config = updated) }
        }
        if (!ok) set { copy(config = this.config?.copy(mainTasksQuiet = !on)) }
    }

    /** "Fold background rounds" (#4314): moves at once; a failure puts it back and says so. */
    suspend fun setMainBackgroundFolded(on: Boolean) {
        val config = state.value.config ?: return
        if (config.mainBackgroundFolded == null || config.mainBackgroundFolded == on) return
        set { copy(config = config.copy(mainBackgroundFolded = on)) }
        val ok = perform(SettingsText(R.string.assistant_settings_save_failed)) {
            val updated = api.updateConfig(AssistantConfigUpdate(mainBackgroundFolded = on))
            set { copy(config = updated) }
        }
        if (!ok) set { copy(config = this.config?.copy(mainBackgroundFolded = !on)) }
    }

    /** Show scheduled task chats in Recent: bind one snapshot updater for the write and every later outcome. */
    suspend fun setRecentTaskChats(on: Boolean) = recentTaskChatsLock.withLock {
        val config = state.value.config ?: return@withLock
        val was = config.recentTaskChats ?: return@withLock
        if (state.value.busy || was == on) return@withLock
        set { copy(config = config.copy(recentTaskChats = on)) }
        var applied = on
        var updateRecent: suspend (Boolean?) -> Unit = {}
        val ok = try {
            perform(SettingsText(R.string.assistant_settings_save_failed)) {
                updateRecent = recentTaskChatsChanged()
                updateRecent(on)
                val updated = api.updateConfig(AssistantConfigUpdate(recentTaskChats = on))
                applied = updated.recentTaskChats ?: on
                set { copy(config = updated.copy(recentTaskChats = applied)) }
            }
        } catch (cancelled: CancellationException) {
            set { copy(config = this.config?.copy(recentTaskChats = was)) }
            withContext(NonCancellable) { quietly { updateRecent(was) } }
            throw cancelled
        }
        if (!ok) {
            set { copy(config = this.config?.copy(recentTaskChats = was)) }
            quietly { updateRecent(was) }
        } else {
            // The hub accepted the write; a local snapshot sync failure is not a refusal and must not roll it back.
            quietly { updateRecent(applied) }
        }
    }

    /** Suggest the next message: moves at once, follows the hub's answer, and rolls back on failure. */
    suspend fun setNextSuggestions(on: Boolean) {
        val config = state.value.config ?: return
        val was = config.nextSuggestions ?: return
        if (state.value.busy || was == on) return
        set { copy(config = config.copy(nextSuggestions = on)) }
        val ok = perform(SettingsText(R.string.assistant_settings_save_failed)) {
            val updated = api.updateConfig(AssistantConfigUpdate(nextSuggestions = on))
            set { copy(config = updated.copy(nextSuggestions = updated.nextSuggestions ?: on)) }
        }
        if (!ok) set { copy(config = this.config?.copy(nextSuggestions = was)) }
    }

    /**
     * "The main chat supervises its own tasks" (#4927): off, the background supervisor follows them up. Moves at once;
     * a failure puts it back and says so.
     */
    suspend fun setMainSupervisesOwnTasks(on: Boolean) {
        val config = state.value.config ?: return
        if (config.mainSupervisesOwnTasks == null || config.mainSupervisesOwnTasks == on) return
        set { copy(config = config.copy(mainSupervisesOwnTasks = on)) }
        val ok = perform(SettingsText(R.string.assistant_settings_save_failed)) {
            val updated = api.updateConfig(AssistantConfigUpdate(mainSupervisesOwnTasks = on))
            set { copy(config = updated) }
        }
        if (!ok) set { copy(config = this.config?.copy(mainSupervisesOwnTasks = !on)) }
    }

    /**
     * "Close an Agent's terminal when it ends" (#10355): off by default. Moves at once; a failure puts it back and says
     * so.
     */
    suspend fun setCloseEndedTerminals(on: Boolean) {
        val config = state.value.config ?: return
        if (config.closeEndedTerminals == null || config.closeEndedTerminals == on) return
        set { copy(config = config.copy(closeEndedTerminals = on)) }
        val ok = perform(SettingsText(R.string.assistant_settings_save_failed)) {
            val updated = api.updateConfig(AssistantConfigUpdate(closeEndedTerminals = on))
            set { copy(config = updated) }
        }
        if (!ok) set { copy(config = this.config?.copy(closeEndedTerminals = !on)) }
    }

    /** "Let Persona make dashboards" (#15017): off by default and when the hub leaves it out; only the owner changes it. */
    suspend fun setDashboards(on: Boolean) {
        val config = state.value.config ?: return
        if ((config.dashboards ?: false) == on) return
        set { copy(config = config.copy(dashboards = on)) }
        val ok = perform(SettingsText(R.string.assistant_settings_save_failed)) {
            val updated = api.updateConfig(AssistantConfigUpdate(dashboards = on))
            set { copy(config = updated) }
        }
        if (!ok) set { copy(config = this.config?.copy(dashboards = !on)) }
    }

    /** Exact owner replies: default off, reversible, and accepted only after both the write and a fresh read agree. */
    suspend fun setOwnerReplyApproval(on: Boolean) = setOwnerApprovalSwitch(reply = true, on = on)

    /** Persistent proposals: default off; existing durable grants remain the server's responsibility when turned off. */
    suspend fun setPersistentOwnerActionCards(on: Boolean) = setOwnerApprovalSwitch(reply = false, on = on)

    private suspend fun setOwnerApprovalSwitch(reply: Boolean, on: Boolean) {
        val config = state.value.config ?: return
        val was = if (reply) config.ownerReplyApproval else config.persistentOwnerActionCards
        if (state.value.busy || was == null || was == on) return
        set {
            copy(config = if (reply) config.copy(ownerReplyApproval = on) else config.copy(persistentOwnerActionCards = on))
        }
        val ok = perform(SettingsText(R.string.assistant_settings_save_failed)) {
            val update = if (reply) AssistantConfigUpdate(ownerReplyApproval = on)
            else AssistantConfigUpdate(persistentOwnerActionCards = on)
            val updated = api.updateConfig(update)
            check(updated.manager == config.manager) { "Assistant identity changed" }
            check((if (reply) updated.ownerReplyApproval else updated.persistentOwnerActionCards) == on) {
                "The hub did not save the owner-approval setting"
            }
            val persisted = api.config()
            check(persisted.manager == config.manager) { "Assistant identity changed" }
            check((if (reply) persisted.ownerReplyApproval else persisted.persistentOwnerActionCards) == on) {
                "The owner-approval setting did not persist"
            }
            set { copy(config = persisted) }
        }
        if (!ok) set {
            copy(config = config?.let {
                if (reply) it.copy(ownerReplyApproval = was) else it.copy(persistentOwnerActionCards = was)
            })
        }
    }

    /** Saves the exact full-reply phrases; an empty list disables phrase approval without touching ordinary cards. */
    suspend fun setOwnerApprovalPhrases(phrases: List<String>) {
        val config = state.value.config ?: return
        if (state.value.busy || config.ownerReplyApprovalPhrases == null || config.ownerReplyApprovalPhrases == phrases) return
        perform(SettingsText(R.string.assistant_settings_save_failed)) {
            val updated = api.updateConfig(AssistantConfigUpdate(ownerReplyApprovalPhrases = phrases))
            check(updated.manager == config.manager && updated.ownerReplyApprovalPhrases == phrases) {
                "The hub did not save the exact approval phrases"
            }
            val persisted = api.config()
            check(persisted.manager == config.manager && persisted.ownerReplyApprovalPhrases == phrases) {
                "The exact approval phrases did not persist"
            }
            set { copy(config = persisted) }
        }
    }

    suspend fun saveNotes(text: String): Boolean = perform(SettingsText(R.string.assistant_settings_save_failed)) {
        val updated = api.updateConfig(AssistantConfigUpdate(notes = text))
        set { copy(config = updated) }
        done(SettingsText(R.string.assistant_settings_notes_saved))
    }

    suspend fun loadMemory() {
        set { copy(memory = memory.copy(loading = true, failed = false)) }
        try {
            val (entries, index) = memoryEntries(api.memory())
            set { copy(memory = MemoryState(entries, index)) }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { set { copy(memory = memory.copy(loading = false, failed = true)) } }
    }

    /** A failed reload keeps the list it had, like iOS; the reason shows only while there is nothing to list. */
    suspend fun loadSkills() {
        set { copy(skills = skills.copy(loading = true)) }
        try {
            val list = api.skills()
            set { copy(skills = SkillsState(list)) }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val reason = (error as? AssistantRefused)?.message?.trim()?.ifEmpty { null }
            set { copy(skills = skills.copy(loading = false, failed = true, reason = reason)) }
        }
    }

    suspend fun saveMemory(name: String, content: String): Boolean {
        val saved = perform(SettingsText(R.string.assistant_settings_save_failed)) {
            api.saveMemory(name, content)
            // Show the edit right away; the reload below picks up anything else that changed meanwhile.
            set {
                val files = memory.entries.orEmpty().map { it.file } + listOfNotNull(memory.index)
                val (entries, index) = memoryEntries(files.map { if (it.name == name) it.copy(content = content) else it })
                copy(memory = memory.copy(entries = entries, index = index))
            }
            done(SettingsText(R.string.assistant_settings_memory_saved))
        }
        if (saved) loadMemory()
        return saved
    }

    suspend fun deleteMemory(name: String): Boolean {
        val deleted = perform(SettingsText(R.string.assistant_settings_delete_failed)) {
            api.deleteMemory(name)
            set { copy(memory = memory.copy(entries = memory.entries?.filterNot { it.file.name == name })) }
            done(SettingsText(R.string.assistant_settings_memory_deleted))
        }
        if (deleted) loadMemory()
        return deleted
    }

    /** A failed reload keeps the list it had; [PlacesState.error] is shown only while there is nothing to list. */
    suspend fun loadPlaces() {
        set { copy(places = places.copy(loading = true, failed = false)) }
        try {
            val entries = api.places()
            set { copy(places = PlacesState(entries = entries)) }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val reason = error.message?.trim()?.ifEmpty { null }
            set {
                copy(places = places.copy(loading = false, failed = true, error = reason ?: places.error))
            }
        }
    }

    /** Adds or updates a place; null when the hub refused (busy, duplicate name, …). Trims name and address first. */
    suspend fun savePlace(place: SavedPlace): SavedPlace? {
        if (state.value.busy) return null
        val draft = place.copy(
            name = place.name.trim(),
            address = place.address.trim(),
        )
        var saved: SavedPlace? = null
        // Prefer the hub's reason (409 name taken, 400 too long / 50 places) over the generic save-failed text (#9293 R1).
        val ok = perform(SettingsText(R.string.assistant_settings_save_failed), preferMessage = true) {
            val result = api.savePlace(draft)
            saved = result
            set {
                val list = places.entries.orEmpty()
                val next = if (list.any { it.id == result.id }) list.map { if (it.id == result.id) result else it }
                else list + result
                copy(places = places.copy(entries = next, failed = false, error = null))
            }
            done(SettingsText(R.string.assistant_settings_places_saved, result.name))
        }
        return if (ok) saved else null
    }

    /** Optimistic: the row goes at once; a failure reports and reloads what the hub has. */
    suspend fun deletePlace(place: SavedPlace): Boolean {
        // Busy first: otherwise an optimistic remove rolls back with a reload and no message (#9293 R7).
        if (place.id.isBlank() || state.value.busy) return false
        val before = state.value.places.entries
        set { copy(places = places.copy(entries = places.entries?.filterNot { it.id == place.id })) }
        val deleted = perform(SettingsText(R.string.assistant_settings_delete_failed)) {
            api.deletePlace(place.id)
            done(SettingsText(R.string.assistant_settings_places_deleted, place.name))
        }
        if (!deleted) {
            set { copy(places = places.copy(entries = before)) }
            loadPlaces()
        }
        return deleted
    }

    // ------------------------------------------------------------------ High-frequency governance (W-E02)

    private suspend fun updateGovernanceConfig(
        requireOwnerMatch: Boolean = false,
        optimistic: (AssistantConfig) -> AssistantConfig,
        rollback: (AssistantConfig) -> AssistantConfig,
        update: AssistantConfigUpdate,
        verify: (AssistantConfig) -> Boolean = { true },
    ): Boolean {
        val current = state.value.config ?: return false
        if (state.value.busy) return false
        val owner = current.manager?.takeIf(String::isNotBlank)
        if (requireOwnerMatch && owner == null) return false
        set { copy(config = optimistic(current)) }
        val ok = perform(SettingsText(R.string.assistant_settings_save_failed), preferMessage = true) {
            val updated = api.updateConfig(update)
            if (requireOwnerMatch) {
                check(updated.manager == owner) { "Assistant identity changed" }
            }
            check(verify(updated)) { "The hub did not save the setting" }
            set { copy(config = updated) }
        }
        if (!ok) set { copy(config = this.config?.let(rollback)) }
        return ok
    }

    suspend fun setReadonlyMode(mode: String): Boolean {
        val config = state.value.config ?: return false
        val was = config.readonlyMode ?: return false
        if (was == mode || mode !in AssistantGovernanceBounds.READONLY_MODES) return false
        return updateGovernanceConfig(
            requireOwnerMatch = true,
            optimistic = { it.copy(readonlyMode = mode) },
            rollback = { it.copy(readonlyMode = was) },
            update = AssistantConfigUpdate(readonlyMode = mode),
            verify = { it.readonlyMode == mode },
        )
    }

    suspend fun setStartRequestedTasks(on: Boolean): Boolean {
        val config = state.value.config ?: return false
        val was = config.startRequestedTasks ?: return false
        if (was == on) return false
        return updateGovernanceConfig(
            optimistic = { it.copy(startRequestedTasks = on) },
            rollback = { it.copy(startRequestedTasks = was) },
            update = AssistantConfigUpdate(startRequestedTasks = on),
            verify = { it.startRequestedTasks == null || it.startRequestedTasks == on },
        )
    }

    suspend fun setDeliveryGate(gate: String): Boolean {
        val config = state.value.config ?: return false
        val was = config.effectiveDeliveryGate ?: return false
        if (was == gate || gate !in AssistantGovernanceBounds.DELIVERY_GATES) return false
        return updateGovernanceConfig(
            optimistic = { it.copy(deliveryGate = gate) },
            rollback = { it.copy(deliveryGate = config.deliveryGate, doneNeedsDelivery = config.doneNeedsDelivery) },
            update = AssistantConfigUpdate(deliveryGate = gate),
            verify = { it.effectiveDeliveryGate == null || it.effectiveDeliveryGate == gate },
        )
    }

    suspend fun setTaskWorkspacePreference(pref: String): Boolean {
        val config = state.value.config ?: return false
        val was = config.taskWorkspacePreference ?: return false
        if (was == pref || pref !in AssistantGovernanceBounds.WORKSPACE_PREFERENCES) return false
        return updateGovernanceConfig(
            optimistic = { it.copy(taskWorkspacePreference = pref) },
            rollback = { it.copy(taskWorkspacePreference = was) },
            update = AssistantConfigUpdate(taskWorkspacePreference = pref),
            verify = { it.taskWorkspacePreference == null || it.taskWorkspacePreference == pref },
        )
    }

    suspend fun setAutoSupervisionMessages(on: Boolean): Boolean {
        val config = state.value.config ?: return false
        val was = config.autoSupervisionMessages ?: return false
        if (was == on) return false
        return updateGovernanceConfig(
            optimistic = { it.copy(autoSupervisionMessages = on) },
            rollback = { it.copy(autoSupervisionMessages = was) },
            update = AssistantConfigUpdate(autoSupervisionMessages = on),
            verify = { it.autoSupervisionMessages == null || it.autoSupervisionMessages == on },
        )
    }

    suspend fun setTaskLessons(on: Boolean, expectedOwner: String? = null): Boolean {
        val config = state.value.config ?: return false
        val was = config.taskLessons ?: return false
        if (was == on) return false
        if (expectedOwner != null && config.manager != expectedOwner) return false
        return updateGovernanceConfig(
            requireOwnerMatch = !config.manager.isNullOrBlank(),
            optimistic = { it.copy(taskLessons = on) },
            rollback = { it.copy(taskLessons = was) },
            update = AssistantConfigUpdate(taskLessons = on),
            verify = { it.taskLessons == null || it.taskLessons == on },
        )
    }

    suspend fun setAgentTasks(on: Boolean): Boolean {
        val config = state.value.config ?: return false
        val was = config.agentTasks ?: return false
        if (was == on) return false
        return updateGovernanceConfig(
            optimistic = { it.copy(agentTasks = on) },
            rollback = { it.copy(agentTasks = was) },
            update = AssistantConfigUpdate(agentTasks = on),
            verify = { it.agentTasks == null || it.agentTasks == on },
        )
    }

    suspend fun setFoldRoutineRelayReplies(on: Boolean): Boolean {
        val config = state.value.config ?: return false
        val was = config.foldRoutineRelayReplies ?: return false
        if (was == on) return false
        return updateGovernanceConfig(
            optimistic = { it.copy(foldRoutineRelayReplies = on) },
            rollback = { it.copy(foldRoutineRelayReplies = was) },
            update = AssistantConfigUpdate(foldRoutineRelayReplies = on),
            verify = { it.foldRoutineRelayReplies == null || it.foldRoutineRelayReplies == on },
        )
    }

    suspend fun setClaudeInteractive(on: Boolean): Boolean {
        val config = state.value.config ?: return false
        val was = config.claudeInteractive ?: return false
        if (was == on) return false
        return updateGovernanceConfig(
            optimistic = { it.copy(claudeInteractive = on) },
            rollback = { it.copy(claudeInteractive = was) },
            update = AssistantConfigUpdate(claudeInteractive = on),
            verify = { it.claudeInteractive == null || it.claudeInteractive == on },
        )
    }

    suspend fun setInboxSignals(on: Boolean): Boolean {
        val config = state.value.config ?: return false
        val was = config.inboxSignals ?: return false
        if (was == on) return false
        return updateGovernanceConfig(
            optimistic = { it.copy(inboxSignals = on) },
            rollback = { it.copy(inboxSignals = was) },
            update = AssistantConfigUpdate(inboxSignals = on),
            verify = { it.inboxSignals == null || it.inboxSignals == on },
        )
    }

    suspend fun setInboxTodoForward(on: Boolean): Boolean {
        val config = state.value.config ?: return false
        val was = config.inboxTodoForward ?: return false
        if (was == on) return false
        return updateGovernanceConfig(
            optimistic = { it.copy(inboxTodoForward = on) },
            rollback = { it.copy(inboxTodoForward = was) },
            update = AssistantConfigUpdate(inboxTodoForward = on),
            verify = { it.inboxTodoForward == null || it.inboxTodoForward == on },
        )
    }

    suspend fun setAgentAutoNaming(
        enabled: Boolean? = null,
        max: Int? = null,
        intervalHours: Int? = null,
    ): Boolean {
        val config = state.value.config ?: return false
        if (max != null && max !in AssistantGovernanceBounds.AUTO_NAMING_MAX) return false
        if (intervalHours != null && intervalHours !in AssistantGovernanceBounds.AUTO_NAMING_INTERVAL_HOURS) return false
        val wasEnabled = config.agentAutoNaming
        val wasMax = config.agentAutoNamingMax
        val wasInterval = config.agentAutoNamingIntervalHours
        if (enabled == null && max == null && intervalHours == null) return false
        return updateGovernanceConfig(
            requireOwnerMatch = !config.manager.isNullOrBlank(),
            optimistic = {
                it.copy(
                    agentAutoNaming = enabled ?: it.agentAutoNaming,
                    agentAutoNamingMax = max ?: it.agentAutoNamingMax,
                    agentAutoNamingIntervalHours = intervalHours ?: it.agentAutoNamingIntervalHours,
                )
            },
            rollback = {
                it.copy(
                    agentAutoNaming = wasEnabled,
                    agentAutoNamingMax = wasMax,
                    agentAutoNamingIntervalHours = wasInterval,
                )
            },
            update = AssistantConfigUpdate(
                agentAutoNaming = enabled,
                agentAutoNamingMax = max,
                agentAutoNamingIntervalHours = intervalHours,
            ),
            verify = { updated ->
                (enabled == null || updated.agentAutoNaming == enabled) &&
                    (max == null || updated.agentAutoNamingMax == max) &&
                    (intervalHours == null || updated.agentAutoNamingIntervalHours == intervalHours)
            },
        )
    }

    suspend fun setCacheKeepalive(
        enabled: Boolean? = null,
        minutes: Int? = null,
        max: Int? = null,
    ): Boolean {
        val config = state.value.config ?: return false
        if (minutes != null && minutes !in AssistantGovernanceBounds.CACHE_KEEPALIVE_MINUTES) return false
        if (max != null && max !in AssistantGovernanceBounds.CACHE_KEEPALIVE_MAX) return false
        val wasEnabled = config.cacheKeepalive
        val wasMinutes = config.cacheKeepaliveMinutes
        val wasMax = config.cacheKeepaliveMax
        if (enabled == null && minutes == null && max == null) return false
        return updateGovernanceConfig(
            optimistic = {
                it.copy(
                    cacheKeepalive = enabled ?: it.cacheKeepalive,
                    cacheKeepaliveMinutes = minutes ?: it.cacheKeepaliveMinutes,
                    cacheKeepaliveMax = max ?: it.cacheKeepaliveMax,
                )
            },
            rollback = {
                it.copy(
                    cacheKeepalive = wasEnabled,
                    cacheKeepaliveMinutes = wasMinutes,
                    cacheKeepaliveMax = wasMax,
                )
            },
            update = AssistantConfigUpdate(
                cacheKeepalive = enabled,
                cacheKeepaliveMinutes = minutes,
                cacheKeepaliveMax = max,
            ),
            verify = { updated ->
                (enabled == null || updated.cacheKeepalive == null || updated.cacheKeepalive == enabled) &&
                    (minutes == null || updated.cacheKeepaliveMinutes == null || updated.cacheKeepaliveMinutes == minutes) &&
                    (max == null || updated.cacheKeepaliveMax == null || updated.cacheKeepaliveMax == max)
            },
        )
    }

    suspend fun setIdleCloseHours(hours: Int): Boolean {
        val config = state.value.config ?: return false
        val was = config.idleCloseHours ?: return false
        if (was == hours || hours !in AssistantGovernanceBounds.IDLE_CLOSE_HOURS) return false
        return updateGovernanceConfig(
            optimistic = { it.copy(idleCloseHours = hours) },
            rollback = { it.copy(idleCloseHours = was) },
            update = AssistantConfigUpdate(idleCloseHours = hours),
            verify = { it.idleCloseHours == null || it.idleCloseHours == hours },
        )
    }

    suspend fun setArchiveClosedDays(days: Int): Boolean {
        val config = state.value.config ?: return false
        val was = config.archiveClosedDays ?: return false
        if (was == days || days !in AssistantGovernanceBounds.ARCHIVE_CLOSED_DAYS) return false
        return updateGovernanceConfig(
            optimistic = { it.copy(archiveClosedDays = days) },
            rollback = { it.copy(archiveClosedDays = was) },
            update = AssistantConfigUpdate(archiveClosedDays = days),
            verify = { it.archiveClosedDays == null || it.archiveClosedDays == days },
        )
    }

    // ------------------------------------------------------------------ Task trust

    suspend fun loadTaskTrust() {
        set { copy(taskTrust = taskTrust.copy(loading = true, failed = false)) }
        try {
            val kinds = api.taskTrust()
            set { copy(taskTrust = TaskTrustState(kinds = kinds, supported = true)) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: UnsupportedOperationException) {
            set { copy(taskTrust = TaskTrustState(supported = false)) }
        } catch (_: Exception) {
            set { copy(taskTrust = taskTrust.copy(loading = false, failed = true)) }
        }
    }

    suspend fun revokeTaskTrust(kind: TaskTrustKind): Boolean {
        if (kind.key.isBlank() || state.value.busy) return false
        val before = state.value.taskTrust.kinds
        set { copy(taskTrust = taskTrust.copy(kinds = taskTrust.kinds?.filterNot { it.key == kind.key })) }
        val ok = perform(SettingsText(R.string.assistant_settings_delete_failed), preferMessage = true) {
            api.revokeTaskTrust(kind.key)
            done(SettingsText(R.string.assistant_governance_task_trust_revoked))
        }
        if (!ok) {
            set { copy(taskTrust = taskTrust.copy(kinds = before)) }
            loadTaskTrust()
        }
        return ok
    }

    // ------------------------------------------------------------------ Direction notes

    suspend fun loadLeaders() {
        set { copy(directionNotes = directionNotes.copy(loading = true, failed = false)) }
        try {
            val active = api.leaders().filter { it.id.isNotBlank() && !it.ended }
            set { copy(directionNotes = DirectionNotesState(leaders = active, supported = true)) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: UnsupportedOperationException) {
            set { copy(directionNotes = DirectionNotesState(supported = false)) }
        } catch (_: Exception) {
            set { copy(directionNotes = directionNotes.copy(loading = false, failed = true)) }
        }
    }

    fun clearDirectionConflict() = set { copy(directionNotes = directionNotes.copy(conflict = null)) }

    suspend fun saveDirectionNotes(
        leaderId: String,
        notes: String,
        expectedNotesAt: String?,
        forceOverwrite: Boolean = false,
    ): Boolean {
        if (leaderId.isBlank() || notes.codePointCount(0, notes.length) > AssistantGovernanceBounds.DIRECTION_NOTES_MAX) return false
        if (state.value.busy) return false
        val conflictAt = state.value.directionNotes.conflict?.takeIf { it.leaderId == leaderId }?.currentNotesAt
        set { copy(busy = true, notice = null, directionNotes = directionNotes.copy(conflict = null)) }
        return try {
            val at = if (forceOverwrite) {
                conflictAt ?: state.value.directionNotes.leaders?.firstOrNull { it.id == leaderId }?.notesAt
            } else expectedNotesAt
            val saved = api.updateDirectionNotes(leaderId, notes, at)
            set {
                val updated = directionNotes.leaders.orEmpty().map { leader ->
                    if (leader.id == leaderId) leader.copy(notes = saved.notes, notesAt = saved.notesAt) else leader
                }
                copy(
                    directionNotes = directionNotes.copy(leaders = updated, conflict = null),
                    notice = SettingsNotice(SettingsText(R.string.assistant_governance_direction_notes_saved), error = false),
                )
            }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (conflict: DirectionNotesConflictException) {
            set {
                val synced = directionNotes.leaders.orEmpty().map { leader ->
                    if (leader.id == leaderId) leader.copy(notes = conflict.currentNotes, notesAt = conflict.currentNotesAt) else leader
                }
                copy(
                    directionNotes = directionNotes.copy(
                        leaders = synced,
                        conflict = DirectionNotesConflict(leaderId, conflict.currentNotes, conflict.currentNotesAt),
                    ),
                )
            }
            false
        } catch (error: Exception) {
            val text = error.message?.trim()?.takeIf { it.isNotEmpty() }?.let(SettingsText::plain)
                ?: SettingsText(R.string.assistant_settings_save_failed)
            set { copy(notice = SettingsNotice(text, error = true)) }
            false
        } finally {
            set { copy(busy = false) }
        }
    }

    // ------------------------------------------------------------------ Repositories CRUD

    suspend fun loadRepositories() {
        set { copy(repositories = repositories.copy(loading = true, failed = false)) }
        try {
            val catalog = api.repositories()
            val providers = runCatching { api.repositoryProviders() }.getOrDefault(DefaultRepositoryProviders)
            set {
                copy(
                    repositories = repositories.copy(
                        catalog = catalog,
                        providers = providers,
                        supported = true,
                        loading = false,
                        failed = false,
                        error = null,
                    ),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: UnsupportedOperationException) {
            set { copy(repositories = RepositoriesState(supported = false)) }
        } catch (error: Exception) {
            val reason = error.message?.trim()?.ifEmpty { null }
            set {
                copy(
                    repositories = repositories.copy(
                        loading = false,
                        failed = true,
                        error = reason ?: repositories.error,
                    ),
                )
            }
        }
    }

    suspend fun previewRepository(url: String, provider: String = ""): Result<RepositoryPreview> = try {
        Result.success(api.previewRepository(url, provider))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    suspend fun saveRepository(id: String?, input: RepositoryInput): RepositoryBinding? {
        if (state.value.busy) return null
        val invalid = validateRepositoryInput(input)
        if (invalid != null) {
            set { copy(notice = SettingsNotice(SettingsText(invalid), error = true)) }
            return null
        }
        var saved: RepositoryBinding? = null
        val ok = perform(SettingsText(R.string.assistant_settings_save_failed), preferMessage = true) {
            val result = if (id.isNullOrBlank()) api.createRepository(input) else api.updateRepository(id, input)
            saved = result
            set {
                val cur = repositories.catalog ?: RepositoryCatalog()
                val next = if (cur.repositories.any { it.id == result.id }) {
                    cur.repositories.map { if (it.id == result.id) result else it }
                } else {
                    cur.repositories + result
                }
                copy(
                    repositories = repositories.copy(
                        catalog = cur.copy(repositories = next),
                        supported = true,
                        failed = false,
                        error = null,
                        undo = null,
                    ),
                )
            }
            done(SettingsText(R.string.assistant_governance_repositories_saved, result.displayName))
        }
        if (ok) quietly { loadRepositories() }
        return if (ok) saved else null
    }

    suspend fun deleteRepository(repo: RepositoryBinding): Boolean {
        if (repo.id.isBlank() || state.value.busy) return false
        val before = state.value.repositories.catalog
        set {
            val cur = repositories.catalog
            copy(
                repositories = repositories.copy(
                    catalog = cur?.copy(repositories = cur.repositories.filterNot { it.id == repo.id }),
                ),
            )
        }
        var receipt = ""
        val deleted = perform(SettingsText(R.string.assistant_settings_delete_failed), preferMessage = true) {
            val removal = api.deleteRepository(repo.id)
            receipt = removal.receipt
            set {
                copy(
                    repositories = repositories.copy(
                        undo = receipt.takeIf(String::isNotBlank)?.let { RemovedRepositoryUndo(repo.id, repo.displayName, it) },
                    ),
                )
            }
            done(SettingsText(R.string.assistant_governance_repositories_removed, repo.displayName))
        }
        if (!deleted) {
            set { copy(repositories = repositories.copy(catalog = before)) }
            loadRepositories()
        } else {
            quietly { loadRepositories() }
        }
        return deleted
    }

    suspend fun undoDeleteRepository(): Boolean {
        val pending = state.value.repositories.undo ?: return false
        if (state.value.busy) return false
        var restored: RepositoryBinding? = null
        val ok = perform(SettingsText(R.string.assistant_settings_save_failed), preferMessage = true) {
            val binding = api.restoreRepository(pending.id, pending.receipt)
            restored = binding
            set {
                val cur = repositories.catalog ?: RepositoryCatalog()
                val next = cur.repositories.filterNot { it.id == binding.id } + binding
                copy(repositories = repositories.copy(catalog = cur.copy(repositories = next), undo = null))
            }
            done(SettingsText(R.string.assistant_governance_repositories_restored, restored?.displayName ?: pending.displayName))
        }
        if (ok) quietly { loadRepositories() }
        return ok
    }

    fun dismissRepositoryUndo() = set { copy(repositories = repositories.copy(undo = null)) }

    private fun done(text: SettingsText) = set { copy(notice = SettingsNotice(text, error = false)) }

    private suspend fun perform(
        failure: SettingsText,
        preferMessage: Boolean = false,
        block: suspend () -> Unit,
    ): Boolean {
        if (state.value.busy) return false
        set { copy(busy = true, notice = null) }
        return try { block(); true }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val text = if (preferMessage) {
                error.message?.trim()?.takeIf { it.isNotEmpty() }?.let(SettingsText::plain) ?: failure
            } else failure
            set { copy(notice = SettingsNotice(text, error = true)) }
            false
        }
        finally { set { copy(busy = false) } }
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try { block() } catch (error: CancellationException) { throw error } catch (_: Exception) { }
    }
}

internal const val MEMORY_INDEX = "MEMORY.md"

/** One memory file: its front matter (`---` … `---`: name, description, type) and the text after it. */
internal data class MemoryEntry(
    val file: MemoryFile,
    val title: String,
    val description: String?,
    val type: String?,
    val body: String,
) {
    /** The kind of memory in the page's language; a kind the app does not know is shown as written. */
    fun typeLabel(resources: Resources): String? = when (type) {
        null -> null
        "user" -> resources.getString(R.string.assistant_settings_memory_type_user)
        "feedback" -> resources.getString(R.string.assistant_settings_memory_type_feedback)
        "project" -> resources.getString(R.string.assistant_settings_memory_type_project)
        "reference" -> resources.getString(R.string.assistant_settings_memory_type_reference)
        else -> type
    }
    val updated: Instant? get() = parseTime(file.updatedAt)
}

/** The memory files newest first, titled from the index where it has a line for them, and the index itself. */
internal fun memoryEntries(files: List<MemoryFile>): Pair<List<MemoryEntry>, MemoryFile?> {
    val index = files.firstOrNull { it.name == MEMORY_INDEX }
    val titles = index?.let { memoryIndexLines(it.content) }.orEmpty()
    val entries = files.filter { it.name != MEMORY_INDEX }.map { parseMemory(it, titles[it.name]) }
        .sortedWith(compareByDescending<MemoryEntry> { it.updated ?: Instant.EPOCH }.thenBy { it.file.name })
    return entries to index
}

/** The index's lines `- [title](file.md) — description`, by file. */
internal fun memoryIndexLines(content: String): Map<String, Pair<String, String?>> = buildMap {
    for (line in content.lines()) {
        val open = line.indexOf('[').takeIf { it >= 0 } ?: continue
        val close = line.indexOf("](", open).takeIf { it > open } ?: continue
        val paren = line.indexOf(')', close + 2).takeIf { it > close } ?: continue
        val title = line.substring(open + 1, close).trim()
        val file = line.substring(close + 2, paren).trim()
        val hook = line.substring(paren + 1).trimStart(' ', '—', '–', '-', ':').trim()
        if (title.isNotEmpty() && file.isNotEmpty()) put(file, title to hook.ifEmpty { null })
    }
}

internal fun parseMemory(file: MemoryFile, indexed: Pair<String, String?>? = null): MemoryEntry {
    val lines = file.content.lines()
    val end = if (lines.firstOrNull()?.trim() == "---") lines.drop(1).indexOfFirst { it.trim() == "---" } + 1 else 0
    val fields = mutableMapOf<String, String>()
    if (end > 0) for (line in lines.subList(1, end)) {
        val colon = line.indexOf(':').takeIf { it > 0 } ?: continue
        // `type` sits under `metadata:` (indented) in newer files and at the top level in older ones.
        val key = line.substring(0, colon).trim()
        var value = line.substring(colon + 1).trim()
        if (value.length >= 2 && (value.first() == '"' || value.first() == '\'') && value.last() == value.first())
            value = value.substring(1, value.length - 1)
        if (value.isNotEmpty() && key in setOf("name", "description", "type")) fields.putIfAbsent(key, value)
    }
    val body = (if (end > 0) lines.drop(end + 1) else lines).joinToString("\n").trim()
    return MemoryEntry(
        file, title = indexed?.first ?: fields["name"] ?: file.name.removeSuffix(".md"),
        description = fields["description"] ?: indexed?.second, type = fields["type"], body = body,
    )
}

/** `~/.persona/assistant` rather than the full path, when it is under the machine's home. */
internal fun homeRelative(path: String, home: String?): String {
    val root = home?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return path
    return when {
        path == root -> "~"
        path.startsWith("$root/") -> "~" + path.removePrefix(root)
        else -> path
    }
}

/** "UTC+8", "UTC+5:30", "UTC" for a zone right now; null for an id Java does not know. */
internal fun zoneOffset(zone: String, at: Instant = Instant.now()): String? = runCatching {
    val seconds = ZoneId.of(zone).rules.getOffset(at).totalSeconds
    if (seconds == 0) return@runCatching "UTC"
    val sign = if (seconds < 0) "-" else "+"
    val hours = Math.abs(seconds) / 3600
    val minutes = Math.abs(seconds) % 3600 / 60
    "UTC$sign$hours" + if (minutes != 0) String.format(Locale.ROOT, ":%02d", minutes) else ""
}.getOrNull()

/** The zone's name in [locale] ("China Standard Time" in English, its own name in Chinese), when the system has one. */
internal fun zoneName(zone: String, locale: Locale): String? = runCatching {
    TimeZone.getTimeZone(ZoneId.of(zone)).getDisplayName(locale).takeIf { it.isNotBlank() && !it.startsWith("GMT") }
}.getOrNull()

/** Region zones (Asia/Shanghai …) by offset, then name: what the picker lists. */
internal fun pickableZones(at: Instant = Instant.now()): List<String> = ZoneId.getAvailableZoneIds()
    .filter { '/' in it && !it.startsWith("Etc/") && !it.startsWith("SystemV/") && it.first().isUpperCase() }
    .sortedWith(compareBy<String> { runCatching { ZoneId.of(it).rules.getOffset(at).totalSeconds }.getOrDefault(0) }.thenBy { it })

internal fun zoneMatches(zone: String, query: String, locale: Locale): Boolean {
    val q = query.trim()
    return q.isEmpty() || zone.contains(q, ignoreCase = true) || zone.replace('_', ' ').contains(q, ignoreCase = true) ||
        zoneName(zone, locale)?.contains(q, ignoreCase = true) == true || zoneOffset(zone)?.contains(q, ignoreCase = true) == true
}

/** Splits comma- or newline-separated repository aliases, trimming and deduplicating case-insensitively. */
internal fun parseRepositoryAliases(raw: String): List<String> {
    val seen = linkedSetOf<String>()
    val out = mutableListOf<String>()
    raw.split(',', '，', '\n').forEach { part ->
        val cleaned = part.trim()
        if (cleaned.isNotEmpty() && seen.add(cleaned.lowercase(Locale.ROOT))) {
            out += cleaned
        }
    }
    return out
}

/** Client-side validation matching server `repositoryInput.details`; returns an error string resource or null when valid. */
@StringRes
internal fun validateRepositoryInput(input: RepositoryInput): Int? {
    if (input.url.trim().isEmpty()) return R.string.assistant_governance_repositories_url_required
    val nameLen = input.name.trim().let { it.codePointCount(0, it.length) }
    val purposeLen = input.purpose.trim().let { it.codePointCount(0, it.length) }
    val aliases = input.aliases.map(String::trim).filter(String::isNotEmpty)
    if (nameLen > AssistantGovernanceBounds.REPOSITORY_NAME_MAX ||
        purposeLen > AssistantGovernanceBounds.REPOSITORY_PURPOSE_MAX ||
        aliases.size > AssistantGovernanceBounds.REPOSITORY_ALIASES_MAX ||
        aliases.any { it.codePointCount(0, it.length) > AssistantGovernanceBounds.REPOSITORY_ALIAS_MAX } ||
        input.taskWorkflow.trim() !in AssistantGovernanceBounds.TASK_WORKFLOWS
    ) {
        return R.string.assistant_governance_repositories_validation_metadata
    }
    return null
}
