package persona.core.domain.tasks

import persona.core.contracts.Todo
import persona.core.contracts.api.RepositoryCatalog
import persona.core.contracts.api.RepositoryLinkInput
import persona.core.contracts.api.TaskRepositoryInput
import persona.core.contracts.api.TaskRepositorySet

/**
 * Manager-scoped read/write seam for a to-do's bound repositories and Issue/PR links (W-D04):
 * [TasksStore] on the paired hub, a lightweight fake in UI tests.
 */
interface TodoRepositoriesSource {
    /** Bound repositories and Issue/PR links for [todo]; null when an older hub lacks the route. */
    suspend fun todoRepositories(todo: Todo): TaskRepositorySet?

    /** Repository catalog owned by [todo]'s manager; null when unavailable on an older hub. */
    suspend fun assistantRepositories(todo: Todo): RepositoryCatalog?

    /** Replaces bound repositories and links using dual CAS (`expected_revision`, `expected_catalog_revision`). */
    suspend fun setTodoRepositories(
        todo: Todo,
        expectedRevision: Long,
        expectedCatalogRevision: Long,
        repositories: List<TaskRepositoryInput>,
    ): TaskRepositorySet

    /** Replaces Issue/PR links on one bound repository (`PUT /api/v1/todos/{id}/repositories/{repoId}/links`). */
    suspend fun setTodoRepositoryLinks(
        todo: Todo,
        repoId: String,
        expectedRevision: Long,
        links: List<RepositoryLinkInput>,
    ): TaskRepositorySet
}
