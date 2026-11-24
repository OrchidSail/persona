package persona.platform.phonetools.control

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import persona.core.contracts.HubScope
import persona.core.contracts.api.ConnectionState
import persona.core.contracts.api.HubConnection
import persona.core.contracts.api.PhoneToolCall
import persona.core.contracts.api.PhoneToolResult
import persona.core.contracts.api.PhoneToolSpec
import persona.core.contracts.api.PhoneToolsTransport
import persona.core.designsystem.AppStyle
import persona.core.designsystem.PersonaTheme
import persona.core.navigation.AppTab
import persona.core.navigation.Navigator
import persona.platform.phonetools.PhoneToolsRuntime
import persona.platform.phonetools.PhoneToolsScreen
import persona.platform.phonetools.R
import java.lang.reflect.Proxy
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-w400dp-h1800dp-xhdpi")
class PhoneControlSettingsUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() {
        PhoneControlGateState.resetForShutdown()
        scope.cancel()
    }

    @Test
    fun masterSwitchDefaultsOffAndRevealsThreePermissionsAndTwoSafetyGatesWhenEnabled() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val connection = Proxy.newProxyInstance(HubConnection::class.java.classLoader, arrayOf(HubConnection::class.java)) { _, method, _ ->
            when (method.name) {
                "getState" -> MutableStateFlow(ConnectionState.WaitingForNetwork)
                "getEvents" -> emptyFlow<Any>()
                else -> error("unexpected: ${method.name}")
            }
        } as HubConnection
        val transport = object : PhoneToolsTransport {
            override val calls = emptyFlow<PhoneToolCall>()
            override fun discardPending() = Unit
            override fun register(deviceId: String, deviceName: String, simulator: Boolean, tools: List<PhoneToolSpec>) = true
            override fun result(callId: String, result: PhoneToolResult) = true
        }
        val runtime = PhoneToolsRuntime(app, HubScope("hub-ui-${UUID.randomUUID()}", "one"), "dev-ui", connection, transport, scope)
        val navigator = object : Navigator {
            override fun navigate(key: NavKey) = Unit
            override fun back() = Unit
            override fun selectTab(tab: AppTab) = Unit
        }

        rule.setContent {
            PersonaTheme(darkTheme = false, reduceMotion = true, style = AppStyle.Glass) {
                PhoneToolsScreen(runtime, navigator)
            }
        }
        rule.waitForIdle()

        val masterTitle = app.getString(R.string.phonetools_control_title)
        val a11yTitle = app.getString(R.string.phonetools_control_perm_a11y_title)
        val notifTitle = app.getString(R.string.phonetools_control_perm_notif_title)
        val overlayTitle = app.getString(R.string.phonetools_control_perm_overlay_title)
        val otpTitle = app.getString(R.string.phonetools_control_gate_otp_title)
        val payTitle = app.getString(R.string.phonetools_control_gate_payment_title)

        // Default OFF: permission guides and safety gate toggles are hidden.
        rule.onNodeWithText(masterTitle).assertExists().assertIsOff()
        rule.onNodeWithText(a11yTitle).assertDoesNotExist()
        rule.onNodeWithText(payTitle).assertDoesNotExist()

        // Toggle master switch ON -> shows 3 permission guides + 2 safety gates (both ON by default).
        rule.onNodeWithText(masterTitle).performClick()
        rule.waitForIdle()
        assertTrue(runtime.settings.remoteControlEnabled.value)
        rule.onNodeWithText(masterTitle).assertIsOn()
        rule.onNodeWithText(a11yTitle).assertExists()
        rule.onNodeWithText(notifTitle).assertExists()
        rule.onNodeWithText(overlayTitle).assertExists()
        rule.onNodeWithText(otpTitle).assertExists().assertIsOn()
        rule.onNodeWithText(payTitle).assertExists().assertIsOn()

        // Payment block gate is toggleable anytime.
        rule.onNodeWithText(payTitle).performClick()
        rule.waitForIdle()
        assertFalse(runtime.settings.blockRealPayment.value)
        rule.onNodeWithText(payTitle).assertIsOff()

        runtime.close()
    }
}
