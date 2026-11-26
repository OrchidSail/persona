package persona.core.contracts.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One remembered task-start approval kind (`GET /api/v1/task-trust`). */
@Serializable
data class TaskTrustKind(
    val key: String = "",
    val machine: String = "",
    val cwd: String = "",
    val readonly: Boolean = false,
    val ambiguous: Boolean = false,
)

/** One direction / leader record (`GET /api/v1/assistant/leaders`). */
@Serializable
data class DirectionLeader(
    val id: String = "",
    val name: String = "",
    val title: String = "",
    val project: String = "",
    val ended: Boolean = false,
    val notes: String = "",
    @SerialName("notes_at") val notesAt: String? = null,
) {
    val displayName: String get() = name.ifBlank { title.ifBlank { id } }
}

/** Result of `PUT /api/v1/assistant/directions/{id}/notes`. */
@Serializable
data class DirectionNotesResult(
    val notes: String = "",
    @SerialName("notes_at") val notesAt: String? = null,
)

/** Thrown when an older hub (404/405) or fake transport does not expose a governance endpoint. */
class GovernanceUnsupportedException(message: String = "Unavailable") : UnsupportedOperationException(message)

/** Thrown on HTTP 409 `notes_changed` so the editor can surface the hub's current notes & timestamp. */
class DirectionNotesConflictException(
    message: String,
    val currentNotes: String,
    val currentNotesAt: String?,
) : Exception(message)

/** Shared repository identity (`server/internal/repositorywire/identity.go`). */
@Serializable
data class RepositoryIdentity(
    val provider: String = "",
    @SerialName("instance_url") val instanceUrl: String = "",
    val path: String = "",
    @SerialName("canonical_url") val canonicalUrl: String = "",
)

/** A provider's display label for a link kind (`issue` / `pull_request` / extension kinds). */
@Serializable
data class RepositoryLinkKind(
    val kind: String = "",
    val label: String = "",
)

/** One repository provider (`GET /api/v1/assistant/repositories/providers`). */
@Serializable
data class RepositoryProvider(
    val id: String = "",
    val name: String = "",
    val builtin: Boolean = false,
    val hosts: List<String> = emptyList(),
    val state: String = "available",
    @SerialName("link_kinds") val linkKinds: List<RepositoryLinkKind> = emptyList(),
    val capabilities: List<String> = emptyList(),
)

/** Fallback built-in providers when an older hub returns 404 for `/repositories/providers`. */
val DefaultRepositoryProviders = listOf(
    RepositoryProvider(
        id = "github",
        name = "GitHub",
        builtin = true,
        hosts = listOf("github.com"),
        state = "available",
        linkKinds = listOf(
            RepositoryLinkKind("issue", "Issue"),
            RepositoryLinkKind("pull_request", "PR"),
        ),
    ),
    RepositoryProvider(
        id = "gitlab",
        name = "GitLab",
        builtin = true,
        hosts = listOf("gitlab.com"),
        state = "available",
        linkKinds = listOf(
            RepositoryLinkKind("issue", "Issue"),
            RepositoryLinkKind("pull_request", "MR"),
        ),
    ),
)

/** One bound repository in an assistant's catalog (`server/internal/assistant/repositories.go`). */
@Serializable
data class RepositoryBinding(
    val id: String = "",
    val provider: String = "",
    @SerialName("instance_url") val instanceUrl: String = "",
    val path: String = "",
    @SerialName("canonical_url") val canonicalUrl: String = "",
    val name: String = "",
    val aliases: List<String> = emptyList(),
    val purpose: String = "",
    @SerialName("clone_url") val cloneUrl: String? = null,
    @SerialName("provider_name") val providerName: String? = null,
    @SerialName("task_workflow") val taskWorkflow: String = "",
    @SerialName("verified_at") val verifiedAt: String? = null,
) {
    val identity: RepositoryIdentity
        get() = RepositoryIdentity(provider = provider, instanceUrl = instanceUrl, path = path, canonicalUrl = canonicalUrl)

    val displayName: String get() = name.ifBlank { path.substringAfterLast('/').ifBlank { path.ifBlank { canonicalUrl } } }
}

/** `GET /api/v1/assistant/repositories`. */
@Serializable
data class RepositoryCatalog(
    val manager: String = "",
    @SerialName("catalog_revision") val catalogRevision: Long = 0,
    val repositories: List<RepositoryBinding> = emptyList(),
)

/** Payload for `POST /preview`, `POST /repositories`, and `PUT /repositories/{id}` (`DisallowUnknownFields`). */
data class RepositoryInput(
    val url: String,
    val provider: String = "",
    val name: String = "",
    val aliases: List<String> = emptyList(),
    val purpose: String = "",
    val cloneUrl: String? = null,
    val taskWorkflow: String = "",
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("url", url.trim())
        put("provider", provider.trim())
        put("name", name.trim())
        put("aliases", JsonArray(aliases.map { JsonPrimitive(it.trim()) }))
        put("purpose", purpose.trim())
        cloneUrl?.let { put("clone_url", it.trim()) }
        if (taskWorkflow.isNotBlank()) put("task_workflow", taskWorkflow.trim())
    }
}

/** `POST /api/v1/assistant/repositories/preview`. */
@Serializable
data class RepositoryPreview(
    val manager: String = "",
    val identity: RepositoryIdentity = RepositoryIdentity(),
    @SerialName("provider_name") val providerName: String? = null,
)

/** `DELETE /api/v1/assistant/repositories/{id}`. */
@Serializable
data class RepositoryRemoval(
    val manager: String = "",
    val receipt: String = "",
)

/**
 * Requested ref for a bound repository on a to-do (`server/internal/repositorywire/task.go`).
 * Valid [kind] values: `default` (value must be empty/omitted), `branch`, `tag`, `ref` (`refs/...`), `commit` (40/64 hex).
 */
@Serializable
data class RequestedRef(
    val kind: String = KIND_DEFAULT,
    val value: String = "",
) {
    /** Strictly non-null JSON matching `decodeTaskRepositoryInput`. */
    fun toJson(): JsonObject = buildJsonObject {
        val normalizedKind = kind.trim().ifEmpty { KIND_DEFAULT }
        put("kind", normalizedKind)
        val normalizedValue = value.trim()
        if (normalizedKind != KIND_DEFAULT && normalizedValue.isNotEmpty()) {
            put("value", normalizedValue)
        }
    }

    companion object {
        const val KIND_DEFAULT = "default"
        const val KIND_BRANCH = "branch"
        const val KIND_TAG = "tag"
        const val KIND_REF = "ref"
        const val KIND_COMMIT = "commit"
    }
}

/** Snapshot of repository catalog metadata stored on a to-do repository binding. */
@Serializable
data class SourceSnapshot(
    val identity: RepositoryIdentity = RepositoryIdentity(),
    @SerialName("clone_url") val cloneUrl: String = "",
    @SerialName("transport_origin") val transportOrigin: String = "",
    @SerialName("catalog_revision") val catalogRevision: Long = 0,
    @SerialName("provider_name") val providerName: String? = null,
)

/** One issue or pull-request link bound to a to-do's repository. */
@Serializable
data class TaskRepositoryLink(
    val kind: String = "",
    val number: String = "",
    @SerialName("canonical_url") val canonicalUrl: String = "",
    val title: String? = null,
    val role: String = ROLE_DELIVERY,
) {
    val effectiveRole: String get() = if (role == ROLE_REFERENCE) ROLE_REFERENCE else ROLE_DELIVERY

    companion object {
        const val KIND_ISSUE = "issue"
        const val KIND_PULL_REQUEST = "pull_request"
        const val ROLE_DELIVERY = "delivery"
        const val ROLE_REFERENCE = "reference"
    }
}

/** One repository bound to a to-do (`GET /api/v1/todos/{id}/repositories`). */
@Serializable
data class TaskRepository(
    @SerialName("repo_id") val repoId: String = "",
    @SerialName("directory_alias") val directoryAlias: String = "",
    @SerialName("requested_ref") val requestedRef: RequestedRef = RequestedRef(),
    @SerialName("source_snapshot") val sourceSnapshot: SourceSnapshot = SourceSnapshot(),
    val links: List<TaskRepositoryLink> = emptyList(),
    @SerialName("catalog_state") val catalogState: String = "",
    @SerialName("provider_state") val providerState: String = "",
    @SerialName("link_kinds") val linkKinds: List<RepositoryLinkKind> = emptyList(),
)

/** `GET /api/v1/todos/{id}/repositories`, `PUT .../repositories`, `PUT .../links`. */
@Serializable
data class TaskRepositorySet(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val manager: String = "",
    @SerialName("todo_id") val todoId: String = "",
    val revision: Long = 0,
    val repositories: List<TaskRepository> = emptyList(),
)

/**
 * Input for one Issue/PR link on a to-do repository.
 * Supply EITHER [url] OR ([kind] + [number]), never both; strictly omits all null/blank fields so
 * `decodeTaskRepositoryInput` never sees a JSON `null`.
 */
data class RepositoryLinkInput(
    val url: String? = null,
    val kind: String? = null,
    val number: String? = null,
    val title: String? = null,
    val role: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        val cleanUrl = url?.trim().orEmpty()
        if (cleanUrl.isNotEmpty()) {
            put("url", cleanUrl)
        } else {
            kind?.trim()?.takeIf(String::isNotEmpty)?.let { put("kind", it) }
            number?.trim()?.takeIf(String::isNotEmpty)?.let { put("number", it) }
        }
        title?.trim()?.takeIf(String::isNotEmpty)?.let { put("title", it) }
        role?.trim()?.takeIf(String::isNotEmpty)?.let { put("role", it) }
    }
}

/**
 * Input for one repository in `PUT /api/v1/todos/{id}/repositories` or `POST /api/v1/todos`.
 * When [links] is null, the `links` key is omitted (preserving existing links on PUT); an explicit empty list clears them.
 */
data class TaskRepositoryInput(
    val repoId: String,
    val directoryAlias: String? = null,
    val requestedRef: RequestedRef = RequestedRef(),
    val links: List<RepositoryLinkInput>? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("repo_id", repoId.trim())
        directoryAlias?.trim()?.takeIf(String::isNotEmpty)?.let { put("directory_alias", it) }
        put("requested_ref", requestedRef.toJson())
        links?.let { list -> put("links", JsonArray(list.map(RepositoryLinkInput::toJson))) }
    }
}

/** Optional `repository_bindings` block for `POST /api/v1/todos`. */
data class CreateRepositoryBindings(
    val catalogRevision: Long,
    val repositories: List<TaskRepositoryInput>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("catalog_revision", catalogRevision)
        put("repositories", JsonArray(repositories.map(TaskRepositoryInput::toJson)))
    }
}

/** Governance field bounds matching server validation. */
object AssistantGovernanceBounds {
    val READONLY_MODES = listOf("sandbox", "prompt")
    val DELIVERY_GATES = listOf("off", "merged", "live")
    val WORKSPACE_PREFERENCES = listOf("prefer-existing", "prefer-new")
    val TASK_WORKFLOWS = listOf("", "isolated", "shared_branch")
    val AUTO_NAMING_MAX = 1..1000
    val AUTO_NAMING_INTERVAL_HOURS = 0..8760
    val CACHE_KEEPALIVE_MINUTES = 1..55
    val CACHE_KEEPALIVE_MAX = 1..144
    val IDLE_CLOSE_HOURS = 0..8760
    val ARCHIVE_CLOSED_DAYS = 0..3650
    const val DIRECTION_NOTES_MAX = 4000
    const val REPOSITORY_NAME_MAX = 80
    const val REPOSITORY_PURPOSE_MAX = 500
    const val REPOSITORY_ALIASES_MAX = 10
    const val REPOSITORY_ALIAS_MAX = 80
}
