package persona.platform.shortcuts

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import persona.core.designsystem.Tokens

/**
 * The standalone "Ask Persona" home-screen widget: a single-purpose everyday entry to Persona's main chat, aligned with
 * the single-responsibility widget design in `docs/apple/home-widgets.md` and distinct from the Agents fleet widget
 * ([PersonaWidget]). Reads only local pairing and appearance state (no extra permissions, no network calls). Tapping
 * opens the main chat with the composer focused when paired ([SurfaceLinks.ask]), or the pairing page otherwise
 * ([SurfaceLinks.app]).
 */
class AskPersonaWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val pairing = SurfacesDependencies.of(context).pairing()
        pairing.restore()
        // Read inside the composition so Glance sessions update when pairing or appearance changes.
        provideContent {
            val paired by pairing.isPaired.collectAsState()
            val revision by WidgetLook.revision.collectAsState()
            val look = remember(revision) { WidgetLook.current(context) }
            Content(context, look, paired)
        }
    }

    @Composable
    private fun Content(context: Context, look: WidgetLook, paired: Boolean) {
        val size = LocalSize.current
        val scale = context.resources.configuration.fontScale
        val spec = AskWidgetLayout.resolve(size.width.value, size.height.value, scale, paired)
        val padding = if (spec.tightPadding) Tokens.Space.s else Tokens.Space.m
        val title = context.getString(if (paired) R.string.shortcuts_ask_label else R.string.shortcuts_not_paired_short)
        val subtitle = context.getString(if (paired) R.string.shortcuts_ask_widget_subtitle else R.string.shortcuts_widget_scan)
        val titleStyle = when (spec.titleScale) {
            AskWidgetTitleScale.Hero -> look.headline
            AskWidgetTitleScale.Section -> look.sectionTitle
            AskWidgetTitleScale.Compact -> look.itemTitle
        }
        val container = GlanceModifier.fillMaxSize().appWidgetBackground()
            .widgetCornerRadius()
            .background(look.card)
            .padding(padding)
            .clickable(actionStartActivity(SurfaceLinks.askWidget(context, paired)))
        when (spec.mode) {
            AskWidgetMode.Row -> Row(container, verticalAlignment = Alignment.CenterVertically) {
                Mark(look, spec.badge)
                Spacer(GlanceModifier.width(if (spec.tightPadding) Tokens.Space.s else Tokens.Space.m))
                Column(GlanceModifier.defaultWeight()) {
                    Text(title, style = titleStyle, maxLines = spec.titleMaxLines)
                    if (spec.showSubtitle) {
                        Spacer(GlanceModifier.height(Tokens.Space.xxs))
                        Text(subtitle, style = look.caption, maxLines = spec.subtitleMaxLines)
                    }
                }
            }
            AskWidgetMode.Stacked -> Column(container) {
                Mark(look, spec.badge)
                Spacer(GlanceModifier.defaultWeight())
                Text(title, style = titleStyle, maxLines = spec.titleMaxLines)
                if (spec.showSubtitle) {
                    Spacer(GlanceModifier.height(Tokens.Space.xs))
                    Text(subtitle, style = look.caption, maxLines = spec.subtitleMaxLines)
                }
            }
        }
    }

    @Composable
    private fun Mark(look: WidgetLook, badge: AskWidgetBadge) {
        when (badge) {
            AskWidgetBadge.MarkOnly -> Image(
                ImageProvider(persona.core.designsystem.R.drawable.ic_stat),
                contentDescription = null,
                modifier = GlanceModifier.size(look.markSize),
                colorFilter = ColorFilter.tint(look.accent),
            )
            AskWidgetBadge.Compact -> BadgeMark(look, look.compactBadgeSize, look.compactMarkSize)
            AskWidgetBadge.Standard -> BadgeMark(look, look.badgeSize, look.badgeMarkSize)
        }
    }

    @Composable
    private fun BadgeMark(look: WidgetLook, badgeSize: Dp, markSize: Dp) {
        Box(
            modifier = GlanceModifier.size(badgeSize).cornerRadius(badgeSize / 2).background(look.badge),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                ImageProvider(persona.core.designsystem.R.drawable.ic_stat),
                contentDescription = null,
                modifier = GlanceModifier.size(markSize),
                colorFilter = ColorFilter.tint(look.accent),
            )
        }
    }

    companion object {
        /** Redraws every placed Ask Persona widget (cheap when none is placed). */
        suspend fun refresh(context: Context) {
            WidgetLook.invalidate()
            val _ = runCatching { AskPersonaWidget().updateAll(context.applicationContext) }
        }
    }
}

class AskPersonaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AskPersonaWidget()
}

internal enum class AskWidgetMode { Row, Stacked }

internal enum class AskWidgetBadge { MarkOnly, Compact, Standard }

internal enum class AskWidgetTitleScale { Compact, Section, Hero }

internal data class AskWidgetSpec(
    val mode: AskWidgetMode,
    val badge: AskWidgetBadge,
    val titleScale: AskWidgetTitleScale,
    val tightPadding: Boolean,
    val showSubtitle: Boolean,
    val titleMaxLines: Int,
    val subtitleMaxLines: Int,
)

/**
 * Pure layout sizing for [AskPersonaWidget] across common launcher cells (2 × 1, 2 × 2, 4 × 1, 4 × 2) and system font
 * scales: 1-row sizes lay out horizontally, 2-row and taller sizes stack the mark above bottom-anchored copy, and
 * larger text drops the subtitle or badge ring before anything clips.
 */
internal object AskWidgetLayout {
    fun resolve(widthDp: Float, heightDp: Float, fontScale: Float = 1f, paired: Boolean = true): AskWidgetSpec {
        val scale = fontScale.coerceAtLeast(MIN_FONT_SCALE)
        val tightPadding = heightDp < TIGHT_HEIGHT
        val pad = if (tightPadding) TIGHT_PADDING else NORMAL_PADDING
        val innerW = (widthDp - pad).coerceAtLeast(0f)
        val innerH = (heightDp - pad).coerceAtLeast(0f)
        val wide = widthDp >= WIDE_MIN_WIDTH
        val stacked = heightDp >= STACKED_MIN_HEIGHT && innerH >= MARK_SIZE + STACKED_GAP + SECTION_LINE * scale
        if (stacked) {
            val titleScale = when {
                wide && scale <= HERO_MAX_FONT_SCALE -> AskWidgetTitleScale.Hero
                innerW >= NARROW_SECTION_MIN_W -> AskWidgetTitleScale.Section
                else -> AskWidgetTitleScale.Compact
            }
            val titleH = titleHeight(titleScale, scale)
            val badge = when {
                innerH >= STANDARD_BADGE + STACKED_GAP + titleH -> AskWidgetBadge.Standard
                innerH >= COMPACT_BADGE + STACKED_GAP + titleH -> AskWidgetBadge.Compact
                else -> AskWidgetBadge.MarkOnly
            }
            val textRoom = innerH - badgeHeight(badge) - STACKED_GAP
            val captionH = CAPTION_LINE * scale
            val subtitleLines = when {
                !paired && textRoom >= titleH + STACKED_TEXT_GAP + captionH * 2f -> 2
                textRoom >= titleH + STACKED_TEXT_GAP + captionH -> 1
                else -> 0
            }
            return AskWidgetSpec(
                mode = AskWidgetMode.Stacked,
                badge = badge,
                titleScale = titleScale,
                tightPadding = tightPadding,
                showSubtitle = subtitleLines > 0,
                titleMaxLines = if (subtitleLines > 0 || wide) 1 else 2,
                subtitleMaxLines = subtitleLines.coerceAtLeast(1),
            )
        }
        val titleScale = if (wide) AskWidgetTitleScale.Section else AskWidgetTitleScale.Compact
        val titleH = titleHeight(titleScale, scale)
        val badge = when {
            wide && innerH >= STANDARD_BADGE -> AskWidgetBadge.Standard
            innerH >= COMPACT_BADGE && innerW >= COMPACT_ROW_BADGE_MIN_W -> AskWidgetBadge.Compact
            else -> AskWidgetBadge.MarkOnly
        }
        val captionH = CAPTION_LINE * scale
        val canFitSubtitle = innerH >= titleH + ROW_TEXT_GAP + captionH &&
            (wide || (heightDp >= NARROW_ROW_SUBTITLE_MIN_H && innerW >= NARROW_ROW_SUBTITLE_MIN_W))
        val subtitleLines = when {
            !canFitSubtitle -> 0
            !paired && innerH >= titleH + ROW_TEXT_GAP + captionH * 2f -> 2
            else -> 1
        }
        return AskWidgetSpec(
            mode = AskWidgetMode.Row,
            badge = badge,
            titleScale = titleScale,
            tightPadding = tightPadding,
            showSubtitle = subtitleLines > 0,
            titleMaxLines = if (subtitleLines > 0 || wide) 1 else 2,
            subtitleMaxLines = subtitleLines.coerceAtLeast(1),
        )
    }

    private fun titleHeight(scale: AskWidgetTitleScale, fontScale: Float): Float = when (scale) {
        AskWidgetTitleScale.Hero -> HERO_LINE * fontScale
        AskWidgetTitleScale.Section -> SECTION_LINE * fontScale
        AskWidgetTitleScale.Compact -> COMPACT_LINE * fontScale
    }

    private fun badgeHeight(badge: AskWidgetBadge): Float = when (badge) {
        AskWidgetBadge.Standard -> STANDARD_BADGE
        AskWidgetBadge.Compact -> COMPACT_BADGE
        AskWidgetBadge.MarkOnly -> MARK_SIZE
    }

    private const val MIN_FONT_SCALE = 0.5f
    private const val TIGHT_HEIGHT = 64f
    private const val TIGHT_PADDING = 16f
    private const val NORMAL_PADDING = 24f
    private const val WIDE_MIN_WIDTH = 220f
    private const val STACKED_MIN_HEIGHT = 120f
    private const val STACKED_GAP = 8f
    private const val STACKED_TEXT_GAP = 4f
    private const val ROW_TEXT_GAP = 2f
    private const val STANDARD_BADGE = 40f
    private const val COMPACT_BADGE = 28f
    private const val MARK_SIZE = 16f
    private const val HERO_LINE = 28f
    private const val SECTION_LINE = 23f
    private const val COMPACT_LINE = 20f
    private const val CAPTION_LINE = 18f
    private const val HERO_MAX_FONT_SCALE = 1.15f
    private const val NARROW_SECTION_MIN_W = 110f
    private const val COMPACT_ROW_BADGE_MIN_W = 116f
    private const val NARROW_ROW_SUBTITLE_MIN_H = 76f
    private const val NARROW_ROW_SUBTITLE_MIN_W = 120f
}
