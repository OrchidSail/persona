package persona.feature.agents

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import persona.core.contracts.Machine
import persona.core.contracts.Session
import persona.core.designsystem.AppStyle
import persona.core.designsystem.HomeChrome
import persona.core.designsystem.LocalHomeChrome
import persona.core.designsystem.PersonaTheme
import persona.core.domain.sort.AgentGrouping

/**
 * Regression for AgentsScreen forcing `compact = true` on `AgentAppProjectRow` in Board style regardless of the
 * user's Detailed (`compact = false`) preference.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w400dp-h900dp-xhdpi")
class AgentsBoardCompactPreferenceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val sessions = listOf(
        Session(
            machineId = "mac",
            sessionId = "codex-desk",
            title = "Review the PR",
            cwd = "/Users/me/code/billing",
            state = "idle",
            agent = "codex",
            agentSurface = "desktop",
            spaceKind = "codex_app",
            spaceId = "codex-project:/Users/me/code/billing",
            spaceName = "billing",
            lastText = "Looks good to merge.",
            updatedAt = "2026-01-01T00:00:40Z",
        ),
    )
    private val machines = listOf(Machine("mac", "MacBook Pro", online = true, icon = "laptop"))

    @Test fun boardRespectsDetailedVsCompactPreferenceForByAppProjectRows() {
        val model = agentsModel(sessions, machines)
        var style by mutableStateOf(AppStyle.Board)
        rule.setContent {
            PersonaTheme(darkTheme = false, reduceMotion = true, style = style) {
                CompositionLocalProvider(LocalHomeChrome provides HomeChrome(emptyList(), "Agents", onSelect = {}, leading = {})) {
                    AgentsScreen(model, NoNavigation, AgentsMetadata())
                }
            }
        }
        rule.waitUntil(5_000) { model.page.value.loaded }
        rule.runOnIdle { model.grouping(AgentGrouping.App) }
        rule.waitForIdle()

        val projectRow = hasContentDescription("billing", substring = true)
        for (s in AppStyle.entries) {
            rule.runOnIdle {
                style = s
                if (model.page.value.preferences.compact) model.compact()
            }
            rule.waitUntil(5_000) { !model.page.value.preferences.compact }
            rule.waitForIdle()
            val detailedPixels = pixelsOf(rule.onNode(projectRow).captureToImage().asAndroidBitmap())

            rule.runOnIdle { model.compact() }
            rule.waitUntil(5_000) { model.page.value.preferences.compact }
            rule.waitForIdle()
            val compactPixels = pixelsOf(rule.onNode(projectRow).captureToImage().asAndroidBitmap())

            assertFalse(
                "$s: Detailed (compact=false) and Compact (compact=true) By-App project row must render differently",
                detailedPixels.contentEquals(compactPixels),
            )

            rule.runOnIdle { model.compact() }
            rule.waitUntil(5_000) { !model.page.value.preferences.compact }
            rule.waitForIdle()
            val restoredDetailedPixels = pixelsOf(rule.onNode(projectRow).captureToImage().asAndroidBitmap())
            assertTrue(
                "$s: toggling back to Detailed must restore the exact Detailed rendering",
                detailedPixels.contentEquals(restoredDetailedPixels),
            )
        }
    }

    private fun pixelsOf(bitmap: Bitmap): IntArray {
        val out = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(out, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return out
    }
}
