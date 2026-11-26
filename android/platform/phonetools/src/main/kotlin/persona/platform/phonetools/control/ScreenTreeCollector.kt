package persona.platform.phonetools.control

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

internal data class CollectedNode(
    val element: UiElement,
    val node: AccessibilityNodeInfo,
)

internal data class CollectedScreen(
    val nodes: List<CollectedNode>,
    val visibleTexts: List<String>,
    val hasPasswordField: Boolean,
    val topPackage: String?,
    val uiHash: Long,
)

/**
 * Traverses interactive accessibility windows to produce a clean, indexed (@e1..@eN) UI table
 * and deterministic FNV-1a UI fingerprint while masking password fields and ignoring our own overlay windows.
 */
internal object ScreenTreeCollector {
    private const val MIN_NODE_PX = 4
    private const val MAX_DEPTH = 64
    private const val FNV_OFFSET = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L

    private val SKIPPED_WINDOW_TYPES = setOf(
        AccessibilityWindowInfo.TYPE_INPUT_METHOD,
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
        AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY,
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER,
    )

    fun collect(
        windows: List<AccessibilityWindowInfo>,
        width: Int,
        height: Int,
        ownPackage: String,
    ): CollectedScreen {
        val contentWindows = windows
            .filter { it.type !in SKIPPED_WINDOW_TYPES }
            .filter { it.root?.packageName?.toString() != ownPackage || it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }

        val topPkg = contentWindows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .firstNotNullOfOrNull { it.root?.packageName?.toString() }

        val collected = mutableListOf<CollectedNode>()
        val texts = mutableListOf<String>()
        val dedup = HashSet<String>()
        var hash = FNV_OFFSET
        var hasPassword = false
        val rect = Rect()

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH) return
            if (node.isVisibleToUser) {
                node.getBoundsInScreen(rect)
                hash = mix(hash, node.className, node.text, node.contentDescription, rect.flattenToString(), node.isFocused)
                if (node.isPassword) hasPassword = true

                val ownText = if (node.isPassword) {
                    node.hintText?.toString()?.trim()?.ifEmpty { null }
                } else {
                    ownLabel(node)
                }
                if (!ownText.isNullOrBlank()) {
                    val _ = texts.add(ownText)
                }

                if (rect.intersect(0, 0, width, height) && rect.width() >= MIN_NODE_PX && rect.height() >= MIN_NODE_PX) {
                    val editable = node.isEditable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
                    val interactive = node.isClickable || editable || node.isScrollable || node.isCheckable
                    if (interactive || !ownText.isNullOrBlank()) {
                        val label = ownText ?: if (node.isClickable) descendantLabel(node) else ""
                        val role = node.className?.toString()?.substringAfterLast('.')?.ifEmpty { "View" } ?: "View"
                        val key = "$role|$label|${rect.flattenToString()}"
                        if (dedup.add(key)) {
                            val ref = "@e${collected.size + 1}"
                            val states = buildSet {
                                @Suppress("DEPRECATION")
                                if (node.isChecked) { val _ = add("checked") }
                                if (node.isSelected) { val _ = add("selected") }
                                if (!node.isEnabled) { val _ = add("disabled") }
                            }
                            val resId = node.viewIdResourceName?.substringAfterLast('/')
                            val el = UiElement(
                                ref = ref,
                                role = role,
                                name = label,
                                centerX = rect.centerX(),
                                centerY = rect.centerY(),
                                bounds = listOf(rect.left, rect.top, rect.right, rect.bottom),
                                resourceId = if (label.isEmpty() || editable) resId else null,
                                clickable = node.isClickable,
                                editable = editable,
                                scrollable = node.isScrollable,
                                focused = node.isFocused,
                                secret = node.isPassword,
                                placeholder = if (editable) node.hintText?.toString()?.trim()?.ifEmpty { null } else null,
                                states = states,
                            )
                            val _ = collected.add(CollectedNode(el, node))
                        }
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { visit(it, depth + 1) }
            }
        }

        contentWindows.forEach { w ->
            w.root?.let { visit(it, 0) }
        }

        return CollectedScreen(
            nodes = collected,
            visibleTexts = texts.distinct().take(120),
            hasPasswordField = hasPassword,
            topPackage = topPkg,
            uiHash = hash,
        )
    }

    private fun ownLabel(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) return text
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty()) return desc
        return node.hintText?.toString()?.trim()?.ifEmpty { null }
    }

    private fun descendantLabel(node: AccessibilityNodeInfo, depth: Int = 0): String {
        if (depth > 4) return ""
        val parts = mutableListOf<String>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (!child.isVisibleToUser || child.isPassword) continue
            val own = ownLabel(child)
            if (!own.isNullOrBlank()) {
                val _ = parts.add(own)
            } else {
                val nested = descendantLabel(child, depth + 1)
                if (nested.isNotBlank()) {
                    val _ = parts.add(nested)
                }
            }
            if (parts.size >= 4) break
        }
        return parts.joinToString(" ").take(80)
    }

    private fun mix(seed: Long, vararg values: Any?): Long {
        var h = seed
        for (v in values) {
            val s = v?.toString() ?: ""
            for (ch in s) {
                h = (h xor ch.code.toLong()) * FNV_PRIME
            }
            h = (h xor 0xffL) * FNV_PRIME
        }
        return h
    }
}
