package persona.feature.settings

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import persona.core.contracts.ServerEvent
import persona.core.contracts.api.ConnectionState
import persona.core.contracts.api.ExtensionUiContext
import persona.core.contracts.api.ExtensionUiResult
import persona.core.contracts.api.ExtensionView
import persona.core.contracts.api.ExtensionViewAction
import persona.core.contracts.api.ExtensionViewItem
import persona.core.contracts.api.ExtensionViewSection
import persona.core.contracts.api.HubConnection
import persona.core.contracts.api.HubConnector
import persona.core.contracts.api.MailAccount
import persona.core.designsystem.AppStyle
import persona.core.designsystem.PersonaTheme
import persona.core.navigation.AppTab
import persona.core.navigation.ConnectorRoute
import persona.core.navigation.ExtensionUiRoute
import persona.core.navigation.Navigator
import persona.core.navigation.SettingsPage
import persona.core.navigation.SettingsPageRoute

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h915dp")
class ExtensionConnectorDedupAndPhoneLinkTest {
    @get:Rule val rule = createComposeRule()

    private class OnlineConnection : HubConnection {
        override val state = MutableStateFlow(ConnectionState.Online)
        override val events = MutableSharedFlow<ServerEvent>()
        override fun reconnect() = Unit
        override fun send(message: JsonObject) = false
    }

    @Test
    fun connectorsPageDeduplicatesExtensionToolsOnlyRowsAcrossAllStylesAndThemes() {
        val connectorsApi = object : ConnectorsApiStub() {
            override suspend fun mailAccounts(): List<MailAccount> = emptyList()
            override suspend fun connectors(all: Boolean): List<HubConnector> = listOf(
                HubConnector(
                    id = "personaphone",
                    name = "Phone Control Extension",
                    status = "ok",
                    extensionId = "persona-phone",
                    capabilities = listOf("status", "tools", "actions"),
                ),
                HubConnector(
                    id = "whatsapp",
                    name = "WhatsApp",
                    status = "not_signed_in",
                    extensionId = "whatsapp",
                    capabilities = listOf("status", "tools", "actions", "login", "inbox"),
                ),
                HubConnector(
                    id = "wechat",
                    name = "WeChat",
                    status = "ok",
                    extensionId = "wechat",
                    capabilities = listOf("status", "login"),
                ),
                HubConnector(
                    id = "github",
                    name = "GitHub",
                    status = "ok",
                    capabilities = listOf("status", "login", "inbox", "archive"),
                ),
            )
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val services = demoSettingsServices(context.resources).copy(
            demo = false,
            connection = OnlineConnection(),
            connectors = connectorsApi,
        )
        val navigated = mutableListOf<NavKey>()
        var style by mutableStateOf(AppStyle.Glass)
        var dark by mutableStateOf(false)

        rule.setContent {
            PersonaTheme(style = style, darkTheme = dark, reduceMotion = true) {
                SettingsScreen(services, initialPage = "connectors", onNavigate = { navigated += it })
            }
        }
        rule.waitUntil { rule.onAllNodesWithText("WhatsApp").fetchSemanticsNodes().isNotEmpty() }

        for (s in AppStyle.entries) {
            for (d in listOf(false, true)) {
                rule.runOnIdle { style = s; dark = d }
                rule.waitForIdle()
                rule.onAllNodesWithText("Phone Control Extension").assertCountEquals(0)
                rule.onAllNodesWithText("WhatsApp").assertCountEquals(1)
                rule.onAllNodesWithText("WeChat").assertCountEquals(1)
                rule.onAllNodesWithText("GitHub").assertCountEquals(1)
            }
        }
        rule.onNodeWithText("WhatsApp").performClick()
        rule.waitForIdle()
        assertEquals(listOf(ConnectorRoute("whatsapp", null)), navigated)
    }

    @Test
    fun extensionUiPageOpensWhitelistedPhoneSettingsAcrossAllStylesAndBlocksUnauthorizedUrls() {
        val openPhoneLabel = "Manage Local Safety"
        val openBadLabel = "Unauthorized Link"
        val api = object : ExtensionApiStub() {
            override suspend fun renderUi(
                extension: String,
                ui: String,
                context: ExtensionUiContext?,
                state: String?,
            ): ExtensionView = ExtensionView(
                title = "Phone Control",
                sections = listOf(
                    ExtensionViewSection(
                        items = listOf(
                            ExtensionViewItem("button", title = openPhoneLabel, action = ExtensionViewAction("open_phone")),
                            ExtensionViewItem("button", title = openBadLabel, action = ExtensionViewAction("open_bad")),
                        ),
                    ),
                ),
            )

            override suspend fun uiAction(
                extension: String,
                ui: String,
                action: String,
                context: ExtensionUiContext?,
                state: String?,
                values: Map<String, String>?,
            ): ExtensionUiResult = when (action) {
                "open_phone" -> ExtensionUiResult(openUrl = "persona://settings/connectors/phone")
                else -> ExtensionUiResult(openUrl = "persona://settings/connectors/mail")
            }
        }
        var phoneOpens = 0
        val openedExternal = mutableListOf<String>()
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) { openedExternal += uri }
        }
        var style by mutableStateOf(AppStyle.Glass)

        rule.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                PersonaTheme(style = style, reduceMotion = true) {
                    ExtensionUiPage(
                        extensions = api,
                        extensionId = "persona-phone",
                        uiId = "persona-phone",
                        title = "Phone Control",
                        onBack = {},
                        onPhoneSettings = { phoneOpens++ },
                    )
                }
            }
        }
        rule.waitUntil { rule.onAllNodesWithText(openPhoneLabel).fetchSemanticsNodes().isNotEmpty() }

        for ((index, s) in AppStyle.entries.withIndex()) {
            rule.runOnIdle { style = s }
            rule.waitForIdle()
            rule.onNodeWithText(openPhoneLabel).performClick()
            rule.waitUntil { phoneOpens == index + 1 }
        }
        rule.onNodeWithText(openBadLabel).performClick()
        rule.waitForIdle()
        assertEquals(AppStyle.entries.size, phoneOpens)
        assertTrue("Unauthorized persona:// link must never open externally", openedExternal.isEmpty())
    }

    @Test
    fun settingsDetailEntriesRoutesPhoneSettingsThroughHostCallbackAndFallback() {
        val openPhoneLabel = "Open Local Phone"
        val api = object : ExtensionApiStub() {
            override suspend fun renderUi(
                extension: String,
                ui: String,
                context: ExtensionUiContext?,
                state: String?,
            ): ExtensionView = ExtensionView(
                title = "Phone Control",
                sections = listOf(
                    ExtensionViewSection(
                        items = listOf(
                            ExtensionViewItem("button", title = openPhoneLabel, action = ExtensionViewAction("open_phone")),
                        ),
                    ),
                ),
            )

            override suspend fun uiAction(
                extension: String,
                ui: String,
                action: String,
                context: ExtensionUiContext?,
                state: String?,
                values: Map<String, String>?,
            ): ExtensionUiResult = ExtensionUiResult(openUrl = "persona://settings/connectors/phone")
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val services = demoSettingsServices(context.resources).copy(demo = false, extensions = api)
        val navigated = mutableListOf<NavKey>()
        val nav = object : Navigator {
            override fun navigate(key: NavKey) { navigated += key }
            override fun back() = Unit
            override fun selectTab(tab: AppTab) = Unit
        }
        var customPhoneCalls = 0
        val route = ExtensionUiRoute("persona-phone", "persona-phone", "Phone Control")
        val customProvider = entryProvider<NavKey> {
            settingsDetailEntries(nav, services, openPhoneSettings = { customPhoneCalls++; it.navigate(SettingsPageRoute(SettingsPage.PhoneData)) })
        }

        rule.setContent {
            PersonaTheme(reduceMotion = true) {
                customProvider(route).Content()
            }
        }
        rule.waitUntil { rule.onAllNodesWithText(openPhoneLabel).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(openPhoneLabel).performClick()
        rule.waitUntil { customPhoneCalls == 1 }
        assertEquals(listOf(SettingsPageRoute(SettingsPage.PhoneData)), navigated)
    }
}
