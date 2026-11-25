package persona.feature.assistant.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import persona.core.contracts.api.AssistantGovernanceBounds
import persona.core.contracts.api.RepositoryBinding
import persona.core.contracts.api.RepositoryInput
import persona.core.contracts.api.RepositoryPreview
import persona.core.designsystem.*
import persona.core.designsystem.icons.PersonaIcons
import persona.feature.assistant.R

/** Assistant bound repositories list (`GET /api/v1/assistant/repositories`) with add, edit, delete & undo restore. */
@Composable
internal fun RepositoriesListPart(
    state: AssistantSettingsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onUndo: suspend () -> Boolean,
) {
    val repos = state.repositories
    val list = repos.catalog?.repositories.orEmpty()
    val scope = rememberCoroutineScope()

    JListPage(
        title = stringResource(R.string.assistant_governance_repositories_title),
        onBack = onBack,
        refreshing = repos.loading && repos.catalog != null,
        onRefresh = onRefresh,
        actions = {
            JIconButton(
                icon = PersonaIcons.add,
                contentDescription = stringResource(R.string.assistant_governance_repositories_add),
                onClick = onAdd,
                modifier = Modifier.testTag("repositories-add-button"),
            )
        },
    ) {
        repos.undo?.let { undo ->
            item("undo") {
                JNotice(
                    text = stringResource(R.string.assistant_governance_repositories_removed, undo.displayName),
                    actionLabel = stringResource(R.string.assistant_governance_repositories_undo),
                    onAction = { scope.launch { val _ = onUndo() } },
                    modifier = Modifier.testTag("repositories-undo-notice"),
                )
            }
        }
        if (repos.failed) {
            item("failed") {
                JNotice(
                    text = repos.error ?: stringResource(R.string.assistant_governance_load_failed),
                    status = Status.NeedsYou,
                    actionLabel = stringResource(R.string.assistant_settings_retry),
                    onAction = onRefresh,
                )
            }
        }
        state.notice?.takeIf { repos.undo == null || it.error }?.let { notice ->
            item("notice") {
                JNotice(notice.text.resolve(LocalResources.current), status = if (notice.error) Status.NeedsYou else Status.Unknown)
            }
        }
        if (repos.catalog == null && repos.loading) {
            item("loading") { JLoading(text = stringResource(R.string.assistant_settings_loading)) }
        }
        if (repos.catalog != null && list.isEmpty() && !repos.failed) {
            item("empty") {
                JEmptyState(
                    title = stringResource(R.string.assistant_governance_repositories_empty_title),
                    description = stringResource(R.string.assistant_governance_repositories_empty_body),
                    icon = PersonaIcons.gitBranch,
                    action = {
                        JButton(
                            text = stringResource(R.string.assistant_governance_repositories_add),
                            onClick = onAdd,
                            icon = PersonaIcons.add,
                        )
                    },
                )
            }
        }
        itemsIndexed(list, key = { _, repo -> repo.id }) { index, repo ->
            val workflowBadge = when (repo.taskWorkflow) {
                "isolated" -> stringResource(R.string.assistant_governance_repositories_workflow_isolated)
                "shared_branch" -> stringResource(R.string.assistant_governance_repositories_workflow_shared)
                else -> null
            }
            val detail = listOfNotNull(
                repo.canonicalUrl.ifBlank { repo.path.ifBlank { null } },
                repo.purpose.takeIf(String::isNotBlank),
                repo.aliases.takeIf { it.isNotEmpty() }?.joinToString(", "),
            ).joinToString("\n")
            JGroupItem(GroupPosition.of(index, list.size)) {
                JRow(
                    title = repo.displayName,
                    subtitle = detail.ifBlank { null },
                    subtitleLines = 3,
                    leadingIcon = PersonaIcons.gitBranch,
                    trailing = {
                        val badge = workflowBadge ?: repo.providerName?.takeIf(String::isNotBlank) ?: repo.provider.takeIf(String::isNotBlank)
                        if (badge != null) JTag(badge)
                    },
                    modifier = Modifier.testTag("repository-row-${repo.id}"),
                    onClick = { onEdit(repo.id) },
                )
            }
        }
    }
}

/** Create or edit a bound repository (`POST /repositories`, `PUT /repositories/{id}`, `POST /preview`, `DELETE /repositories/{id}`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RepositoryEditorPart(
    state: AssistantSettingsUiState,
    repoId: String?,
    onBack: () -> Unit,
    onPreview: suspend (String, String) -> Result<RepositoryPreview>,
    onSave: suspend (String?, RepositoryInput) -> RepositoryBinding?,
    onDelete: suspend (RepositoryBinding) -> Boolean,
) {
    val existing = repoId?.let { id -> state.repositories.catalog?.repositories?.firstOrNull { it.id == id } }
    val providers = state.repositories.providers
    val scope = rememberCoroutineScope()
    var url by rememberSaveable(repoId) { mutableStateOf(existing?.canonicalUrl.orEmpty()) }
    var provider by rememberSaveable(repoId) { mutableStateOf(existing?.provider.orEmpty()) }
    var name by rememberSaveable(repoId) { mutableStateOf(existing?.name.orEmpty()) }
    var aliasesText by rememberSaveable(repoId) { mutableStateOf(existing?.aliases.orEmpty().joinToString(", ")) }
    var purpose by rememberSaveable(repoId) { mutableStateOf(existing?.purpose.orEmpty()) }
    var taskWorkflow by rememberSaveable(repoId) { mutableStateOf(existing?.taskWorkflow.orEmpty()) }
    var previewing by remember { mutableStateOf(false) }
    var previewResult by remember { mutableStateOf<RepositoryPreview?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var askDelete by remember { mutableStateOf(false) }

    val input = RepositoryInput(
        url = url,
        provider = provider,
        name = name,
        aliases = parseRepositoryAliases(aliasesText),
        purpose = purpose,
        taskWorkflow = taskWorkflow,
    )
    val validationError = validateRepositoryInput(input)
    val workflows = AssistantGovernanceBounds.TASK_WORKFLOWS
    val workflowIndex = workflows.indexOf(taskWorkflow).coerceAtLeast(0)

    JListPage(
        title = stringResource(
            if (existing == null) R.string.assistant_governance_repositories_add
            else R.string.assistant_governance_repositories_edit,
        ),
        modifier = Modifier.imePadding(),
        onBack = onBack,
        bottomBar = {
            JBottomBar {
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                    if (validationError != null && url.isNotBlank()) {
                        Text(
                            stringResource(validationError),
                            style = PersonaTheme.type.caption,
                            color = PersonaTheme.palette.danger.dot,
                        )
                    }
                    JButton(
                        text = stringResource(R.string.assistant_settings_save),
                        onClick = {
                            scope.launch {
                                if (onSave(existing?.id, input) != null) onBack()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("repository-editor-save"),
                        enabled = validationError == null && !state.busy && !previewing,
                        loading = state.busy,
                    )
                }
            }
        },
    ) {
        state.notice?.takeIf { it.error }?.let { notice ->
            item("notice") {
                JNotice(notice.text.resolve(LocalResources.current), status = Status.NeedsYou)
            }
        }
        item("url") {
            JTextField(
                value = url,
                onValueChange = { url = it; previewResult = null; previewError = null },
                modifier = Modifier.fillMaxWidth().padding(top = Tokens.Space.s).testTag("repository-editor-url"),
                label = stringResource(R.string.assistant_governance_repositories_url),
                placeholder = stringResource(R.string.assistant_governance_repositories_url_placeholder),
                singleLine = true,
                enabled = !state.busy,
                isError = previewError != null,
                autofill = false,
                monospace = true,
            )
            Spacer(Modifier.height(Tokens.Space.s))
            JButton(
                text = stringResource(R.string.assistant_governance_repositories_preview),
                onClick = {
                    previewing = true
                    previewError = null
                    scope.launch {
                        val res = onPreview(url, provider)
                        previewing = false
                        res.onSuccess { preview ->
                            previewResult = preview
                            previewError = null
                            if (name.isBlank() && preview.identity.path.isNotBlank()) {
                                name = preview.identity.path.substringAfterLast('/')
                            }
                        }.onFailure { err ->
                            previewResult = null
                            previewError = err.message?.takeIf(String::isNotBlank)
                        }
                    }
                },
                modifier = Modifier.testTag("repository-editor-preview"),
                kind = JButtonKind.Secondary,
                size = JButtonSize.Small,
                enabled = url.isNotBlank() && !state.busy && !previewing,
                loading = previewing,
            )
            previewResult?.let { preview ->
                Spacer(Modifier.height(Tokens.Space.xs))
                val label = preview.providerName?.takeIf(String::isNotBlank) ?: preview.identity.provider
                Text(
                    stringResource(
                        R.string.assistant_governance_repositories_preview_ok,
                        preview.identity.canonicalUrl.ifBlank { preview.identity.path },
                        label,
                    ),
                    style = PersonaTheme.type.caption,
                    color = PersonaTheme.palette.done.dot,
                    modifier = Modifier.testTag("repository-editor-preview-ok"),
                )
            }
            previewError?.let { msg ->
                Spacer(Modifier.height(Tokens.Space.xs))
                Text(
                    msg,
                    style = PersonaTheme.type.caption,
                    color = PersonaTheme.palette.danger.dot,
                    modifier = Modifier.testTag("repository-editor-preview-error"),
                )
            }
        }
        item("provider") {
            JSectionHeader(stringResource(R.string.assistant_governance_repositories_provider))
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
                verticalArrangement = Arrangement.spacedBy(Tokens.Space.s),
            ) {
                JChip(
                    text = stringResource(R.string.assistant_governance_repositories_provider_auto),
                    selected = provider.isEmpty(),
                    onClick = { if (!state.busy) provider = "" },
                    modifier = Modifier.testTag("repository-provider-auto"),
                )
                providers.forEach { p ->
                    JChip(
                        text = p.name.ifBlank { p.id },
                        selected = provider == p.id,
                        onClick = { if (!state.busy) provider = p.id },
                        modifier = Modifier.testTag("repository-provider-${p.id}"),
                    )
                }
            }
        }
        item("metadata") {
            Spacer(Modifier.height(Tokens.Space.m))
            JTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth().testTag("repository-editor-name"),
                label = stringResource(R.string.assistant_governance_repositories_name),
                placeholder = stringResource(R.string.assistant_governance_repositories_name_placeholder),
                singleLine = true,
                enabled = !state.busy,
            )
            Spacer(Modifier.height(Tokens.Space.m))
            JTextField(
                value = aliasesText,
                onValueChange = { aliasesText = it },
                modifier = Modifier.fillMaxWidth().testTag("repository-editor-aliases"),
                label = stringResource(R.string.assistant_governance_repositories_aliases),
                placeholder = stringResource(R.string.assistant_governance_repositories_aliases_placeholder),
                singleLine = true,
                enabled = !state.busy,
            )
            Spacer(Modifier.height(Tokens.Space.m))
            JTextField(
                value = purpose,
                onValueChange = { purpose = it },
                modifier = Modifier.fillMaxWidth().testTag("repository-editor-purpose"),
                label = stringResource(R.string.assistant_governance_repositories_purpose),
                placeholder = stringResource(R.string.assistant_governance_repositories_purpose_placeholder),
                minLines = 3,
                maxLines = 6,
                enabled = !state.busy,
            )
        }
        item("workflow") {
            JSectionHeader(stringResource(R.string.assistant_governance_repositories_workflow))
            JCard {
                JSegmented(
                    options = listOf(
                        JSegment(stringResource(R.string.assistant_governance_repositories_workflow_default)),
                        JSegment(stringResource(R.string.assistant_governance_repositories_workflow_isolated)),
                        JSegment(stringResource(R.string.assistant_governance_repositories_workflow_shared)),
                    ),
                    selected = workflowIndex,
                    onSelect = { if (!state.busy) taskWorkflow = workflows[it] },
                    modifier = Modifier.fillMaxWidth().testTag("repository-editor-workflow"),
                )
            }
        }
        if (existing != null) {
            item("delete") {
                Spacer(Modifier.height(Tokens.Space.xl))
                JGroupItem(GroupPosition.Single) {
                    JRow(
                        title = stringResource(R.string.assistant_governance_repositories_delete),
                        leadingIcon = PersonaIcons.delete,
                        destructive = true,
                        chevron = false,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("repository-editor-delete"),
                        onClick = { askDelete = true },
                    )
                }
            }
        }
    }

    if (askDelete && existing != null) {
        JDialog(
            onDismiss = { if (!state.busy) askDelete = false },
            title = stringResource(R.string.assistant_governance_repositories_delete_title, existing.displayName),
            text = stringResource(R.string.assistant_governance_repositories_delete_body),
            destructive = true,
            confirmEnabled = !state.busy,
            confirm = stringResource(R.string.assistant_governance_repositories_delete) to {
                askDelete = false
                scope.launch {
                    if (onDelete(existing)) onBack()
                }
            },
            dismiss = stringResource(R.string.assistant_settings_cancel) to { askDelete = false },
        )
    }
}
