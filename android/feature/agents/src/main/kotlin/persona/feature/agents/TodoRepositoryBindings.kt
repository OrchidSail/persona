package persona.feature.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import persona.core.contracts.Todo
import persona.core.contracts.TodoPRStatus
import persona.core.contracts.api.RepositoryBinding
import persona.core.contracts.api.RepositoryCatalog
import persona.core.contracts.api.RepositoryLinkInput
import persona.core.contracts.api.RequestedRef
import persona.core.contracts.api.TaskRepository
import persona.core.contracts.api.TaskRepositoryInput
import persona.core.contracts.api.TaskRepositoryLink
import persona.core.contracts.api.TaskRepositorySet
import persona.core.contracts.api.hubCodedFailure
import persona.core.designsystem.*
import persona.core.designsystem.icons.PersonaIcons
import persona.core.domain.tasks.TodoRepositoriesSource

/** Normalizes a PR / Issue URL for matching [Todo.prStatus] and [Todo.otherPrs] against bound repository links. */
internal fun normalizeLinkUrl(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty()) return ""
    val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed.lowercase(Locale.ROOT)
    val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return trimmed.lowercase(Locale.ROOT)
    val host = parsed.host?.lowercase(Locale.ROOT) ?: return trimmed.lowercase(Locale.ROOT)
    val port = if (parsed.port > 0) ":${parsed.port}" else ""
    val path = parsed.rawPath.orEmpty().trimEnd('/')
    return "$scheme://$host$port$path"
}

/** Indexes observed PR facts from [Todo.prStatus] and [Todo.otherPrs] by normalized URL. */
internal fun prFactsByUrl(todo: Todo): Map<String, TodoPRStatus> = buildMap {
    todo.otherPrs.orEmpty().forEach { status ->
        val key = normalizeLinkUrl(status.url)
        if (key.isNotEmpty()) put(key, status)
    }
    todo.prStatus?.let { status ->
        val key = normalizeLinkUrl(status.url)
        if (key.isNotEmpty()) put(key, status)
    }
}

/** One link being edited inside [TodoRepositoriesEditorSheet]. Enforces URL-or-kind+number exclusivity. */
internal data class LinkBindingDraft(
    val key: String,
    val url: String? = null,
    val kind: String? = null,
    val number: String? = null,
    val title: String? = null,
    val role: String = TaskRepositoryLink.ROLE_DELIVERY,
) {
    val displayLabel: String
        get() = when {
            !kind.isNullOrBlank() && !number.isNullOrBlank() -> {
                val prefix = if (kind == TaskRepositoryLink.KIND_PULL_REQUEST) "PR" else "Issue"
                "$prefix #$number"
            }
            !url.isNullOrBlank() -> url
            else -> ""
        }

    fun toInput(): RepositoryLinkInput = if (!url.isNullOrBlank()) {
        RepositoryLinkInput(url = url.trim(), title = title?.takeIf(String::isNotBlank), role = role)
    } else {
        RepositoryLinkInput(
            kind = kind?.trim(),
            number = number?.trim(),
            title = title?.takeIf(String::isNotBlank),
            role = role,
        )
    }
}

/** One bound repository being edited inside [TodoRepositoriesEditorSheet]. */
internal data class RepoBindingDraft(
    val repoId: String,
    val displayName: String,
    val canonicalUrl: String,
    val directoryAlias: String = "",
    val refKind: String = RequestedRef.KIND_DEFAULT,
    val refValue: String = "",
    val links: List<LinkBindingDraft> = emptyList(),
) {
    fun toInput(): TaskRepositoryInput = TaskRepositoryInput(
        repoId = repoId,
        directoryAlias = directoryAlias.trim().takeIf(String::isNotEmpty),
        requestedRef = RequestedRef(
            kind = refKind.ifBlank { RequestedRef.KIND_DEFAULT },
            value = if (refKind == RequestedRef.KIND_DEFAULT) "" else refValue.trim(),
        ),
        links = links.map(LinkBindingDraft::toInput),
    )
}

private val LinkNumberPattern = Regex("^#?([1-9][0-9]{0,19})$")

/** Parses pasted URL or `#123` / `123` input into a valid [LinkBindingDraft], or null if invalid. */
internal fun parseLinkDraftInput(raw: String, defaultKind: String, key: String): LinkBindingDraft? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) {
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank()) return null
        return LinkBindingDraft(key = key, url = trimmed, role = TaskRepositoryLink.ROLE_DELIVERY)
    }
    val match = LinkNumberPattern.matchEntire(trimmed) ?: return null
    val kind = if (defaultKind == TaskRepositoryLink.KIND_PULL_REQUEST) {
        TaskRepositoryLink.KIND_PULL_REQUEST
    } else {
        TaskRepositoryLink.KIND_ISSUE
    }
    return LinkBindingDraft(key = key, kind = kind, number = match.groupValues[1], role = TaskRepositoryLink.ROLE_DELIVERY)
}

internal fun TaskRepository.toDraft(catalog: RepositoryCatalog?): RepoBindingDraft {
    val matched = catalog?.repositories?.firstOrNull { it.id == repoId }
    val name = matched?.displayName
        ?: sourceSnapshot.identity.path.substringAfterLast('/').takeIf(String::isNotBlank)
        ?: sourceSnapshot.identity.path.takeIf(String::isNotBlank)
        ?: repoId
    return RepoBindingDraft(
        repoId = repoId,
        displayName = name,
        canonicalUrl = matched?.canonicalUrl?.ifBlank { sourceSnapshot.identity.canonicalUrl } ?: sourceSnapshot.identity.canonicalUrl,
        directoryAlias = directoryAlias,
        refKind = requestedRef.kind.ifBlank { RequestedRef.KIND_DEFAULT },
        refValue = requestedRef.value,
        links = links.mapIndexed { index, link ->
            // Existing links carry canonicalUrl; send kind+number when present or url when not.
            if (link.kind.isNotBlank() && link.number.isNotBlank()) {
                LinkBindingDraft(
                    key = "$repoId-link-$index",
                    kind = link.kind,
                    number = link.number,
                    title = link.title,
                    role = link.effectiveRole,
                )
            } else {
                LinkBindingDraft(
                    key = "$repoId-link-$index",
                    url = link.canonicalUrl,
                    title = link.title,
                    role = link.effectiveRole,
                )
            }
        },
    )
}

internal fun RepositoryBinding.toEmptyDraft(): RepoBindingDraft = RepoBindingDraft(
    repoId = id,
    displayName = displayName,
    canonicalUrl = canonicalUrl.ifBlank { path },
)

/**
 * Loads and edits a to-do's bound repositories & Issue/PR links (`GET/PUT /api/v1/todos/{id}/repositories`),
 * scoped to the to-do's own manager (`Todo.manager`). Falls back gracefully when an older hub lacks the route.
 */
@Stable
internal class TodoRepositoriesState(private val source: TodoRepositoriesSource?) {
    var supported by mutableStateOf(source != null)
        private set
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var bindings by mutableStateOf<TaskRepositorySet?>(null)
        private set
    var catalog by mutableStateOf<RepositoryCatalog?>(null)
        private set
    var editing by mutableStateOf(false)
    var saving by mutableStateOf(false)
        private set
    var conflict by mutableStateOf(false)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set
    var editorVersion by mutableIntStateOf(0)
        private set

    suspend fun load(todo: Todo) {
        val api = source ?: run {
            supported = false
            return
        }
        loading = true
        failed = false
        try {
            val loaded = api.todoRepositories(todo)
            if (loaded == null) {
                supported = false
                bindings = null
                catalog = null
                return
            }
            supported = true
            bindings = loaded
            catalog = runCatching { api.assistantRepositories(todo) }.getOrNull() ?: catalog
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }

    suspend fun openEditor(todo: Todo) {
        val api = source ?: return
        editing = true
        conflict = false
        saveError = null
        loading = true
        try {
            coroutineScope {
                val freshBindings = async { api.todoRepositories(todo) }
                val freshCatalog = async { runCatching { api.assistantRepositories(todo) }.getOrNull() }
                freshBindings.await()?.let {
                    supported = true
                    bindings = it
                }
                freshCatalog.await()?.let { catalog = it }
                editorVersion++
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: Exception) {
            saveError = err.message
        } finally {
            loading = false
        }
    }

    suspend fun reloadEditor(todo: Todo) {
        openEditor(todo)
    }

    suspend fun save(todo: Todo, drafts: List<RepoBindingDraft>): Boolean {
        val api = source ?: return false
        if (saving) return false
        saving = true
        conflict = false
        saveError = null
        return try {
            val expectedRevision = bindings?.revision ?: 0L
            val expectedCatalogRevision = catalog?.catalogRevision ?: 0L
            val updated = api.setTodoRepositories(
                todo = todo,
                expectedRevision = expectedRevision,
                expectedCatalogRevision = expectedCatalogRevision,
                repositories = drafts.map(RepoBindingDraft::toInput),
            )
            bindings = updated
            editing = false
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: Exception) {
            val coded = hubCodedFailure(err)
            if (coded?.status == 409 ||
                coded?.code == "task_repositories_revision_conflict" ||
                coded?.code == "task_repositories_catalog_conflict"
            ) {
                conflict = true
            } else {
                saveError = err.message?.takeIf(String::isNotBlank) ?: err.toString()
            }
            false
        } finally {
            saving = false
        }
    }
}

/** Read-only Repositories & Links section on [TodoDetailScreen], plus older-hub `todo.prUrl` fallback. */
internal fun LazyListScope.todoRepositoriesSection(
    state: TodoRepositoriesState,
    todo: Todo,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
) {
    if (!state.supported) {
        val legacyUrl = todo.prUrl?.takeIf(String::isNotBlank) ?: return
        item(key = "repos-legacy-header") {
            JSectionHeader(stringResource(R.string.agents_todo_repos_header))
        }
        item(key = "repos-legacy") {
            val uriHandler = LocalUriHandler.current
            JCard(Modifier.testTag("todo-repos-legacy-card")) {
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                    JRow(
                        title = todo.prStatus?.title?.takeIf(String::isNotBlank)
                            ?: stringResource(R.string.agents_todo_repos_legacy_pr),
                        subtitle = legacyUrl,
                        leadingIcon = PersonaIcons.pullRequest,
                        trailing = { todo.prStatus?.let { PrStatusBadges(it) } },
                        chevron = false,
                        onClick = { val _ = runCatching { uriHandler.openUri(legacyUrl) } },
                    )
                    Text(
                        stringResource(R.string.agents_todo_repos_legacy_note),
                        style = PersonaTheme.type.caption,
                        color = PersonaTheme.palette.ink3,
                    )
                }
            }
        }
        return
    }

    val bound = state.bindings?.repositories.orEmpty()
    item(key = "repos-header") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            JSectionHeader(
                stringResource(R.string.agents_todo_repos_header),
                count = bound.size.takeIf { it > 0 },
                modifier = Modifier.weight(1f),
            )
            JTextButton(
                text = stringResource(R.string.agents_todo_repos_edit),
                onClick = onEdit,
                modifier = Modifier.testTag("todo-repos-edit"),
            )
        }
    }

    if (state.loading && state.bindings == null) {
        item(key = "repos-loading") { JLoading() }
        return
    }
    if (state.failed && state.bindings == null) {
        item(key = "repos-failed") {
            JNotice(
                stringResource(R.string.agents_todo_repos_load_failed),
                status = Status.NeedsYou,
                actionLabel = stringResource(R.string.agents_todo_repos_retry),
                onAction = onRetry,
            )
        }
        return
    }
    if (bound.isEmpty()) {
        item(key = "repos-empty") {
            JCard(onClick = onEdit, modifier = Modifier.testTag("todo-repos-empty")) {
                Text(
                    stringResource(R.string.agents_todo_repos_empty),
                    style = PersonaTheme.type.caption,
                    color = PersonaTheme.palette.ink3,
                )
            }
        }
        return
    }

    val prFacts = prFactsByUrl(todo)
    bound.forEachIndexed { index, repo ->
        item(key = "repo-${repo.repoId}-$index") {
            BoundRepositoryCard(repo, state.catalog, prFacts)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoundRepositoryCard(
    repo: TaskRepository,
    catalog: RepositoryCatalog?,
    prFacts: Map<String, TodoPRStatus>,
) {
    val uriHandler = LocalUriHandler.current
    val matched = catalog?.repositories?.firstOrNull { it.id == repo.repoId }
    val name = matched?.displayName
        ?: repo.sourceSnapshot.identity.path.takeIf(String::isNotBlank)
        ?: repo.repoId
    val canonical = matched?.canonicalUrl?.ifBlank { repo.sourceSnapshot.identity.canonicalUrl }
        ?: repo.sourceSnapshot.identity.canonicalUrl

    JCard(Modifier.testTag("todo-repo-card-${repo.repoId}")) {
        Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
            ) {
                JIcon(PersonaIcons.gitBranch, null, size = Tokens.Space.l, tint = PersonaTheme.palette.ink2)
                Column(Modifier.weight(1f)) {
                    Text(name, style = PersonaTheme.type.headline, color = PersonaTheme.palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (canonical.isNotBlank() && canonical != name) {
                        Text(canonical, style = PersonaTheme.type.caption, color = PersonaTheme.palette.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
                verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
            ) {
                JTag(requestedRefLabel(repo.requestedRef))
                if (repo.directoryAlias.isNotBlank()) {
                    JTag(repo.directoryAlias)
                }
                if (repo.catalogState == "removed") {
                    JTag(stringResource(R.string.agents_todo_repos_state_removed), Tone.Caution)
                }
                if (repo.providerState == "off") {
                    JTag(stringResource(R.string.agents_todo_repos_state_provider_off), Tone.Caution)
                } else if (repo.providerState == "missing") {
                    JTag(stringResource(R.string.agents_todo_repos_state_provider_missing), Tone.Danger)
                }
            }
            repo.links.forEachIndexed { linkIndex, link ->
                val fact = prFacts[normalizeLinkUrl(link.canonicalUrl)]
                val kindLabel = repo.linkKinds.firstOrNull { it.kind == link.kind }?.label?.takeIf(String::isNotBlank)
                    ?: stringResource(
                        if (link.kind == TaskRepositoryLink.KIND_PULL_REQUEST) R.string.agents_todo_repos_kind_pr
                        else R.string.agents_todo_repos_kind_issue,
                    )
                val titleText = link.title?.takeIf(String::isNotBlank) ?: fact?.title?.takeIf(String::isNotBlank)
                val headline = buildString {
                    append(kindLabel)
                    if (link.number.isNotBlank()) append(" #").append(link.number)
                    if (!titleText.isNullOrBlank()) append(" · ").append(titleText)
                }.ifBlank { link.canonicalUrl }
                val roleLabel = stringResource(
                    if (link.effectiveRole == TaskRepositoryLink.ROLE_REFERENCE) R.string.agents_todo_repos_role_reference
                    else R.string.agents_todo_repos_role_delivery,
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .testTag("todo-repo-link-${repo.repoId}-$linkIndex")
                        .clickable(enabled = link.canonicalUrl.isNotBlank()) {
                            val _ = runCatching { uriHandler.openUri(link.canonicalUrl) }
                        }
                        .padding(vertical = Tokens.Space.xs),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
                    ) {
                        JIcon(
                            if (link.kind == TaskRepositoryLink.KIND_PULL_REQUEST) PersonaIcons.pullRequest else PersonaIcons.link,
                            null,
                            size = Tokens.Space.m,
                            tint = PersonaTheme.palette.ink2,
                        )
                        Text(
                            headline,
                            style = PersonaTheme.type.body,
                            color = PersonaTheme.palette.accent,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
                        verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
                    ) {
                        JTag(
                            roleLabel,
                            if (link.effectiveRole == TaskRepositoryLink.ROLE_REFERENCE) Tone.Neutral else Tone.Positive,
                        )
                        if (fact != null) PrStatusBadges(fact)
                    }
                }
            }
        }
    }
}

@Composable
private fun PrStatusBadges(status: TodoPRStatus) {
    when (status.state.lowercase(Locale.ROOT)) {
        "merged" -> JTag(stringResource(R.string.agents_todo_repos_pr_merged), Tone.Positive)
        "closed" -> JTag(stringResource(R.string.agents_todo_repos_pr_closed), Tone.Neutral)
        "open" -> JTag(stringResource(R.string.agents_todo_repos_pr_open), status = Status.Working)
    }
    if (status.draft) JTag(stringResource(R.string.agents_todo_repos_pr_draft), Tone.Caution)
    if (status.conflicts) JTag(stringResource(R.string.agents_todo_repos_pr_conflicts), Tone.Danger)
}

@Composable
private fun requestedRefLabel(ref: RequestedRef): String = when (ref.kind) {
    RequestedRef.KIND_BRANCH -> "${stringResource(R.string.agents_todo_repos_ref_branch)}: ${ref.value}"
    RequestedRef.KIND_TAG -> "${stringResource(R.string.agents_todo_repos_ref_tag)}: ${ref.value}"
    RequestedRef.KIND_REF -> "${stringResource(R.string.agents_todo_repos_ref_ref)}: ${ref.value}"
    RequestedRef.KIND_COMMIT -> "${stringResource(R.string.agents_todo_repos_ref_commit)}: ${ref.value.take(8)}"
    else -> stringResource(R.string.agents_todo_repos_ref_default)
}

/** Bottom-sheet editor for binding/unbinding repositories, editing refs/aliases, and adding/removing Issue/PR links. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TodoRepositoriesEditorSheet(
    state: TodoRepositoriesState,
    todo: Todo,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val res = agentResources()
    var drafts by remember(state.editorVersion) {
        mutableStateOf(state.bindings?.repositories.orEmpty().map { it.toDraft(state.catalog) })
    }
    var removedUndo by remember(state.editorVersion) { mutableStateOf<Pair<Int, RepoBindingDraft>?>(null) }
    val linkInputs = remember(state.editorVersion) { mutableStateMapOf<String, String>() }
    val linkKinds = remember(state.editorVersion) { mutableStateMapOf<String, String>() }
    val linkErrors = remember(state.editorVersion) { mutableStateMapOf<String, Boolean>() }
    var nextLinkKey by remember(state.editorVersion) { mutableIntStateOf(0) }

    val catalogRepos = state.catalog?.repositories.orEmpty()
    val boundIds = drafts.mapTo(HashSet()) { it.repoId }
    val available = catalogRepos.filter { it.id !in boundIds }
    val refKinds = listOf(
        RequestedRef.KIND_DEFAULT to R.string.agents_todo_repos_ref_default,
        RequestedRef.KIND_BRANCH to R.string.agents_todo_repos_ref_branch,
        RequestedRef.KIND_TAG to R.string.agents_todo_repos_ref_tag,
        RequestedRef.KIND_REF to R.string.agents_todo_repos_ref_ref,
        RequestedRef.KIND_COMMIT to R.string.agents_todo_repos_ref_commit,
    )

    JSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.agents_todo_repos_editor_title),
        scrollable = true,
        dismissible = !state.saving,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Space.m)) {
            if (state.conflict) {
                JNotice(
                    text = stringResource(R.string.agents_todo_repos_conflict),
                    status = Status.NeedsYou,
                    actionLabel = stringResource(R.string.agents_todo_repos_reload),
                    onAction = { scope.launch { state.reloadEditor(todo) } },
                    modifier = Modifier.testTag("todo-repos-conflict"),
                )
            }
            state.saveError?.let { msg ->
                JNotice(
                    text = stringResource(R.string.agents_todo_repos_save_failed, msg),
                    status = Status.NeedsYou,
                )
            }
            removedUndo?.let { (idx, removed) ->
                JNotice(
                    text = stringResource(R.string.agents_todo_repos_removed_undo, removed.displayName),
                    actionLabel = stringResource(R.string.agents_todo_repos_undo),
                    onAction = {
                        val pos = idx.coerceIn(0, drafts.size)
                        drafts = drafts.toMutableList().apply { add(pos, removed) }
                        removedUndo = null
                    },
                    modifier = Modifier.testTag("todo-repos-unbind-undo"),
                )
            }

            drafts.forEachIndexed { index, draft ->
                JCard(Modifier.testTag("todo-repo-editor-${draft.repoId}")) {
                    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(draft.displayName, style = PersonaTheme.type.headline, color = PersonaTheme.palette.ink)
                                if (draft.canonicalUrl.isNotBlank()) {
                                    Text(draft.canonicalUrl, style = PersonaTheme.type.caption, color = PersonaTheme.palette.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            JButton(
                                text = stringResource(R.string.agents_todo_repos_remove_repo),
                                onClick = {
                                    removedUndo = index to draft
                                    drafts = drafts.filterNot { it.repoId == draft.repoId }
                                },
                                modifier = Modifier.testTag("todo-repo-unbind-${draft.repoId}"),
                                kind = JButtonKind.Destructive,
                                size = JButtonSize.Small,
                                enabled = !state.saving,
                            )
                        }

                        JTextField(
                            value = draft.directoryAlias,
                            onValueChange = { alias ->
                                drafts = drafts.map { if (it.repoId == draft.repoId) it.copy(directoryAlias = alias) else it }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("todo-repo-alias-${draft.repoId}"),
                            label = stringResource(R.string.agents_todo_repos_alias_label),
                            placeholder = stringResource(R.string.agents_todo_repos_alias_placeholder),
                            singleLine = true,
                            enabled = !state.saving,
                            autofill = false,
                        )

                        Text(
                            stringResource(R.string.agents_todo_repos_ref_kind_label),
                            style = PersonaTheme.type.caption,
                            color = PersonaTheme.palette.ink2,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
                            verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
                        ) {
                            refKinds.forEach { (kind, labelRes) ->
                                JChip(
                                    text = stringResource(labelRes),
                                    selected = draft.refKind == kind,
                                    onClick = {
                                        if (!state.saving) {
                                            drafts = drafts.map {
                                                if (it.repoId == draft.repoId) {
                                                    it.copy(refKind = kind, refValue = if (kind == RequestedRef.KIND_DEFAULT) "" else it.refValue)
                                                } else it
                                            }
                                        }
                                    },
                                    modifier = Modifier.testTag("todo-repo-ref-${draft.repoId}-$kind"),
                                )
                            }
                        }
                        if (draft.refKind != RequestedRef.KIND_DEFAULT) {
                            JTextField(
                                value = draft.refValue,
                                onValueChange = { value ->
                                    drafts = drafts.map { if (it.repoId == draft.repoId) it.copy(refValue = value) else it }
                                },
                                modifier = Modifier.fillMaxWidth().testTag("todo-repo-ref-value-${draft.repoId}"),
                                label = stringResource(R.string.agents_todo_repos_ref_value_label),
                                placeholder = stringResource(R.string.agents_todo_repos_ref_value_placeholder),
                                singleLine = true,
                                enabled = !state.saving,
                                monospace = true,
                                autofill = false,
                            )
                        }

                        Text(
                            stringResource(R.string.agents_todo_repos_links_label),
                            style = PersonaTheme.type.caption,
                            color = PersonaTheme.palette.ink2,
                        )
                        draft.links.forEachIndexed { linkIdx, link ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
                            ) {
                                Text(
                                    link.displayLabel,
                                    style = PersonaTheme.type.body,
                                    color = PersonaTheme.palette.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                JChip(
                                    text = stringResource(
                                        if (link.role == TaskRepositoryLink.ROLE_REFERENCE) R.string.agents_todo_repos_role_reference
                                        else R.string.agents_todo_repos_role_delivery,
                                    ),
                                    selected = link.role == TaskRepositoryLink.ROLE_DELIVERY,
                                    onClick = {
                                        if (!state.saving) {
                                            val nextRole = if (link.role == TaskRepositoryLink.ROLE_DELIVERY) {
                                                TaskRepositoryLink.ROLE_REFERENCE
                                            } else {
                                                TaskRepositoryLink.ROLE_DELIVERY
                                            }
                                            drafts = drafts.map { r ->
                                                if (r.repoId == draft.repoId) {
                                                    r.copy(links = r.links.map { l -> if (l.key == link.key) l.copy(role = nextRole) else l })
                                                } else r
                                            }
                                        }
                                    },
                                    modifier = Modifier.testTag("todo-link-role-${draft.repoId}-$linkIdx"),
                                )
                                JIconButton(
                                    icon = PersonaIcons.close,
                                    contentDescription = stringResource(R.string.agents_todo_repos_link_remove),
                                    onClick = {
                                        if (!state.saving) {
                                            drafts = drafts.map { r ->
                                                if (r.repoId == draft.repoId) r.copy(links = r.links.filterNot { it.key == link.key }) else r
                                            }
                                        }
                                    },
                                    modifier = Modifier.testTag("todo-link-remove-${draft.repoId}-$linkIdx"),
                                )
                            }
                        }

                        val chosenKind = linkKinds[draft.repoId] ?: TaskRepositoryLink.KIND_PULL_REQUEST
                        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                            JChip(
                                text = stringResource(R.string.agents_todo_repos_kind_pr),
                                selected = chosenKind == TaskRepositoryLink.KIND_PULL_REQUEST,
                                onClick = { linkKinds[draft.repoId] = TaskRepositoryLink.KIND_PULL_REQUEST },
                            )
                            JChip(
                                text = stringResource(R.string.agents_todo_repos_kind_issue),
                                selected = chosenKind == TaskRepositoryLink.KIND_ISSUE,
                                onClick = { linkKinds[draft.repoId] = TaskRepositoryLink.KIND_ISSUE },
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
                        ) {
                            JTextField(
                                value = linkInputs[draft.repoId].orEmpty(),
                                onValueChange = {
                                    linkInputs[draft.repoId] = it
                                    linkErrors[draft.repoId] = false
                                },
                                modifier = Modifier.weight(1f).testTag("todo-link-input-${draft.repoId}"),
                                label = stringResource(R.string.agents_todo_repos_link_input_label),
                                placeholder = stringResource(R.string.agents_todo_repos_link_input_placeholder),
                                supportingText = if (linkErrors[draft.repoId] == true) stringResource(R.string.agents_todo_repos_link_invalid) else null,
                                isError = linkErrors[draft.repoId] == true,
                                singleLine = true,
                                enabled = !state.saving,
                                autofill = false,
                            )
                            JButton(
                                text = stringResource(R.string.agents_todo_repos_link_add),
                                onClick = {
                                    val parsed = parseLinkDraftInput(
                                        linkInputs[draft.repoId].orEmpty(),
                                        chosenKind,
                                        key = "${draft.repoId}-new-${nextLinkKey++}",
                                    )
                                    if (parsed == null) {
                                        linkErrors[draft.repoId] = true
                                    } else {
                                        drafts = drafts.map { r ->
                                            if (r.repoId == draft.repoId) r.copy(links = r.links + parsed) else r
                                        }
                                        linkInputs[draft.repoId] = ""
                                        linkErrors[draft.repoId] = false
                                    }
                                },
                                modifier = Modifier.testTag("todo-link-add-${draft.repoId}"),
                                kind = JButtonKind.Secondary,
                                size = JButtonSize.Medium,
                                enabled = !state.saving && !linkInputs[draft.repoId].isNullOrBlank(),
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                Text(
                    stringResource(R.string.agents_todo_repos_add_repo),
                    style = PersonaTheme.type.label,
                    color = PersonaTheme.palette.ink,
                )
                when {
                    catalogRepos.isEmpty() -> Text(
                        stringResource(R.string.agents_todo_repos_no_catalog),
                        style = PersonaTheme.type.caption,
                        color = PersonaTheme.palette.ink3,
                    )
                    available.isEmpty() -> Text(
                        stringResource(R.string.agents_todo_repos_all_bound),
                        style = PersonaTheme.type.caption,
                        color = PersonaTheme.palette.ink3,
                    )
                    else -> FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
                        verticalArrangement = Arrangement.spacedBy(Tokens.Space.s),
                    ) {
                        available.forEach { repo ->
                            JChip(
                                text = repo.displayName,
                                selected = false,
                                onClick = {
                                    if (!state.saving) {
                                        drafts = drafts + repo.toEmptyDraft()
                                    }
                                },
                                modifier = Modifier.testTag("todo-repo-add-${repo.id}"),
                            )
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = Tokens.Space.s),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
            ) {
                JButton(
                    text = stringResource(R.string.agents_todo_repos_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    kind = JButtonKind.Secondary,
                    enabled = !state.saving,
                )
                JButton(
                    text = stringResource(R.string.agents_todo_repos_save),
                    onClick = {
                        scope.launch {
                            val ok = state.save(todo, drafts)
                            if (!ok && !state.conflict && state.saveError != null) {
                                onError(res.getString(R.string.agents_todo_repos_save_failed, state.saveError.orEmpty()))
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("todo-repos-save"),
                    enabled = !state.saving,
                    loading = state.saving,
                )
            }
        }
    }
}
