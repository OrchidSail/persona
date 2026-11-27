package persona.platform.phonetools.control

import android.app.Application
import android.os.Bundle
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import persona.platform.phonetools.control.shell.IPhoneControlShell
import persona.platform.phonetools.control.shell.PrivilegedBackendKind
import persona.platform.phonetools.control.shell.PrivilegedShellHub
import persona.platform.phonetools.control.shell.ShellHandoff

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PrivilegedShellLoopbackHubTest {

    private class FakeShellBinder(private val uid: Int, private val pid: Int) : IPhoneControlShell.Stub() {
        override fun createDisplay(surface: Surface?, width: Int, height: Int, dpi: Int): Int = -1
        override fun releaseDisplay(displayId: Int) = Unit
        override fun startApp(displayId: Int, packageName: String?): Int = 0
        override fun topTaskId(displayId: Int): Int = -1
        override fun topTaskPackage(displayId: Int): String? = null
        override fun moveTask(taskId: Int, displayId: Int): Boolean = false
        override fun injectTap(displayId: Int, x: Int, y: Int): Boolean = false
        override fun injectStroke(displayId: Int, xs: IntArray?, ys: IntArray?, times: LongArray?): Boolean = false
        override fun injectKey(displayId: Int, keyCode: Int): Boolean = false
        override fun identity(): IntArray = intArrayOf(uid, pid)
        override fun releaseAll() = Unit
        override fun destroy() = Unit
        override fun captureMainForegroundTask(): String? = null
        override fun restoreMainTask(taskId: Int): Boolean = false
        override fun setDisplaySurface(displayId: Int, surface: Surface?): Boolean = false
        override fun injectMotionEvent(
            displayId: Int,
            action: Int,
            downTime: Long,
            eventTime: Long,
            x: Float,
            y: Float,
            pressure: Float,
        ): Boolean = false
    }

    @Test
    fun hubRecordsLoopbackTcpBackendOnLaunchPreservesAcrossResumeAndSerializesToDeviceState() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val hub = PrivilegedShellHub(app)
        hub.forget()

        val launchNonce = hub.issueLaunchNonce(PrivilegedBackendKind.BuiltinLoopbackTcpAdb)
        val binder = FakeShellBinder(uid = ShellHandoff.SHELL_UID, pid = 4321)
        val launchBundle = Bundle().apply {
            putBinder(ShellHandoff.KEY_BINDER, binder)
            putInt(ShellHandoff.KEY_UID, ShellHandoff.SHELL_UID)
            putInt(ShellHandoff.KEY_PID, 4321)
            putString(ShellHandoff.KEY_APK, app.applicationInfo.sourceDir)
            putString(ShellHandoff.KEY_NONCE, launchNonce)
        }
        val reply = hub.offer(launchBundle, callingUid = ShellHandoff.SHELL_UID, callingPid = 4321)
        assertTrue(reply.getBoolean(ShellHandoff.KEY_ACCEPTED))
        val resumeNonce = reply.getString(ShellHandoff.KEY_NEXT)
        assertNotNull(resumeNonce)
        assertEquals(PrivilegedBackendKind.BuiltinLoopbackTcpAdb, hub.active.value?.backend)

        // Verify device_state wire JSON cleanly advertises builtin_loopback_tcp_adb.
        val snap = DeviceControlSnapshot(
            masterEnabled = true,
            interactive = true,
            keyguardShowing = false,
            deviceLocked = false,
            secure = true,
            helperAvailable = true,
            userUnlocked = true,
            virtualDisplayBackend = hub.active.value!!.backend.wireName,
            virtualDisplayReady = true,
            wirelessAdbPaired = true,
            shizukuReady = false,
        )
        val devJson = snap.toDeviceJson()
        assertEquals("builtin_loopback_tcp_adb", devJson["virtual_display_backend"]?.jsonPrimitive?.content)
        assertTrue(devJson["virtual_display_ready"]?.jsonPrimitive?.boolean == true)

        // Simulate a new app process instance resuming the already-running uid-2000 helper with the resume nonce.
        val resumedHub = PrivilegedShellHub(app)
        val resumeBundle = Bundle().apply {
            putBinder(ShellHandoff.KEY_BINDER, binder)
            putInt(ShellHandoff.KEY_UID, ShellHandoff.SHELL_UID)
            putInt(ShellHandoff.KEY_PID, 4321)
            putString(ShellHandoff.KEY_APK, app.applicationInfo.sourceDir)
            putString(ShellHandoff.KEY_NONCE, resumeNonce)
        }
        val resumeReply = resumedHub.offer(resumeBundle, callingUid = ShellHandoff.SHELL_UID, callingPid = 4321)
        assertTrue(resumeReply.getBoolean(ShellHandoff.KEY_ACCEPTED))
        assertEquals(PrivilegedBackendKind.BuiltinLoopbackTcpAdb, resumedHub.active.value?.backend)

        resumedHub.forget()
    }
}
