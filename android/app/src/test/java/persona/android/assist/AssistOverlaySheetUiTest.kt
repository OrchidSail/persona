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
import kotlin.math.abs
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
    fun dualIslandLayoutHasGenerousTouchTargetsSymmetricBrandAnchorAndNoCloseButton() {
        var openedSettings = 0
        rule.setContent {
            PersonaTheme(darkTheme = false, reduceMotion = true) {
                AssistOverlaySheet(
                    ui = AssistOverlayUiState(
                        invocationId = "inv-dual-island",
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
        val contextBounds = rule.onNodeWithTag("assist-context-island").getUnclippedBoundsInRoot()
        val composerBounds = rule.onNodeWithTag("assist-composer-island").getUnclippedBoundsInRoot()
        val brandBounds = rule.onNodeWithTag("assist-brand-orb").getUnclippedBoundsInRoot()
        val sendBounds = rule.onNodeWithTag("assist-send-button").getUnclippedBoundsInRoot()
        val modelPillBounds = rule.onNodeWithTag("assist-model-pill").getUnclippedBoundsInRoot()
        val openPersonaBounds = rule.onNodeWithTag("assist-open-in-persona").getUnclippedBoundsInRoot()
        val settingsButtonBounds = rule.onNodeWithTag("assist-open-settings-button").getUnclippedBoundsInRoot()

        // Independent floating margins and bottom lift.
        assertTrue("Context island must keep left margin", contextBounds.left > rootBounds.left)
        assertTrue("Context island must keep right margin", contextBounds.right < rootBounds.right)
        assertTrue("Composer island must keep left margin", composerBounds.left > rootBounds.left)
        assertTrue("Composer island must keep right margin", composerBounds.right < rootBounds.right)
        assertTrue("Composer island must float above bottom edge", composerBounds.bottom < rootBounds.bottom)

        // Real 8dp spatial air gap between Upper Context Island and Primary Composer Island.
        val gap = composerBounds.top - contextBounds.bottom
        assertTrue("Expected >= 7dp spatial air gap between islands, got $gap", gap >= 7.dp)

        // Generous island sizing & comfortable touch targets (>= 54dp upper, >= 64dp lower, >= 44-48dp controls).
        assertTrue("Upper context island should be >= 54dp tall, got ${contextBounds.height}", contextBounds.height >= 54.dp)
        assertTrue("Primary composer island should be >= 64dp tall, got ${composerBounds.height}", composerBounds.height >= 64.dp)
        assertTrue("Send button should be >= 48dp tall, got ${sendBounds.height}", sendBounds.height >= 48.dp)
        assertTrue("Send button should be >= 48dp wide, got ${sendBounds.width}", sendBounds.width >= 48.dp)
        assertTrue("Model pill should be >= 44dp tall, got ${modelPillBounds.height}", modelPillBounds.height >= 44.dp)
        assertTrue("Open in Persona should be >= 44dp tall, got ${openPersonaBounds.height}", openPersonaBounds.height >= 44.dp)
        assertTrue("Enable settings button should be >= 44dp tall, got ${settingsButtonBounds.height}", settingsButtonBounds.height >= 44.dp)

        // Left Persona icon slot and right Send button slot must sit symmetrically inside the composer capsule.
        val leftInset = (brandBounds.left - composerBounds.left).value
        val rightInset = (composerBounds.right - sendBounds.right).value
        assertTrue(
            "Persona brand orb left inset ($leftInset dp) must match Send right inset ($rightInset dp)",
            abs(leftInset - rightInset) <= 1.5f,
        )

        // Redundant X close button stays removed.
        rule.onNodeWithTag("assist-close-button").assertDoesNotExist()

        // Immediate DisabledBySettings prompt and Enable action pill stay directly reachable.
        rule.onNodeWithTag("assist-screen-disabled").assertExists()
        rule.onNodeWithTag("assist-open-settings-button").performClick()
        assertEquals(1, openedSettings)
    }

    @Test
    fun privacyScreenToggleUsesPureIconStateAndNeverRendersScreenshotThumbnail() {
        var attachClicks = 0
        var removeClicks = 0
        var uiState by mutableStateOf(
            AssistOverlayUiState(
                invocationId = "inv-privacy-toggle",
                captureStatus = AssistCaptureStatus.Available,
                hubStatus = AssistHubStatus.Ready,
            ),
        )

        rule.setContent {
            PersonaTheme(darkTheme = true, reduceMotion = true) {
                AssistOverlaySheet(
                    ui = uiState,
                    onDraftChange = {},
                    onDictationHeard = {},
                    onNotice = {},
                    onAttachScreen = { attachClicks++ },
                    onRemoveScreen = { removeClicks++ },
                    onSend = {},
                    onOpenInPersona = {},
                    onDismiss = {},
                )
            }
        }

        val attachChipBounds = rule.onNodeWithTag("assist-attach-screen-chip").getUnclippedBoundsInRoot()
        assertTrue("Attach screen toggle must be >= 44dp tall, got ${attachChipBounds.height}", attachChipBounds.height >= 44.dp)
        // Uses concise non-truncating "Attach screen" label on 1080p screens.
        rule.onNodeWithText("Attach screen").assertExists()
        rule.onNodeWithTag("assist-screen-thumbnail", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("assist-screen-unattached-badge", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("assist-attach-screen-chip").performClick()
        assertEquals(1, attachClicks)

        // Transition to Attached: check badge switches to filled state while still rendering zero screenshot preview.
        rule.runOnIdle {
            uiState = uiState.copy(captureStatus = AssistCaptureStatus.Attached)
        }
        rule.waitForIdle()

        val attachedBounds = rule.onNodeWithTag("assist-screen-attached-row").getUnclippedBoundsInRoot()
        assertTrue("Attached screen toggle must be >= 44dp tall, got ${attachedBounds.height}", attachedBounds.height >= 44.dp)
        rule.onNodeWithTag("assist-screen-thumbnail", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("assist-screen-attached-badge", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("assist-screen-attached-row").performClick()
        assertEquals(1, removeClicks)
    }

    @Test
    fun darkThemeExpandedStreamingGrowsThirdLayerReplyIslandAboveContextIsland() {
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

        val replyBounds = rule.onNodeWithTag("assist-reply-island").getUnclippedBoundsInRoot()
        val contextBounds = rule.onNodeWithTag("assist-context-island").getUnclippedBoundsInRoot()
        val composerBounds = rule.onNodeWithTag("assist-composer-island").getUnclippedBoundsInRoot()

        assertTrue("Reply island must sit above context island with air gap", contextBounds.top - replyBounds.bottom >= 7.dp)
        assertTrue("Context island must sit above composer island with air gap", composerBounds.top - contextBounds.bottom >= 7.dp)

        rule.onNodeWithTag("assist-screen-thumbnail", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("assist-model-pill").assertExists()
        rule.onNodeWithTag("assist-open-in-persona").assertExists()
        rule.onNodeWithTag("assist-attach-screen-chip").assertExists()
        rule.onNodeWithTag("assist-reply-brand").assertExists()
        rule.onNodeWithTag("assist-transcript").assertExists()
        rule.onNodeWithTag("assist-brand-orb").assertExists()
        rule.onNodeWithTag("assist-input-field").assertExists()
        rule.onNodeWithTag("assist-send-button").assertExists()
    }

    @Test
    fun singleLayerOfflineAndErrorNoticeCardsExposeDirectActionsWithAirGap() {
        var openedPersona = 0
        var retriedMain = 0
        rule.setContent {
            PersonaTheme(darkTheme = true, reduceMotion = true) {
                AssistOverlaySheet(
                    ui = AssistOverlayUiState(
                        invocationId = "inv-notices",
                        captureStatus = AssistCaptureStatus.Available,
                        hubStatus = AssistHubStatus.Offline,
                        errorMessage = "Selected model is unavailable right now",
                        modelFallbackAvailable = true,
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

        val offlineBounds = rule.onNodeWithTag("assist-hub-offline").getUnclippedBoundsInRoot()
        val errorBounds = rule.onNodeWithTag("assist-error-notice").getUnclippedBoundsInRoot()
        val contextBounds = rule.onNodeWithTag("assist-context-island").getUnclippedBoundsInRoot()
        val offlineActionBounds = rule.onNodeWithTag("assist-hub-offline-action").getUnclippedBoundsInRoot()
        val errorActionBounds = rule.onNodeWithTag("assist-error-action").getUnclippedBoundsInRoot()

        assertTrue("Offline notice must sit above error notice with air gap", errorBounds.top - offlineBounds.bottom >= 7.dp)
        assertTrue("Error notice must sit above context island with air gap", contextBounds.top - errorBounds.bottom >= 7.dp)
        assertTrue("Offline notice action must be >= 44dp tall", offlineActionBounds.height >= 44.dp)
        assertTrue("Error fallback action must be >= 44dp tall", errorActionBounds.height >= 44.dp)

        rule.onNodeWithTag("assist-hub-offline-action").performClick()
        assertEquals(1, openedPersona)
        rule.onNodeWithTag("assist-error-action").performClick()
        assertEquals(1, retriedMain)
    }
}
