package dev.persona.watch.extensions

internal data class RecoveredWidgetSelection(
    val widgets: List<ExtensionWidget>, val selected: ExtensionWidget?, val selectedKey: String?,
    val projection: ExtensionWidgetView?,
)

/** Resume can hide private content while checking credentials without losing a key-only selection. */
internal fun recoverWidgetSelection(
    mayKeep: Boolean, sameConnection: Boolean, requestedKey: String?,
    previousWidgets: List<ExtensionWidget>, previousSelectedKey: String?, previousProjection: ExtensionWidgetView?,
    savedWidgets: List<ExtensionWidget>, savedWidget: ExtensionWidget?, savedView: ExtensionWidgetView?,
): RecoveredWidgetSelection {
    if (!mayKeep) return RecoveredWidgetSelection(emptyList(), null, null, null)
    val widgets = savedWidgets.ifEmpty { if (sameConnection) previousWidgets else emptyList() }
    val selected = savedWidget ?: widgets.firstOrNull { it.key == requestedKey }
        ?: previousWidgets.firstOrNull { sameConnection && it.key == requestedKey }
    val projection = savedView.takeIf { savedWidget != null && savedWidget.key == selected?.key }
        ?: previousProjection.takeIf { sameConnection && previousSelectedKey != null && previousSelectedKey == selected?.key }
    return RecoveredWidgetSelection(widgets, selected, selected?.key ?: requestedKey, projection)
}

/** A bounded wake checks the original absolute deadline, never a newly extended expiry. */
internal object WidgetExpiry {
    const val MAX_WAIT_MS = 86_400_000L
    fun nextDelay(deadline: Long, now: Long): Long? {
        if (deadline <= now) return null
        // The positive difference can overflow only across widely separated signed timestamps.
        val remaining = deadline - now
        return if (remaining <= 0) MAX_WAIT_MS else minOf(remaining, MAX_WAIT_MS)
    }
}
