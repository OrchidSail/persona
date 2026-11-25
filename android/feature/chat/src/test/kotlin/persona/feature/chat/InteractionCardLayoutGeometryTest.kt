package persona.feature.chat

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import persona.core.contracts.Interaction
import persona.core.contracts.Question
import persona.core.contracts.QuestionOption
import persona.core.designsystem.AppStyle
import persona.core.designsystem.JButtonSize
import persona.core.designsystem.PersonaTheme
import persona.core.designsystem.Tokens
import java.io.File
import java.time.Instant

/**
 * Geometric layout regression tests for pending interaction cards above the chat composer:
 * - Verifies across phone widths (360dp, 384dp, 412dp), Glass / Brief / Board, light & dark,
 *   en / zh / ja, normal & large font scales, and default / minimum-resized / tight / keyboard states:
 *   1. Request body bounds sit strictly above action buttons (no overlap).
 *   2. Action buttons sit completely inside the card container (zero vertical clipping) with >= 48dp touch height.
 *   3. Long tool names in the header (e.g. `device_relay_run_task`) are never truncated mid-word by expiry/icons.
 *   4. Question and plan approval cards keep content and actions unclipped across collapsed and expanded states.
 *
 * Optional screenshots are written only when `-Dpersona.shots=<dir>` is provided (never inside the repo by default).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InteractionCardLayoutGeometryTest {
    @get:Rule val rule = createComposeRule()

    private val summaryLine = "[demo-phone (default, Android 16)] Check remaining data allowance in the carrier app and return the balance."
    private val expiresSoon = Instant.now().plusSeconds(12 * 60L + 30L).toString()

    private fun permissionCard(
        id: String = "perm-phone",
        toolName: String = "device_relay_run_task",
        description: String = summaryLine,
        offersAlways: Boolean = false,
    ) = Interaction(
        id = id,
        machineId = "hub",
        sessionId = "main",
        kind = "permission",
        toolName = toolName,
        expiresAt = expiresSoon,
        toolInput = buildJsonObject {
            put("device", JsonPrimitive("demo-phone"))
            put("description", JsonPrimitive(description))
        },
        alwaysHonored = offersAlways,
    )

    private fun planCard() = Interaction(
        id = "plan-1",
        machineId = "hub",
        sessionId = "main",
        kind = "plan",
        summary = "Update bottom interaction card geometry",
        expiresAt = expiresSoon,
        plan = (1..12).joinToString("\n") { "$it. Verify layout step $it keeps body above pinned action buttons." },
    )

    private fun questionCard() = Interaction(
        id = "ask-1",
        machineId = "hub",
        sessionId = "main",
        kind = "question",
        expiresAt = expiresSoon,
        questions = listOf(
            Question(
                question = "Which target should be checked first?",
                header = "Target",
                options = listOf(
                    QuestionOption("Primary phone", "Use the paired default device"),
                    QuestionOption("Secondary tablet", "Use the standby test device"),
                ),
            ),
            Question(
                question = "Include verbose diagnostics?",
                header = "Logs",
                options = listOf(
                    QuestionOption("Yes", "Attach layout coordinates"),
                    QuestionOption("No", "Summary only"),
                ),
            ),
        ),
    )

    private fun textOverflows(node: SemanticsNodeInteraction): Boolean {
        val layouts = mutableListOf<TextLayoutResult>()
        val action = node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!
        check(action(layouts))
        val result = layouts.single()
        val lastLine = (result.lineCount - 1).coerceAtLeast(0)
        return result.hasVisualOverflow || result.isLineEllipsized(lastLine)
    }

    private fun maybeSaveShot(name: String) {
        val dir = (System.getProperty("persona.shots") ?: System.getenv("PERSONA_CARD_SHOTS_DIR"))
            ?.takeIf(String::isNotBlank) ?: return
        val output = File(dir).also { it.mkdirs() }
        val bitmap = rule.onNodeWithTag("shot-stage").captureToImage().asAndroidBitmap()
        File(output, "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun assertPermissionCardGeometry(
        contextLabel: String,
        cardId: String,
        headlineText: String,
        denyLabel: String,
        allowLabel: String,
        expectPinned: Boolean,
        expectViewportFitsFullButton: Boolean = true,
    ) {
        val inCard = hasAnyAncestor(hasTestTag("pending-card-$cardId"))
        val cardBounds = rule.onNodeWithTag("pending-card-$cardId").getBoundsInRoot()
        val bodyNode = rule.onNode(hasTestTag("interaction-card-body") and inCard)
        val actionsNode = rule.onNode(hasTestTag("interaction-card-actions") and inCard)
        val headlineNode = rule.onNode(hasText(headlineText) and inCard, useUnmergedTree = true)
        val summaryNode = rule.onNode(hasText(summaryLine, substring = true) and inCard, useUnmergedTree = true)
        val denyNode = rule.onNode(hasText(denyLabel) and hasClickAction() and inCard)
        val allowNode = rule.onNode(hasText(allowLabel) and hasClickAction() and inCard)

        headlineNode.assertIsDisplayed()
        assertFalse("$contextLabel: tool headline truncated: '$headlineText'", textOverflows(headlineNode))

        if (!expectPinned) {
            summaryNode.performScrollTo()
            rule.waitForIdle()
        }

        val bodyBounds = bodyNode.getBoundsInRoot()
        val summaryBounds = summaryNode.getBoundsInRoot()
        val minBodyVisible = if (expectViewportFitsFullButton) 24.dp else 16.dp
        assertTrue(
            "$contextLabel: summary must be clearly visible (height=${summaryBounds.height}, body=${bodyBounds.height})",
            summaryBounds.height >= 16.dp && bodyBounds.height >= minBodyVisible,
        )

        if (!expectPinned) {
            allowNode.performScrollTo()
            rule.waitForIdle()
        }

        val bodyUnclipped = bodyNode.getUnclippedBoundsInRoot()
        val actionsBounds = actionsNode.getBoundsInRoot()
        val denyClipped = denyNode.getBoundsInRoot()
        val denyUnclipped = denyNode.getUnclippedBoundsInRoot()
        val allowClipped = allowNode.getBoundsInRoot()
        val allowUnclipped = allowNode.getUnclippedBoundsInRoot()

        val eps = 1.dp
        if (expectPinned) {
            assertTrue(
                "$contextLabel: body bottom (${bodyBounds.bottom}) must stay above actions top (${actionsBounds.top})",
                bodyBounds.bottom <= actionsBounds.top + eps,
            )
            assertTrue(
                "$contextLabel: summary bottom (${summaryBounds.bottom}) must stay above Allow button top (${allowClipped.top})",
                summaryBounds.bottom <= allowClipped.top + eps,
            )
        } else {
            assertTrue(
                "$contextLabel: unclipped body bottom (${bodyUnclipped.bottom}) must stay above unclipped Allow top (${allowUnclipped.top})",
                bodyUnclipped.bottom <= allowUnclipped.top + eps,
            )
        }

        assertTrue(
            "$contextLabel: Deny button unclipped height (${denyUnclipped.height}) must be >= ${Tokens.MinTouch.android}",
            denyUnclipped.height >= Tokens.MinTouch.android - eps,
        )
        assertTrue(
            "$contextLabel: Allow button unclipped height (${allowUnclipped.height}) must be >= ${Tokens.MinTouch.android}",
            allowUnclipped.height >= Tokens.MinTouch.android - eps,
        )
        if (expectViewportFitsFullButton) {
            assertTrue(
                "$contextLabel: Deny button clipped height (${denyClipped.height}) must not be vertically sliced (unclipped=${denyUnclipped.height})",
                denyClipped.height >= Tokens.MinTouch.android - eps,
            )
            assertTrue(
                "$contextLabel: Allow button clipped height (${allowClipped.height}) must not be vertically sliced (unclipped=${allowUnclipped.height})",
                allowClipped.height >= Tokens.MinTouch.android - eps,
            )
            assertTrue(
                "$contextLabel: Deny button bottom (${denyUnclipped.bottom}) must stay inside card bottom (${cardBounds.bottom})",
                denyUnclipped.bottom <= cardBounds.bottom + eps,
            )
            assertTrue(
                "$contextLabel: Allow button bottom (${allowUnclipped.bottom}) must stay inside card bottom (${cardBounds.bottom})",
                allowUnclipped.bottom <= cardBounds.bottom + eps,
            )
        }
    }

    @Config(sdk = [35], qualifiers = "zh-w412dp-h860dp-xhdpi", application = QuestionTestApplication::class)
    @Test fun zhPermissionCardNeverClipsOrTruncatesAcrossStylesWidthsAndPersistedZeroShare() {
        val card = permissionCard()
        val persistedZero = CardHeightStore(readShare = { 0f }, writeShare = {})
        val resizeRange = PendingStack.resizeRange(page = 800.dp, rest = 110.dp, header = 88.dp, floor = Tokens.Space.xl)!!
        var style by mutableStateOf(AppStyle.Brief)
        var dark by mutableStateOf(true)
        var width by mutableStateOf(384.dp)
        var cap by mutableStateOf(persistedZero.cap(default = 380.dp, range = resizeRange, id = card.id))
        var fontScale by mutableStateOf(1f)
        var keyboard by mutableStateOf(false)

        rule.setContent {
            val config = Configuration(LocalConfiguration.current).apply { this.fontScale = fontScale }
            val density = Density(LocalDensity.current.density, fontScale)
            CompositionLocalProvider(LocalConfiguration provides config, LocalDensity provides density) {
                PersonaTheme(darkTheme = dark, reduceMotion = true, style = style) {
                    Box(
                        Modifier.width(width).fillMaxHeight().background(PersonaTheme.palette.background).testTag("shot-stage"),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        InteractionPanel(
                            requests = listOf(card),
                            machine = null,
                            maxHeight = cap,
                            busyId = null,
                            uncertainIds = emptySet(),
                            onResolve = { _, _ -> },
                            onTerminal = null,
                            onOverlay = {},
                            keyboard = keyboard,
                            resize = if (keyboard) null else CardResize(card.id, height = { cap }, range = resizeRange, store = persistedZero),
                        )
                    }
                }
            }
        }

        // 1. Persisted share=0f (the exact regression state) across 360dp / 384dp / 412dp and all 3 styles (dark & light)
        for (currentWidth in listOf(360.dp, 384.dp, 412.dp)) {
            for (currentStyle in AppStyle.entries) {
                for (isDark in listOf(true, false)) {
                    rule.runOnIdle {
                        width = currentWidth
                        style = currentStyle
                        dark = isDark
                        fontScale = 1f
                        keyboard = false
                        cap = persistedZero.cap(default = 380.dp, range = resizeRange, id = card.id)
                    }
                    rule.waitForIdle()
                    assertPermissionCardGeometry(
                        contextLabel = "zh $currentStyle dark=$isDark w=$currentWidth share=0f",
                        cardId = card.id,
                        headlineText = "请求使用 device_relay_run_task",
                        denyLabel = "拒绝",
                        allowLabel = "允许",
                        expectPinned = true,
                    )
                    if (currentWidth == 384.dp && isDark) {
                        maybeSaveShot("zh-min-${currentStyle.name.lowercase()}-dark")
                    }
                }
            }
        }

        // 2. Default cap + keyboard-up lone card (must not fade/clip bottom buttons)
        for (currentStyle in AppStyle.entries) {
            rule.runOnIdle {
                width = 384.dp
                style = currentStyle
                dark = true
                fontScale = 1f
                keyboard = true
                cap = 260.dp
            }
            rule.waitForIdle()
            assertPermissionCardGeometry(
                contextLabel = "zh $currentStyle keyboard=true cap=260dp",
                cardId = card.id,
                headlineText = "请求使用 device_relay_run_task",
                denyLabel = "拒绝",
                allowLabel = "允许",
                expectPinned = true,
            )
        }

        // 3. Compact unified-scroll cap (200dp, e.g. IME + open tray): scrolls cleanly with full unclipped 48dp buttons
        rule.runOnIdle {
            width = 360.dp
            style = AppStyle.Brief
            dark = true
            fontScale = 1f
            keyboard = true
            cap = 200.dp
        }
        rule.waitForIdle()
        assertPermissionCardGeometry(
            contextLabel = "zh Brief compact cap=200dp",
            cardId = card.id,
            headlineText = "请求使用 device_relay_run_task",
            denyLabel = "拒绝",
            allowLabel = "允许",
            expectPinned = false,
            expectViewportFitsFullButton = true,
        )

        // 3b. Extreme forced cap (150dp): unclipped 48dp buttons are never sliced by a 0.6f cap and stay strictly below body
        rule.runOnIdle {
            width = 360.dp
            style = AppStyle.Brief
            dark = true
            fontScale = 1f
            keyboard = true
            cap = Tokens.Size.box150
        }
        rule.waitForIdle()
        assertPermissionCardGeometry(
            contextLabel = "zh Brief extreme cap=150dp",
            cardId = card.id,
            headlineText = "请求使用 device_relay_run_task",
            denyLabel = "拒绝",
            allowLabel = "允许",
            expectPinned = false,
            expectViewportFitsFullButton = false,
        )

        // 4. Large accessibility text (1.6x) at 360dp across all 3 styles
        for (currentStyle in AppStyle.entries) {
            rule.runOnIdle {
                width = 360.dp
                style = currentStyle
                dark = false
                fontScale = 1.6f
                keyboard = false
                cap = 380.dp
            }
            rule.waitForIdle()
            assertPermissionCardGeometry(
                contextLabel = "zh $currentStyle fontScale=1.6 w=360dp",
                cardId = card.id,
                headlineText = "请求使用 device_relay_run_task",
                denyLabel = "拒绝",
                allowLabel = "允许",
                expectPinned = false,
            )
            if (currentStyle == AppStyle.Brief) maybeSaveShot("zh-large-brief-light")
        }
    }

    @Config(sdk = [35], qualifiers = "en-w360dp-h800dp-xhdpi", application = QuestionTestApplication::class)
    @Test fun enPermissionCardNeverClipsOrTruncatesAt360dpAndLargeFont() {
        val card = permissionCard()
        val persistedZero = CardHeightStore(readShare = { 0f }, writeShare = {})
        val resizeRange = PendingStack.resizeRange(page = 780.dp, rest = 110.dp, header = 88.dp, floor = Tokens.Space.xl)!!
        var style by mutableStateOf(AppStyle.Glass)
        var fontScale by mutableStateOf(1f)
        var cap by mutableStateOf(persistedZero.cap(default = 380.dp, range = resizeRange, id = card.id))

        rule.setContent {
            val config = Configuration(LocalConfiguration.current).apply { this.fontScale = fontScale }
            val density = Density(LocalDensity.current.density, fontScale)
            CompositionLocalProvider(LocalConfiguration provides config, LocalDensity provides density) {
                PersonaTheme(darkTheme = true, reduceMotion = true, style = style) {
                    Box(
                        Modifier.width(360.dp).fillMaxHeight().background(PersonaTheme.palette.background).testTag("shot-stage"),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        InteractionPanel(
                            requests = listOf(card),
                            machine = null,
                            maxHeight = cap,
                            busyId = null,
                            uncertainIds = emptySet(),
                            onResolve = { _, _ -> },
                            onTerminal = null,
                            onOverlay = {},
                            resize = CardResize(card.id, height = { cap }, range = resizeRange, store = persistedZero),
                        )
                    }
                }
            }
        }

        for (currentStyle in AppStyle.entries) {
            for (scale in listOf(1f, 1.6f)) {
                rule.runOnIdle {
                    style = currentStyle
                    fontScale = scale
                    cap = if (scale > 1f) 380.dp else persistedZero.cap(default = 380.dp, range = resizeRange, id = card.id)
                }
                rule.waitForIdle()
                assertPermissionCardGeometry(
                    contextLabel = "en $currentStyle scale=$scale w=360dp",
                    cardId = card.id,
                    headlineText = "Wants to use device_relay_run_task",
                    denyLabel = "Deny",
                    allowLabel = "Allow",
                    expectPinned = scale < 1.5f,
                )
            }
        }
    }

    @Config(sdk = [35], qualifiers = "ja-w360dp-h800dp-xhdpi", application = QuestionTestApplication::class)
    @Test fun jaPermissionCardNeverClipsOrTruncatesAt360dpAndLargeFont() {
        val card = permissionCard()
        val persistedZero = CardHeightStore(readShare = { 0f }, writeShare = {})
        val resizeRange = PendingStack.resizeRange(page = 780.dp, rest = 110.dp, header = 88.dp, floor = Tokens.Space.xl)!!
        var style by mutableStateOf(AppStyle.Board)
        var fontScale by mutableStateOf(1f)
        var cap by mutableStateOf(persistedZero.cap(default = 380.dp, range = resizeRange, id = card.id))

        rule.setContent {
            val config = Configuration(LocalConfiguration.current).apply { this.fontScale = fontScale }
            val density = Density(LocalDensity.current.density, fontScale)
            CompositionLocalProvider(LocalConfiguration provides config, LocalDensity provides density) {
                PersonaTheme(darkTheme = false, reduceMotion = true, style = style) {
                    Box(
                        Modifier.width(360.dp).fillMaxHeight().background(PersonaTheme.palette.background).testTag("shot-stage"),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        InteractionPanel(
                            requests = listOf(card),
                            machine = null,
                            maxHeight = cap,
                            busyId = null,
                            uncertainIds = emptySet(),
                            onResolve = { _, _ -> },
                            onTerminal = null,
                            onOverlay = {},
                            resize = CardResize(card.id, height = { cap }, range = resizeRange, store = persistedZero),
                        )
                    }
                }
            }
        }

        for (currentStyle in AppStyle.entries) {
            for (scale in listOf(1f, 1.6f)) {
                rule.runOnIdle {
                    style = currentStyle
                    fontScale = scale
                    cap = if (scale > 1f) 380.dp else persistedZero.cap(default = 380.dp, range = resizeRange, id = card.id)
                }
                rule.waitForIdle()
                assertPermissionCardGeometry(
                    contextLabel = "ja $currentStyle scale=$scale w=360dp",
                    cardId = card.id,
                    headlineText = "device_relay_run_taskの使用をリクエスト",
                    denyLabel = "拒否",
                    allowLabel = "許可",
                    expectPinned = scale < 1.5f,
                )
            }
        }
    }

    @Config(sdk = [35], qualifiers = "en-w384dp-h840dp-xhdpi", application = QuestionTestApplication::class)
    @Test fun planQuestionAndAlwaysPermissionCardsStayUnclippedAcrossFoldAndResizeStates() {
        val alwaysPerm = permissionCard(id = "perm-always", offersAlways = true)
        val plan = planCard()
        val question = questionCard()
        val folds = RequestFolds()
        val store = CardHeightStore()
        val range = PendingStack.MINIMUM_RESIZE_HEIGHT..560.dp
        var active by mutableStateOf<Interaction>(plan)
        var style by mutableStateOf(AppStyle.Brief)
        var cap by mutableStateOf(360.dp)

        rule.setContent {
            PersonaTheme(darkTheme = true, reduceMotion = true, style = style) {
                Box(
                    Modifier.width(384.dp).fillMaxHeight().background(PersonaTheme.palette.background).testTag("shot-stage"),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    InteractionPanel(
                        requests = listOf(active),
                        machine = null,
                        maxHeight = cap,
                        busyId = null,
                        uncertainIds = emptySet(),
                        onResolve = { _, _ -> },
                        onTerminal = null,
                        onOverlay = {},
                        folds = folds,
                        resize = CardResize(active.id, height = { cap }, range = range, store = store),
                    )
                }
            }
        }

        val eps = 1.dp
        for (currentStyle in AppStyle.entries) {
            // Plan card at default height
            rule.runOnIdle {
                style = currentStyle
                active = plan
                cap = 360.dp
                folds.set(plan.id, false)
            }
            rule.waitForIdle()
            val planCardBounds = rule.onNodeWithTag("pending-card-${plan.id}").getBoundsInRoot()
            val planBody = rule.onNodeWithTag("interaction-card-body").getBoundsInRoot()
            val planActions = rule.onNodeWithTag("interaction-card-actions").getBoundsInRoot()
            val rejectBtn = rule.onNode(hasText("Send back") and hasClickAction()).getBoundsInRoot()
            assertTrue("plan $currentStyle: body above actions", planBody.bottom <= planActions.top + eps)
            assertTrue("plan $currentStyle: Send back >= 48dp", rejectBtn.height >= Tokens.MinTouch.android - eps)
            assertTrue("plan $currentStyle: Send back inside card", rejectBtn.bottom <= planCardBounds.bottom + eps)

            // Multi-question card at minimum resized height (240dp)
            rule.runOnIdle {
                active = question
                cap = PendingStack.MINIMUM_RESIZE_HEIGHT
                folds.set(question.id, false)
            }
            rule.waitForIdle()
            val qCardBounds = rule.onNodeWithTag("pending-card-${question.id}").getBoundsInRoot()
            val qBody = rule.onNodeWithTag("interaction-card-body").getBoundsInRoot()
            val qActions = rule.onNodeWithTag("interaction-card-actions").getBoundsInRoot()
            val nextNode = rule.onNode(hasText("Next") and hasClickAction())
            val nextBtn = nextNode.getBoundsInRoot()
            val nextUnclipped = nextNode.getUnclippedBoundsInRoot()
            assertTrue("question $currentStyle: body above actions", qBody.bottom <= qActions.top + eps)
            // Next is a JTextButton (drawn height JButtonSize.Medium = 40dp, Compose touch target expanded to 48dp).
            assertTrue("question $currentStyle: Next unclipped >= ${JButtonSize.Medium.height}", nextUnclipped.height >= JButtonSize.Medium.height - eps)
            assertTrue("question $currentStyle: Next not vertically sliced", nextBtn.height >= nextUnclipped.height - eps)
            assertTrue("question $currentStyle: Next inside card", nextUnclipped.bottom <= qCardBounds.bottom + eps)

            // Fold and unfold question card
            rule.onNodeWithTag("interaction-grabber").assertIsDisplayed()
            folds.set(question.id, true)
            rule.waitForIdle()
            rule.onNodeWithTag("interaction-grabber").assertDoesNotExist()
            folds.set(question.id, false)
            rule.waitForIdle()
            rule.onNodeWithTag("interaction-grabber").assertIsDisplayed()

            // Permission card with Always allow + scope caption at minimum resized height (240dp -> unified scroll)
            rule.runOnIdle {
                active = alwaysPerm
                cap = PendingStack.MINIMUM_RESIZE_HEIGHT
            }
            rule.waitForIdle()
            assertPermissionCardGeometry(
                contextLabel = "en alwaysPerm $currentStyle cap=240dp",
                cardId = alwaysPerm.id,
                headlineText = "Wants to use device_relay_run_task",
                denyLabel = "Deny",
                allowLabel = "Allow",
                expectPinned = false,
            )

            // Tap grabber to toggle to tallest (560dp -> pinned with full Always + scope visible)
            rule.onNodeWithTag("interaction-grabber").performClick()
            rule.runOnIdle { cap = store.cap(360.dp, range, alwaysPerm.id) }
            rule.waitForIdle()
            assertPermissionCardGeometry(
                contextLabel = "en alwaysPerm $currentStyle tallest=560dp",
                cardId = alwaysPerm.id,
                headlineText = "Wants to use device_relay_run_task",
                denyLabel = "Deny",
                allowLabel = "Allow",
                expectPinned = true,
            )
            store.toggle()
        }
    }
}
