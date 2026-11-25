@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package persona.core.contracts.api

import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import persona.core.contracts.WireSerializer

/**
 * Model profiles (`GET/PUT /api/v1/model-profiles`, `POST .../apply`, `POST .../rollback`;
 * docs/assistant/model-profiles.md): named bundles of purpose bindings, managed launcher overrides,
 * and Agent presets (`tiers`) switched atomically in one step.
 */
@KeepGeneratedSerializer
@Serializable(with = ModelProfileLauncherModelsSerializer::class)
data class ModelProfileLauncherModels(
    @SerialName("model") val model: String? = null,
    @SerialName("small_model") val smallModel: String? = null,
    @SerialName("subagent_model") val subagentModel: String? = null,
    @SerialName("opus_model") val opusModel: String? = null,
    @SerialName("sonnet_model") val sonnetModel: String? = null,
    @SerialName("haiku_model") val haikuModel: String? = null,
    @SerialName("fable_model") val fableModel: String? = null,
    @SerialName("effort") val effort: String? = null,
) {
    /** Emits only non-null fields; an empty string explicitly clears the field on switch. */
    fun toJson(): JsonObject = buildJsonObject {
        model?.let { put("model", it) }
        smallModel?.let { put("small_model", it) }
        subagentModel?.let { put("subagent_model", it) }
        opusModel?.let { put("opus_model", it) }
        sonnetModel?.let { put("sonnet_model", it) }
        haikuModel?.let { put("haiku_model", it) }
        fableModel?.let { put("fable_model", it) }
        effort?.let { put("effort", it) }
    }

    fun isEmpty(): Boolean =
        model == null && smallModel == null && subagentModel == null && opusModel == null &&
            sonnetModel == null && haikuModel == null && fableModel == null && effort == null
}

/** One ordered fallback candidate inside an Agent preset (`ModelTier.candidates`). */
@KeepGeneratedSerializer
@Serializable(with = ModelProfileTierCandidateSerializer::class)
data class ModelProfileTierCandidate(
    @SerialName("launcher") val launcher: String = "",
    @SerialName("model") val model: String? = null,
    @SerialName("effort") val effort: String? = null,
    @SerialName("speed") val speed: String? = null,
    @SerialName("permission_mode") val permissionMode: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("launcher", launcher)
        model?.takeIf(String::isNotBlank)?.let { put("model", it) }
        effort?.takeIf(String::isNotBlank)?.let { put("effort", it) }
        speed?.trim()?.takeIf { it.isNotEmpty() && it != "standard" }?.let { put("speed", it) }
        permissionMode?.takeIf(String::isNotBlank)?.let { put("permission_mode", it) }
    }
}

@KeepGeneratedSerializer
@Serializable(with = ModelProfileTierSerializer::class)
data class ModelProfileTier(
    @SerialName("launcher") val launcher: String = "",
    @SerialName("model") val model: String? = null,
    @SerialName("effort") val effort: String? = null,
    @SerialName("speed") val speed: String? = null,
    @SerialName("permission_mode") val permissionMode: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("candidates") val candidates: List<ModelProfileTierCandidate>? = null,
    @SerialName("aliases") val aliases: List<String>? = null,
) {
    /** Effective ordered candidate chain: explicit [candidates] when present, else the flat single candidate. */
    fun effectiveCandidates(): List<ModelProfileTierCandidate> =
        candidates?.takeIf { it.isNotEmpty() } ?: listOf(
            ModelProfileTierCandidate(
                launcher = launcher,
                model = model,
                effort = effort,
                speed = speed,
                permissionMode = permissionMode,
            )
        )

    fun toJson(): JsonObject = buildJsonObject {
        put("launcher", launcher)
        model?.takeIf(String::isNotBlank)?.let { put("model", it) }
        effort?.takeIf(String::isNotBlank)?.let { put("effort", it) }
        speed?.trim()?.takeIf { it.isNotEmpty() && it != "standard" }?.let { put("speed", it) }
        permissionMode?.takeIf(String::isNotBlank)?.let { put("permission_mode", it) }
        description?.takeIf(String::isNotBlank)?.let { put("description", it) }
        candidates?.takeIf { it.isNotEmpty() }?.let { list ->
            put("candidates", JsonArray(list.map(ModelProfileTierCandidate::toJson)))
        }
        aliases?.map(String::trim)?.filter(String::isNotEmpty)?.takeIf { it.isNotEmpty() }?.let { list ->
            put("aliases", JsonArray(list.map(::JsonPrimitive)))
        }
    }
}

/** 7-day dry-run comparison shown on inactive profiles (`ModelProfile.trial`). */
@KeepGeneratedSerializer
@Serializable(with = ModelProfileTrialSerializer::class)
data class ModelProfileTrial(
    @SerialName("days") val days: Int = 7,
    @SerialName("dispatches") val dispatches: Int = 0,
    @SerialName("would_switch") val wouldSwitch: Int = 0,
)

@KeepGeneratedSerializer
@Serializable(with = ModelProfileCandidateHeadroomSerializer::class)
data class ModelProfileCandidateHeadroom(
    @SerialName("known") val known: Boolean = false,
    @SerialName("percent") val percent: Double = 0.0,
    @SerialName("stale") val stale: Boolean = false,
    @SerialName("recovers_at") val recoversAt: String? = null,
)

@KeepGeneratedSerializer
@Serializable(with = ModelProfileCandidateStatsSerializer::class)
data class ModelProfileCandidateStats(
    @SerialName("n") val n: Int = 0,
    @SerialName("minutes_p50") val minutesP50: Double? = null,
    @SerialName("first_pass") val firstPass: Double? = null,
)

@KeepGeneratedSerializer
@Serializable(with = ModelProfileCandidateStatusSerializer::class)
data class ModelProfileCandidateStatus(
    @SerialName("index") val index: Int = 0,
    @SerialName("launcher") val launcher: String = "",
    @SerialName("model") val model: String? = null,
    @SerialName("headroom") val headroom: ModelProfileCandidateHeadroom = ModelProfileCandidateHeadroom(),
    @SerialName("stats") val stats: ModelProfileCandidateStats = ModelProfileCandidateStats(),
)

/** Live headroom and 14-day trial stats for a profile's candidates (`GET /api/v1/model-profiles/{id}/candidates`). */
@KeepGeneratedSerializer
@Serializable(with = ModelProfileCandidatesViewSerializer::class)
data class ModelProfileCandidatesView(
    @SerialName("days") val days: Int = 14,
    @SerialName("tiers") val tiers: Map<String, List<ModelProfileCandidateStatus>> = emptyMap(),
)

@KeepGeneratedSerializer
@Serializable(with = ProfileActorSerializer::class)
data class ProfileActor(
    @SerialName("kind") val kind: String = "",
    @SerialName("name") val name: String? = null,
)

@KeepGeneratedSerializer
@Serializable(with = ModelProfileEventSerializer::class)
data class ModelProfileEvent(
    @SerialName("at") val at: String = "",
    @SerialName("action") val action: String = "",
    @SerialName("from") val from: String? = null,
    @SerialName("to") val to: String? = null,
    @SerialName("by") val by: ProfileActor = ProfileActor(),
    @SerialName("changed") val changed: List<String> = emptyList(),
)

@KeepGeneratedSerializer
@Serializable(with = ModelProfileSerializer::class)
data class ModelProfile(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String = "",
    @SerialName("description") val description: String? = null,
    @SerialName("purposes") val purposes: Map<String, ModelBinding> = emptyMap(),
    @SerialName("launchers") val launchers: Map<String, ModelProfileLauncherModels> = emptyMap(),
    @SerialName("tiers") val tiers: Map<String, ModelProfileTier> = emptyMap(),
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("active") val active: Boolean = false,
    @SerialName("changes") val changes: List<String> = emptyList(),
    @SerialName("trial") val trial: ModelProfileTrial? = null,
) {
    val displayName: String get() = name.ifBlank { id }
}

@KeepGeneratedSerializer
@Serializable(with = ModelProfileUndoSerializer::class)
data class ModelProfileUndo(
    @SerialName("id") val id: String? = null,
    @SerialName("to") val to: String? = null,
    @SerialName("at") val at: String? = null,
    @SerialName("changes") val changes: List<String> = emptyList(),
    @SerialName("redo") val redo: Boolean = false,
)

@KeepGeneratedSerializer
@Serializable(with = ModelProfilesViewSerializer::class)
data class ModelProfilesView(
    @SerialName("revision") val revision: Long = 0,
    @SerialName("active") val active: String? = null,
    @SerialName("at") val at: String? = null,
    @SerialName("by") val by: ProfileActor? = null,
    @SerialName("rollback") val rollback: ModelProfileUndo? = null,
    @SerialName("rollbacks") val rollbacks: List<ModelProfileUndo> = emptyList(),
    @SerialName("profiles") val profiles: List<ModelProfile> = emptyList(),
    @SerialName("events") val events: List<ModelProfileEvent> = emptyList(),
)

/**
 * Payload for `PUT /api/v1/model-profiles/{id}`. Matches the server's `modelProfileInput`
 * (including `capture: true` to snapshot live purpose and launcher bindings while preserving tiers)
 * and never emits credentials or `config_dir`.
 */
data class ModelProfileSaveInput(
    val revision: Long,
    val name: String,
    val description: String = "",
    val purposes: Map<String, ModelBinding>? = null,
    val launchers: Map<String, ModelProfileLauncherModels>? = null,
    val tiers: Map<String, ModelProfileTier>? = null,
    val capture: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("revision", revision)
        put("name", name)
        if (!capture || description.isNotEmpty()) {
            put("description", description)
        }
        purposes?.takeIf { it.isNotEmpty() }?.let { map ->
            put("purposes", buildJsonObject {
                map.forEach { (id, binding) ->
                    put(id, buildJsonObject {
                        binding.provider?.takeIf(String::isNotBlank)?.let { put("provider", it) }
                        binding.model?.takeIf(String::isNotBlank)?.let { put("model", it) }
                        binding.effort?.takeIf(String::isNotBlank)?.let { put("effort", it) }
                        binding.permissionMode?.takeIf(String::isNotBlank)?.let { put("permission_mode", it) }
                    })
                }
            })
        }
        launchers?.filterValues { !it.isEmpty() }?.takeIf { it.isNotEmpty() }?.let { map ->
            put("launchers", buildJsonObject {
                map.forEach { (name, models) -> put(name, models.toJson()) }
            })
        }
        tiers?.takeIf { it.isNotEmpty() }?.let { map ->
            put("tiers", buildJsonObject {
                map.forEach { (name, tier) -> put(name, tier.toJson()) }
            })
        }
        if (capture) put("capture", true)
    }

    companion object {
        /** Snapshot live purpose and launcher bindings into [profile] without altering its tiers. */
        fun capture(profile: ModelProfile, revision: Long): ModelProfileSaveInput = ModelProfileSaveInput(
            revision = revision,
            name = profile.name,
            description = profile.description.orEmpty(),
            capture = true,
        )
    }
}

sealed interface ModelProfileSave {
    data class Saved(val view: ModelProfilesView) : ModelProfileSave
    /** The revision moved on (another device saved first); nothing was written. */
    data object Conflict : ModelProfileSave
}

object ModelProfileLauncherModelsSerializer : WireSerializer<ModelProfileLauncherModels>(ModelProfileLauncherModels.generatedSerializer())
object ModelProfileTierCandidateSerializer : WireSerializer<ModelProfileTierCandidate>(ModelProfileTierCandidate.generatedSerializer())
object ModelProfileTierSerializer : WireSerializer<ModelProfileTier>(ModelProfileTier.generatedSerializer())
object ModelProfileTrialSerializer : WireSerializer<ModelProfileTrial>(ModelProfileTrial.generatedSerializer())
object ModelProfileCandidateHeadroomSerializer : WireSerializer<ModelProfileCandidateHeadroom>(ModelProfileCandidateHeadroom.generatedSerializer())
object ModelProfileCandidateStatsSerializer : WireSerializer<ModelProfileCandidateStats>(ModelProfileCandidateStats.generatedSerializer())
object ModelProfileCandidateStatusSerializer : WireSerializer<ModelProfileCandidateStatus>(ModelProfileCandidateStatus.generatedSerializer())
object ModelProfileCandidatesViewSerializer : WireSerializer<ModelProfileCandidatesView>(ModelProfileCandidatesView.generatedSerializer())
object ProfileActorSerializer : WireSerializer<ProfileActor>(ProfileActor.generatedSerializer())
object ModelProfileEventSerializer : WireSerializer<ModelProfileEvent>(ModelProfileEvent.generatedSerializer())
object ModelProfileSerializer : WireSerializer<ModelProfile>(ModelProfile.generatedSerializer())
object ModelProfileUndoSerializer : WireSerializer<ModelProfileUndo>(ModelProfileUndo.generatedSerializer())
object ModelProfilesViewSerializer : WireSerializer<ModelProfilesView>(ModelProfilesView.generatedSerializer())
