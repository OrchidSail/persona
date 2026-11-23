package persona.platform.shortcuts

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.glance.appwidget.SizeMode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import persona.core.designsystem.AppStyle
import persona.core.designsystem.AppStyleStore
import persona.core.designsystem.SystemSurfaces
import persona.core.designsystem.ThemeColor
import kotlin.math.roundToInt

/** Layout sizing, theme tokens, manifest registration and i18n coverage for [AskPersonaWidget] and [PersonaWidget]. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AskPersonaWidgetTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun askWidgetUsesExactSizeModeAndPreservesExistingAgentsWidgetReceiver() {
        val askReceiver = AskPersonaWidgetReceiver()
        assertTrue(askReceiver.glanceAppWidget is AskPersonaWidget)
        assertEquals(SizeMode.Exact, askReceiver.glanceAppWidget.sizeMode)

        // Upgrade compatibility: already-placed Agents widgets stay bound to PersonaWidgetReceiver.
        val agentsReceiver = PersonaWidgetReceiver()
        assertTrue(agentsReceiver.glanceAppWidget is PersonaWidget)
        assertEquals("persona.platform.shortcuts.PersonaWidgetReceiver", PersonaWidgetReceiver::class.java.name)
        assertEquals("persona.platform.shortcuts.AskPersonaWidgetReceiver", AskPersonaWidgetReceiver::class.java.name)
    }

    @Test fun manifestRegistersBothWidgetsWithDistinctLabelsAndProviders() {
        val pm = context.packageManager
        val agentsInfo = pm.getReceiverInfo(
            ComponentName(context, PersonaWidgetReceiver::class.java),
            PackageManager.GET_META_DATA,
        )
        val askInfo = pm.getReceiverInfo(
            ComponentName(context, AskPersonaWidgetReceiver::class.java),
            PackageManager.GET_META_DATA,
        )
        assertTrue(agentsInfo.exported)
        assertTrue(askInfo.exported)
        assertEquals(R.string.shortcuts_widget_label, agentsInfo.labelRes)
        assertEquals(R.string.shortcuts_ask_label, askInfo.labelRes)
        assertEquals(R.xml.persona_widget_info, agentsInfo.metaData.getInt("android.appwidget.provider"))
        assertEquals(R.xml.ask_persona_widget_info, askInfo.metaData.getInt("android.appwidget.provider"))
    }

    @Test fun layoutAdaptsToCommonLauncherSizes() {
        // 2 × 1 tight resize (110 × 48 dp): 32 dp inner height fits one compact title line without clipping.
        val tiny = AskWidgetLayout.resolve(widthDp = 110f, heightDp = 48f)
        assertEquals(AskWidgetMode.Row, tiny.mode)
        assertEquals(AskWidgetBadge.MarkOnly, tiny.badge)
        assertEquals(AskWidgetTitleScale.Compact, tiny.titleScale)
        assertTrue(tiny.tightPadding)
        assertFalse(tiny.showSubtitle)
        assertEquals(1, tiny.titleMaxLines)

        // 2 × 1 standard (160 × 68 dp): 44 dp inner height fits two 20 dp compact title lines without subtitle.
        val twoByOne = AskWidgetLayout.resolve(widthDp = 160f, heightDp = 68f)
        assertEquals(AskWidgetMode.Row, twoByOne.mode)
        assertEquals(AskWidgetBadge.Compact, twoByOne.badge)
        assertEquals(AskWidgetTitleScale.Compact, twoByOne.titleScale)
        assertFalse(twoByOne.tightPadding)
        assertFalse(twoByOne.showSubtitle)
        assertEquals(2, twoByOne.titleMaxLines)

        // 2 × 1 tall launcher row (160 × 100 dp): room for subtle 1-line subtitle when paired, 2 lines when unpaired.
        val twoByOneTall = AskWidgetLayout.resolve(widthDp = 160f, heightDp = 100f, paired = true)
        assertEquals(AskWidgetMode.Row, twoByOneTall.mode)
        assertTrue(twoByOneTall.showSubtitle)
        assertEquals(1, twoByOneTall.subtitleMaxLines)
        val twoByOneUnpaired = AskWidgetLayout.resolve(widthDp = 160f, heightDp = 100f, paired = false)
        assertTrue(twoByOneUnpaired.showSubtitle)
        assertEquals(2, twoByOneUnpaired.subtitleMaxLines)

        // 4 × 1 wide bar (300 × 68 dp): standard 40dp badge, section title, 1-line subtitle.
        val fourByOne = AskWidgetLayout.resolve(widthDp = 300f, heightDp = 68f)
        assertEquals(AskWidgetMode.Row, fourByOne.mode)
        assertEquals(AskWidgetBadge.Standard, fourByOne.badge)
        assertEquals(AskWidgetTitleScale.Section, fourByOne.titleScale)
        assertTrue(fourByOne.showSubtitle)
        assertEquals(1, fourByOne.titleMaxLines)
        assertEquals(1, fourByOne.subtitleMaxLines)

        // 4 × 1 squeezed height (300 × 52 dp): drops subtitle before clipping.
        val fourByOneShort = AskWidgetLayout.resolve(widthDp = 300f, heightDp = 52f)
        assertEquals(AskWidgetMode.Row, fourByOneShort.mode)
        assertTrue(fourByOneShort.tightPadding)
        assertFalse(fourByOneShort.showSubtitle)

        // 2 × 2 square tile (160 × 160 dp): stacked hero badge above bottom-anchored section title + subtitle.
        val twoByTwo = AskWidgetLayout.resolve(widthDp = 160f, heightDp = 160f, paired = true)
        assertEquals(AskWidgetMode.Stacked, twoByTwo.mode)
        assertEquals(AskWidgetBadge.Standard, twoByTwo.badge)
        assertEquals(AskWidgetTitleScale.Section, twoByTwo.titleScale)
        assertTrue(twoByTwo.showSubtitle)
        assertEquals(1, twoByTwo.subtitleMaxLines)

        // 4 × 2 wide card (300 × 160 dp): stacked standard badge + hero title + subtitle (2 lines when unpaired).
        val fourByTwo = AskWidgetLayout.resolve(widthDp = 300f, heightDp = 160f, paired = true)
        assertEquals(AskWidgetMode.Stacked, fourByTwo.mode)
        assertEquals(AskWidgetBadge.Standard, fourByTwo.badge)
        assertEquals(AskWidgetTitleScale.Hero, fourByTwo.titleScale)
        assertTrue(fourByTwo.showSubtitle)
        assertEquals(1, fourByTwo.subtitleMaxLines)

        val fourByTwoUnpaired = AskWidgetLayout.resolve(widthDp = 300f, heightDp = 160f, paired = false)
        assertEquals(AskWidgetMode.Stacked, fourByTwoUnpaired.mode)
        assertTrue(fourByTwoUnpaired.showSubtitle)
        assertEquals(2, fourByTwoUnpaired.subtitleMaxLines)
    }

    @Test fun layoutDegradesGracefullyUnderLargeSystemFontScales() {
        // 4 × 2 steps hero title down to section title when system text is enlarged.
        val largeFourByTwo = AskWidgetLayout.resolve(widthDp = 300f, heightDp = 160f, fontScale = 1.5f)
        assertEquals(AskWidgetMode.Stacked, largeFourByTwo.mode)
        assertEquals(AskWidgetTitleScale.Section, largeFourByTwo.titleScale)
        assertTrue(largeFourByTwo.showSubtitle)

        // 2 × 2 at 2× font scale drops subtitle and stays single-line so mark and title never clip.
        val maxTwoByTwo = AskWidgetLayout.resolve(widthDp = 160f, heightDp = 130f, fontScale = 2.0f)
        assertEquals(AskWidgetMode.Stacked, maxTwoByTwo.mode)
        assertFalse(maxTwoByTwo.showSubtitle)
        assertEquals(1, maxTwoByTwo.titleMaxLines)

        // 4 × 1 at 1.5× font scale drops subtitle so the bar stays single-line and unclipped.
        val largeFourByOne = AskWidgetLayout.resolve(widthDp = 300f, heightDp = 68f, fontScale = 1.5f)
        assertEquals(AskWidgetMode.Row, largeFourByOne.mode)
        assertFalse(largeFourByOne.showSubtitle)
        assertEquals(1, largeFourByOne.titleMaxLines)

        // Declared minimum height (110 × 40 dp) at 2.0× font scale drops vertical padding and limits title to 1 line.
        val minAtDouble = AskWidgetLayout.resolve(widthDp = 110f, heightDp = 40f, fontScale = 2.0f)
        assertEquals(AskWidgetMode.Row, minAtDouble.mode)
        assertEquals(AskWidgetBadge.MarkOnly, minAtDouble.badge)
        assertEquals(AskWidgetTitleScale.Compact, minAtDouble.titleScale)
        assertEquals(AskWidgetPadding.FlushVertical, minAtDouble.padding)
        assertFalse(minAtDouble.showSubtitle)
        assertEquals(1, minAtDouble.titleMaxLines)

        // Wide minimum height (300 × 40 dp) at 2.0× steps title down from Section to Compact so 1 line fits.
        val wideMinAtDouble = AskWidgetLayout.resolve(widthDp = 300f, heightDp = 40f, fontScale = 2.0f)
        assertEquals(AskWidgetMode.Row, wideMinAtDouble.mode)
        assertEquals(AskWidgetTitleScale.Compact, wideMinAtDouble.titleScale)
        assertEquals(AskWidgetPadding.FlushVertical, wideMinAtDouble.padding)
        assertFalse(wideMinAtDouble.showSubtitle)
        assertEquals(1, wideMinAtDouble.titleMaxLines)

        // Small height (110 × 48 dp) at 2.0× uses compact vertical padding and stays single-line.
        val smallAtDouble = AskWidgetLayout.resolve(widthDp = 110f, heightDp = 48f, fontScale = 2.0f)
        assertEquals(AskWidgetMode.Row, smallAtDouble.mode)
        assertEquals(AskWidgetTitleScale.Compact, smallAtDouble.titleScale)
        assertEquals(AskWidgetPadding.CompactVertical, smallAtDouble.padding)
        assertFalse(smallAtDouble.showSubtitle)
        assertEquals(1, smallAtDouble.titleMaxLines)

        // Every supported launcher size and font scale fits within its declared vertical budget.
        val sizes = listOf(
            110f to 40f, 300f to 40f, 110f to 48f, 160f to 68f,
            300f to 68f, 160f to 100f, 160f to 130f, 160f to 160f, 300f to 160f,
        )
        for ((w, h) in sizes) {
            for (scale in listOf(1.0f, 1.3f, 1.5f, 2.0f)) {
                for (paired in listOf(true, false)) {
                    val spec = AskWidgetLayout.resolve(w, h, scale, paired)
                    val required = AskWidgetLayout.requiredHeightDp(spec, scale)
                    assertTrue(
                        "Expected ${w}x${h}dp @ ${scale}x (paired=$paired) to fit: required=$required, spec=$spec",
                        required <= h,
                    )
                }
            }
        }
    }

    @Test fun smallHeightAtDoubleFontScaleKeepsRenderedTitleVisible() {
        val scaledConfig = Configuration(context.resources.configuration).apply { fontScale = 2.0f }
        val scaledContext = context.createConfigurationContext(scaledConfig)
        val metrics = scaledContext.resources.displayMetrics
        fun dpToPx(dp: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, metrics).roundToInt()

        val cases = listOf(110f to 40f, 300f to 40f, 110f to 48f)
        for (style in AppStyle.entries) {
            val look = WidgetLook.of(style)
            for ((widthDp, heightDp) in cases) {
                val spec = AskWidgetLayout.resolve(widthDp, heightDp, fontScale = 2.0f, paired = true)
                val titleStyle = when (spec.titleScale) {
                    AskWidgetTitleScale.Hero -> look.headline
                    AskWidgetTitleScale.Section -> look.sectionTitle
                    AskWidgetTitleScale.Compact -> look.itemTitle
                }
                val hPadDp = AskWidgetLayout.horizontalPaddingDp(spec.padding)
                val vPadDp = AskWidgetLayout.verticalPaddingDp(spec.padding)
                val gapDp = if (spec.padding == AskWidgetPadding.Normal) 12f else 8f
                val textWidthDp = (widthDp - hPadDp - AskWidgetLayout.badgeHeight(spec.badge) - gapDp).coerceAtLeast(1f)
                val innerHeightPx = dpToPx(heightDp - vPadDp)
                val containerHeightPx = dpToPx(heightDp)

                val view = TextView(scaledContext).apply {
                    text = scaledContext.getString(R.string.shortcuts_ask_label)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, titleStyle.fontSize!!.value)
                    maxLines = spec.titleMaxLines
                    ellipsize = TextUtils.TruncateAt.END
                }
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(dpToPx(textWidthDp), View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)

                assertNotNull(view.layout)
                assertEquals(1, view.lineCount)
                assertTrue(view.measuredHeight > 0)
                assertTrue(
                    "Rendered title clipped for $style ${widthDp}x${heightDp}dp @ 2.0x: " +
                        "measured=${view.measuredHeight}px, vPad=${dpToPx(vPadDp)}px, container=${containerHeightPx}px",
                    view.measuredHeight + dpToPx(vPadDp) <= containerHeightPx,
                )
                assertTrue(
                    "First line bottom exceeded inner height for $style ${widthDp}x${heightDp}dp @ 2.0x: " +
                        "lineBottom=${view.layout.getLineBottom(0)}px, inner=${innerHeightPx}px",
                    view.layout.getLineBottom(0) <= innerHeightPx,
                )
            }
        }
    }

    @Test fun widgetLookFollowsAllThreeStylesLightDarkAndThemeColor() {
        for (style in AppStyle.entries) {
            val look = WidgetLook.of(style)
            assertEquals(SystemSurfaces.palette(style, false).surfaceOpaque, look.lightPalette.surfaceOpaque)
            assertEquals(SystemSurfaces.palette(style, true).surfaceOpaque, look.darkPalette.surfaceOpaque)
            assertNotEquals(look.lightPalette.surfaceOpaque, look.darkPalette.surfaceOpaque)
            assertEquals(SystemSurfaces.type(style).title.fontSize, look.headline.fontSize)
            assertEquals(SystemSurfaces.type(style).headline.fontSize, look.sectionTitle.fontSize)
            assertEquals(SystemSurfaces.type(style).caption.fontSize, look.caption.fontSize)
        }

        val store = AppStyleStore(context)
        try {
            store.setStyle(AppStyle.Board)
            store.setColor(ThemeColor.Coral)
            val before = WidgetLook.revision.value
            WidgetLook.invalidate()
            assertEquals(before + 1, WidgetLook.revision.value)
            val current = WidgetLook.current(context)
            assertEquals(AppStyle.Board, current.style)
            assertNotEquals(SystemSurfaces.palette(AppStyle.Board, false).accent, current.lightPalette.accent)
        } finally {
            store.setStyle(AppStyle.Default)
            store.setColor(ThemeColor.Default)
        }
    }

    @Config(sdk = [35], qualifiers = "en")
    @Test fun englishWidgetResourcesDistinguishAgentsAndAskPersona() {
        assertEquals("Agents", context.getString(R.string.shortcuts_widget_label))
        assertEquals("Which agents need you, are working, or are done", context.getString(R.string.shortcuts_widget_description))
        assertEquals("Ask Persona", context.getString(R.string.shortcuts_ask_label))
        assertEquals("Open your main chat with Persona and start typing", context.getString(R.string.shortcuts_ask_widget_description))
        assertEquals("Main chat", context.getString(R.string.shortcuts_ask_widget_subtitle))
    }

    @Config(sdk = [35], qualifiers = "zh")
    @Test fun simplifiedChineseWidgetResourcesDistinguishAgentsAndAskPersona() {
        assertEquals("Agents", context.getString(R.string.shortcuts_widget_label))
        assertEquals("几个 Agent 需要你、在工作、做完了", context.getString(R.string.shortcuts_widget_description))
        assertEquals("问 Persona", context.getString(R.string.shortcuts_ask_label))
        assertEquals("直接打开与 Persona 的主对话并开始输入", context.getString(R.string.shortcuts_ask_widget_description))
        assertEquals("主对话", context.getString(R.string.shortcuts_ask_widget_subtitle))
    }

    @Config(sdk = [35], qualifiers = "ja")
    @Test fun japaneseWidgetResourcesDistinguishAgentsAndAskPersona() {
        assertEquals("Agents", context.getString(R.string.shortcuts_widget_label))
        assertEquals("あなたの対応待ち・作業中・完了したエージェント", context.getString(R.string.shortcuts_widget_description))
        assertEquals("Personaに質問", context.getString(R.string.shortcuts_ask_label))
        assertEquals("Personaとのメインチャットを開いてすぐに入力", context.getString(R.string.shortcuts_ask_widget_description))
        assertEquals("メインチャット", context.getString(R.string.shortcuts_ask_widget_subtitle))
    }
}
