@file:OptIn(androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi::class)

package persona.android.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import persona.android.navigation.SettingsSheetNavigator
import persona.core.designsystem.AppStyle
import persona.core.designsystem.JSettingsSheet
import persona.core.designsystem.LocalLargeWindowInput
import persona.core.designsystem.PersonaTheme
import persona.core.designsystem.Tokens
import persona.core.navigation.AppTab
import persona.core.navigation.Navigator
import persona.core.navigation.SettingsPage
import persona.core.navigation.SettingsPageRoute
import persona.core.navigation.SettingsRoute
import persona.feature.settings.SettingsScreen
import persona.feature.settings.demoSettingsServices
import persona.feature.settings.toInitialPage

/**
 * Verifies the real Settings sheet stack (root + nested subpages) across Glass, Brief and Board:
 * downward swipes at the top never dismiss Settings, while top Close/Back buttons and system Back exit reliably.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "en-w400dp-h800dp-xhdpi")
class SettingsSheetDismissBehaviorTest {
    val rule = createComposeRule()

    @get:Rule val host = RuleChain.outerRule(ComponentActivityHostRule()).around(rule)

    @Test fun glassRootAndSubpagesIgnoreDownwardSwipeAndExitViaButtonsAndSystemBack() {
        verifyStyle(AppStyle.Glass)
    }

    @Test fun briefRootAndSubpagesIgnoreDownwardSwipeAndExitViaButtonsAndSystemBack() {
        verifyStyle(AppStyle.Brief)
    }

    @Test fun boardRootAndSubpagesIgnoreDownwardSwipeAndExitViaButtonsAndSystemBack() {
        verifyStyle(AppStyle.Board)
    }

    private fun verifyStyle(style: AppStyle) {
        val services = demoSettingsServices(ApplicationProvider.getApplicationContext<Application>().resources)
        val stack = NavBackStack<NavKey>(SettingsRoute)
        var opened by mutableStateOf(true)
        var dismissed = 0
        val outside = object : Navigator {
            override fun navigate(key: NavKey) = Unit
            override fun back() = Unit
            override fun selectTab(tab: AppTab) = Unit
        }
        lateinit var navigator: SettingsSheetNavigator

        rule.setContent {
            CompositionLocalProvider(LocalLargeWindowInput provides false) {
                PersonaTheme(darkTheme = false, reduceMotion = true, style = style) {
                    if (opened) {
                        navigator = remember {
                            SettingsSheetNavigator(stack, close = {
                                dismissed++
                                opened = false
                            }, outside = outside)
                        }
                        JSettingsSheet(onDismiss = {
                            dismissed++
                            opened = false
                        }) {
                            val directive = calculatePaneScaffoldDirective(currentWindowAdaptiveInfo())
                            SettingsSheetPanes(
                                stack = stack,
                                onBack = navigator::back,
                                regular = false,
                                directive = directive.onePane(),
                                hinges = emptyList(),
                                entries = { key ->
                                    when (key) {
                                        is SettingsPageRoute -> NavEntry(key) {
                                            Box(Modifier.fillMaxSize().testTag("settings-subpage-${key.page.name}")) {
                                                SettingsScreen(
                                                    services = services,
                                                    initialPage = key.page.toInitialPage(),
                                                    onOpen = { navigator.navigate(SettingsPageRoute(it)) },
                                                    onBack = navigator::back,
                                                    onNavigate = navigator::navigate,
                                                )
                                            }
                                        }
                                        else -> NavEntry(key) {
                                            Box(Modifier.fillMaxSize().testTag("settings-root-page")) {
                                                SettingsScreen(
                                                    services = services,
                                                    onOpen = { navigator.navigate(SettingsPageRoute(it)) },
                                                    onNavigate = navigator::navigate,
                                                    onClose = {
                                                        dismissed++
                                                        opened = false
                                                    },
                                                )
                                            }
                                        }
                                    }
                                },
                            ) { paneMetadata(it) }
                        }
                    }
                }
            }
        }

        rule.waitForIdle()
        rule.onNodeWithTag("settings-root-page").assertIsDisplayed()
        rule.onNodeWithTag("settings-search").assertIsDisplayed()

        // 1. Root Settings: large downward swipes at the top do not dismiss the sheet.
        rule.onNodeWithTag("settings-root-page").performTouchInput {
            swipeDown(startY = top + height * 0.15f, endY = bottom - height * 0.08f, durationMillis = 120)
        }
        rule.waitForIdle()
        rule.onNodeWithTag("settings-root-page").performTouchInput {
            swipeDown(startY = top + 12f, endY = bottom - 12f, durationMillis = 240)
        }
        rule.waitForIdle()
        assertTrue("Root downward swipe must not close Settings in $style", opened)
        assertEquals(0, dismissed)
        assertEquals(listOf(SettingsRoute), stack.toList())
        rule.onNodeWithTag("settings-root-page").assertIsDisplayed()

        // 2. Navigate to a first-level subpage and verify top downward swipe does not dismiss or pop.
        rule.runOnIdle { navigator.navigate(SettingsPageRoute(SettingsPage.Connectors)) }
        rule.waitForIdle()
        rule.onNodeWithTag("settings-subpage-Connectors").assertIsDisplayed()
        rule.onNodeWithTag("settings-subpage-Connectors").performTouchInput {
            swipeDown(startY = top + height * 0.15f, endY = bottom - height * 0.08f, durationMillis = 120)
        }
        rule.waitForIdle()
        assertTrue("Subpage downward swipe must not close Settings in $style", opened)
        assertEquals(0, dismissed)
        assertEquals(listOf(SettingsRoute, SettingsPageRoute(SettingsPage.Connectors)), stack.toList())

        // 3. Navigate to a second-level subpage; downward swipe does not close, while top Back pops one level.
        rule.runOnIdle { navigator.navigate(SettingsPageRoute(SettingsPage.About)) }
        rule.waitForIdle()
        rule.onNodeWithTag("settings-subpage-About").assertIsDisplayed()
        rule.onNodeWithTag("settings-subpage-About").performTouchInput {
            swipeDown(startY = top + height * 0.15f, endY = bottom - height * 0.08f, durationMillis = 120)
        }
        rule.waitForIdle()
        assertTrue("Nested subpage downward swipe must not close Settings in $style", opened)
        assertEquals(0, dismissed)
        assertEquals(
            listOf(SettingsRoute, SettingsPageRoute(SettingsPage.Connectors), SettingsPageRoute(SettingsPage.About)),
            stack.toList(),
        )

        val backButton = rule.onNodeWithContentDescription("Back")
        val backBounds = backButton.getUnclippedBoundsInRoot()
        assertTrue(backBounds.right - backBounds.left >= Tokens.MinTouch.android)
        assertTrue(backBounds.bottom - backBounds.top >= Tokens.MinTouch.android)
        backButton.performClick()
        rule.waitForIdle()
        assertTrue(opened)
        assertEquals(0, dismissed)
        assertEquals(listOf(SettingsRoute, SettingsPageRoute(SettingsPage.Connectors)), stack.toList())
        rule.onNodeWithTag("settings-subpage-Connectors").assertIsDisplayed()

        // 4. System Back on a subpage pops only the subpage; system Back on root exits Settings.
        rule.runOnIdle { ShadowDialog.getLatestDialog().onBackPressed() }
        rule.waitForIdle()
        assertTrue("System Back on subpage must return to root in $style", opened)
        assertEquals(0, dismissed)
        assertEquals(listOf(SettingsRoute), stack.toList())
        rule.onNodeWithTag("settings-root-page").assertIsDisplayed()

        rule.runOnIdle { ShadowDialog.getLatestDialog().onBackPressed() }
        rule.waitForIdle()
        assertFalse("System Back on root must close Settings in $style", opened)
        assertEquals(1, dismissed)

        // 5. Reopen Settings on root and verify top Close button exits cleanly with >= 48dp touch target.
        opened = true
        rule.waitForIdle()
        rule.onNodeWithTag("settings-root-page").assertIsDisplayed()
        val closeButton = rule.onNodeWithContentDescription("Close")
        val closeBounds = closeButton.getUnclippedBoundsInRoot()
        assertTrue(closeBounds.right - closeBounds.left >= Tokens.MinTouch.android)
        assertTrue(closeBounds.bottom - closeBounds.top >= Tokens.MinTouch.android)
        closeButton.performClick()
        rule.waitForIdle()
        assertFalse("Top Close button must close Settings in $style", opened)
        assertEquals(2, dismissed)
    }
}
