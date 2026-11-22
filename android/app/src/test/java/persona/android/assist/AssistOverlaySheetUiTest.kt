package persona.android.assist

import android.app.Application
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import persona.android.ui.ComponentActivityHostRule
import persona.core.designsystem.PersonaTheme

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-w411dp-h915dp-xhdpi")
class AssistOverlaySheetUiTest {
    private val rule = createComposeRule()
    @get:Rule val host = RuleChain.outerRule(ComponentActivityHostRule()).around(rule)

    @Test
    fun floatingCardRendersWithIndependentMarginsAndImmediateDisabledSettingsAction() {
        var openedSettings = 0
        rule.setContent {
            PersonaTheme(darkTheme = false, reduceMotion = true) {
                AssistOverlaySheet(
                    ui = AssistOverlayUiState(
                        invocationId = "inv-floating",
                        captureStatus = AssistCaptureStatus.DisabledBySettings,
                        hubStatus = AssistHubStatus.Ready,
                    ),
                    onDraftChange = {},
                    onDictationHeard = {},
                    onNotice = {},
                    onAttachScreen = {},
                    onRemoveScreen = {},
                    onOpenAssistantSettings = { openedSettings++ },
                    onSend = {},
                    onOpenInPersona = {},
                    onDismiss = {},
                )
            }
        }

        val rootBounds = rule.onNodeWithTag("assist-overlay-root").getUnclippedBoundsInRoot()
        val cardBounds = rule.onNodeWithTag("assist-overlay-card").getUnclippedBoundsInRoot()

        // True floating card: inset from left, right, and bottom edges of the overlay root.
        assertTrue("Floating card must keep left margin", cardBounds.left > rootBounds.left)
        assertTrue("Floating card must keep right margin", cardBounds.right < rootBounds.right)
        assertTrue("Floating card must float above bottom edge", cardBounds.bottom < rootBounds.bottom)

        rule.onNodeWithTag("assist-screen-disabled").assertExists()
        rule.onNodeWithTag("assist-open-settings-button").performClick()
        assertEquals(1, openedSettings)
    }

    @Test
    fun darkThemeActiveStreamingFloatingCardRendersTranscriptAndControlsCleanly() {
        rule.setContent {
            PersonaTheme(darkTheme = true, reduceMotion = true) {
                AssistOverlaySheet(
                    ui = AssistOverlayUiState(
                        invocationId = "inv-dark-stream",
                        captureStatus = AssistCaptureStatus.Available,
                        hubStatus = AssistHubStatus.Ready,
                        awaitingReply = true,
                        turns = listOf(
                            AssistOverlayTurn(
                                id = "u1",
                                fromUser = true,
                                text = "Summarize this page",
                            ),
                            AssistOverlayTurn(
                                id = "a1",
                                fromUser = false,
                                text = "Here are the key takeaways…",
                                streaming = true,
                            ),
                        ),
                    ),
                    onDraftChange = {},
                    onDictationHeard = {},
                    onNotice = {},
                    onAttachScreen = {},
                    onRemoveScreen = {},
                    onSend = {},
                    onOpenInPersona = {},
                    onDismiss = {},
                )
            }
        }

        rule.onNodeWithTag("assist-overlay-card").assertExists()
        rule.onNodeWithTag("assist-model-pill").assertExists()
        rule.onNodeWithTag("assist-attach-screen-chip").assertExists()
        rule.onNodeWithTag("assist-transcript").assertExists()
        rule.onNodeWithTag("assist-input-field").assertExists()
    }
}
