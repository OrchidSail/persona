package persona.feature.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import persona.core.contracts.Session
import persona.core.contracts.SessionKey
import persona.core.contracts.api.AssistantConfigUpdate
import persona.core.contracts.api.AssistantManagement
import persona.core.contracts.api.AssistantRepository
import persona.core.contracts.api.ConversationTagFilters
import persona.core.designsystem.*
import persona.core.designsystem.icons.PersonaIcons
import persona.core.domain.ConversationTitle

/**
 * What the conversation history keeps of its own edits, for the Persona page's list and the app's sidebar alike (iOS
 * `AssistantSidebar`): the titles renamed here, shown until the hub's list carries them, and the archived conversations as
 * last read, which "Archived (N)" counts and the Archived sheet lists, so the count is what the sheet shows.
 */
@Stable
internal class ConversationHistory(private val management: AssistantManagement?, private val repository: AssistantRepository) {
    val canEditTags: Boolean get() = management != null
    /** The archived conversations as the hub last listed them; null before the first read and where the hub can't list them. */
    var archived: List<Session>? by mutableStateOf(null)
        private set
    /** Titles renamed here, by conversation, until the hub's list has them. */
    var titles: Map<SessionKey, String> by mutableStateOf(emptyMap())
        private set
    /** Whole-set tag writes made here, shown until the hub carries the same set. */
    var tagEdits: Map<SessionKey, List<String>> by mutableStateOf(emptyMap())
        private set
    /** The selected exact tag; null leaves the list unfiltered. */
    var activeTag: String? by mutableStateOf(null)
        private set
    /** The conversation whose whole-set editor is open. */
    var editingTags: Session? by mutableStateOf(null)
    /** Brought back here and not confirmed yet: off the archived list at once. */
    var restoring: Set<SessionKey> by mutableStateOf(emptySet())
        private set
    /** The conversation the rename dialog is open on. */
    var renaming: Session? by mutableStateOf(null)
    /** The conversation the permanent-delete confirmation dialog is open on. */
    var deleting: Session? by mutableStateOf(null)
        private set
    var deleteBusy: Boolean by mutableStateOf(false)
        private set
    var deleteError: String? by mutableStateOf(null)
        private set
    /** Permanently deleted here: hidden from all lists immediately while the hub refresh catches up. */
    var deleted: Set<SessionKey> by mutableStateOf(emptySet())
        private set
    var archivedOpen: Boolean by mutableStateOf(false)
    /** Whether the controlled filter bar shows its add/remove/reset/done controls. */
    var editingTagFilters: Boolean by mutableStateOf(false)
    var tagFiltersBusy: Boolean by mutableStateOf(false)
        private set
    private val tagFilterLock = Mutex()

    /** The archived conversations to show: the last read, less deleted tombstones, restoring ones, and open ones. */
    fun shownArchived(open: Set<SessionKey>): List<Session> =
        archived.orEmpty().filter { !it.deleted && it.key !in deleted && it.key !in restoring && it.key !in open }

    /** [sessions] with the titles renamed here. */
    fun titled(sessions: List<Session>): List<Session> =
        if (titles.isEmpty()) sessions else sessions.map { s -> titles[s.key]?.let { s.copy(title = it) } ?: s }

    /** [sessions] with this client's temporary titles and optimistic whole-set tag writes. */
    fun tagged(sessions: List<Session>): List<Session> {
        val shown = titled(sessions)
        return if (tagEdits.isEmpty()) shown else shown.map { session ->
            tagEdits[session.key]?.let { session.copy(tags = it) } ?: session
        }
    }

    fun selectTag(tag: String) {
        activeTag = if (activeTag == tag) null else tag
    }

    /** Forgets edits the hub carries now, and clears a filter whose tag has disappeared everywhere. */
    fun settle(sessions: List<Session>) {
        val now = sessions.associateBy { it.key }
        if (titles.isNotEmpty()) {
            titles.filter { (key, title) -> now[key]?.title == title }.keys.takeIf { it.isNotEmpty() }?.let { titles = titles - it }
        }
        if (tagEdits.isNotEmpty()) {
            tagEdits.filter { (key, tags) -> now[key]?.tags.orEmpty() == tags }.keys
                .takeIf { it.isNotEmpty() }?.let { tagEdits = tagEdits - it }
        }
        val selected = activeTag
        if (selected != null && !repository.assistant.value.conversationTagFilters.customized &&
            tagged(sessions).none { it.tags?.contains(selected) == true }) activeTag = null
    }

    private fun clearRemovedSelection(entries: List<String>?) {
        if (entries != null && activeTag != null && activeTag !in entries) activeTag = null
    }

    /** Optimistically saves the exact entries/order; null resets to automatic. Failure restores and reloads the snapshot. */
    suspend fun saveConversationTagFilters(entries: List<String>?): Result<Unit> = tagFilterLock.withLock {
        val api = management ?: return@withLock Result.failure(UnsupportedOperationException("Conversation tag filters unavailable"))
        val before = repository.assistant.value.conversationTagFilters
        if (!before.supported) return@withLock Result.failure(UnsupportedOperationException("Conversation tag filters unavailable"))
        entries?.let(::conversationTagFiltersProblem)?.let { problem ->
            return@withLock Result.failure(IllegalArgumentException("Conversation tag filters are invalid: $problem"))
        }
        val normalized = entries?.let(::normalizeConversationTags)
        if (before.entries == normalized) return@withLock Result.success(Unit)
        val selectedBefore = activeTag
        val optimistic = ConversationTagFilters(normalized)
        tagFiltersBusy = true
        var update: suspend (ConversationTagFilters) -> Unit = {}
        try {
            update = repository.conversationTagFiltersUpdater()
            update(optimistic)
            clearRemovedSelection(normalized)
            val answer = api.updateConfig(AssistantConfigUpdate(conversationTagFilters = optimistic))
            if (!answer.conversationTagFilters.supported) {
                throw UnsupportedOperationException("Hub does not support conversation tag filters")
            }
            update(answer.conversationTagFilters)
            clearRemovedSelection(answer.conversationTagFilters.entries)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                update(before)
                if (selectedBefore != null && activeTag == null) activeTag = selectedBefore
                assistantAttempt { repository.refresh() }
            }
            throw cancelled
        } catch (error: Exception) {
            update(before)
            if (selectedBefore != null && activeTag == null) activeTag = selectedBefore
            assistantAttempt { repository.refresh() }
            Result.failure(error)
        } finally {
            tagFiltersBusy = false
        }
    }

    /** Replaces one conversation's tags optimistically; a refusal restores the edit it superseded. */
    suspend fun saveTags(key: SessionKey, tags: List<String>): Result<Unit> {
        conversationTagsProblem(tags)?.let { problem ->
            return Result.failure(IllegalArgumentException("Conversation tags are invalid: $problem"))
        }
        val api = management ?: return Result.failure(UnsupportedOperationException("Conversation tags unavailable"))
        val normalized = normalizeConversationTags(tags)
        val previous = tagEdits[key]
        tagEdits = tagEdits + (key to normalized)
        val result = assistantAttempt { api.setConversationTags(key, normalized) }
        if (result.isSuccess) {
            // A stale acknowledged read must not erase the optimistic row; settle removes it once the new set arrives.
            assistantAttempt { repository.refresh() }
        } else {
            tagEdits = if (previous == null) tagEdits - key else tagEdits + (key to previous)
        }
        return result
    }

    /** Reads the archived conversations again; a failed read keeps the list shown, a hub that can't list them has none. */
    suspend fun loadArchived() {
        val api = management ?: return
        archived = try {
            api.archivedConversations()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: UnsupportedOperationException) {
            null
        } catch (_: Exception) {
            archived
        }
    }

    /** The hub's rename, then the list read again; the new title shows until that list carries it. */
    suspend fun rename(key: SessionKey, title: String): Result<Unit> {
        val result = assistantAttempt { requireNotNull(management).renameConversation(key, title) }
        if (result.isSuccess) {
            titles = titles + (key to title)
            assistantAttempt { repository.refresh() }
        }
        return result
    }

    /** Off the archived list at once; back on it when the hub refuses. */
    suspend fun unarchive(key: SessionKey): Result<Unit> {
        restoring = restoring + key
        val result = assistantAttempt { requireNotNull(management).unarchiveConversation(key) }
        if (result.isSuccess) {
            assistantAttempt { repository.refresh() }
            loadArchived()
        }
        restoring = restoring - key
        return result
    }

    /** Opens the permanent-delete confirmation dialog for [session]. */
    fun requestDelete(session: Session) {
        if (deleteBusy) return
        deleteError = null
        deleting = session
    }

    /** Dismisses the permanent-delete confirmation dialog when no deletion is in flight. */
    fun dismissDelete() {
        if (deleteBusy) return
        deleteError = null
        deleting = null
    }

    /** Closes the confirmation dialog when another device already deleted the target conversation. */
    fun acknowledgeExternalDeletion(key: SessionKey) {
        deleted = deleted + key
        archived = archived?.filterNot { it.key == key }
        if (deleting?.key == key && !deleteBusy) {
            deleteError = null
            deleting = null
        }
    }

    /**
     * Permanently deletes the currently armed conversation after re-checking hard protections (main, background
     * supervisor, busy state) against [latestConversations]. Prevents re-entry while in flight.
     */
    suspend fun confirmDelete(
        latestConversations: List<Session>,
        supported: Boolean,
        main: SessionKey?,
        builtIn: String?,
        unavailableMessage: String,
        errorMessage: (Exception) -> String,
        onConfirmed: (SessionKey) -> Unit = {},
    ): ConversationDeleteOutcome {
        val target = deleting ?: return ConversationDeleteOutcome.Ignored
        if (deleteBusy) return ConversationDeleteOutcome.Ignored
        val current = latestConversations.firstOrNull { it.key == target.key }
            ?: archived?.firstOrNull { it.key == target.key }
            ?: target
        if (current.deleted) {
            acknowledgeExternalDeletion(current.key)
            onConfirmed(current.key)
            return ConversationDeleteOutcome.Deleted(current.key)
        }
        if (!conversationDeleteAllowed(current, supported, main, builtIn)) {
            deleteError = unavailableMessage
            return ConversationDeleteOutcome.Failed(unavailableMessage)
        }
        val api = management ?: run {
            deleteError = unavailableMessage
            return ConversationDeleteOutcome.Failed(unavailableMessage)
        }
        deleteBusy = true
        deleteError = null
        return try {
            api.deleteConversation(current.key)
            deleted = deleted + current.key
            archived = archived?.filterNot { it.key == current.key }
            deleting = null
            deleteError = null
            onConfirmed(current.key)
            assistantAttempt { repository.refresh() }
            loadArchived()
            ConversationDeleteOutcome.Deleted(current.key)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = errorMessage(error)
            deleteError = message
            ConversationDeleteOutcome.Failed(message)
        } finally {
            deleteBusy = false
        }
    }
}

/** Result of confirming permanent deletion in [ConversationHistory.confirmDelete]. */
internal sealed interface ConversationDeleteOutcome {
    data object Ignored : ConversationDeleteOutcome
    data class Deleted(val key: SessionKey) : ConversationDeleteOutcome
    data class Failed(val message: String) : ConversationDeleteOutcome
}

/** The conversations [conversations] lists open (not archived): an archived one listed again has been brought back. */
internal fun openKeys(conversations: List<Session>): Set<SessionKey> = conversations.filter { !it.archived }.mapTo(HashSet()) { it.key }

@Composable
internal fun rememberConversationHistory(management: AssistantManagement?, repository: AssistantRepository): ConversationHistory =
    remember(management, repository) { ConversationHistory(management, repository) }

/**
 * The history's rename dialog and Archived sheet (iOS `renameSessionAlert`, `ArchivedConversationsView`), and the reads
 * that keep "Archived (N)" current: when the list shows, and whenever a conversation joins or leaves it. [conversations]
 * is the list as the hub has it; [onOpen] opens an archived one; [notice] and [failure] say how an edit went.
 */
@Composable
internal fun ConversationHistoryDialogs(
    history: ConversationHistory, conversations: List<Session>, scope: CoroutineScope, onOpen: (SessionKey) -> Unit,
    notice: (String) -> Unit, failure: (Exception) -> Unit, customizedTagFilters: Boolean = false,
    mainSession: SessionKey? = null,
    builtInSupervisor: String? = null,
    deleteSupported: Boolean = false,
    onDeleted: (SessionKey) -> Unit = {},
    showDeleteDialog: Boolean = true,
) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val haptics = rememberHaptics()
    val words = conversationWords()
    val open = remember(conversations) { openKeys(conversations) }
    val known = remember(conversations, history.archived, history.restoring, history.deleted) {
        (conversations + history.shownArchived(open)).distinctBy { it.key }
    }
    LaunchedEffect(history, open) { history.loadArchived() }
    LaunchedEffect(history, known, history.tagEdits, customizedTagFilters) { history.settle(known) }
    history.renaming?.let { session ->
        RenameConversationDialog(session, onDismiss = { history.renaming = null }) { title ->
            history.renaming = null
            scope.launch {
                history.rename(session.key, title)
                    .onSuccess { notice(resources.getString(R.string.assistant_renamed)) }
                    .onFailure { (it as? Exception)?.let(failure) }
            }
        }
    }
    history.editingTags?.let { session ->
        var busy by remember(session.key.path) { mutableStateOf(false) }
        ConversationTagsDialog(session, busy, onDismiss = { history.editingTags = null }) { tags ->
            busy = true
            scope.launch {
                history.saveTags(session.key, tags)
                    .onSuccess {
                        history.editingTags = null
                        notice(resources.getString(R.string.assistant_tags_saved, session.conversationTitle(words)))
                    }
                    .onFailure { (it as? Exception)?.let(failure) }
                busy = false
            }
        }
    }
    if (history.archivedOpen) ArchivedConversationsSheet(
        history.tagged(history.shownArchived(open)).withConversationTag(history.activeTag),
        onDismiss = { history.archivedOpen = false },
        onOpen = { history.archivedOpen = false; onOpen(it.key) },
        onUnarchive = { session ->
            // As iOS: a tap now, "Unarchived" once the hub has it (DESIGN_SYSTEM.md 4.3).
            haptics(Haptic.Tap)
            scope.launch {
                history.unarchive(session.key)
                    .onSuccess { notice(resources.getString(R.string.assistant_unarchived)) }
                    .onFailure { (it as? Exception)?.let(failure) }
            }
        },
        onEditTags = if (history.canEditTags) ({ history.editingTags = it }) else null,
        canDelete = { session -> conversationDeleteAllowed(session, deleteSupported, mainSession, builtInSupervisor) },
        onDelete = if (deleteSupported) ({ history.requestDelete(it) }) else null,
    )
    if (showDeleteDialog) ConversationDeleteDialogHost(
        history = history,
        conversations = conversations,
        scope = scope,
        mainSession = mainSession,
        builtInSupervisor = builtInSupervisor,
        deleteSupported = deleteSupported,
        onDeleted = onDeleted,
        notice = notice,
    )
}

/**
 * Shared confirmation dialog host for permanently deleting an assistant conversation. Mounted after any sheet so the
 * confirmation always renders on top, whether triggered from the drawer, archived sheet, or title menu.
 */
@Composable
internal fun ConversationDeleteDialogHost(
    history: ConversationHistory,
    conversations: List<Session>,
    scope: CoroutineScope,
    mainSession: SessionKey?,
    builtInSupervisor: String?,
    deleteSupported: Boolean,
    onDeleted: (SessionKey) -> Unit,
    notice: (String) -> Unit,
) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val haptics = rememberHaptics()
    val words = conversationWords()
    val deletingTarget = history.deleting
    val externallyDeleted = deletingTarget != null && conversations.any { it.key == deletingTarget.key && it.deleted }
    LaunchedEffect(externallyDeleted, deletingTarget?.key) {
        if (externallyDeleted && deletingTarget != null) {
            history.acknowledgeExternalDeletion(deletingTarget.key)
            onDeleted(deletingTarget.key)
        }
    }
    history.deleting?.let { session ->
        DeleteConversationDialog(
            title = session.conversationTitle(words),
            busy = history.deleteBusy,
            error = history.deleteError,
            onConfirm = {
                val unavailable = resources.getString(R.string.assistant_delete_unavailable)
                scope.launch {
                    val outcome = history.confirmDelete(
                        latestConversations = conversations,
                        supported = deleteSupported,
                        main = mainSession,
                        builtIn = builtInSupervisor,
                        unavailableMessage = unavailable,
                        errorMessage = { error ->
                            (error as? persona.core.contracts.api.AssistantRefused)?.message?.takeIf { it.isNotBlank() }
                                ?: operationFeedback(error).text.resolve(resources)
                        },
                        onConfirmed = { key ->
                            haptics(Haptic.Tap)
                            onDeleted(key)
                            notice(resources.getString(R.string.assistant_deleted))
                        },
                    )
                    if (outcome is ConversationDeleteOutcome.Failed || outcome is ConversationDeleteOutcome.Ignored) Unit
                }
            },
            onDismiss = history::dismissDelete,
        )
    }
}

/**
 * Rename… (iOS `RenameSessionAlert` for a conversation with no tab): one name, the conversation's title (empty while it
 * has only Claude Code's placeholder), saved when it is not empty and differs.
 */
@Composable
internal fun RenameConversationDialog(session: Session, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable(session.key.path) { mutableStateOf(session.title.takeUnless { it == "Claude Code" }.orEmpty()) }
    val title = name.trim().takeIf { it.isNotEmpty() && it != session.title.trim() }
    JDialog(onDismiss = onDismiss, title = stringResource(R.string.assistant_rename_title),
        confirmEnabled = title != null,
        confirm = stringResource(R.string.assistant_save) to { title?.let(onSave) },
        dismiss = stringResource(R.string.assistant_cancel) to onDismiss) {
        JTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("rename-conversation"), singleLine = true,
            label = stringResource(R.string.assistant_rename_field))
    }
}

/**
 * Archived (iOS `ArchivedConversationsView`): the conversations archived from the list (or by Persona), newest first. A tap
 * opens one; a swipe left or Unarchive brings it back into the list; long press also offers Delete… when the hub advertises
 * `assistant_conversation_delete` and the conversation is eligible. The rows are the Agents archive's (`AgentArchivesScreen`):
 * the title (on Glass a hub title's day and kind quiet before its topic), when as the history says it ([historyTime]), and
 * the action at the end.
 */
@Composable
internal fun ArchivedConversationsSheet(
    sessions: List<Session>, onDismiss: () -> Unit, onOpen: (Session) -> Unit, onUnarchive: (Session) -> Unit,
    onEditTags: ((Session) -> Unit)? = null,
    canDelete: (Session) -> Boolean = { false },
    onDelete: ((Session) -> Unit)? = null,
) {
    val words = conversationWords()
    val resources = androidx.compose.ui.platform.LocalResources.current
    val unarchive = stringResource(R.string.assistant_unarchive)
    var menu by remember { mutableStateOf<Session?>(null) }
    JSheet(onDismiss, title = stringResource(R.string.assistant_archived)) {
        if (sessions.isEmpty()) JEmptyState(stringResource(R.string.assistant_archived_empty),
            stringResource(R.string.assistant_archived_empty_detail), icon = PersonaIcons.archive)
        else LazyColumn(Modifier.weight(1f, fill = false).testTag("archived-conversations")) {
            items(sessions, key = { it.key.path }) { session ->
                JSwipeActions(end = SwipeAction(unarchive, PersonaIcons.unarchive, Status.Working, { onUnarchive(session) })) {
                    // A hub title's day and kind in the quiet ink, the topic after it across every style (iOS `ConversationTitle`).
                    val title = session.conversationTitle(words)
                    val dated = ConversationTitle.parse(title)
                    Column {
                        JRow(dated?.topic ?: title, titleLead = dated?.head, subtitle = historyTime(session.updated, resources),
                            onClick = { onOpen(session) }, onLongClick = { menu = session }, trailing = {
                                JButton(unarchive, { onUnarchive(session) }, kind = JButtonKind.Quiet, size = JButtonSize.Small)
                            })
                        ConversationTagLabels(session, Modifier.padding(horizontal = rowPadding).padding(bottom = Tokens.Space.xs))
                    }
                }
            }
        }
    }
    menu?.let { session ->
        JActionSheet(buildList {
            onEditTags?.let { edit ->
                add(JAction(stringResource(R.string.assistant_edit_tags_menu), PersonaIcons.edit, onClick = { menu = null; edit(session) }))
            }
            add(JAction(unarchive, PersonaIcons.unarchive, onClick = { onUnarchive(session) }))
            if (onDelete != null && canDelete(session)) {
                add(JAction(
                    stringResource(R.string.assistant_delete_menu),
                    PersonaIcons.delete,
                    destructive = true,
                    separatorBefore = true,
                    onClick = { menu = null; onDelete(session) },
                ))
            }
        }, onDismiss = { menu = null }, title = session.conversationTitle(words))
    }
}

/**
 * What Copy diagnostics copies: the conversation's structure as this app has it, never a word of it (its title, its
 * messages). iOS copies its chat trace there (`ChatTrace`), which Android doesn't record.
 */
internal fun conversationDiagnostics(session: Session): String = listOfNotNull(
    "Persona 对话诊断（Android，只有结构，没有内容）", // i18n-ignore: diagnostics for a bug report, as iOS ChatTrace.text
    "session=${session.key.path}",
    "status=${session.status} state=${session.state ?: "-"} since=${session.stateSince ?: "-"}",
    "updated_at=${session.updatedAt ?: "-"} rev=${session.rev ?: "-"}",
    "origin=${session.origin ?: "-"} manager=${session.manager ?: "-"} archived=${session.archived}",
    "launcher=${session.launcher ?: "-"} model=${session.model ?: "-"}",
    "pending=${session.pendingInteractions} queued=${session.queued?.size ?: 0} awaits_reply=${session.awaitsReply} starting=${session.starting}",
    session.turnErrorCode?.let { "turn_error_code=$it" },
).joinToString("\n")
