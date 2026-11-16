package dev.persona.watch.extensions

import dev.persona.watch.data.HubConfig
import dev.persona.watch.data.HubException
import dev.persona.watch.data.HubResponse
import dev.persona.watch.data.HubTransport
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WidgetRecoveryTest {
    private val config = HubConfig("https://hub.example.com", "fixture-watch-only")
    private val catalog = """{"widgets":[{"extension":"sample","id":"summary","title":"Sample summary","families":["large"]}]}"""
    private val widget = ExtensionParser.catalog(JSONObject(catalog)).single()
    private val answer = """{"expires_at":"2030-01-01T00:00:00Z","view":{"title":"Sample","sections":[]}}"""
    private val view = ExtensionParser.view(JSONObject(answer), "large")

    @Test fun resumeAfterPauseKeepsAnUncachedKeyOnlySelectionOnRetryableFailure() {
        val previousWidgets = listOf(widget)
        // The foreground read hides widgets/projection until it checks the same credential again.
        val recovered = recoverWidgetSelection(true, true, widget.key, previousWidgets,
            widget.key, null, emptyList(), null, null)
        assertEquals(previousWidgets, recovered.widgets)
        assertEquals(widget, recovered.selected)
        assertEquals(widget.key, recovered.selectedKey)
        assertNull(recovered.projection)
    }

    @Test fun retryableCatalogFailureCanFetchThePreviouslyAuthorizedUncachedProjection() {
        var catalogs = 0
        var projections = 0
        val api = ExtensionClient(config, transport = HubTransport { _, method, _, _, _ ->
            if (method == "GET") {
                if (++catalogs > 1) throw HubException("network_failed")
                HubResponse(200, catalog)
            } else { projections++; HubResponse(200, answer) }
        }, now = { 1_000 })
        val widgets = api.widgets()
        assertEquals(ExtensionProblem.RETRYABLE, assertThrows(ExtensionException::class.java) { api.widgets() }.problem)
        val recovered = recoverWidgetSelection(true, true, widget.key, widgets, widget.key, null, emptyList(), null, null)
        assertTrue(api.canProject(recovered.selected!!))
        assertEquals(view, api.widget(recovered.selected!!))
        assertEquals(1, projections)
    }

    @Test fun diskOnlyCatalogOffersCachedRetryDetailWithoutAuthorizingAProjection() {
        var projections = 0
        val api = ExtensionClient(config, transport = HubTransport { _, method, _, _, _ ->
            if (method != "GET") projections++
            HubResponse(503, "{}")
        })
        assertThrows(ExtensionException::class.java) { api.widgets() }
        val recovered = recoverWidgetSelection(true, false, widget.key, emptyList(), null, null,
            listOf(widget), widget, view)
        assertEquals(widget, recovered.selected)
        assertEquals(view, recovered.projection)
        assertFalse(api.canProject(widget))
        assertThrows(ExtensionException::class.java) { api.widget(widget) }
        assertEquals(0, projections)
    }

    @Test fun connectionChangeCannotRestorePreviousPrivateSelectionOrProjection() {
        val recovered = recoverWidgetSelection(true, false, widget.key, listOf(widget), widget.key, view,
            emptyList(), null, null)
        assertTrue(recovered.widgets.isEmpty())
        assertNull(recovered.selected)
        assertNull(recovered.projection)
    }

    @Test fun projectionMustBelongToTheSelectedWidget() {
        val other = widget.copy(id = "other")
        val recovered = recoverWidgetSelection(true, true, other.key, listOf(widget, other), widget.key, view,
            emptyList(), null, null)
        assertEquals(other, recovered.selected)
        assertNull(recovered.projection)
    }

    @Test fun nonRetryableFailureDiscardsSelectionAndAllCachedContent() {
        val recovered = recoverWidgetSelection(false, true, widget.key, listOf(widget), widget.key, view,
            listOf(widget), widget, view)
        assertEquals(RecoveredWidgetSelection(emptyList(), null, null, null), recovered)
    }

    @Test fun providerDeadlineBeyondOneDayUsesItsOriginalRemainderAtEveryWake() {
        val start = 1_000L
        val hour = 3_600_000L
        val deadline = start + 49 * hour
        assertEquals(24 * hour, WidgetExpiry.nextDelay(deadline, start))
        assertEquals(24 * hour, WidgetExpiry.nextDelay(deadline, start + 24 * hour))
        assertEquals(hour, WidgetExpiry.nextDelay(deadline, start + 48 * hour))
        assertEquals(1L, WidgetExpiry.nextDelay(deadline, deadline - 1))
        assertNull(WidgetExpiry.nextDelay(deadline, deadline))
        assertNull(WidgetExpiry.nextDelay(deadline, deadline + hour))
    }

    @Test fun delayedWakeAndClockChangesNeverRenewTheProviderDeadline() {
        val day = WidgetExpiry.MAX_WAIT_MS
        val deadline = day + 5_000
        assertEquals(2_000L, WidgetExpiry.nextDelay(deadline, day + 3_000))
        assertNull(WidgetExpiry.nextDelay(deadline, deadline + 1))
        assertEquals(day, WidgetExpiry.nextDelay(deadline, -1))
        assertEquals(day, WidgetExpiry.nextDelay(Long.MAX_VALUE, Long.MIN_VALUE))
    }
}
