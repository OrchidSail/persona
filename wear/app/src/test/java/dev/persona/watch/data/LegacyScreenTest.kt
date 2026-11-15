package dev.persona.watch.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LegacyScreenTest {
    @Test fun onlyTheLastContiguousNumberedBlockIsSelectable() {
        val options = WatchParser.rawScreenOptions("1. Older\n2. Old option\n---\n❯ 1. Current\n2. Another")
        assertEquals(listOf("Current", "Another"), options.map { it.label })
        assertTrue(options.first().checked)
        assertTrue(WatchParser.rawScreenOptions("1. One\n3. Gap").isEmpty())
    }
    @Test fun numericLegacyChoiceUsesKeysWhileStructuredChoiceUsesOption() {
        val base = WatchInteraction("sample", "computer", "session", "screen", "Sample", "Choose",
            options = listOf(DialogOption("1", "First")), canAnswer = true)
        val legacy = base.copy(rawOptions = true)
        val legacyAnswer = WatchResolution.screen(legacy, setOf("1"))
        assertEquals("1", legacyAnswer.getJSONArray("keys").getString(0))
        WatchResolution.validate(legacy, legacyAnswer)
        assertEquals("1", WatchResolution.screen(base, setOf("1")).getString("option"))
        try { WatchResolution.validate(base, legacyAnswer); fail("A structured dialog must not accept inferred numeric keys") } catch (_: HubException) { }
    }
    @Test fun sourceMetadataNeverBecomesTheWatchOwnersBubble() {
        val body = JSONObject("""{"items":[{"id":"sample","kind":"text","role":"user","text":"Sample relay","source":{"kind":"conversation","title":"Another conversation"}}]}""")
        val message = WatchParser.messages(body).single()
        assertFalse(message.own)
        assertEquals("Another conversation", message.sourceTitle)
        assertEquals("conversation", message.sourceKind)
    }
}
