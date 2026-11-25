package persona.feature.chat

import persona.core.designsystem.AppStyle

/**
 * Where an Agent's chat opens its session's terminal (#4339 C7, iOS `ChatView.openTerminal`), which comes up over the
 * chat as a cover: ⋯'s Terminal and the mark at the end of the live work line across every style.
 */
internal object ChatTerminalPlaces {
    /** The chat has a terminal: an Agent's, not read-only, whose session runs in one (a Codex thread does not). */
    fun has(readOnly: Boolean, canTerminal: Boolean, style: ChatRenderStyle): Boolean =
        !readOnly && canTerminal && style != ChatRenderStyle.Assistant

    /** No separate header terminal button in any style: terminal lives on the live work line and in ⋯. */
    fun inHeader(style: AppStyle): Boolean = false

    /** The mark at the end of the work line (iOS `WorkLine.openTerminal`): the live line across every style. */
    fun onWorkLine(live: Boolean, style: AppStyle): Boolean = live
}
