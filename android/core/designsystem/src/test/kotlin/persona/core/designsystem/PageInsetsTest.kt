package persona.core.designsystem

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Verifies navigation-bar and IME inset placement for [JPage] across [AppStyle.Glass], [AppStyle.Brief], and
 * [AppStyle.Board] in both standalone and embedded (`AssistantScreen` -> `ChatScreen`) configurations (#8392).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w400dp-h900dp-mdpi")
class PageInsetsTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var composeView: View
    private val topBar: @Composable () -> Unit = { Box(Modifier.fillMaxWidth().height(56.dp).testTag("top-bar")) }
    private val composer: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth().navigationBarsPadding().height(72.dp).testTag("composer"))
    }

    private fun applyInsets(
        navigation: Insets = Insets.NONE,
        imeBottom: Int = 0,
        statusTop: Int = 24,
    ) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusTop, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), navigation)
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottom))
            .setVisible(WindowInsetsCompat.Type.ime(), imeBottom > 0)
            .build()
        rule.runOnIdle { ViewCompat.dispatchApplyWindowInsets(composeView, insets) }
        rule.waitForIdle()
    }

    private fun assertComposerBottomFromWindowBottom(expectedInset: Dp, label: String) {
        val rootBottom = rule.onRoot().getUnclippedBoundsInRoot().bottom
        val bounds = rule.onNodeWithTag("composer").getUnclippedBoundsInRoot()
        assertEquals("$label: composer height must not double-pad navigationBars", 72f, (bounds.bottom - bounds.top).value, 0.5f)
        assertEquals("$label: composer bottom inset", (rootBottom - expectedInset).value, bounds.bottom.value, 0.5f)
    }

    @Test fun embeddedConversationKeepsComposerClearOfNavigationBarAndFlushWithKeyboard() {
        var style by mutableStateOf(AppStyle.Brief)
        var dark by mutableStateOf(false)
        var hostContentCompositions = 0
        rule.setContent {
            composeView = LocalView.current
            PersonaTheme(darkTheme = dark, reduceMotion = true, style = style) {
                // Matches AssistantScreen hosting ChatScreen(embedded = true): outer JPage carries imePadding() and
                // forwards its bottom/top padding through LocalChromeInsets with handlesNavigationBar = true.
                JPage(
                    title = "Persona",
                    modifier = Modifier.fillMaxSize().imePadding(),
                    topBar = topBar,
                    edges = false,
                    scrollEdgeStyle = ScrollEdgeStyle.Gradient,
                ) { padding ->
                    SideEffect { hostContentCompositions++ }
                    val host = ChromeInsets(
                        bottom = padding.calculateBottomPadding(),
                        handlesNavigationBar = true,
                        top = padding.calculateTopPadding(),
                    )
                    CompositionLocalProvider(LocalChromeInsets provides host) {
                        JPage(
                            title = "Conversation",
                            modifier = Modifier.fillMaxSize(),
                            topBar = {},
                            bottomBar = composer,
                            scrollEdgeStyle = ScrollEdgeStyle.Gradient,
                        ) { innerPadding ->
                            Box(Modifier.fillMaxSize().testTag("messages"))
                        }
                    }
                }
            }
        }

        for (currentStyle in AppStyle.entries) {
            for (currentDark in listOf(false, true)) {
                rule.runOnIdle {
                    style = currentStyle
                    dark = currentDark
                }
                rule.waitForIdle()
                val label = "embedded style=$currentStyle dark=$currentDark"

                // Three-button navigation (48dp) with keyboard down: composer sits 48dp above window bottom.
                applyInsets(navigation = Insets.of(0, 0, 0, 48), imeBottom = 0)
                assertComposerBottomFromWindowBottom(48.dp, "$label 3-button ime=0")

                // Gesture navigation (20dp) with keyboard down: composer sits 20dp above window bottom.
                applyInsets(navigation = Insets.of(0, 0, 0, 20), imeBottom = 0)
                assertComposerBottomFromWindowBottom(20.dp, "$label gesture ime=0")

                // Keyboard sliding past the bottom edge but still below the 48dp navigation bar (20dp IME):
                // 20dp imePadding + 28dp unconsumed navigation bar = 48dp total, with no jump.
                applyInsets(navigation = Insets.of(0, 0, 0, 48), imeBottom = 20)
                assertComposerBottomFromWindowBottom(48.dp, "$label 3-button ime=20")

                // Keyboard sliding above the navigation bar (80 -> 160 -> 240 -> 300dp):
                // composer sits flush on top of the keyboard without an extra 48dp gap, and host ChromeInsets
                // stops recomposing once unconsumed navigation reaches 0dp.
                applyInsets(navigation = Insets.of(0, 0, 0, 48), imeBottom = 80)
                assertComposerBottomFromWindowBottom(80.dp, "$label 3-button ime=80")
                val beforeSteadySlide = hostContentCompositions
                for (imeStep in listOf(160, 240, 300)) {
                    applyInsets(navigation = Insets.of(0, 0, 0, 48), imeBottom = imeStep)
                    assertComposerBottomFromWindowBottom(imeStep.dp, "$label 3-button ime=$imeStep")
                }
                assertEquals(
                    "$label: host content must not recompose on keyboard frames above navigation bar",
                    beforeSteadySlide,
                    hostContentCompositions,
                )

                // Keyboard dismissed again: composer returns above the 48dp three-button navigation bar.
                applyInsets(navigation = Insets.of(0, 0, 0, 48), imeBottom = 0)
                assertComposerBottomFromWindowBottom(48.dp, "$label 3-button ime=0 after close")
            }
        }
    }

    @Test fun standalonePageKeepsComposerClearOfNavigationBarAndFlushWithKeyboard() {
        var style by mutableStateOf(AppStyle.Brief)
        rule.setContent {
            composeView = LocalView.current
            PersonaTheme(darkTheme = false, reduceMotion = true, style = style) {
                // Matches standalone ChatScreen inside ChatLinkHost(Modifier.fillMaxSize().imePadding()).
                Box(Modifier.fillMaxSize().imePadding()) {
                    JPage(
                        title = "Agent",
                        topBar = topBar,
                        bottomBar = composer,
                        scrollEdgeStyle = ScrollEdgeStyle.Gradient,
                    ) {
                        Box(Modifier.fillMaxSize().testTag("messages"))
                    }
                }
            }
        }

        for (currentStyle in AppStyle.entries) {
            rule.runOnIdle { style = currentStyle }
            rule.waitForIdle()
            val label = "standalone style=$currentStyle"

            applyInsets(navigation = Insets.of(0, 0, 0, 48), imeBottom = 0)
            assertComposerBottomFromWindowBottom(48.dp, "$label 3-button ime=0")

            applyInsets(navigation = Insets.of(0, 0, 0, 20), imeBottom = 0)
            assertComposerBottomFromWindowBottom(20.dp, "$label gesture ime=0")

            applyInsets(navigation = Insets.of(0, 0, 0, 48), imeBottom = 20)
            assertComposerBottomFromWindowBottom(48.dp, "$label 3-button ime=20")

            applyInsets(navigation = Insets.of(0, 0, 0, 48), imeBottom = 300)
            assertComposerBottomFromWindowBottom(300.dp, "$label 3-button ime=300")
        }
    }

    @Test fun docklessEmbeddedPagePropagatesHostBottomPaddingAcrossAllStyles() {
        var style by mutableStateOf(AppStyle.Brief)
        var innerPadding = PaddingValues()
        rule.setContent {
            composeView = LocalView.current
            PersonaTheme(darkTheme = false, reduceMotion = true, style = style) {
                JPage(
                    title = "Host",
                    modifier = Modifier.fillMaxSize().imePadding(),
                    topBar = topBar,
                    edges = false,
                ) { hostPadding ->
                    val host = ChromeInsets(
                        bottom = hostPadding.calculateBottomPadding(),
                        handlesNavigationBar = true,
                        top = hostPadding.calculateTopPadding(),
                    )
                    CompositionLocalProvider(LocalChromeInsets provides host) {
                        JPage(title = "Embedded", modifier = Modifier.fillMaxSize(), topBar = {}) { padding ->
                            SideEffect { innerPadding = padding }
                            Box(Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }

        for (currentStyle in AppStyle.entries) {
            rule.runOnIdle { style = currentStyle }
            rule.waitForIdle()
            val label = "dockless embedded style=$currentStyle"

            applyInsets(navigation = Insets.of(0, 0, 0, 48), imeBottom = 0)
            assertEquals("$label 3-button ime=0", 48f, innerPadding.calculateBottomPadding().value, 0.5f)

            applyInsets(navigation = Insets.of(0, 0, 0, 20), imeBottom = 0)
            assertEquals("$label gesture ime=0", 20f, innerPadding.calculateBottomPadding().value, 0.5f)

            applyInsets(navigation = Insets.of(0, 0, 0, 48), imeBottom = 300)
            assertEquals("$label 3-button ime=300", 0f, innerPadding.calculateBottomPadding().value, 0.5f)
        }
    }

    @Test
    @Config(qualifiers = "en-w900dp-h400dp-mdpi")
    fun landscapeSideNavigationBarDoesNotDoubleInsetUnderShellHorizontalSafeDrawing() {
        var underShellSides by mutableStateOf(false)
        var style by mutableStateOf(AppStyle.Brief)
        rule.setContent {
            composeView = LocalView.current
            PersonaTheme(darkTheme = false, reduceMotion = true, style = style) {
                val sides = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                Box(Modifier.fillMaxSize().then(if (underShellSides) Modifier.windowInsetsPadding(sides) else Modifier)) {
                    JPage(title = "Landscape", topBar = topBar, bottomBar = composer) {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }

        for (currentStyle in AppStyle.entries) {
            for (shellHandled in listOf(false, true)) {
                rule.runOnIdle {
                    style = currentStyle
                    underShellSides = shellHandled
                }
                rule.waitForIdle()
                applyInsets(navigation = Insets.of(0, 0, 48, 0), imeBottom = 0)
                val rootWidth = rule.onRoot().getUnclippedBoundsInRoot().let { it.right - it.left }
                val dockBounds = rule.onNodeWithTag("composer").getUnclippedBoundsInRoot()
                val availablePaneRight = rootWidth - 48.dp
                // Centered inside the 900 - 48 = 852dp safe pane width (composer max width is ReadingWidth + 40.dp = 760.dp),
                // never shifted left by a second 48dp horizontal inset when Root.kt already applied safeDrawing horizontal padding.
                val expectedWidth = minOf(ReadingWidth + 40.dp, availablePaneRight)
                val expectedLeft = (availablePaneRight - expectedWidth) / 2
                assertEquals(
                    "landscape style=$currentStyle shellHandled=$shellHandled left",
                    expectedLeft.value,
                    dockBounds.left.value,
                    0.5f,
                )
            }
        }
    }
}
