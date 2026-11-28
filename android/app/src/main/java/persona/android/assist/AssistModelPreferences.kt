package persona.android.assist

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import persona.android.PersonaApp
import persona.android.R
import persona.android.ui.AppServices
import persona.core.contracts.ModelPick
import persona.core.contracts.SessionKey
import persona.core.contracts.api.AgentLauncher
import persona.core.contracts.api.ConnectionState
import persona.core.contracts.api.DigitalAssistantModelCatalog
import persona.core.contracts.api.DigitalAssistantModelChoice
import persona.core.contracts.api.DigitalAssistantModelGroup
import persona.core.contracts.api.DigitalAssistantModelOption
import persona.core.contracts.api.DigitalAssistantModelSettings
import persona.core.contracts.api.LauncherModels
import persona.core.domain.lines.ModelChanges
import persona.feature.chat.ChatWiringSource

/**
 * Locally persisted floating-overlay target conversation + optional non-main model override,
 * scoped per paired Hub. When [target] is [AssistTargetConversation.Main], Main's persisted
 * [DigitalAssistantModelChoice] (`overlay_model:<scope>`) remains the single source of truth.
 */
internal data class AssistOverlaySelection(
    val target: AssistTargetConversation = AssistTargetConversation.Main,
    val targetModelChoice: DigitalAssistantModelChoice? = null,
    val explicitDefaultModel: Boolean = false,
)

/**
 * Local per-Hub persistence for Android's Digital Assistant floating overlay model preference.
 * Stores only non-sensitive launcher/model identifiers and display names in private app prefs.
 */
internal class AssistModelPreferences(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    fun read(hubScope: String): DigitalAssistantModelChoice {
        val raw = prefs.getString(keyFor(hubScope), null) ?: return DigitalAssistantModelChoice()
        return decodeChoice(raw) ?: DigitalAssistantModelChoice()
    }

    fun write(hubScope: String, choice: DigitalAssistantModelChoice) {
        val key = keyFor(hubScope)
        if (choice.pick == null) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, encodeChoice(choice)).apply()
        }
    }

    fun clear(hubScope: String) {
        prefs.edit().remove(keyFor(hubScope)).apply()
    }

    fun readSelection(hubScope: String): AssistOverlaySelection {
        val raw = prefs.getString(selectionKeyFor(hubScope), null) ?: return AssistOverlaySelection()
        return decodeSelection(raw) ?: AssistOverlaySelection()
    }

    fun writeSelection(hubScope: String, selection: AssistOverlaySelection) {
        val key = selectionKeyFor(hubScope)
        if (selection.target is AssistTargetConversation.Main &&
            selection.targetModelChoice == null &&
            !selection.explicitDefaultModel
        ) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, encodeSelection(selection)).apply()
        }
    }

    fun clearSelection(hubScope: String) {
        prefs.edit().remove(selectionKeyFor(hubScope)).apply()
    }

    private fun keyFor(hubScope: String): String =
        "$KEY_PREFIX${hubScope.trim().ifEmpty { DEFAULT_SCOPE }}"

    private fun selectionKeyFor(hubScope: String): String =
        "$SELECTION_KEY_PREFIX${hubScope.trim().ifEmpty { DEFAULT_SCOPE }}"

    companion object {
        private const val PREFS_NAME = "persona_assist_model_prefs"
        private const val KEY_PREFIX = "overlay_model:"
        private const val SELECTION_KEY_PREFIX = "overlay_selection:"
        private const val DEFAULT_SCOPE = "default"

        internal fun encodeChoice(choice: DigitalAssistantModelChoice): String {
            val pick = choice.pick ?: return ""
            return JSONObject().apply {
                put("launcher", pick.launcher)
                pick.model?.takeIf(String::isNotBlank)?.let { put("model", it) }
                pick.effort?.takeIf(String::isNotBlank)?.let { put("effort", it) }
                choice.modelLabel.takeIf(String::isNotBlank)?.let { put("modelLabel", it) }
                choice.launcherLabel.takeIf(String::isNotBlank)?.let { put("launcherLabel", it) }
            }.toString()
        }

        internal fun decodeChoice(raw: String): DigitalAssistantModelChoice? {
            if (raw.isBlank()) return null
            val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val launcher = obj.optString("launcher").trim()
            if (launcher.isEmpty()) return null
            val model = obj.optString("model").trim().ifEmpty { null }
            val effort = obj.optString("effort").trim().ifEmpty { null }
            val modelLabel = obj.optString("modelLabel").trim()
            val launcherLabel = obj.optString("launcherLabel").trim()
            return DigitalAssistantModelChoice(
                pick = ModelPick(launcher = launcher, model = model, effort = effort),
                modelLabel = modelLabel,
                launcherLabel = launcherLabel,
            )
        }

        internal fun encodeSelection(selection: AssistOverlaySelection): String =
            JSONObject().apply {
                when (val target = selection.target) {
                    AssistTargetConversation.Main -> put("kind", "main")
                    AssistTargetConversation.NewChat -> put("kind", "new")
                    is AssistTargetConversation.Existing -> {
                        put("kind", "existing")
                        put("machineId", target.key.machineId)
                        put("sessionId", target.key.sessionId)
                        target.title.trim().takeIf(String::isNotEmpty)?.let { put("title", it) }
                    }
                }
                if (selection.target !is AssistTargetConversation.Main) {
                    val choice = selection.targetModelChoice
                    if (choice?.pick != null) {
                        put("modelChoice", JSONObject(encodeChoice(choice)))
                    }
                    if (selection.explicitDefaultModel) {
                        put("explicitDefaultModel", true)
                    }
                }
            }.toString()

        internal fun decodeSelection(raw: String): AssistOverlaySelection? {
            if (raw.isBlank()) return null
            val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val target: AssistTargetConversation = when (obj.optString("kind").trim()) {
                "main", "" -> AssistTargetConversation.Main
                "new" -> AssistTargetConversation.NewChat
                "existing" -> {
                    val machineId = obj.optString("machineId").trim()
                    val sessionId = obj.optString("sessionId").trim()
                    if (machineId.isEmpty() || sessionId.isEmpty()) return null
                    AssistTargetConversation.Existing(
                        key = SessionKey(machineId = machineId, sessionId = sessionId),
                        title = obj.optString("title").trim(),
                    )
                }
                else -> return null
            }
            if (target is AssistTargetConversation.Main) {
                return AssistOverlaySelection(target = AssistTargetConversation.Main)
            }
            val modelObj = obj.optJSONObject("modelChoice")
            val targetModelChoice = modelObj?.toString()?.let(::decodeChoice)?.takeIf { it.pick != null }
            val explicitDefaultModel = targetModelChoice == null && obj.optBoolean("explicitDefaultModel", false)
            return AssistOverlaySelection(
                target = target,
                targetModelChoice = targetModelChoice,
                explicitDefaultModel = explicitDefaultModel,
            )
        }
    }
}

private val RAW_PREVIEW_DATE_SUFFIX = Regex("""-(?:preview|exp)(?:-\d{2}-\d{2})?$""", RegexOption.IGNORE_CASE)
private val CLAUDE_FRIENDLY_PREFIX = Regex("""^claude\s+(opus|sonnet|haiku)\b""", RegexOption.IGNORE_CASE)
private val UPPERCASE_MODEL_TOKENS = setOf("gpt", "glm", "o1", "o3", "o4", "xl", "xhigh", "ai")

internal fun formatCompactAssistEffort(effort: String): String = when (effort.trim().lowercase()) {
    "xhigh", "very_high", "very-high" -> "XHigh"
    "high" -> "High"
    "medium", "med" -> "Med"
    "low" -> "Low"
    "max" -> "Max"
    else -> effort.trim().replaceFirstChar { it.uppercaseChar() }
}

/**
 * Formats a concise, readable model display name for the floating overlay pill:
 * - Reuses [ModelChanges.replyModelName] for raw vendor/date-suffixed model IDs (e.g.
 *   `claude-sonnet-4-5-20250929` -> `Sonnet 4.5`, `openai/gpt-5.3-codex-20260401` -> `GPT-5.3 Codex`).
 * - Strips redundant leading `Claude ` before `Opus`/`Sonnet`/`Haiku` and cleans kebab-case IDs
 *   like `gemini-4-neon-medium` -> `Gemini 4 Neon Medium`.
 * - Appends compact effort (`· XHigh` / `· High` / `· Med` / `· Low`) when `pick.effort` is set
 *   and not already present in the label.
 */
internal fun formatCompactAssistModelLabel(choice: DigitalAssistantModelChoice): String {
    val raw = choice.modelLabel.trim()
        .ifBlank { choice.pick?.model?.trim().orEmpty() }
        .ifBlank { choice.launcherLabel.trim() }
        .ifBlank { choice.pick?.launcher?.trim().orEmpty() }
    if (raw.isEmpty()) return ""

    val looksLikeRawId = !raw.contains(' ') && (raw.contains('-') || raw.contains('/') || raw.contains('@'))
    var label = if (looksLikeRawId) {
        val cleaned = ModelChanges.bareModel(raw).replace(RAW_PREVIEW_DATE_SUFFIX, "")
        val fromDomain = ModelChanges.replyModelName(cleaned)
        if (fromDomain.contains('-') && !fromDomain.contains(' ')) {
            formatKebabModelId(fromDomain)
        } else {
            fromDomain
        }
    } else {
        raw
    }

    if (CLAUDE_FRIENDLY_PREFIX.containsMatchIn(label)) {
        label = label.replaceFirst(Regex("""^claude\s+""", RegexOption.IGNORE_CASE), "")
    }

    val effort = choice.pick?.effort?.trim().orEmpty()
    if (effort.isNotEmpty()) {
        val formattedEffort = formatCompactAssistEffort(effort)
        val lower = label.lowercase()
        val hasEffort = lower.contains(effort.lowercase()) ||
            lower.contains(formattedEffort.lowercase()) ||
            (effort.equals("medium", ignoreCase = true) && lower.contains("med"))
        if (!hasEffort) {
            label = "$label · $formattedEffort"
        }
    }
    return label.trim()
}

private fun formatKebabModelId(id: String): String {
    val parts = id.split('-').filter(String::isNotEmpty)
    if (parts.isEmpty()) return id
    val out = mutableListOf<String>()
    var i = 0
    while (i < parts.size) {
        val token = parts[i]
        val lower = token.lowercase()
        if ((lower == "gpt" || lower == "glm") && i + 1 < parts.size && parts[i + 1].firstOrNull()?.isDigit() == true) {
            out += "${token.uppercase()}-${parts[i + 1]}"
            i += 2
            continue
        }
        out += when {
            lower == "xhigh" -> "XHigh"
            lower in UPPERCASE_MODEL_TOKENS -> token.uppercase()
            token.first().isDigit() -> token
            else -> token.replaceFirstChar { it.uppercaseChar() }
        }
        i++
    }
    return out.joinToString(" ")
}

/**
 * Pure mapper converting a Hub's assistant launchers and per-launcher model lists into grouped
 * picker options for both the floating overlay model pill and Settings › Default digital assistant.
 */
internal fun buildDigitalAssistantModelGroups(
    launchers: List<AgentLauncher>,
    modelsByLauncher: Map<String, LauncherModels?>,
): List<DigitalAssistantModelGroup> = launchers.mapNotNull { launcher ->
    val launcherName = launcher.name.trim()
    if (launcherName.isEmpty()) return@mapNotNull null
    val title = launcher.title.ifBlank { launcherName }
    val info = modelsByLauncher[launcherName]
    val unlockedModels = if (info != null && info.locked.isNullOrBlank()) info.models else emptyList()
    val options = if (unlockedModels.isNotEmpty()) {
        val defaultModelId = launcher.model?.trim().orEmpty()
        unlockedModels
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .map { model ->
                DigitalAssistantModelOption(
                    pick = ModelPick(launcher = launcherName, model = model.id),
                    modelLabel = model.name.trim().ifBlank { model.id },
                    launcherLabel = title,
                    description = model.description?.trim()?.takeIf(String::isNotEmpty),
                    isDefaultForLauncher = defaultModelId.isNotEmpty() && model.id == defaultModelId,
                )
            }
    } else {
        val defaultModel = launcher.model?.trim()?.takeIf(String::isNotEmpty)
        listOf(
            DigitalAssistantModelOption(
                pick = ModelPick(launcher = launcherName, model = defaultModel),
                modelLabel = defaultModel.orEmpty(),
                launcherLabel = title,
                description = null,
                isDefaultForLauncher = true,
            ),
        )
    }
    if (options.isEmpty()) return@mapNotNull null
    DigitalAssistantModelGroup(
        launcher = launcherName,
        title = title,
        options = options,
    )
}

/**
 * Singleton runtime owning the Digital Assistant floating overlay's Hub-scoped model preference
 * and Hub model catalog loading. Shared between [PersonaVoiceInteractionSession], [AssistActivity],
 * and Settings › Default digital assistant so changes sync immediately in both places.
 */
@Singleton
class AssistModelRuntime @Inject constructor(
    application: Application,
    private val services: AppServices,
    private val wiring: ChatWiringSource,
) : DigitalAssistantModelSettings {
    private val app = application as PersonaApp
    private val store = AssistModelPreferences(application)
    private val activeScope = MutableStateFlow(services.session.value?.identity.orEmpty())
    private val mutableChoice = MutableStateFlow(store.read(activeScope.value))
    override val choice: StateFlow<DigitalAssistantModelChoice> = mutableChoice.asStateFlow()
    private val mutableSelection = MutableStateFlow(store.readSelection(activeScope.value))
    internal val selection: StateFlow<AssistOverlaySelection> = mutableSelection.asStateFlow()

    init {
        app.scope.launch {
            services.session.collect { session ->
                val scopeId = session?.identity.orEmpty()
                activeScope.value = scopeId
                mutableChoice.value = store.read(scopeId)
                mutableSelection.value = store.readSelection(scopeId)
            }
        }
    }

    internal fun saveSelection(selection: AssistOverlaySelection) {
        val scopeId = currentScopeId()
        store.writeSelection(scopeId, selection)
        mutableSelection.value = selection
    }

    override fun selectDefault() {
        val scopeId = currentScopeId()
        store.clear(scopeId)
        mutableChoice.value = DigitalAssistantModelChoice()
    }

    override fun selectModel(choice: DigitalAssistantModelChoice) {
        val scopeId = currentScopeId()
        if (choice.pick == null) {
            store.clear(scopeId)
            mutableChoice.value = DigitalAssistantModelChoice()
            return
        }
        store.write(scopeId, choice)
        mutableChoice.value = choice
    }

    override suspend fun loadCatalog(refresh: Boolean): DigitalAssistantModelCatalog {
        val session = services.session.value
            ?: return DigitalAssistantModelCatalog(
                loading = false,
                hubAvailable = false,
                error = app.getString(R.string.app_assist_hub_unpaired_title),
            )
        if (session.demo != null) {
            return DigitalAssistantModelCatalog(
                loading = false,
                hubAvailable = false,
            )
        }
        val activeBinding = wiring.current.value?.takeIf { it.scope.path == session.identity }
        val connState = (activeBinding?.connection ?: session.connection).state.value
        if (activeBinding == null || connState != ConnectionState.Online) {
            return DigitalAssistantModelCatalog(
                loading = false,
                hubAvailable = false,
                error = app.getString(R.string.app_assist_hub_offline_title),
            )
        }
        val management = activeBinding.assistantManagement
        return try {
            val machineId = resolveAssistantMachineId(session, management)
                ?: return DigitalAssistantModelCatalog(
                    loading = false,
                    hubAvailable = true,
                    groups = emptyList(),
                    error = app.getString(R.string.app_assist_model_load_failed),
                )
            val launchers = management.assistantLauncherList(machineId, refresh).launchers
            val modelsByLauncher = coroutineScope {
                launchers.map { launcher ->
                    async(Dispatchers.IO) {
                        val models = try {
                            management.launcherModels(machineId, launcher.name)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                        launcher.name to models
                    }
                }.awaitAll().toMap()
            }
            DigitalAssistantModelCatalog(
                loading = false,
                groups = buildDigitalAssistantModelGroups(launchers, modelsByLauncher),
                error = null,
                hubAvailable = true,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DigitalAssistantModelCatalog(
                loading = false,
                groups = emptyList(),
                error = error.message?.takeIf(String::isNotBlank)
                    ?: app.getString(R.string.app_assist_model_load_failed),
                hubAvailable = true,
            )
        }
    }

    private fun currentScopeId(): String =
        services.session.value?.identity ?: activeScope.value

    private suspend fun resolveAssistantMachineId(
        session: persona.android.ui.ShellSession,
        management: persona.core.contracts.api.AssistantManagement,
    ): String? {
        val assistantSnapshot = session.assistant.assistant.value
        val direct = assistantSnapshot.mainSession?.machineId?.takeIf(String::isNotBlank)
            ?: assistantSnapshot.conversations.firstOrNull { it.machineId.isNotBlank() }?.machineId
        if (direct != null) return direct
        val fromConfig = try {
            management.config().machineId.takeIf(String::isNotBlank)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (fromConfig != null) return fromConfig
        val machines = session.sessions.machines.value
        return machines.firstOrNull { it.online && it.id.isNotBlank() }?.id
            ?: machines.firstOrNull { it.id.isNotBlank() }?.id
    }
}
