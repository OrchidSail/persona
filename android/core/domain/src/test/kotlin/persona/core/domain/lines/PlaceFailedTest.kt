package persona.core.domain.lines

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import persona.core.contracts.FeedItem
import persona.core.contracts.Item
import persona.core.contracts.SessionKey
import persona.core.contracts.api.DeliveryState
import persona.core.contracts.api.OutboxMessage
import persona.core.domain.tasks.AssistantExtra
import persona.core.domain.tasks.DispatchHint
import persona.core.domain.tasks.DispatchedTask
import java.time.Instant

class PlaceFailedTest {
    private val key = SessionKey("m1", "s1")
    private val at = Instant.parse("2026-01-02T03:04:05Z").toEpochMilli()

    private fun user(id: String, text: String, client: String? = null) =
        ChatLine.Row(ChatRow("user", LineItem(Item(id, 1, role = "user", kind = "text", text = text, ts = "2026-01-02T03:04:05Z", clientMsgId = client))))

    private fun reply(id: String, text: String) =
        ChatLine.Row(ChatRow("reply", LineItem(Item(id, 2, role = "assistant", kind = "text", text = text, ts = "2026-01-02T03:04:05Z"))))

    @Test fun aFailedSendStaysBeforeTheMessageWrittenAfterIt() {
        val failed = OutboxMessage("m1", key, "Find a dinner place.", DeliveryState.Refused, createdAt = at + 1_000)
        val lines = listOf(user("l1#0", "Plan a weekend trip."), reply("l2#0", "Saturday hike."), user("l4#0", "And a backup plan for rain.", "m2"))
        val placed = placeFailed(lines, listOf(failed), writtenAt = mapOf("m2" to at + 2_000))
        assertEquals(listOf("m1"), placed.before["l4#0"]?.map { it.clientMessageId })
        assertTrue(placed.rest.isEmpty())
    }

    @Test fun aMessageStillOnItsWayStaysAtTheEnd() {
        val sending = OutboxMessage("m3", key, "One more thing.", DeliveryState.Sending, createdAt = at + 3_000)
        val lines = listOf(user("l1#0", "Plan a weekend trip."))
        val placed = placeFailed(lines, listOf(sending), writtenAt = mapOf("m3" to at + 3_000))
        assertTrue(placed.before.isEmpty())
        assertEquals(listOf("m3"), placed.rest.map { it.clientMessageId })
    }

    @Test fun failedSendAnchorsBeforeLaterTimedExtraTaskCard() {
        val routed = ChatLine.Extra(
            "feed-1",
            AssistantExtra.Feed(FeedItem("feed-1", at = "2026-11-25T05:41:45Z", kind = "routed")),
        )
        val dispatchCard = ChatLine.Extra(
            "dispatch-plan-1",
            DispatchHint(listOf(DispatchedTask("todo-1", "Investigate network")), at = Instant.parse("2026-11-25T11:07:17Z")),
        )
        val laterUser = ChatLine.Row(
            ChatRow("user", LineItem(Item("u-381", 381, role = "user", kind = "text", text = "Later relay", ts = "2026-11-25T12:05:31Z"))),
        )
        val failed = OutboxMessage(
            "outbox-1530",
            key,
            "Question sent at 15:30",
            DeliveryState.NeedsConfirmation,
            createdAt = Instant.parse("2026-11-25T07:30:00Z").toEpochMilli(),
        )
        val placed = placeFailed(listOf(routed, dispatchCard, laterUser), listOf(failed))
        assertEquals(listOf("outbox-1530"), placed.before["dispatch-plan-1"]?.map { it.clientMessageId })
        assertTrue(placed.rest.isEmpty())
    }
}
