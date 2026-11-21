package persona.android.assist

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import persona.android.MainActivity
import persona.core.navigation.AskPersonaLink
import persona.feature.chat.AssistDraftBus

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AssistActivityTest {
    @After
    fun cleanUp() {
        AssistDraftBus.clear()
    }

    @Test
    fun actionAssistExtractsSafeContextLaunchesMainComposerAndFinishes() {
        val intent = Intent(Intent.ACTION_ASSIST).apply {
            putExtra(Intent.EXTRA_ASSIST_PACKAGE, "com.android.chrome")
            putExtra(Intent.EXTRA_TITLE, "Release Notes")
        }
        val controller = Robolectric.buildActivity(AssistActivity::class.java, intent).create()
        val activity = controller.get()

        assertTrue(activity.isFinishing)
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(MainActivity::class.java.name, started.component?.className)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(AskPersonaLink, started.dataString)

        val pending = AssistDraftBus.current()
        assertNotNull(pending)
        assertNull(pending!!.screenshot)
        assertEquals("Release Notes", pending.context?.title)
        assertTrue(pending.context?.text.orEmpty().contains("com.android.chrome"))
    }

    @Test
    fun actionAssistWithoutExtrasStillLaunchesMainComposerCleanly() {
        val controller = Robolectric.buildActivity(AssistActivity::class.java, Intent(Intent.ACTION_ASSIST)).create()
        val activity = controller.get()

        assertTrue(activity.isFinishing)
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(AskPersonaLink, started.dataString)
        assertNull(AssistDraftBus.current())
    }
}
