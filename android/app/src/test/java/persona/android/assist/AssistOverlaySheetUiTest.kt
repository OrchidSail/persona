package persona.android.assist

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import persona.android.ui.ComponentActivityHostRule
import persona.core.designsystem.AppStyle
import persona.core.designsystem.PersonaTheme

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-w411dp-h915dp-xhdpi")
class AssistOverlaySheetUiTest {
    private val rule = createComposeRule()
    @get:Rule val host = RuleChain.outerRule(ComponentActivityHostRule()).around(rule)

    @Test
    fun unifiedComposerCardAdaptsCleanlyAcrossGlassBriefAndBoardInLightAndDark() {
        var currentStyle by mutableStateOf(AppStyle.Glass)
        var darkTheme by mutableStateOf(false)

        rule.setContent {
            PersonaTheme(darkTheme = darkTheme, reduceMotion = true, style = currentStyle) {
                AssistOverlaySheet(
                    ui = AssistOverlayUiState(
                        invocationId = "inv-styles",
                        captureStatus = AssistCaptureStatus.Available,
                        hubStatus = AssistHubStatus.Ready,
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

        val matrix = listOf(
            AppStyle.Glass to false,
            AppStyle.Glass to true,
            AppStyle.Brief to false,
            AppStyle.Brief to true,
            AppStyle.Board to false,
            AppStyle.Board to true,
        )
        for ((style, dark) in matrix) {
            rule.runOnIdle {
                currentStyle = style
                darkTheme = dark
            }
            rule.waitForIdle()

            val tag = "${style.name}/${if (dark) "Dark" else "Light"}"
            val rootBounds = rule.onNodeWithTag("assist-overlay-root").getUnclippedBoundsInRoot()
            val cardBounds = rule.onNodeWithTag("assist-composer-island").getUnclippedBoundsInRoot()
            val brandBounds = rule.onNodeWithTag("assist-brand-orb").getUnclippedBoundsInRoot()
            val screenBounds = rule.onNodeWithTag("assist-attach-screen-chip").getUnclippedBoundsInRoot()
            val modelBounds = rule.onNodeWithTag("assist-model-pill").getUnclippedBoundsInRoot()
            val openBounds = rule.onNodeWithTag("assist-open-in-persona").getUnclippedBoundsInRoot()
            val sendBounds = rule.onNodeWithTag("assist-send-button").getUnclippedBoundsInRoot()

            assertTrue("$tag: card must keep left margin", cardBounds.left > rootBounds.left)
            assertTrue("$tag: card must keep right margin", cardBounds.right < rootBounds.right)
            assertTrue("$tag: card must float above bottom edge", cardBounds.bottom < rootBounds.bottom)
            assertTrue("$tag: unified card height should be compact (~110-156dp), got ${cardBounds.height}", cardBounds.height in 108.dp..156.dp)
            assertTrue("$tag: PersonaFace anchor must sit inside card", brandBounds.left >= cardBounds.left && brandBounds.top >= cardBounds.top)
            assertTrue("$tag: attach screen chip must be >= 44dp tall, got ${screenBounds.height}", screenBounds.height >= 44.dp)
            assertTrue("$tag: model pill must be >= 44dp tall, got ${modelBounds.height}", modelBounds.height >= 44.dp)
            assertTrue("$tag: open Persona must be >= 44dp tall, got ${openBounds.height}", openBounds.height >= 44.dp)
            assertTrue("$tag: send button must be >= 44dp tall, got ${sendBounds.height}", sendBounds.height >= 44.dp)
            assertTrue("$tag: send button must be >= 44dp wide, got ${sendBounds.width}", sendBounds.width >= 44.dp)

            // Concise non-truncating screen label, zero screenshot thumbnail, zero X close clutter.
            rule.onNodeWithText("Attach screen").assertExists()
            rule.onNodeWithTag("assist-screen-thumbnail", useUnmergedTree = true).assertDoesNotExist()
            rule.onNodeWithTag("assist-close-button").assertDoesNotExist()
            rule.onNodeWithTag("assist-input-field").assertExists()
        }
    }

    @Test
    fun privacyScreenToggleAndDisabledSettingsWorkWithoutThumbnails() {
        var attachClicks = 0
        var removeClicks = 0
        var openedSettings = 0
        var uiState by mutableStateOf(
            AssistOverlayUiState(
                invocationId = "inv-privacy-toggle",
                captureStatus = AssistCaptureStatus.Available,
                hubStatus = AssistHubStatus.Ready,
            ),
        )

        rule.setContent {
            PersonaTheme(darkTheme = true, reduceMotion = true, style = AppStyle.Glass) {
                AssistOverlaySheet(
                    ui = uiState,
                    onDraftChange = {},
                    onDictationHeard = {},
                    onNotice = {},
                    onAttachScreen = { attachClicks++ },
                    onRemoveScreen = { removeClicks++ },
                    onOpenAssistantSettings = { openedSettings++ },
                    onSend = {},
                    onOpenInPersona = {},
                    onDismiss = {},
                )
            }
        }

        rule.onNodeWithText("Attach screen").assertExists()
        rule.onNodeWithTag("assist-screen-thumbnail", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("assist-attach-screen-chip").performClick()
        assertEquals(1, attachClicks)

        // Transition to Attached: check badge renders while still revealing zero screen pixels.
        rule.runOnIdle {
            uiState = uiState.copy(captureStatus = AssistCaptureStatus.Attached)
        }
        rule.waitForIdle()

        val attachedBounds = rule.onNodeWithTag("assist-screen-attached-row").getUnclippedBoundsInRoot()
        assertTrue("Attached chip must be >= 44dp tall, got ${attachedBounds.height}", attachedBounds.height >= 44.dp)
        rule.onNodeWithTag("assist-screen-thumbnail", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("assist-screen-attached-badge", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("assist-screen-attached-row").performClick()
        assertEquals(1, removeClicks)

        // Transition to DisabledBySettings: direct Enable action stays >= 44dp tall and clickable.
        rule.runOnIdle {
            uiState = uiState.copy(captureStatus = AssistCaptureStatus.DisabledBySettings)
        }
        rule.waitForIdle()

        val settingsButtonBounds = rule.onNodeWithTag("assist-open-settings-button").getUnclippedBoundsInRoot()
        assertTrue("Enable settings button must be >= 44dp tall, got ${settingsButtonBounds.height}", settingsButtonBounds.height >= 44.dp)
        rule.onNodeWithTag("assist-screen-disabled").assertExists()
        rule.onNodeWithTag("assist-open-settings-button").performClick()
        assertEquals(1, openedSettings)
    }

    @Test
    @Config(application = Application::class, sdk = [35], qualifiers = "en-w360dp-h780dp-xhdpi")
    fun narrow360dpPhoneKeepsShortAttachLabelAndControlsComfortable() {
        rule.setContent {
            PersonaTheme(darkTheme = false, reduceMotion = true, style = AppStyle.Brief) {
                AssistOverlaySheet(
                    ui = AssistOverlayUiState(
                        invocationId = "inv-narrow-360",
                        captureStatus = AssistCaptureStatus.Available,
                        hubStatus = AssistHubStatus.Ready,
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

        val screenBounds = rule.onNodeWithTag("assist-attach-screen-chip").getUnclippedBoundsInRoot()
        val modelBounds = rule.onNodeWithTag("assist-model-pill").getUnclippedBoundsInRoot()
        val openBounds = rule.onNodeWithTag("assist-open-in-persona").getUnclippedBoundsInRoot()
        val sendBounds = rule.onNodeWithTag("assist-send-button").getUnclippedBoundsInRoot()

        rule.onNodeWithText("Attach screen").assertExists()
        assertTrue("360dp attach chip >= 44dp tall", screenBounds.height >= 44.dp)
        assertTrue("360dp model pill >= 44dp tall", modelBounds.height >= 44.dp)
        assertTrue("360dp open button >= 44dp tall", openBounds.height >= 44.dp)
        assertTrue("360dp send button >= 44dp tall", sendBounds.height >= 44.dp)
        assertTrue("Controls must not overlap horizontally", screenBounds.right <= modelBounds.left)
    }

    @Test
    fun expandedReplyIslandAndSingleLayerNoticesFloatAboveUnifiedCardAcrossBoardAndBrief() {
        var openedPersona = 0
        var retriedMain = 0
        var currentStyle by mutableStateOf(AppStyle.Board)
        var darkTheme by mutableStateOf(true)

        rule.setContent {
            PersonaTheme(darkTheme = darkTheme, reduceMotion = true, style = currentStyle) {
                AssistOverlaySheet(
                    ui = AssistOverlayUiState(
                        invocationId = "inv-expanded-notices",
                        captureStatus = AssistCaptureStatus.Available,
                        hubStatus = AssistHubStatus.Offline,
                        errorMessage = "Selected model is unavailable right now",
                        modelFallbackAvailable = true,
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
                    onRetryWithDefaultMain = { retriedMain++ },
                    onSend = {},
                    onOpenInPersona = { openedPersona++ },
                    onDismiss = {},
                )
            }
        }

        // Verify Board Dark expanded stack & single-layer notice actions.
        val replyBounds = rule.onNodeWithTag("assist-reply-island").getUnclippedBoundsInRoot()
        val offlineBounds = rule.onNodeWithTag("assist-hub-offline").getUnclippedBoundsInRoot()
        val errorBounds = rule.onNodeWithTag("assist-error-notice").getUnclippedBoundsInRoot()
        val composerBounds = rule.onNodeWithTag("assist-composer-island").getUnclippedBoundsInRoot()
        val offlineActionBounds = rule.onNodeWithTag("assist-hub-offline-action").getUnclippedBoundsInRoot()
        val errorActionBounds = rule.onNodeWithTag("assist-error-action").getUnclippedBoundsInRoot()

        assertTrue("Reply island sits above offline notice", offlineBounds.top - replyBounds.bottom >= 7.dp)
        assertTrue("Offline notice sits above error notice", errorBounds.top - offlineBounds.bottom >= 7.dp)
        assertTrue("Error notice sits above unified composer card", composerBounds.top - errorBounds.bottom >= 7.dp)
        assertTrue("Offline notice action must be >= 44dp tall", offlineActionBounds.height >= 44.dp)
        assertTrue("Error fallback action must be >= 44dp tall", errorActionBounds.height >= 44.dp)

        rule.onNodeWithTag("assist-reply-brand").assertExists()
        rule.onNodeWithTag("assist-transcript").assertExists()
        rule.onNodeWithTag("assist-hub-offline-action").performClick()
        assertEquals(1, openedPersona)
        rule.onNodeWithTag("assist-error-action").performClick()
        assertEquals(1, retriedMain)

        // Switch live to Brief Light and ensure every layer remains cleanly rendered and reachable.
        rule.runOnIdle {
            currentStyle = AppStyle.Brief
            darkTheme = false
        }
        rule.waitForIdle()
        rule.onNodeWithTag("assist-reply-island").assertExists()
        rule.onNodeWithTag("assist-hub-offline").assertExists()
        rule.onNodeWithTag("assist-error-notice").assertExists()
        rule.onNodeWithTag("assist-composer-island").assertExists()
    }
}
