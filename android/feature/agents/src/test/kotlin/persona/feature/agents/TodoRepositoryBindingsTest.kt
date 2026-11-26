package persona.feature.agents

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import persona.core.contracts.Todo
import persona.core.contracts.TodoPRStatus
import persona.core.contracts.api.HubCodedFailure
import persona.core.contracts.api.RepositoryBinding
import persona.core.contracts.api.RepositoryCatalog
import persona.core.contracts.api.RepositoryIdentity
import persona.core.contracts.api.RepositoryLinkInput
import persona.core.contracts.api.RequestedRef
import persona.core.contracts.api.TaskRepository
import persona.core.contracts.api.TaskRepositoryInput
import persona.core.contracts.api.TaskRepositoryLink
import persona.core.contracts.api.TaskRepositorySet
import persona.core.contracts.api.SourceSnapshot
import persona.core.designsystem.AppStyle
import persona.core.designsystem.PersonaTheme
import persona.core.domain.tasks.TodoRepositoriesSource

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-w411dp-h900dp")
class TodoRepositoryBindingsTest {
    @get:Rule val rule = createComposeRule()

    private class ConflictError(override val code: String) : Exception("conflict"), HubCodedFailure {
        override val status: Int = 409
    }

    private class FakeReposSource(
        var set: TaskRepositorySet?,
        var catalog: RepositoryCatalog? = null,
    ) : TodoRepositoriesSource {
        val savedCalls = mutableListOf<Triple<Long, Long, List<TaskRepositoryInput>>>()
        val queriedManagers = mutableListOf<String?>()
        var conflictOnce: String? = null

        override suspend fun todoRepositories(todo: Todo): TaskRepositorySet? {
            queriedManagers += todo.manager
            return set
        }

        override suspend fun assistantRepositories(todo: Todo): RepositoryCatalog? {
            queriedManagers += todo.manager
            return catalog
        }

        override suspend fun setTodoRepositories(
            todo: Todo,
            expectedRevision: Long,
            expectedCatalogRevision: Long,
            repositories: List<TaskRepositoryInput>,
        ): TaskRepositorySet {
            conflictOnce?.let { code ->
                conflictOnce = null
                throw ConflictError(code)
            }
            savedCalls += Triple(expectedRevision, expectedCatalogRevision, repositories)
            val next = TaskRepositorySet(
                manager = todo.manager.orEmpty(),
                todoId = todo.id,
                revision = expectedRevision + 1,
                repositories = repositories.map { input ->
                    val binding = catalog?.repositories?.firstOrNull { it.id == input.repoId }
                    TaskRepository(
                        repoId = input.repoId,
                        directoryAlias = input.directoryAlias.orEmpty(),
                        requestedRef = input.requestedRef,
                        sourceSnapshot = SourceSnapshot(
                            identity = binding?.identity ?: RepositoryIdentity(path = input.repoId, canonicalUrl = "https://github.com/acme/${input.repoId}"),
                        ),
                        links = input.links.orEmpty().map { l ->
                            TaskRepositoryLink(
                                kind = l.kind ?: TaskRepositoryLink.KIND_PULL_REQUEST,
                                number = l.number.orEmpty(),
                                canonicalUrl = l.url ?: "https://github.com/acme/repo/issues/${l.number}",
                                role = l.role ?: TaskRepositoryLink.ROLE_DELIVERY,
                            )
                        },
                    )
                },
            )
            set = next
            return next
        }

        override suspend fun setTodoRepositoryLinks(
            todo: Todo,
            repoId: String,
            expectedRevision: Long,
            links: List<RepositoryLinkInput>,
        ): TaskRepositorySet = set ?: TaskRepositorySet()
    }

    @Test fun linkParsingNormalizationAndNonNullPayloadInvariants() {
        assertEquals("https://github.com/acme/app/pull/42", normalizeLinkUrl("  HTTPS://GitHub.com/acme/app/pull/42/ "))

        val fromUrl = parseLinkDraftInput("https://github.com/acme/app/pull/42", TaskRepositoryLink.KIND_ISSUE, "k1")!!
        val urlJson = fromUrl.toInput().toJson().toString()
        assertTrue(urlJson.contains("https://github.com/acme/app/pull/42"))
        assertFalse(urlJson.contains("\"kind\""))
        assertFalse(urlJson.contains("null"))

        val fromNum = parseLinkDraftInput("#108", TaskRepositoryLink.KIND_ISSUE, "k2")!!
        assertEquals("issue", fromNum.kind)
        assertEquals("108", fromNum.number)
        val numJson = fromNum.toInput().toJson().toString()
        assertFalse(numJson.contains("\"url\""))
        assertFalse(numJson.contains("null"))

        assertNull(parseLinkDraftInput("not a link", TaskRepositoryLink.KIND_PULL_REQUEST, "k3"))
        assertNull(parseLinkDraftInput("#0", TaskRepositoryLink.KIND_PULL_REQUEST, "k4"))
    }

    @Test fun readViewMergesPrFactsAcrossGlassBriefAndBoardAndFallsBackOnOlderHub() {
        val todo = Todo(
            id = "t1",
            title = "Ship parity",
            manager = "work",
            prUrl = "https://github.com/acme/app/pull/12",
            prStatus = TodoPRStatus(
                url = "https://github.com/acme/app/pull/12/",
                state = "open",
                draft = true,
                conflicts = true,
                title = "Fallback PR title",
            ),
        )
        val initialSet = TaskRepositorySet(
            manager = "work",
            todoId = "t1",
            revision = 2,
            repositories = listOf(
                TaskRepository(
                    repoId = "repo_1",
                    directoryAlias = "frontend",
                    requestedRef = RequestedRef(RequestedRef.KIND_BRANCH, "feat/parity"),
                    sourceSnapshot = SourceSnapshot(
                        identity = RepositoryIdentity("github", "https://github.com", "acme/app", "https://github.com/acme/app"),
                    ),
                    links = listOf(
                        TaskRepositoryLink(
                            kind = TaskRepositoryLink.KIND_PULL_REQUEST,
                            number = "12",
                            canonicalUrl = "https://github.com/acme/app/pull/12",
                            title = null,
                            role = TaskRepositoryLink.ROLE_DELIVERY,
                        ),
                    ),
                ),
            ),
        )
        val catalog = RepositoryCatalog(
            manager = "work",
            catalogRevision = 9,
            repositories = listOf(
                RepositoryBinding(id = "repo_1", provider = "github", path = "acme/app", canonicalUrl = "https://github.com/acme/app", name = "App"),
            ),
        )
        val source = FakeReposSource(initialSet, catalog)
        var style by mutableStateOf(AppStyle.Glass)

        rule.setContent {
            PersonaTheme(darkTheme = false, reduceMotion = true, style = style) {
                TodoDetailScreen(
                    todoId = "t1",
                    todos = listOf(todo),
                    machines = emptyList(),
                    sessions = emptyList(),
                    tasks = null,
                    onBack = {},
                    onOpenSession = {},
                    onHandOff = {},
                    decisions = null,
                    repositoriesSource = source,
                )
            }
        }
        rule.waitForIdle()

        for (nextStyle in AppStyle.entries) {
            rule.runOnIdle { style = nextStyle }
            rule.onNodeWithTag("todo-repo-card-repo_1").performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("todo-repo-link-repo_1-0").performScrollTo().assertTextContains("Fallback PR title", substring = true)
            rule.onNodeWithText("Draft").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Conflicts").performScrollTo().assertIsDisplayed()
        }
        assertTrue(source.queriedManagers.all { it == "work" })
    }

    @Test fun olderHubShowsReadOnlyLegacyPrFallback() {
        val todo = Todo(
            id = "t_old",
            title = "Legacy task",
            prUrl = "https://github.com/acme/legacy/pull/7",
            prStatus = TodoPRStatus(url = "https://github.com/acme/legacy/pull/7", state = "merged", title = "Legacy PR"),
        )
        val legacySource = FakeReposSource(set = null, catalog = null)
        rule.setContent {
            PersonaTheme(darkTheme = false, reduceMotion = true, style = AppStyle.Brief) {
                TodoDetailScreen(
                    todoId = "t_old",
                    todos = listOf(todo),
                    machines = emptyList(),
                    sessions = emptyList(),
                    tasks = null,
                    onBack = {},
                    onOpenSession = {},
                    onHandOff = {},
                    decisions = null,
                    repositoriesSource = legacySource,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("todo-repos-legacy-card").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Legacy PR").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTag("todo-repos-edit").fetchSemanticsNodes().isEmpty())
    }

    @Test fun editorBindsReposAddsLinksTogglesRoleHandlesConflictAndSavesDualCas() {
        val todo = Todo(id = "t2", title = "Multi-repo task", manager = "work")
        val initialSet = TaskRepositorySet(manager = "work", todoId = "t2", revision = 4, repositories = emptyList())
        val catalog = RepositoryCatalog(
            manager = "work",
            catalogRevision = 11,
            repositories = listOf(
                RepositoryBinding(id = "repo_1", provider = "github", path = "acme/api", canonicalUrl = "https://github.com/acme/api", name = "API"),
                RepositoryBinding(id = "repo_2", provider = "github", path = "acme/web", canonicalUrl = "https://github.com/acme/web", name = "Web"),
            ),
        )
        val source = FakeReposSource(initialSet, catalog)
        var style by mutableStateOf(AppStyle.Board)

        rule.setContent {
            PersonaTheme(darkTheme = false, reduceMotion = true, style = style) {
                TodoDetailScreen(
                    todoId = "t2",
                    todos = listOf(todo),
                    machines = emptyList(),
                    sessions = emptyList(),
                    tasks = null,
                    onBack = {},
                    onOpenSession = {},
                    onHandOff = {},
                    decisions = null,
                    repositoriesSource = source,
                )
            }
        }
        rule.waitForIdle()

        // Open editor, bind repo_1, add #42, toggle role Delivery -> Reference.
        rule.onNodeWithTag("todo-repos-edit").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("todo-repo-add-repo_1").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("todo-link-input-repo_1")))
            .performScrollTo()
            .performTextInput("#42")
        rule.onNodeWithTag("todo-link-add-repo_1").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("todo-link-role-repo_1-0").performScrollTo().performClick()
        rule.waitForIdle()

        // Unbind repo_1, verify Undo restores it.
        rule.onNodeWithTag("todo-repo-unbind-repo_1").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("todo-repos-unbind-undo").assertIsDisplayed()
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("todo-repo-editor-repo_1").assertIsDisplayed()

        // First save triggers 409 conflict -> conflict banner appears, second save succeeds with dual CAS.
        source.conflictOnce = "task_repositories_revision_conflict"
        rule.onNodeWithTag("todo-repos-save").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("todo-repos-conflict").assertIsDisplayed()

        rule.onNodeWithTag("todo-repos-save").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, source.savedCalls.size)
        val (rev, catRev, repos) = source.savedCalls.single()
        assertEquals(4L, rev)
        assertEquals(11L, catRev)
        assertEquals("repo_1", repos.single().repoId)
        val link = repos.single().links!!.single()
        assertEquals("pull_request", link.kind)
        assertEquals("42", link.number)
        assertEquals(TaskRepositoryLink.ROLE_REFERENCE, link.role)
        assertNotNull(source.set)
    }
}
