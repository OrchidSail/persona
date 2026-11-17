package dev.persona.watch

/** A fixture abort is bounded and never turns an incomplete cleanup into a passing run. */
internal fun cleanupFixtureRegistrations(owned: Collection<PendingHubRegistration>, abort: (PendingHubRegistration) -> Unit) {
    var failure: Exception? = null
    for (entry in owned) {
        var last: Exception? = null
        for (attempt in 1..3) {
            try { abort(entry); last = null; break }
            catch (problem: Exception) { last = problem }
        }
        last?.let { problem -> if (failure == null) failure = problem else failure!!.addSuppressed(problem) }
    }
    failure?.let { throw it }
}
