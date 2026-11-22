package persona.android.assist

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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
    fun dualIslandLayoutSeparatesContextAndComposerWithAirGapAndNoCloseButton() {
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

        // Independent floating horizontal margins and bottom lift.
        assertTrue("Context island must keep left margin", contextBounds.left > rootBounds.left)
        assertTrue("Context island must keep right margin", contextBounds.right < rootBounds.right)
        assertTrue("Composer island must keep left margin", composerBounds.left > rootBounds.left)
        assertTrue("Composer island must keep right margin", composerBounds.right < rootBounds.right)
        assertTrue("Composer island must float above bottom edge", composerBounds.bottom < rootBounds.bottom)

        // Real transparent 8dp spatial air gap between Upper Context Island and Primary Composer Island.
        val gap = composerBounds.top - contextBounds.bottom
        assertTrue("Expected >= 7dp spatial air gap between islands, got $gap", gap >= 7.dp)

        // Redundant X close button is completely removed in Proposal C.
        rule.onNodeWithTag("assist-close-button").assertDoesNotExist()

        // Official Persona brand identity anchor is rendered inside the primary composer island.
        rule.onNodeWithTag("assist-brand-orb").assertExists()

        // Immediate DisabledBySettings prompt and Enable action pill stay directly reachable.
        rule.onNodeWithTag("assist-screen-disabled").assertExists()
        rule.onNodeWithTag("assist-open-settings-button").performClick()
        assertEquals(1, openedSettings)
    }

    @Test
    fun upperContextIslandTogglesLiveScreenshotThumbnailBetweenAvailableAndAttached() {
        val preview = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.CYAN)
        }
        var attachClicks = 0
        var removeClicks = 0
        var uiState by mutableStateOf(
            AssistOverlayUiState(
                invocationId = "inv-screen-toggle",
                captureStatus = AssistCaptureStatus.Available,
                previewThumbnail = preview,
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

        rule.onNodeWithTag("assist-attach-screen-chip").assertExists()
        rule.onNodeWithTag("assist-screen-thumbnail", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("assist-screen-unattached-badge", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("assist-attach-screen-chip").performClick()
        assertEquals(1, attachClicks)

        // Transition state cleanly inside the single composition to verify Attached check badge & removal toggle.
        rule.runOnIdle {
            uiState = uiState.copy(
                captureStatus = AssistCaptureStatus.Attached,
                attachedThumbnail = preview,
            )
        }
        rule.waitForIdle()

        rule.onNodeWithTag("assist-screen-attached-row").assertExists()
        rule.onNodeWithTag("assist-screen-thumbnail", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("assist-screen-attached-badge", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("assist-screen-attached-row").performClick()
        assertEquals(1, removeClicks)

        preview.recycle()
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

        rule.onNodeWithTag("assist-model-pill").assertExists()
        rule.onNodeWithTag("assist-open-in-persona").assertExists()
        rule.onNodeWithTag("assist-attach-screen-chip").assertExists()
        rule.onNodeWithTag("assist-reply-brand").assertExists()
        rule.onNodeWithTag("assist-transcript").assertExists()
        rule.onNodeWithTag("assist-brand-orb").assertExists()
        rule.onNodeWithTag("assist-input-field").assertExists()
        rule.onNodeWithTag("assist-send-button").assertExists()
    }
}
