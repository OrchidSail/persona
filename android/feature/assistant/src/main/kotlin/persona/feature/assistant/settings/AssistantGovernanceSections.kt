package persona.feature.assistant.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch
import persona.core.contracts.GroupKeys
import persona.core.contracts.Machine
import persona.core.contracts.api.AssistantConfig
import persona.core.contracts.api.AssistantGovernanceBounds
import persona.core.contracts.api.DirectionLeader
import persona.core.contracts.api.TaskTrustKind
import persona.core.designsystem.*
import persona.core.designsystem.icons.PersonaIcons
import persona.feature.assistant.R

internal class GovernanceActions(
    val onOpenTaskTrust: () -> Unit,
    val onOpenDirectionNotes: () -> Unit,
    val onOpenRepositories: () -> Unit,
    val onReadonlyMode: (String) -> Unit,
    val onStartRequestedTasks: (Boolean) -> Unit,
    val onDeliveryGate: (String) -> Unit,
    val onWorkspacePreference: (String) -> Unit,
    val onAutoSupervisionMessages: (Boolean) -> Unit,
    val onTaskLessons: (Boolean) -> Unit,
    val onAgentTasks: (Boolean) -> Unit,
    val onFoldRoutineRelayReplies: (Boolean) -> Unit,
    val onClaudeInteractive: (Boolean) -> Unit,
    val onInboxSignals: (Boolean) -> Unit,
    val onInboxTodoForward: (Boolean) -> Unit,
    val onAgentAutoNaming: (Boolean?, Int?, Int?) -> Unit,
    val onCacheKeepalive: (Boolean?, Int?, Int?) -> Unit,
    val onIdleCloseHours: (Int) -> Unit,
    val onArchiveClosedDays: (Int) -> Unit,
)

/**
 * High-frequency assistant governance sections (W-E02), strictly field-presence gated so older hubs and minimal test
 * fixtures hide unsupported controls gracefully.
 */
internal fun LazyListScope.assistantGovernanceSections(
    state: AssistantSettingsUiState,
    config: AssistantConfig,
    owner: String,
    actions: GovernanceActions,
) {
    val busy = state.busy
    val entryRows = buildList<@Composable () -> Unit> {
        if (state.taskTrust.supported) {
            val count = state.taskTrust.kinds?.size?.takeIf { it > 0 }?.toString()
            add {
                JRow(
                    title = stringResource(R.string.assistant_governance_task_trust_title),
                    subtitle = stringResource(R.string.assistant_governance_task_trust_subtitle),
                    value = count,
                    leadingIcon = PersonaIcons.privacy,
                    modifier = Modifier.testTag("governance-entry-task-trust"),
                    onClick = actions.onOpenTaskTrust,
                )
            }
        }
        if (state.directionNotes.supported) {
            val count = state.directionNotes.leaders?.size?.takeIf { it > 0 }?.toString()
            add {
                JRow(
                    title = stringResource(R.string.assistant_governance_direction_notes_title),
                    subtitle = stringResource(R.string.assistant_governance_direction_notes_subtitle),
                    value = count,
                    leadingIcon = PersonaIcons.goal,
                    modifier = Modifier.testTag("governance-entry-direction-notes"),
                    onClick = actions.onOpenDirectionNotes,
                )
            }
        }
        if (state.repositories.supported) {
            val count = state.repositories.catalog?.repositories?.size?.takeIf { it > 0 }?.toString()
            add {
                JRow(
                    title = stringResource(R.string.assistant_governance_repositories_title),
                    subtitle = stringResource(R.string.assistant_governance_repositories_subtitle),
                    value = count,
                    leadingIcon = PersonaIcons.gitBranch,
                    modifier = Modifier.testTag("governance-entry-repositories"),
                    onClick = actions.onOpenRepositories,
                )
            }
        }
    }
    if (entryRows.isNotEmpty()) {
        item("governance-entries-header") { JSectionHeader(stringResource(R.string.assistant_governance_entries_header)) }
        entryRows.forEachIndexed { index, row ->
            item("governance-entry-$index") { JGroupItem(GroupPosition.of(index, entryRows.size)) { row() } }
        }
    }

    val deliveryGate = config.effectiveDeliveryGate
    val hasTaskGovernance = config.readonlyMode != null ||
        deliveryGate != null ||
        config.taskWorkspacePreference != null ||
        config.startRequestedTasks != null ||
        config.autoSupervisionMessages != null ||
        config.taskLessons != null ||
        config.agentTasks != null ||
        config.foldRoutineRelayReplies != null ||
        config.claudeInteractive != null

    if (hasTaskGovernance) {
        item("governance-tasks-header") { JSectionHeader(stringResource(R.string.assistant_governance_tasks_header)) }

        config.readonlyMode?.let { mode ->
            item("governance-readonly-mode") {
                val modes = AssistantGovernanceBounds.READONLY_MODES
                val selected = modes.indexOf(mode).coerceAtLeast(0)
                val canEdit = !busy && !config.manager.isNullOrBlank() && config.manager == owner
                JCard(Modifier.testTag("governance-readonly-mode")) {
                    Text(
                        stringResource(R.string.assistant_governance_readonly_mode),
                        style = PersonaTheme.type.label,
                        color = PersonaTheme.palette.ink,
                    )
                    Spacer(Modifier.height(Tokens.Space.s))
                    JSegmented(
                        options = listOf(
                            JSegment(stringResource(R.string.assistant_governance_readonly_sandbox)),
                            JSegment(stringResource(R.string.assistant_governance_readonly_prompt)),
                        ),
                        selected = selected,
                        onSelect = { if (canEdit) actions.onReadonlyMode(modes[it]) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Caption(stringResource(R.string.assistant_governance_readonly_caption))
            }
        }

        deliveryGate?.let { gate ->
            item("governance-delivery-gate") {
                val gates = AssistantGovernanceBounds.DELIVERY_GATES
                val selected = gates.indexOf(gate).coerceAtLeast(0)
                Spacer(Modifier.height(Tokens.Space.s))
                JCard(Modifier.testTag("governance-delivery-gate")) {
                    Text(
                        stringResource(R.string.assistant_governance_delivery_gate),
                        style = PersonaTheme.type.label,
                        color = PersonaTheme.palette.ink,
                    )
                    Spacer(Modifier.height(Tokens.Space.s))
                    JSegmented(
                        options = listOf(
                            JSegment(stringResource(R.string.assistant_governance_delivery_off)),
                            JSegment(stringResource(R.string.assistant_governance_delivery_merged)),
                            JSegment(stringResource(R.string.assistant_governance_delivery_live)),
                        ),
                        selected = selected,
                        onSelect = { if (!busy) actions.onDeliveryGate(gates[it]) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Caption(stringResource(R.string.assistant_governance_delivery_caption))
            }
        }

        config.taskWorkspacePreference?.let { pref ->
            item("governance-workspace-pref") {
                val prefs = AssistantGovernanceBounds.WORKSPACE_PREFERENCES
                val selected = prefs.indexOf(pref).coerceAtLeast(0)
                Spacer(Modifier.height(Tokens.Space.s))
                JCard(Modifier.testTag("governance-workspace-pref")) {
                    Text(
                        stringResource(R.string.assistant_governance_workspace_pref),
                        style = PersonaTheme.type.label,
                        color = PersonaTheme.palette.ink,
                    )
                    Spacer(Modifier.height(Tokens.Space.s))
                    JSegmented(
                        options = listOf(
                            JSegment(stringResource(R.string.assistant_governance_workspace_existing)),
                            JSegment(stringResource(R.string.assistant_governance_workspace_new)),
                        ),
                        selected = selected,
                        onSelect = { if (!busy) actions.onWorkspacePreference(prefs[it]) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Caption(stringResource(R.string.assistant_governance_workspace_caption))
            }
        }

        val taskSwitches = buildList<@Composable () -> Unit> {
            config.startRequestedTasks?.let { on ->
                add {
                    JSwitchRow(
                        title = stringResource(R.string.assistant_governance_start_requested),
                        checked = on,
                        onCheckedChange = actions.onStartRequestedTasks,
                        subtitle = stringResource(R.string.assistant_governance_start_requested_caption),
                        leadingIcon = PersonaIcons.run,
                        enabled = !busy,
                        modifier = Modifier.testTag("governance-switch-start-requested"),
                    )
                }
            }
            config.autoSupervisionMessages?.let { on ->
                add {
                    JSwitchRow(
                        title = stringResource(R.string.assistant_governance_auto_supervision),
                        checked = on,
                        onCheckedChange = actions.onAutoSupervisionMessages,
                        subtitle = stringResource(R.string.assistant_governance_auto_supervision_caption),
                        leadingIcon = PersonaIcons.message,
                        enabled = !busy,
                        modifier = Modifier.testTag("governance-switch-auto-supervision"),
                    )
                }
            }
            config.taskLessons?.let { on ->
                val canToggleLessons = !busy && (config.manager.isNullOrBlank() || config.manager == owner)
                add {
                    JSwitchRow(
                        title = stringResource(R.string.assistant_governance_task_lessons),
                        checked = on,
                        onCheckedChange = actions.onTaskLessons,
                        subtitle = stringResource(R.string.assistant_governance_task_lessons_caption),
                        leadingIcon = PersonaIcons.topic,
                        enabled = canToggleLessons,
                        modifier = Modifier.testTag("governance-switch-task-lessons"),
                    )
                }
            }
            config.agentTasks?.let { on ->
                add {
                    JSwitchRow(
                        title = stringResource(R.string.assistant_governance_agent_tasks),
                        checked = on,
                        onCheckedChange = actions.onAgentTasks,
                        subtitle = stringResource(R.string.assistant_governance_agent_tasks_caption),
                        leadingIcon = PersonaIcons.todo,
                        enabled = !busy,
                        modifier = Modifier.testTag("governance-switch-agent-tasks"),
                    )
                }
            }
            config.foldRoutineRelayReplies?.let { on ->
                add {
                    JSwitchRow(
                        title = stringResource(R.string.assistant_governance_fold_routine_relay),
                        checked = on,
                        onCheckedChange = actions.onFoldRoutineRelayReplies,
                        subtitle = stringResource(R.string.assistant_governance_fold_routine_relay_caption),
                        leadingIcon = PersonaIcons.schedule,
                        enabled = !busy,
                        modifier = Modifier.testTag("governance-switch-fold-routine-relay"),
                    )
                }
            }
            config.claudeInteractive?.let { on ->
                add {
                    JSwitchRow(
                        title = stringResource(R.string.assistant_governance_claude_interactive),
                        checked = on,
                        onCheckedChange = actions.onClaudeInteractive,
                        subtitle = stringResource(R.string.assistant_governance_claude_interactive_caption),
                        leadingIcon = PersonaIcons.askFirst,
                        enabled = !busy,
                        modifier = Modifier.testTag("governance-switch-claude-interactive"),
                    )
                }
            }
        }
        if (taskSwitches.isNotEmpty()) {
            item("governance-task-switches-spacer") { Spacer(Modifier.height(Tokens.Space.s)) }
            taskSwitches.forEachIndexed { index, row ->
                item("governance-task-switch-$index") { JGroupItem(GroupPosition.of(index, taskSwitches.size)) { row() } }
            }
        }
    }

    val inboxSwitches = buildList<@Composable () -> Unit> {
        config.inboxSignals?.let { on ->
            add {
                JSwitchRow(
                    title = stringResource(R.string.assistant_governance_inbox_signals),
                    checked = on,
                    onCheckedChange = actions.onInboxSignals,
                    subtitle = stringResource(R.string.assistant_governance_inbox_signals_caption),
                    leadingIcon = PersonaIcons.inbox,
                    enabled = !busy,
                    modifier = Modifier.testTag("governance-switch-inbox-signals"),
                )
            }
        }
        config.inboxTodoForward?.let { on ->
            add {
                JSwitchRow(
                    title = stringResource(R.string.assistant_governance_inbox_todo_forward),
                    checked = on,
                    onCheckedChange = actions.onInboxTodoForward,
                    subtitle = stringResource(R.string.assistant_governance_inbox_todo_forward_caption),
                    leadingIcon = PersonaIcons.forwardMessage,
                    enabled = !busy,
                    modifier = Modifier.testTag("governance-switch-inbox-todo-forward"),
                )
            }
        }
    }
    if (inboxSwitches.isNotEmpty()) {
        item("governance-inbox-header") { JSectionHeader(stringResource(R.string.assistant_governance_inbox_header)) }
        inboxSwitches.forEachIndexed { index, row ->
            item("governance-inbox-$index") { JGroupItem(GroupPosition.of(index, inboxSwitches.size)) { row() } }
        }
    }

    val hasLifecycle = config.agentAutoNaming != null ||
        config.cacheKeepalive != null ||
        config.idleCloseHours != null ||
        config.archiveClosedDays != null
    if (hasLifecycle) {
        item("governance-lifecycle-header") { JSectionHeader(stringResource(R.string.assistant_governance_lifecycle_header)) }

        config.agentAutoNaming?.let { enabled ->
            item("governance-auto-naming") {
                AgentAutoNamingEditor(
                    enabled = enabled,
                    maxRenames = config.agentAutoNamingMax ?: 3,
                    intervalHours = config.agentAutoNamingIntervalHours ?: 0,
                    busy = busy || (!config.manager.isNullOrBlank() && config.manager != owner),
                    onChange = actions.onAgentAutoNaming,
                )
            }
        }

        config.cacheKeepalive?.let { enabled ->
            item("governance-cache-keepalive") {
                Spacer(Modifier.height(Tokens.Space.s))
                CacheKeepaliveEditor(
                    enabled = enabled,
                    minutes = config.cacheKeepaliveMinutes ?: 4,
                    maxPings = config.cacheKeepaliveMax ?: 6,
                    busy = busy,
                    onChange = actions.onCacheKeepalive,
                )
            }
        }

        if (config.idleCloseHours != null || config.archiveClosedDays != null) {
            item("governance-cleanup") {
                Spacer(Modifier.height(Tokens.Space.s))
                SessionCleanupEditor(
                    idleCloseHours = config.idleCloseHours,
                    archiveClosedDays = config.archiveClosedDays,
                    busy = busy,
                    onSaveIdleClose = actions.onIdleCloseHours,
                    onSaveArchiveClosed = actions.onArchiveClosedDays,
                )
            }
        }
    }
}

@Composable
private fun AgentAutoNamingEditor(
    enabled: Boolean,
    maxRenames: Int,
    intervalHours: Int,
    busy: Boolean,
    onChange: (Boolean?, Int?, Int?) -> Unit,
) {
    var maxText by rememberSaveable(maxRenames) { mutableStateOf(maxRenames.toString()) }
    var intervalText by rememberSaveable(intervalHours) { mutableStateOf(intervalHours.toString()) }
    val parsedMax = maxText.trim().toIntOrNull()
    val parsedInterval = intervalText.trim().toIntOrNull()
    val maxValid = parsedMax != null && parsedMax in AssistantGovernanceBounds.AUTO_NAMING_MAX
    val intervalValid = parsedInterval != null && parsedInterval in AssistantGovernanceBounds.AUTO_NAMING_INTERVAL_HOURS
    val dirty = (parsedMax != maxRenames) || (parsedInterval != intervalHours)

    JCard(Modifier.testTag("governance-auto-naming-card")) {
        JSwitchRow(
            title = stringResource(R.string.assistant_governance_auto_naming),
            checked = enabled,
            onCheckedChange = { onChange(it, null, null) },
            subtitle = stringResource(R.string.assistant_governance_auto_naming_caption),
            leadingIcon = PersonaIcons.edit,
            enabled = !busy,
            modifier = Modifier.testTag("governance-switch-auto-naming"),
        )
        if (enabled) {
            Spacer(Modifier.height(Tokens.Space.s))
            JTextField(
                value = maxText,
                onValueChange = { maxText = it },
                modifier = Modifier.fillMaxWidth().testTag("governance-auto-naming-max"),
                label = stringResource(R.string.assistant_governance_auto_naming_max),
                supportingText = if (!maxValid) stringResource(
                    R.string.assistant_governance_invalid_range,
                    AssistantGovernanceBounds.AUTO_NAMING_MAX.first,
                    AssistantGovernanceBounds.AUTO_NAMING_MAX.last,
                ) else null,
                isError = !maxValid,
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                autofill = false,
            )
            Spacer(Modifier.height(Tokens.Space.s))
            JSwitchRow(
                title = stringResource(R.string.assistant_governance_auto_naming_periodic),
                checked = intervalHours > 0,
                onCheckedChange = { periodic ->
                    val nextInterval = if (periodic) 24 else 0
                    intervalText = nextInterval.toString()
                    onChange(null, null, nextInterval)
                },
                enabled = !busy,
                modifier = Modifier.testTag("governance-switch-auto-naming-periodic"),
            )
            if ((parsedInterval ?: intervalHours) > 0) {
                Spacer(Modifier.height(Tokens.Space.s))
                JTextField(
                    value = intervalText,
                    onValueChange = { intervalText = it },
                    modifier = Modifier.fillMaxWidth().testTag("governance-auto-naming-interval"),
                    label = stringResource(R.string.assistant_governance_auto_naming_interval),
                    supportingText = if (!intervalValid) stringResource(
                        R.string.assistant_governance_invalid_range,
                        AssistantGovernanceBounds.AUTO_NAMING_INTERVAL_HOURS.first,
                        AssistantGovernanceBounds.AUTO_NAMING_INTERVAL_HOURS.last,
                    ) else stringResource(R.string.assistant_governance_zero_disables),
                    isError = !intervalValid,
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    autofill = false,
                )
            }
            if ((parsedInterval ?: intervalHours) != 0 && (parsedMax ?: maxRenames) == 1) {
                Spacer(Modifier.height(Tokens.Space.s))
                JNotice(
                    stringResource(R.string.assistant_governance_auto_naming_max_warning),
                    status = Status.NeedsYou,
                    modifier = Modifier.testTag("governance-auto-naming-warning"),
                )
            }
            if (dirty) {
                Spacer(Modifier.height(Tokens.Space.s))
                JButton(
                    text = stringResource(R.string.assistant_governance_save_numbers),
                    onClick = {
                        if (maxValid && intervalValid) {
                            onChange(null, parsedMax.takeIf { it != maxRenames }, parsedInterval.takeIf { it != intervalHours })
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("governance-auto-naming-save"),
                    size = JButtonSize.Medium,
                    enabled = !busy && maxValid && intervalValid,
                )
            }
        }
    }
}

@Composable
private fun CacheKeepaliveEditor(
    enabled: Boolean,
    minutes: Int,
    maxPings: Int,
    busy: Boolean,
    onChange: (Boolean?, Int?, Int?) -> Unit,
) {
    var minutesText by rememberSaveable(minutes) { mutableStateOf(minutes.toString()) }
    var maxText by rememberSaveable(maxPings) { mutableStateOf(maxPings.toString()) }
    val parsedMinutes = minutesText.trim().toIntOrNull()
    val parsedMax = maxText.trim().toIntOrNull()
    val minutesValid = parsedMinutes != null && parsedMinutes in AssistantGovernanceBounds.CACHE_KEEPALIVE_MINUTES
    val maxValid = parsedMax != null && parsedMax in AssistantGovernanceBounds.CACHE_KEEPALIVE_MAX
    val dirty = (parsedMinutes != minutes) || (parsedMax != maxPings)

    JCard(Modifier.testTag("governance-cache-keepalive-card")) {
        JSwitchRow(
            title = stringResource(R.string.assistant_governance_cache_keepalive),
            checked = enabled,
            onCheckedChange = { onChange(it, null, null) },
            subtitle = stringResource(R.string.assistant_governance_cache_keepalive_caption),
            leadingIcon = PersonaIcons.ai,
            enabled = !busy,
            modifier = Modifier.testTag("governance-switch-cache-keepalive"),
        )
        if (enabled) {
            Spacer(Modifier.height(Tokens.Space.s))
            JTextField(
                value = minutesText,
                onValueChange = { minutesText = it },
                modifier = Modifier.fillMaxWidth().testTag("governance-cache-keepalive-minutes"),
                label = stringResource(R.string.assistant_governance_cache_keepalive_minutes),
                supportingText = if (!minutesValid) stringResource(
                    R.string.assistant_governance_invalid_range,
                    AssistantGovernanceBounds.CACHE_KEEPALIVE_MINUTES.first,
                    AssistantGovernanceBounds.CACHE_KEEPALIVE_MINUTES.last,
                ) else null,
                isError = !minutesValid,
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                autofill = false,
            )
            Spacer(Modifier.height(Tokens.Space.s))
            JTextField(
                value = maxText,
                onValueChange = { maxText = it },
                modifier = Modifier.fillMaxWidth().testTag("governance-cache-keepalive-max"),
                label = stringResource(R.string.assistant_governance_cache_keepalive_max),
                supportingText = if (!maxValid) stringResource(
                    R.string.assistant_governance_invalid_range,
                    AssistantGovernanceBounds.CACHE_KEEPALIVE_MAX.first,
                    AssistantGovernanceBounds.CACHE_KEEPALIVE_MAX.last,
                ) else null,
                isError = !maxValid,
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                autofill = false,
            )
            if (dirty) {
                Spacer(Modifier.height(Tokens.Space.s))
                JButton(
                    text = stringResource(R.string.assistant_governance_save_numbers),
                    onClick = {
                        if (minutesValid && maxValid) {
                            onChange(null, parsedMinutes.takeIf { it != minutes }, parsedMax.takeIf { it != maxPings })
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("governance-cache-keepalive-save"),
                    size = JButtonSize.Medium,
                    enabled = !busy && minutesValid && maxValid,
                )
            }
        }
    }
}

@Composable
private fun SessionCleanupEditor(
    idleCloseHours: Int?,
    archiveClosedDays: Int?,
    busy: Boolean,
    onSaveIdleClose: (Int) -> Unit,
    onSaveArchiveClosed: (Int) -> Unit,
) {
    var idleText by rememberSaveable(idleCloseHours) { mutableStateOf((idleCloseHours ?: 0).toString()) }
    var archiveText by rememberSaveable(archiveClosedDays) { mutableStateOf((archiveClosedDays ?: 0).toString()) }
    val parsedIdle = idleText.trim().toIntOrNull()
    val parsedArchive = archiveText.trim().toIntOrNull()
    val idleValid = idleCloseHours == null || (parsedIdle != null && parsedIdle in AssistantGovernanceBounds.IDLE_CLOSE_HOURS)
    val archiveValid = archiveClosedDays == null || (parsedArchive != null && parsedArchive in AssistantGovernanceBounds.ARCHIVE_CLOSED_DAYS)
    val idleDirty = idleCloseHours != null && parsedIdle != idleCloseHours
    val archiveDirty = archiveClosedDays != null && parsedArchive != archiveClosedDays

    JCard(Modifier.testTag("governance-cleanup-card")) {
        if (idleCloseHours != null) {
            JTextField(
                value = idleText,
                onValueChange = { idleText = it },
                modifier = Modifier.fillMaxWidth().testTag("governance-idle-close-hours"),
                label = stringResource(R.string.assistant_governance_idle_close_hours),
                supportingText = if (!idleValid) stringResource(
                    R.string.assistant_governance_invalid_range,
                    AssistantGovernanceBounds.IDLE_CLOSE_HOURS.first,
                    AssistantGovernanceBounds.IDLE_CLOSE_HOURS.last,
                ) else stringResource(R.string.assistant_governance_zero_disables),
                isError = !idleValid,
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                autofill = false,
            )
        }
        if (archiveClosedDays != null) {
            if (idleCloseHours != null) Spacer(Modifier.height(Tokens.Space.s))
            JTextField(
                value = archiveText,
                onValueChange = { archiveText = it },
                modifier = Modifier.fillMaxWidth().testTag("governance-archive-closed-days"),
                label = stringResource(R.string.assistant_governance_archive_closed_days),
                supportingText = if (!archiveValid) stringResource(
                    R.string.assistant_governance_invalid_range,
                    AssistantGovernanceBounds.ARCHIVE_CLOSED_DAYS.first,
                    AssistantGovernanceBounds.ARCHIVE_CLOSED_DAYS.last,
                ) else stringResource(R.string.assistant_governance_zero_disables),
                isError = !archiveValid,
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                autofill = false,
            )
        }
        if (idleDirty || archiveDirty) {
            Spacer(Modifier.height(Tokens.Space.s))
            JButton(
                text = stringResource(R.string.assistant_governance_save_numbers),
                onClick = {
                    if (idleDirty && idleValid && parsedIdle != null) onSaveIdleClose(parsedIdle)
                    if (archiveDirty && archiveValid && parsedArchive != null) onSaveArchiveClosed(parsedArchive)
                },
                modifier = Modifier.fillMaxWidth().testTag("governance-cleanup-save"),
                size = JButtonSize.Medium,
                enabled = !busy && idleValid && archiveValid,
            )
        }
    }
}

/** Sub-page for viewing and revoking remembered task-start approvals (`GET/DELETE /api/v1/task-trust`). */
@Composable
internal fun TaskTrustPart(
    state: AssistantSettingsUiState,
    machines: List<Machine>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRevoke: suspend (TaskTrustKind) -> Boolean,
) {
    val trust = state.taskTrust
    val kinds = trust.kinds.orEmpty()
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf<TaskTrustKind?>(null) }

    fun displayPlace(kind: TaskTrustKind): String {
        val host = kind.machine.takeIf(String::isNotBlank)?.let { id ->
            machines.firstOrNull { it.id == id || GroupKeys.raw(it.id) == GroupKeys.raw(id) }?.displayName ?: id
        }
        val cwd = kind.cwd.takeIf(String::isNotBlank)
        return listOfNotNull(host, cwd).joinToString(" · ").ifBlank { kind.key }
    }

    JListPage(
        title = stringResource(R.string.assistant_governance_task_trust_title),
        onBack = onBack,
        refreshing = trust.loading && trust.kinds != null,
        onRefresh = onRefresh,
    ) {
        if (trust.failed) {
            item("failed") {
                JNotice(
                    stringResource(R.string.assistant_governance_load_failed),
                    status = Status.NeedsYou,
                    actionLabel = stringResource(R.string.assistant_settings_retry),
                    onAction = onRefresh,
                )
            }
        }
        state.notice?.let { notice ->
            item("notice") {
                JNotice(notice.text.resolve(LocalResources.current), status = if (notice.error) Status.NeedsYou else Status.Unknown)
            }
        }
        if (trust.kinds == null && trust.loading) {
            item("loading") { JLoading(text = stringResource(R.string.assistant_settings_loading)) }
        }
        if (trust.kinds != null && kinds.isEmpty() && !trust.failed) {
            item("empty") {
                JEmptyState(
                    stringResource(R.string.assistant_governance_task_trust_empty_title),
                    stringResource(R.string.assistant_governance_task_trust_empty_body),
                    icon = PersonaIcons.privacy,
                )
            }
        }
        itemsIndexed(kinds, key = { index, kind -> "trust-$index-${kind.key}" }) { index, kind ->
            val modeText = stringResource(
                if (kind.readonly) R.string.assistant_governance_task_trust_readonly
                else R.string.assistant_governance_task_trust_edit,
            )
            val subtitle = if (kind.ambiguous) {
                "$modeText · ${stringResource(R.string.assistant_governance_task_trust_ambiguous)}"
            } else modeText
            JGroupItem(GroupPosition.of(index, kinds.size)) {
                JRow(
                    title = displayPlace(kind),
                    subtitle = subtitle,
                    leadingIcon = PersonaIcons.folder,
                    chevron = false,
                    trailing = {
                        JButton(
                            text = stringResource(R.string.assistant_governance_task_trust_revoke),
                            onClick = { confirming = kind },
                            modifier = Modifier.testTag("task-trust-revoke-${kind.key}"),
                            kind = JButtonKind.Destructive,
                            size = JButtonSize.Small,
                            enabled = !state.busy,
                        )
                    },
                )
            }
        }
    }

    confirming?.let { target ->
        JDialog(
            onDismiss = { confirming = null },
            title = stringResource(R.string.assistant_governance_task_trust_revoke_title),
            text = stringResource(R.string.assistant_governance_task_trust_revoke_body, displayPlace(target)),
            destructive = true,
            confirmEnabled = !state.busy,
            confirm = stringResource(R.string.assistant_governance_task_trust_revoke) to {
                val chosen = target
                confirming = null
                scope.launch { val _ = onRevoke(chosen) }
            },
            dismiss = stringResource(R.string.assistant_settings_cancel) to { confirming = null },
        )
    }
}

/** Sub-page listing active leader directions so the owner can inspect and edit standing notes. */
@Composable
internal fun DirectionNotesListPart(
    state: AssistantSettingsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val notes = state.directionNotes
    val leaders = notes.leaders.orEmpty()
    JListPage(
        title = stringResource(R.string.assistant_governance_direction_notes_title),
        onBack = onBack,
        refreshing = notes.loading && notes.leaders != null,
        onRefresh = onRefresh,
    ) {
        if (notes.failed) {
            item("failed") {
                JNotice(
                    stringResource(R.string.assistant_governance_load_failed),
                    status = Status.NeedsYou,
                    actionLabel = stringResource(R.string.assistant_settings_retry),
                    onAction = onRefresh,
                )
            }
        }
        if (notes.leaders == null && notes.loading) {
            item("loading") { JLoading(text = stringResource(R.string.assistant_settings_loading)) }
        }
        if (notes.leaders != null && leaders.isEmpty() && !notes.failed) {
            item("empty") {
                JEmptyState(
                    stringResource(R.string.assistant_governance_direction_notes_empty_title),
                    stringResource(R.string.assistant_governance_direction_notes_empty_body),
                    icon = PersonaIcons.goal,
                )
            }
        }
        itemsIndexed(leaders, key = { _, leader -> leader.id }) { index, leader ->
            val preview = leader.notes.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                ?: leader.project.takeIf(String::isNotBlank)
            JGroupItem(GroupPosition.of(index, leaders.size)) {
                JRow(
                    title = leader.displayName,
                    subtitle = preview,
                    leadingIcon = PersonaIcons.goal,
                    modifier = Modifier.testTag("direction-notes-row-${leader.id}"),
                    onClick = { onOpen(leader.id) },
                )
            }
        }
    }
}

/** Editor sub-page for one direction's standing notes (`PUT /api/v1/assistant/directions/{id}/notes`, with 409 conflict recovery). */
@Composable
internal fun DirectionNotesEditorPart(
    state: AssistantSettingsUiState,
    leaderId: String?,
    onBack: () -> Unit,
    onSave: suspend (String, String, String?, Boolean) -> Boolean,
    onClearConflict: () -> Unit,
) {
    val leader: DirectionLeader? = leaderId?.let { id -> state.directionNotes.leaders?.firstOrNull { it.id == id } }
    val conflict = state.directionNotes.conflict?.takeIf { it.leaderId == leaderId }
    val scope = rememberCoroutineScope()
    var text by rememberSaveable(leaderId) { mutableStateOf(leader?.notes.orEmpty()) }
    var baseNotesAt by rememberSaveable(leaderId) { mutableStateOf(leader?.notesAt) }
    var initialized by rememberSaveable(leaderId) { mutableStateOf(leader != null) }
    LaunchedEffect(leader?.id) {
        if (!initialized && leader != null) {
            text = leader.notes
            baseNotesAt = leader.notesAt
            initialized = true
        }
    }
    val charCount = text.codePointCount(0, text.length)
    val tooLong = charCount > AssistantGovernanceBounds.DIRECTION_NOTES_MAX
    val dirty = leader != null && text != leader.notes

    JListPage(
        title = leader?.displayName ?: stringResource(R.string.assistant_governance_direction_notes_title),
        modifier = Modifier.imePadding(),
        onBack = {
            onClearConflict()
            onBack()
        },
        bottomBar = {
            JBottomBar {
                JButton(
                    text = stringResource(R.string.assistant_settings_save),
                    onClick = {
                        val id = leader?.id ?: return@JButton
                        scope.launch {
                            if (onSave(id, text, baseNotesAt, false)) {
                                baseNotesAt = state.directionNotes.leaders?.firstOrNull { it.id == id }?.notesAt
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("direction-notes-save"),
                    enabled = leader != null && dirty && !tooLong && !state.busy,
                    loading = state.busy,
                )
            }
        },
    ) {
        if (conflict != null) {
            item("conflict") {
                JCard(Modifier.testTag("direction-notes-conflict")) {
                    Text(
                        stringResource(R.string.assistant_governance_direction_notes_conflict),
                        style = PersonaTheme.type.body,
                        color = PersonaTheme.palette.danger.dot,
                    )
                    Spacer(Modifier.height(Tokens.Space.s))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                        JButton(
                            text = stringResource(R.string.assistant_governance_direction_notes_load_latest),
                            onClick = {
                                text = conflict.currentNotes
                                baseNotesAt = conflict.currentNotesAt
                                onClearConflict()
                            },
                            modifier = Modifier.weight(1f).testTag("direction-notes-load-latest"),
                            kind = JButtonKind.Secondary,
                            size = JButtonSize.Small,
                            enabled = !state.busy,
                        )
                        JButton(
                            text = stringResource(R.string.assistant_governance_direction_notes_overwrite),
                            onClick = {
                                val id = leader?.id ?: return@JButton
                                scope.launch {
                                    if (onSave(id, text, conflict.currentNotesAt, true)) {
                                        baseNotesAt = state.directionNotes.leaders?.firstOrNull { it.id == id }?.notesAt
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f).testTag("direction-notes-overwrite"),
                            kind = JButtonKind.Destructive,
                            size = JButtonSize.Small,
                            enabled = !tooLong && !state.busy,
                        )
                    }
                }
            }
        }
        state.notice?.let { notice ->
            item("notice") {
                JNotice(notice.text.resolve(LocalResources.current), status = if (notice.error) Status.NeedsYou else Status.Unknown)
            }
        }
        item("editor") {
            Spacer(Modifier.height(Tokens.Space.s))
            JTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().testTag("direction-notes-field"),
                label = stringResource(R.string.assistant_governance_direction_notes_field),
                placeholder = stringResource(R.string.assistant_governance_direction_notes_placeholder),
                supportingText = if (tooLong) {
                    stringResource(R.string.assistant_governance_direction_notes_too_long, AssistantGovernanceBounds.DIRECTION_NOTES_MAX)
                } else {
                    stringResource(R.string.assistant_governance_direction_notes_count, charCount, AssistantGovernanceBounds.DIRECTION_NOTES_MAX)
                },
                isError = tooLong,
                enabled = leader != null && !state.busy,
                minLines = 10,
                maxLines = Int.MAX_VALUE,
                readingContent = true,
            )
        }
    }
}
