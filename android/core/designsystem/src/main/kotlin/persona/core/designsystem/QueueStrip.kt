package persona.core.designsystem

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import persona.core.designsystem.icons.PersonaIcons
import persona.core.designsystem.icons.PhIcons

/** How many rows an opened queue shows before it scrolls (iOS `QueueStrip.visibleRows`). */
const val QUEUE_VISIBLE_ROWS = 3

/**
 * How tall an opened queue's list may be, in pixels, from the height its rows take together (spacing included): about
 * [QUEUE_VISIBLE_ROWS] of them, all of them when there are fewer (iOS measures one row and multiplies). Null until it
 * was measured.
 */
fun queueListCap(rowsHeight: Int, count: Int): Int? =
    if (rowsHeight <= 0 || count <= 0) null else rowsHeight * minOf(count, QUEUE_VISIBLE_ROWS) / count

/**
 * Messages typed while the agent works and not taken yet, right above the composer (iOS QueueStrip): [title] says how
 * many and that they go in order ("2 queued · Claude will handle them in order"), then each one numbered, at most two
 * lines. Tapping one hands its index to [onItem] (its actions). While an operation runs ([busy]) a spinner shows and
 * nothing can be tapped; [enabled] false shows the queue without actions. A long queue scrolls under the title.
 *
 * Every style shares the same disclosure, Send Now and attachment-summary behaviour: folded to the title line by
 * default, which opens and folds the strip ([initiallyExpanded] opens it from the start: catalog, screenshots); opened,
 * about [QUEUE_VISIBLE_ROWS] rows show before it scrolls. The title line carries [sendNow], a text button folded or
 * open, for [onSendNow] (iOS Send Now); [sendNowDescription], when set, is spoken with that button and is not drawn.
 * Styles vary only in their container and row skins (floating glass panel with sunken wells in Glass, rounded paper
 * card with dividers in Brief, bordered ticket with dividers in Board). In every style a row with a note in [notes]
 * (by index) says it in the caption size under its words, and [photos] (by index) shows "N images" with the photo mark
 * under the words; a row whose [items] words are empty draws just that.
 *
 * Messages relayed from other conversations are not mixed into [items]: since #6394 they arrive as [relayedItems] and
 * collapse into one summary line ("N messages from other conversations are waiting") in every style. Tapping the line
 * expands them, read-only; tapping one hands its index in the whole queue (the owner's count plus its position) to
 * [onRelayedItem], whose sheet offers copy only.
 */
@Composable
fun QueueStrip(
    title: String,
    items: List<String>,
    onItem: (Int) -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    enabled: Boolean = true,
    sendNow: String? = null,
    sendNowDescription: String? = null,
    onSendNow: () -> Unit = {},
    notes: List<String?> = emptyList(),
    initiallyExpanded: Boolean = false,
    photos: List<Int> = emptyList(),
    relayedItems: List<RelayedQueueItem> = emptyList(),
    relayedSummary: String? = null,
    onRelayedItem: (Int) -> Unit = {},
) {
    if (items.isEmpty() && relayedItems.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    var relayedExpanded by remember { mutableStateOf(false) }
    val spoken = stringResource(R.string.designsystem_with_state, title,
        stringResource(if (expanded) R.string.designsystem_expanded else R.string.designsystem_collapsed))
    val palette = PersonaTheme.palette
    val type = PersonaTheme.type
    val style = PersonaTheme.style
    val glass = style == AppStyle.Glass
    val active = enabled && !busy
    val headerLine = if (glass) type.micro.copy(fontWeight = FontWeight.SemiBold) else type.caption
    val outer = queueStripContainerModifier(style, palette)
    Column(modifier.fillMaxWidth().then(outer).animateContentSize(PersonaTheme.motion.size())) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouch.android)
                .clickable(role = Role.Button) { expanded = !expanded }.semantics { contentDescription = spoken }
                .padding(horizontal = Tokens.Space.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.sm),
        ) {
            JIcon(PhIcons.Tray, null, size = Tokens.Space.l, tint = palette.ink2)
            Text(title, style = headerLine, color = palette.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (busy) JSpinner(Modifier.size(Tokens.Space.Standard.m), stroke = Tokens.Space.Standard.xxs)
            else if (enabled) sendNow?.let { SendNowButton(it, sendNowDescription, headerLine, onSendNow) }
            JIcon(if (expanded) PersonaIcons.expand else PhIcons.CaretRight, null, size = Tokens.Space.m, tint = palette.ink3)
        }
        if (expanded) {
            val hasRelayed = relayedItems.isNotEmpty() && relayedSummary != null
            if (items.isNotEmpty()) {
                var rowsHeight by remember { mutableIntStateOf(0) }
                // The cap bounds the owner's own rows; the relayed block stays outside the scroll so its rows are always
                // reachable, and its own summary line folds them (#6394).
                val cap = queueListCap(rowsHeight, items.size)?.let { with(LocalDensity.current) { it.toDp() } }
                Box(
                    Modifier.fillMaxWidth().padding(bottom = if (hasRelayed) Tokens.Space.xs else Tokens.Space.s)
                        .then(if (cap != null) Modifier.heightIn(max = cap) else Modifier)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Column(
                        Modifier.fillMaxWidth()
                            .then(if (glass) Modifier.padding(horizontal = Tokens.Space.m) else Modifier)
                            .onSizeChanged { rowsHeight = it.height },
                        verticalArrangement = if (glass) Arrangement.spacedBy(Tokens.Space.xs) else Arrangement.Top,
                    ) {
                        val itemActions = stringResource(R.string.designsystem_queue_actions)
                        items.forEachIndexed { index, text ->
                            if (!glass && index > 0) {
                                JDivider(Modifier.padding(start = Tokens.Space.m + Tokens.Size.icon + Tokens.Space.s, end = Tokens.Space.m))
                            }
                            QueueStripRow(
                                index = index,
                                text = text,
                                note = notes.getOrNull(index),
                                photoCount = photos.getOrNull(index) ?: 0,
                                glass = glass,
                                enabled = enabled,
                                active = active,
                                itemActions = itemActions,
                                onClick = { onItem(index) },
                            )
                        }
                    }
                }
            }
            RelayedBlock(relayedItems, relayedSummary, items.size, relayedExpanded,
                onToggle = { relayedExpanded = it }, onRelayedItem)
        }
    }
}

@Composable
private fun queueStripContainerModifier(style: AppStyle, palette: PersonaPalette): Modifier = when (style) {
    AppStyle.Glass -> Modifier.padding(horizontal = 10.dp.dense, vertical = Tokens.Space.xxs)
        .floatingSurface(RoundedCornerShape(Tokens.Radius.panel))
    AppStyle.Brief -> Modifier.padding(horizontal = Tokens.Space.m, vertical = Tokens.Space.xxs)
        .clip(RoundedCornerShape(20.dp.corner)).background(palette.surface)
    AppStyle.Board -> {
        val shape = RoundedCornerShape(8.dp.corner)
        Modifier.padding(horizontal = 10.dp.dense).padding(top = Tokens.Space.xxs, bottom = Tokens.Space.xs)
            .clip(shape).background(palette.surface).border(Tokens.Size.line, palette.line, shape)
    }
}

@Composable
private fun QueueStripRow(
    index: Int,
    text: String,
    note: String?,
    photoCount: Int,
    glass: Boolean,
    enabled: Boolean,
    active: Boolean,
    itemActions: String,
    onClick: () -> Unit,
) {
    val palette = PersonaTheme.palette
    val type = PersonaTheme.type
    val photoWords = if (photoCount > 0) pluralStringResource(R.plurals.designsystem_queue_photos, photoCount, photoCount) else null
    val said = listOfNotNull(photoWords, note).joinToString(". ").ifEmpty { null }
    val spoken = if (said == null) stringResource(R.string.designsystem_queue_item, index + 1, text)
        else stringResource(R.string.designsystem_queue_item_note, index + 1, text, said)
    val rowSkin = if (glass) {
        Modifier.heightIn(min = JChipTokens.height).clip(RoundedCornerShape(Tokens.Radius.card)).background(palette.sunken)
    } else {
        Modifier.heightIn(min = Tokens.MinTouch.ios)
    }
    Row(
        Modifier.fillMaxWidth().then(rowSkin)
            .clickable(enabled = active, role = Role.Button, onClickLabel = itemActions, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .padding(horizontal = Tokens.Space.m, vertical = Tokens.Space.sm),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
    ) {
        QueueNumber(index + 1)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs)) {
            if (text.isNotEmpty()) Text(text, style = type.callout, color = palette.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // iOS: the photos a message carries, as a caption with the photo mark under its words.
            photoWords?.let {
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    JIcon(PersonaIcons.photo, null, size = Tokens.Space.m, tint = palette.ink2, decorative = true)
                    Text(it, style = type.caption, color = palette.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            note?.let { Text(it, style = type.caption, color = palette.ink2) }
        }
        if (enabled) JIcon(PersonaIcons.more, null, size = Tokens.Space.l, tint = palette.ink3,
            modifier = Modifier.padding(top = Tokens.Space.xxs))
    }
}

data class RelayedQueueItem(val text: String, val note: String? = null)

/**
 * The messages other conversations sent in, collapsed to one summary line in every style (#6394). Tapping the line
 * expands the read-only rows; their numbers continue after the owner's own ([ownCount]), and a tap hands the row's
 * whole-queue index to [onRelayedItem] (its sheet offers copy only).
 */
@Composable
private fun RelayedBlock(
    relayedItems: List<RelayedQueueItem>,
    relayedSummary: String?,
    ownCount: Int,
    expanded: Boolean,
    onToggle: (Boolean) -> Unit,
    onRelayedItem: (Int) -> Unit,
) {
    if (relayedItems.isEmpty() || relayedSummary == null) return
    val palette = PersonaTheme.palette
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = JChipTokens.height).clip(RoundedCornerShape(Tokens.Radius.card)).clickable(role = Role.Button) { onToggle(!expanded) }
                .semantics { contentDescription = relayedSummary }
                .padding(horizontal = Tokens.Space.m, vertical = Tokens.Space.sm),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
        ) {
            JIcon(PhIcons.Tray, null, size = Tokens.Space.l, tint = palette.ink2, decorative = true)
            Text(relayedSummary, style = PersonaTheme.type.micro.copy(fontWeight = FontWeight.SemiBold),
                color = palette.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            JIcon(if (expanded) PersonaIcons.expand else PhIcons.CaretRight, null, size = Tokens.Space.m, tint = palette.ink3)
        }
        if (expanded) {
            relayedItems.forEachIndexed { pos, (text, note) ->
                val index = ownCount + pos
                val spoken = if (note == null) stringResource(R.string.designsystem_queue_item, index + 1, text)
                    else stringResource(R.string.designsystem_queue_item_note, index + 1, text, note)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = JChipTokens.height).clip(RoundedCornerShape(Tokens.Radius.card)).background(palette.sunken)
                        .clickable(role = Role.Button) { onRelayedItem(index) }
                        .semantics(mergeDescendants = true) { contentDescription = spoken }
                        .padding(horizontal = Tokens.Space.m, vertical = Tokens.Space.sm),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
                ) {
                    QueueNumber(index + 1)
                    Column(Modifier.weight(1f)) {
                        Text(text, style = PersonaTheme.type.callout, color = palette.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        note?.let { Text(it, style = PersonaTheme.type.caption, color = palette.ink2) }
                    }
                }
            }
        }
    }
}

/**
 * Send Now on Glass's title line: the accent's words, as iOS's plain button, with the platform's touch target around
 * them. [description] is the button's spoken state (what a confirmation used to say) and is not drawn (#7170).
 */
@Composable
private fun SendNowButton(label: String, description: String?, style: TextStyle, onClick: () -> Unit) {
    val spoken = if (description.isNullOrEmpty()) Modifier else Modifier.semantics { stateDescription = description }
    Box(
        Modifier.minimumInteractiveComponentSize().clip(PersonaTheme.shapes.controlShape)
            .clickable(role = Role.Button, onClick = onClick).then(spoken)
            .padding(horizontal = Tokens.Space.xs),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = style, color = PersonaTheme.palette.accent, maxLines = 1, softWrap = false) }
}

/**
 * The place of a queued message: an accent disc in glass (iOS's, `Theme.Size.badge`, growing with the text size as its
 * `scaledFrame` does, up to 1.6 times), ink on a grey disc in brief, a small square on the board.
 */
@Composable
private fun QueueNumber(number: Int) {
    val palette = PersonaTheme.palette
    val style = PersonaTheme.style
    val (fill, ink) = when (style) {
        AppStyle.Brief -> palette.surfaceStrong to palette.ink
        AppStyle.Glass, AppStyle.Board -> palette.accentSoft to palette.accent
    }
    Box(
        Modifier.size(if (style == AppStyle.Glass) Tokens.Size.badge * LocalDensity.current.fontScale.coerceIn(1f, HomeTokens.shellScaleMaximum) else 24.dp)
            .clip(if (style == AppStyle.Board) RoundedCornerShape(4.dp) else CircleShape).background(fill),
        contentAlignment = Alignment.Center,
    ) { Text("$number", style = PersonaTheme.type.micro.copy(fontWeight = FontWeight.Bold), color = ink, maxLines = 1) }
}
