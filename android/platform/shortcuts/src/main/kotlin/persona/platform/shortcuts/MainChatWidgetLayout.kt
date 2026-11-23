package persona.platform.shortcuts

import kotlin.math.floor
import kotlin.math.max

/**
 * Computed layout plan for [MainChatWidget] based on the actual container size (`widthDp` × `heightDp`)
 * and system `fontScale`. Every slot is budgeted conservatively against scaled text line heights so
 * text and controls never clip or overlap even on minimum 2×2 cells at 2.0× font scale.
 */
internal data class MainChatLayoutPlan(
    val tightPadding: Boolean,
    val compactPadding: Boolean,
    val compactHeader: Boolean,
    val showHeaderStatus: Boolean,
    val showHeaderTime: Boolean,
    val showUnpairedHeadline: Boolean,
    val showPendingBanner: Boolean,
    val compactPendingBanner: Boolean,
    val summaryMaxLines: Int,
    val todoRows: Int,
    val showTodoStateLabel: Boolean,
    val showActionBar: Boolean,
    val expandedActionBar: Boolean,
    val estimatedHeightDp: Float,
)

internal object MainChatWidgetLayout {
    /** Width threshold separating narrow 2×2 cards from wide 4×2 / 4×3 cards. */
    private const val WIDE_MIN_DP = 200f
    /** Height threshold where large 4×3 density (3 summary lines + open to-dos) becomes eligible. */
    private const val LARGE_MIN_HEIGHT_DP = 210f
    /** Font scale above which the quick action bar expands past 40dp to prevent text clipping. */
    private const val EXPANDED_ACTION_SCALE = 1.25f

    private const val PAD_STANDARD_DP = 12f
    private const val PAD_COMPACT_DP = 8f
    private const val PAD_TIGHT_DP = 4f

    private const val FACE_STANDARD_DP = 28f
    private const val FACE_COMPACT_DP = 24f

    private const val HEADER_TEXT_STANDARD_DP = 22.5f
    private const val HEADER_TEXT_COMPACT_DP = 21f

    private const val SUMMARY_LINE_DP = 21f
    private const val SUMMARY_GAP_DP = 6f
    private const val SUMMARY_GAP_COMPACT_DP = 4f
    private const val SUMMARY_GAP_TIGHT_DP = 2f

    private const val PENDING_TEXT_DP = 21f
    private const val PENDING_VPAD_STANDARD_DP = 12f
    private const val PENDING_VPAD_COMPACT_DP = 4f
    private const val PENDING_GAP_DP = 6f
    private const val PENDING_GAP_COMPACT_DP = 4f
    private const val PENDING_GAP_TIGHT_DP = 2f

    private const val TODO_TEXT_DP = 21f
    private const val TODO_VPAD_DP = 6f
    private const val TODO_SECTION_GAP_DP = 6f

    private const val ACTION_BAR_MIN_DP = 40f
    private const val ACTION_BAR_LARGE_MIN_DP = 48f
    private const val ACTION_BAR_TEXT_DP = 21f
    private const val ACTION_BAR_VPAD_DP = 16f
    private const val ACTION_BAR_GAP_DP = 8f

    private const val UNPAIRED_HEADLINE_DP = 22.5f
    private const val UNPAIRED_HINT_GAP_DP = 2f
    private const val UNPAIRED_HINT_LINE_DP = 21f

    fun compute(
        widthDp: Float,
        heightDp: Float,
        fontScale: Float = 1f,
        paired: Boolean = true,
        hasPending: Boolean = false,
        hasStatusBadge: Boolean = false,
        availableTodos: Int = 0,
    ): MainChatLayoutPlan {
        val scale = fontScale.coerceAtLeast(0.85f)
        val isWide = widthDp >= WIDE_MIN_DP
        val isLarge = isWide && heightDp >= LARGE_MIN_HEIGHT_DP

        val tightPadding = heightDp <= 110f && scale >= 1.75f
        val compactPadding = tightPadding ||
            !isWide && (heightDp <= 125f || scale >= 1.5f) ||
            heightDp <= 115f || (scale >= 1.75f && heightDp < 230f)
        val pad = when {
            tightPadding -> PAD_TIGHT_DP
            compactPadding -> PAD_COMPACT_DP
            else -> PAD_STANDARD_DP
        }
        val innerHeight = (heightDp - pad * 2f).coerceAtLeast(0f)
        val innerWidth = (widthDp - pad * 2f).coerceAtLeast(0f)

        val compactHeader = !isWide || heightDp < 150f || scale >= 1.5f
        val faceDp = if (compactHeader) FACE_COMPACT_DP else FACE_STANDARD_DP
        val headerTextDp = (if (compactHeader) HEADER_TEXT_COMPACT_DP else HEADER_TEXT_STANDARD_DP) * scale
        val headerHeight = max(faceDp, headerTextDp)

        val showHeaderStatus = paired && hasStatusBadge && innerWidth >= (52f + 130f * scale)
        val timeMinWidth = if (showHeaderStatus) (56f + 178f * scale) else (40f + 104f * scale)
        val showHeaderTime = paired && isWide && innerWidth >= timeMinWidth

        if (!paired) {
            return computeUnpaired(
                pad = pad,
                innerHeight = innerHeight,
                scale = scale,
                tightPadding = tightPadding,
                compactPadding = compactPadding,
                compactHeader = compactHeader,
                headerHeight = headerHeight,
            )
        }

        val summaryGap = when {
            tightPadding -> SUMMARY_GAP_TIGHT_DP
            compactPadding -> SUMMARY_GAP_COMPACT_DP
            else -> SUMMARY_GAP_DP
        }
        val summaryLineHeight = SUMMARY_LINE_DP * scale
        val pendingGap = when {
            tightPadding -> PENDING_GAP_TIGHT_DP
            compactPadding -> PENDING_GAP_COMPACT_DP
            else -> PENDING_GAP_DP
        }
        val pendingStandardHeight = pendingGap + PENDING_TEXT_DP * scale + PENDING_VPAD_STANDARD_DP
        val pendingCompactHeight = pendingGap + PENDING_TEXT_DP * scale + PENDING_VPAD_COMPACT_DP
        val expandedActionBar = scale > EXPANDED_ACTION_SCALE
        val actionBarMin = if (expandedActionBar) ACTION_BAR_LARGE_MIN_DP else ACTION_BAR_MIN_DP
        val actionBarHeight = ACTION_BAR_GAP_DP + max(actionBarMin, ACTION_BAR_TEXT_DP * scale + ACTION_BAR_VPAD_DP)

        var used = headerHeight
        var showPendingBanner = false
        var compactPendingBanner = false

        // Priority 1: Waiting approval / question / plan card takes top slot under header whenever it fits.
        if (hasPending) {
            val remainingForPending = innerHeight - used
            when {
                remainingForPending >= pendingStandardHeight -> {
                    showPendingBanner = true
                    compactPendingBanner = false
                    used += pendingStandardHeight
                }
                remainingForPending >= pendingCompactHeight -> {
                    showPendingBanner = true
                    compactPendingBanner = true
                    used += pendingCompactHeight
                }
            }
        }

        // Decide whether the bottom Quick Action Bar fits without starving primary content.
        val minPrimaryAfterPending = if (showPendingBanner) 0f else (summaryGap + summaryLineHeight)
        val showActionBar = isWide && (innerHeight - used - minPrimaryAfterPending) >= actionBarHeight
        if (showActionBar) {
            used += actionBarHeight
        }

        // Priority 2: Main chat dynamic summary (up to 2 lines on 2x2 / 4x2, up to 3 lines on 4x3).
        val desiredSummaryLines = when {
            isLarge -> 3
            else -> 2
        }
        val todoRowHeight = TODO_TEXT_DP * scale + TODO_VPAD_DP
        val cappedTodos = if (isLarge) availableTodos.coerceIn(0, MainChatWidgetState.MAX_TODOS) else 0

        // On 4x3 with open to-dos, allow summary up to 2 lines if reserving room lets 1-2 urgent to-dos appear too,
        // otherwise give the summary up to 3 full lines.
        val remainingBeforeSummary = (innerHeight - used).coerceAtLeast(0f)
        val maxFittingSummaryLines = if (remainingBeforeSummary >= summaryGap + summaryLineHeight) {
            floor((remainingBeforeSummary - summaryGap) / summaryLineHeight).toInt().coerceIn(0, desiredSummaryLines)
        } else {
            0
        }

        var summaryLines = maxFittingSummaryLines
        var todoRows = 0

        if (cappedTodos > 0 && remainingBeforeSummary > 0f) {
            // Try fitting up to 2 to-dos with full summary lines first; if needed on 4x3, allow 2 summary lines + to-dos.
            val summaryHeightFull = if (summaryLines > 0) summaryGap + summaryLines * summaryLineHeight else 0f
            val leftAfterFull = remainingBeforeSummary - summaryHeightFull
            val fullTodoCount = fittingTodos(leftAfterFull, todoRowHeight, cappedTodos)
            if (fullTodoCount > 0) {
                todoRows = fullTodoCount
            } else if (summaryLines >= 3) {
                val summaryHeightTwo = summaryGap + 2 * summaryLineHeight
                val leftAfterTwo = remainingBeforeSummary - summaryHeightTwo
                val twoTodoCount = fittingTodos(leftAfterTwo, todoRowHeight, cappedTodos)
                if (twoTodoCount > 0) {
                    summaryLines = 2
                    todoRows = twoTodoCount
                }
            }
        }

        if (summaryLines > 0) {
            used += summaryGap + summaryLines * summaryLineHeight
        }
        if (todoRows > 0) {
            used += TODO_SECTION_GAP_DP + todoRows * todoRowHeight
        }

        val showTodoStateLabel = isWide && innerWidth >= (210f + 30f * (scale - 1f).coerceAtLeast(0f))

        return MainChatLayoutPlan(
            tightPadding = tightPadding,
            compactPadding = compactPadding,
            compactHeader = compactHeader,
            showHeaderStatus = showHeaderStatus,
            showHeaderTime = showHeaderTime,
            showUnpairedHeadline = false,
            showPendingBanner = showPendingBanner,
            compactPendingBanner = compactPendingBanner,
            summaryMaxLines = summaryLines,
            todoRows = todoRows,
            showTodoStateLabel = showTodoStateLabel,
            showActionBar = showActionBar,
            expandedActionBar = expandedActionBar,
            estimatedHeightDp = pad * 2f + used,
        )
    }

    private fun computeUnpaired(
        pad: Float,
        innerHeight: Float,
        scale: Float,
        tightPadding: Boolean,
        compactPadding: Boolean,
        compactHeader: Boolean,
        headerHeight: Float,
    ): MainChatLayoutPlan {
        val gap = when {
            tightPadding -> SUMMARY_GAP_TIGHT_DP
            compactPadding -> SUMMARY_GAP_COMPACT_DP
            else -> SUMMARY_GAP_DP
        }
        val headlineLine = (if (compactHeader) HEADER_TEXT_COMPACT_DP else UNPAIRED_HEADLINE_DP) * scale
        val headlineHeight = gap + headlineLine
        val hintLineHeight = UNPAIRED_HINT_LINE_DP * scale
        var used = headerHeight
        var showHeadline = false
        var hintLines = 0
        if (innerHeight - used >= headlineHeight) {
            showHeadline = true
            used += headlineHeight
            val rem = (innerHeight - used - UNPAIRED_HINT_GAP_DP).coerceAtLeast(0f)
            hintLines = floor(rem / hintLineHeight).toInt().coerceIn(0, 2)
            if (hintLines > 0) {
                used += UNPAIRED_HINT_GAP_DP + hintLines * hintLineHeight
            }
        }
        return MainChatLayoutPlan(
            tightPadding = tightPadding,
            compactPadding = compactPadding,
            compactHeader = compactHeader,
            showHeaderStatus = false,
            showHeaderTime = false,
            showUnpairedHeadline = showHeadline,
            showPendingBanner = false,
            compactPendingBanner = false,
            summaryMaxLines = hintLines,
            todoRows = 0,
            showTodoStateLabel = false,
            showActionBar = false,
            expandedActionBar = false,
            estimatedHeightDp = pad * 2f + used,
        )
    }

    private fun fittingTodos(remainingDp: Float, rowHeightDp: Float, maxTodos: Int): Int {
        if (remainingDp < TODO_SECTION_GAP_DP + rowHeightDp) return 0
        return floor((remainingDp - TODO_SECTION_GAP_DP) / rowHeightDp).toInt().coerceIn(0, maxTodos)
    }
}
