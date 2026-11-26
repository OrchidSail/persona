package persona.feature.assistant

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import persona.core.contracts.Session
import persona.core.contracts.SessionKey
import persona.core.designsystem.InlineTone
import persona.core.designsystem.JDialog
import persona.core.designsystem.JInlineStatus

/** Hub feature advertising `DELETE /api/v1/assistant/conversations/{id}` (docs/protocol/protocol.md). */
internal const val ConversationDeleteFeature = "assistant_conversation_delete"

/**
 * Matches web `conversationDeleteAllowed` busy guards (`web/src/lib/conversation-delete.ts`) and server
 * `conversation_busy` checks (`server/internal/assistant/conversation_delete.go`).
 */
internal fun conversationBusyForDelete(session: Session): Boolean =
    session.starting ||
        session.waiting == true ||
        session.pendingInteractions > 0 ||
        !session.queued.isNullOrEmpty() ||
        session.compaction?.state == "running" ||
        session.backgroundWorking == true ||
        session.backgroundWaiting ||
        session.status == "working" ||
        session.status == "blocked" ||
        session.state == "working" ||
        session.state == "needs_you"

/**
 * Whether permanent deletion may be offered and executed for [session]:
 * - Hub advertises `assistant_conversation_delete` ([supported]).
 * - Never the current main conversation ([main]) or built-in background supervisor ([builtIn]).
 * - Never an already-deleted tombstone or external placeholder without a session key.
 * - Never while actively working, waiting, compacting, queued, or holding pending interactions.
 */
internal fun conversationDeleteAllowed(
    session: Session,
    supported: Boolean,
    main: SessionKey?,
    builtIn: String? = null,
): Boolean {
    if (!supported) return false
    if (session.machineId.isBlank() || session.sessionId.isBlank()) return false
    if (session.deleted) return false
    if (main != null && (session.key == main || session.sessionId == main.sessionId)) return false
    if (!builtIn.isNullOrBlank() && session.sessionId == builtIn) return false
    if (conversationBusyForDelete(session)) return false
    return true
}

/**
 * Shared irreversible confirmation dialog for permanently deleting a non-main assistant conversation across Glass,
 * Brief, and Board (DESIGN_SYSTEM.md §4.4, matching web `DeleteConversationDialog.tsx`).
 */
@Composable
internal fun DeleteConversationDialog(
    title: String,
    busy: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    JDialog(
        onDismiss = { if (!busy) onDismiss() },
        title = stringResource(R.string.assistant_delete_title, title),
        text = stringResource(R.string.assistant_delete_body),
        destructive = true,
        confirm = stringResource(R.string.assistant_delete_confirm) to onConfirm,
        dismiss = stringResource(R.string.assistant_cancel) to { if (!busy) onDismiss() },
        confirmEnabled = !busy,
        confirmLoading = busy,
        dismissEnabled = !busy,
        content = error?.let { message ->
            {
                JInlineStatus(message, tone = InlineTone.Failed)
            }
        },
    )
}
