package dev.persona.watch

/** Re-read persisted state even when removal failed after changing the directory. */
internal fun completeConnectionRemoval(result: Result<Unit>, showError: () -> Unit, reload: () -> Unit) {
    if (result.isFailure) {
        showError()
    }
    reload()
}
