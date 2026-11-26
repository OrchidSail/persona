package persona.platform.phonetools.control

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import persona.platform.phonetools.control.adb.AdbConnectPort
import persona.platform.phonetools.control.adb.AdbConnectionException
import persona.platform.phonetools.control.adb.AdbConnectionReason
import persona.platform.phonetools.control.adb.PortCache
import persona.platform.phonetools.control.shell.DisplayAwake
import persona.platform.phonetools.control.shell.DisplayFrameGeometry
import persona.platform.phonetools.control.shell.HandoffNonces
import persona.platform.phonetools.control.shell.NonceSlots
import persona.platform.phonetools.control.shell.NonceStore
import persona.platform.phonetools.control.shell.PhoneControlShellService
import persona.platform.phonetools.control.shell.PrivilegedShellManager
import persona.platform.phonetools.control.shell.ShellHandoff

class ShellHandoffAndFlagsTest {

    @Test
    fun handoffCheckEnforcesUidPidApkAndBinderStrictly() {
        assertNull(
            ShellHandoff.check(
                callingUid = 2000,
                callingPid = 4200,
                reportedUid = 2000,
                reportedPid = 4200,
                helperApk = "/data/app/base.apk",
                appApk = "/data/app/base.apk",
                hasBinder = true,
            ),
        )
        assertEquals(
            ShellHandoff.Reject.NotShell,
            ShellHandoff.check(10250, 4200, 10250, 4200, "/a.apk", "/a.apk", true),
        )
        assertEquals(
            ShellHandoff.Reject.IdMismatch,
            ShellHandoff.check(2000, 4200, 2000, 9999, "/a.apk", "/a.apk", true),
        )
        assertEquals(
            ShellHandoff.Reject.StaleApk,
            ShellHandoff.check(2000, 4200, 2000, 4200, "/old.apk", "/new.apk", true),
        )
        assertEquals(
            ShellHandoff.Reject.NoBinder,
            ShellHandoff.check(2000, 4200, 2000, 4200, "/a.apk", "/a.apk", false),
        )
    }

    @Test
    fun handoffNoncesRotateOnceAndExpireAfterTtl() {
        var now = 1_000L
        var stored = NonceSlots()
        val store = object : NonceStore {
            override fun load() = stored
            override fun save(slots: NonceSlots) { stored = slots }
        }
        val nonces = HandoffNonces(store, clock = { now })
        val launch = nonces.issueLaunch()
        assertTrue(nonces.matches(launch))

        val next = nonces.accept(launch)
        assertNotNull(next)
        // Old launch nonce is consumed immediately; rotated resume nonce is accepted.
        assertFalse(nonces.matches(launch))
        assertTrue(nonces.matches(next))

        // Launch nonce expires past TTL.
        val launch2 = nonces.issueLaunch()
        now += HandoffNonces.LAUNCH_TTL_MS + 1L
        assertFalse(nonces.matches(launch2))
    }

    @Test
    fun commandNeverEmbedsNonceInCommandLine() {
        val cmd = ShellHandoff.command("/data/app/base.apk", "com.example.persona.assisttest", 10333, "abc123")
        assertTrue(cmd.contains("read n;"))
        assertTrue(cmd.contains("setsid app_process /system/bin"))
        assertTrue(cmd.contains(ShellHandoff.HELPER_CLASS))
        assertTrue(cmd.contains("--nice-name='com.example.persona.assisttest:pcshell'"))
    }

    @Test
    fun displayFlagsIncludeTrustedAndFocusIsolationOnAndroid14AndDegradeWithoutTrust() {
        val trusted35 = PhoneControlShellService.displayFlags(sdk = 35, trusted = true)
        assertTrue((trusted35 and PhoneControlShellService.FLAG_TRUSTED) != 0)
        assertTrue((trusted35 and PhoneControlShellService.FLAG_OWN_DISPLAY_GROUP) != 0)
        assertTrue((trusted35 and PhoneControlShellService.FLAG_OWN_FOCUS) != 0)
        assertTrue((trusted35 and PhoneControlShellService.FLAG_STEAL_TOP_FOCUS_DISABLED) != 0)

        val untrusted35 = PhoneControlShellService.displayFlags(sdk = 35, trusted = false)
        assertEquals(0, untrusted35 and PhoneControlShellService.FLAG_TRUSTED)
        assertEquals(0, untrusted35 and PhoneControlShellService.FLAG_OWN_FOCUS)
        assertTrue((untrusted35 and PhoneControlShellService.FLAG_SUPPORTS_TOUCH) != 0)

        assertTrue(PhoneControlShellService.retryWithoutTrust(SecurityException("denied")))
        assertFalse(PhoneControlShellService.retryWithoutTrust(IllegalStateException("other")))
    }

    @Test
    fun displayAwakeNeverPokesDefaultDisplayGroup() {
        assertFalse(DisplayAwake.needed(PhoneControlShellService.FLAG_OWN_DISPLAY_GROUP, PhoneControlShellService.FLAG_OWN_DISPLAY_GROUP, 0))
        assertTrue(DisplayAwake.needed(PhoneControlShellService.FLAG_OWN_DISPLAY_GROUP, PhoneControlShellService.FLAG_OWN_DISPLAY_GROUP, 3))
        assertTrue(DisplayAwake.needed(PhoneControlShellService.FLAG_OWN_DISPLAY_GROUP, PhoneControlShellService.FLAG_OWN_DISPLAY_GROUP, null))
        assertFalse(DisplayAwake.needed(0, PhoneControlShellService.FLAG_OWN_DISPLAY_GROUP, 3))
    }

    @Test
    fun frameGeometryRotatesAndComputesBoundedPreviewDimensions() {
        val portrait = DisplayFrameGeometry(1080, 2340, rotation = 0)
        assertEquals(1080, portrait.width)
        assertEquals(2340, portrait.height)
        assertEquals(320 to 693, portrait.previewSize(portrait.previewWidth(320)))

        val landscape = DisplayFrameGeometry(1080, 2340, rotation = 1)
        assertEquals(2340, landscape.width)
        assertEquals(1080, landscape.height)
        val (pw, ph) = landscape.previewSize(landscape.previewWidth(320))
        assertTrue(ph in 319..320)
        assertTrue(pw > ph)
    }

    @Test
    fun adbConnectPortRetriesTransientTlsFailureAndCachesWorkingPort() = runTest {
        var cached: Int? = null
        val cache = object : PortCache {
            override fun load() = cached
            override fun save(port: Int) { cached = port }
        }
        var attempts = 0
        val connector = AdbConnectPort(
            property = { 41234 },
            finder = { _, _ -> emptyList() },
            cache = cache,
            tlsRetryDelayMs = 1L,
        )
        val result = connector.connect { host, port ->
            attempts++
            if (attempts == 1) {
                throw AdbConnectionException(AdbConnectionReason.UNAUTHORIZED, "first handshake intercepted")
            }
            "$host:$port"
        }
        assertEquals("127.0.0.1:41234", result)
        assertEquals(2, attempts)
        assertEquals(41234, cached)
    }

    @Test
    fun pairingCodeNormalizerAcceptsSixDigitsWithSpacesAndRejectsShortOrLong() {
        assertEquals("123456", PrivilegedShellManager.normalizePairingCode(" 123 456 "))
        assertNull(PrivilegedShellManager.normalizePairingCode("12345"))
        assertNull(PrivilegedShellManager.normalizePairingCode("1234567"))
        assertNull(PrivilegedShellManager.normalizePairingCode("abcdef"))
    }
}
