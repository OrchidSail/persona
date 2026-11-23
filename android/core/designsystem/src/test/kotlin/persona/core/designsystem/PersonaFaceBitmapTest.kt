package persona.core.designsystem

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PersonaFaceBitmapTest {
    private fun pixelHash(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.contentHashCode()
    }

    @Test
    fun rendersAllStatesInLightAndDarkWithDistinctOutput() {
        val ringLight = SystemSurfaces.palette(AppStyle.Glass, dark = false).needsYou.dot
        val ringDark = SystemSurfaces.palette(AppStyle.Glass, dark = true).needsYou.dot

        for (dark in listOf(false, true)) {
            val ring = if (dark) ringDark else ringLight
            val hashes = PersonaState.entries.associateWith { state ->
                val bmp = renderPersonaFaceBitmap(state = state, sizePx = 84, dark = dark, needsYouRing = ring, density = 3f, compact = true)
                assertEquals(84, bmp.width)
                assertEquals(84, bmp.height)
                assertEquals(Bitmap.Config.ARGB_8888, bmp.config)
                assertFalse(bmp.isRecycled)
                pixelHash(bmp)
            }
            // Idle, Thinking (thought bubble), NeedsYou (outer ring), and Offline (muted disc) should be visually distinct.
            assertNotEquals("Idle vs Thinking (dark=$dark)", hashes[PersonaState.Idle], hashes[PersonaState.Thinking])
            assertNotEquals("Idle vs NeedsYou (dark=$dark)", hashes[PersonaState.Idle], hashes[PersonaState.NeedsYou])
            assertNotEquals("Idle vs Working (dark=$dark)", hashes[PersonaState.Idle], hashes[PersonaState.Working])
            assertNotEquals("Idle vs Offline (dark=$dark)", hashes[PersonaState.Idle], hashes[PersonaState.Offline])
        }

        // Cached helper returns a valid bitmap of the requested size.
        val cached = personaFaceBitmap(PersonaState.Thinking, sizePx = 72, dark = false, needsYouRing = ringLight)
        assertEquals(72, cached.width)
        assertTrue(cached === personaFaceBitmap(PersonaState.Thinking, sizePx = 72, dark = false, needsYouRing = ringLight))
    }
}
