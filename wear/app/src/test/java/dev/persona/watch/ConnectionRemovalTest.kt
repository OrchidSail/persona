package dev.persona.watch

import org.junit.Assert.*
import org.junit.Test

class ConnectionRemovalTest {
    @Test fun successfulRemovalReloadsPersistedRowsWithoutAssigningAnError() {
        val persisted = mutableListOf("removed", "retained")
        var displayed = persisted.toList()
        var error = "previous status"
        var reloads = 0
        val result = runCatching { persisted.remove("removed"); Unit }

        completeConnectionRemoval(result,
            showError = { error = "failed" },
            reload = { reloads++; displayed = persisted.toList() })

        assertEquals(listOf("retained"), displayed)
        assertEquals("previous status", error)
        assertEquals(1, reloads)
    }

    @Test fun failedRemovalReloadsRetainedRowsWithTheFailureAlreadyVisible() {
        val persisted = listOf("retained")
        var displayed = emptyList<String>()
        var error = ""
        val events = mutableListOf<String>()

        completeConnectionRemoval(Result.failure(IllegalStateException("directory write failed")),
            showError = { error = "failed"; events += "error" },
            reload = {
                assertEquals("failed", error)
                events += "reload"
                displayed = persisted.toList()
            })

        assertEquals(persisted, displayed)
        assertEquals(listOf("error", "reload"), events)
    }

    @Test fun failureAfterPersistedRemovalStillRemovesTheVisibleRowOnReload() {
        val persisted = mutableListOf("removed", "retained")
        var displayed = persisted.toList()
        var error = ""
        var reloads = 0
        val result = runCatching<Unit> {
            persisted.remove("removed")
            throw IllegalStateException("credential cleanup failed")
        }

        completeConnectionRemoval(result,
            showError = { error = "failed" },
            reload = { reloads++; displayed = persisted.toList() })

        assertEquals(listOf("retained"), displayed)
        assertEquals("failed", error)
        assertEquals(1, reloads)
    }
}
