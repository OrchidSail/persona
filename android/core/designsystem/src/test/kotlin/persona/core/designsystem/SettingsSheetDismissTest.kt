package persona.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import persona.core.designsystem.icons.PersonaIcons

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w400dp-h800dp-xhdpi")
class SettingsSheetDismissTest {
    @get:Rule val rule = createComposeRule()

    @Test fun compactGlassSettingsSheetIgnoresTopDownwardSwipe() {
        assertSettingsSheetSwipeIgnoredAndExplicitExitsWork(style = AppStyle.Glass, largeWindow = false)
    }

    @Test fun compactBriefSettingsSheetIgnoresTopDownwardSwipe() {
        assertSettingsSheetSwipeIgnoredAndExplicitExitsWork(style = AppStyle.Brief, largeWindow = false)
    }

    @Test fun compactBoardSettingsSheetIgnoresTopDownwardSwipe() {
        assertSettingsSheetSwipeIgnoredAndExplicitExitsWork(style = AppStyle.Board, largeWindow = false)
    }

    @Config(sdk = [35], qualifiers = "en-w840dp-h900dp-xhdpi")
    @Test fun pageSizedGlassSettingsSheetIgnoresTopDownwardSwipe() {
        assertSettingsSheetSwipeIgnoredAndExplicitExitsWork(style = AppStyle.Glass, largeWindow = true)
    }

    @Config(sdk = [35], qualifiers = "en-w840dp-h900dp-xhdpi")
    @Test fun pageSizedBriefSettingsSheetIgnoresTopDownwardSwipe() {
        assertSettingsSheetSwipeIgnoredAndExplicitExitsWork(style = AppStyle.Brief, largeWindow = true)
    }

    @Config(sdk = [35], qualifiers = "en-w840dp-h900dp-xhdpi")
    @Test fun pageSizedBoardSettingsSheetIgnoresTopDownwardSwipe() {
        assertSettingsSheetSwipeIgnoredAndExplicitExitsWork(style = AppStyle.Board, largeWindow = true)
    }

    @Test fun genericJSheetRetainsDownwardSwipeDismiss() {
        var opened by mutableStateOf(true)
        var dismissed = 0
        rule.setContent {
            PersonaTheme(darkTheme = false, reduceMotion = true, style = AppStyle.Glass) {
                if (opened) {
                    JSheet(onDismiss = {
                        dismissed++
                        opened = false
                    }) {
                        Box(Modifier.fillMaxWidth().height(Tokens.Size.box280).testTag("generic-sheet-body"))
                    }
                }
            }
        }
        rule.onNodeWithTag("generic-sheet-body").assertIsDisplayed()
        rule.onNodeWithTag("generic-sheet-body").performTouchInput {
            swipeDown(startY = top + height * 0.1f, endY = bottom - height * 0.05f, durationMillis = 120)
        }
        rule.waitForIdle()
        assertEquals(1, dismissed)
        assertFalse(opened)
        rule.onNodeWithTag("generic-sheet-body").assertDoesNotExist()
    }

    private fun assertSettingsSheetSwipeIgnoredAndExplicitExitsWork(style: AppStyle, largeWindow: Boolean) {
        var opened by mutableStateOf(true)
        var dismissed = 0
        var listIndex = 0
        rule.setContent {
            CompositionLocalProvider(LocalLargeWindowInput provides largeWindow) {
                PersonaTheme(darkTheme = false, reduceMotion = true, style = style) {
                    if (opened) {
                        JSettingsSheet(onDismiss = {
                            dismissed++
                            opened = false
                        }) {
                            val listState = rememberLazyListState()
                            listIndex = listState.firstVisibleItemIndex
                            val closeLabel = stringResource(R.string.designsystem_close)
                            JListPage(
                                title = "Settings",
                                modifier = Modifier.fillMaxSize().testTag("settings-list-page"),
                                state = listState,
                                actions = {
                                    JIconButton(
                                        icon = PersonaIcons.close,
                                        contentDescription = closeLabel,
                                        onClick = {
                                            dismissed++
                                            opened = false
                                        },
                                        modifier = Modifier.testTag("settings-close-button"),
                                        raised = true,
                                    )
                                },
                            ) {
                                items(24) { index ->
                                    JGroupItem(GroupPosition.of(index, 24)) {
                                        JRow(title = "Option $index", onClick = {})
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Option 0").assertIsDisplayed()

        // Swipe down aggressively from the top of the list and from the top bar area; neither may dismiss Settings.
        rule.onNodeWithTag("settings-list-page").performTouchInput {
            swipeDown(startY = top + height * 0.15f, endY = bottom - height * 0.08f, durationMillis = 120)
        }
        rule.waitForIdle()
        rule.onNodeWithTag("settings-list-page").performTouchInput {
            swipeDown(startY = top + 12f, endY = bottom - 12f, durationMillis = 250)
        }
        rule.waitForIdle()
        assertEquals("Downward swipe must not dismiss JSettingsSheet in $style", 0, dismissed)
        assertTrue(opened)
        rule.onNodeWithText("Option 0").assertIsDisplayed()

        // List scrolling stays smooth and responsive after the ignored top swipe.
        rule.onNodeWithTag("settings-list-page").performTouchInput {
            swipeUp(startY = bottom - height * 0.15f, endY = top + height * 0.15f, durationMillis = 200)
        }
        rule.waitForIdle()
        assertTrue("Settings list should still scroll in $style", listIndex > 0)
        rule.onNodeWithTag("settings-list-page").performTouchInput {
            swipeDown(startY = top + height * 0.2f, endY = bottom - height * 0.1f, durationMillis = 150)
            swipeDown(startY = top + height * 0.2f, endY = bottom - height * 0.1f, durationMillis = 150)
        }
        rule.waitForIdle()
        assertEquals("Swiping past top after scrolling must not dismiss JSettingsSheet in $style", 0, dismissed)
        assertTrue(opened)

        // Top Close button keeps at least the 48dp touch target and reliably dismisses Settings.
        val closeButton = rule.onNodeWithTag("settings-close-button")
        closeButton.assertContentDescriptionEquals("Close")
        val closeBounds = closeButton.getUnclippedBoundsInRoot()
        assertTrue(closeBounds.right - closeBounds.left >= Tokens.MinTouch.android)
        assertTrue(closeBounds.bottom - closeBounds.top >= Tokens.MinTouch.android)
        closeButton.performClick()
        rule.waitForIdle()
        assertEquals(1, dismissed)
        assertFalse(opened)
        rule.onNodeWithTag("settings-list-page").assertDoesNotExist()

        // Reopen and verify Android system Back dismisses Settings cleanly.
        opened = true
        rule.waitForIdle()
        rule.onNodeWithTag("settings-list-page").assertIsDisplayed()
        rule.runOnIdle { ShadowDialog.getLatestDialog().onBackPressed() }
        rule.waitForIdle()
        assertEquals("System Back must dismiss JSettingsSheet in $style", 2, dismissed)
        assertFalse(opened)
        rule.onNodeWithTag("settings-list-page").assertDoesNotExist()
    }
}
