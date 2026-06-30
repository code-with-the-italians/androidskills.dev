package dev.androidskills.api

import dev.androidskills.AppConfig
import dev.androidskills.Database
import dev.androidskills.TestSupport
import dev.androidskills.auth.GitHubUser
import dev.androidskills.auth.Principal
import dev.androidskills.auth.SessionStore
import dev.androidskills.auth.UsersRepo
import dev.androidskills.db.Bundles
import dev.androidskills.db.Role
import dev.androidskills.db.Sessions
import dev.androidskills.db.Skills
import dev.androidskills.db.Submissions
import dev.androidskills.db.UserStatus
import dev.androidskills.db.Users
import dev.androidskills.github.DisabledGitHubAppClient
import dev.androidskills.github.GitHubAppClient
import dev.androidskills.github.GitHubAppException
import dev.androidskills.github.Installation
import dev.androidskills.github.RepoRef
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SubmissionQueriesTest {

    private val dir = TestSupport.tempDir()

    @AfterTest
    fun teardown() {
        dir.toFile().deleteRecursively()
    }

    private fun setupDb() {
        Database.init(TestSupport.newConfig(dir))
    }

    private fun createUser(githubId: Long, handle: String): Pair<String, Principal> {
        val uid = UsersRepo.upsertFromGitHub(GitHubUser(githubId, handle, handle, null), null)
        val token = SessionStore.create(uid, 3600)
        val row = transaction {
            Users.selectAll().where { Users.id eq uid }.single()
        }
        val principal = Principal(
            sessionId = token,
            userId = uid,
            githubId = githubId,
            handle = handle,
            name = handle,
            avatarUrl = null,
            role = Role.parse(row[Users.role]),
            status = UserStatus.parse(row[Users.status]),
            createdAt = row[Users.createdAt],
        )
        return uid to principal
    }

    private fun fakeGh(installations: List<Installation> = listOf(Installation(1L, 42L, "owner", "User"))) =
        object : GitHubAppClient {
            override val configured = true
            override suspend fun installations() = installations
            override suspend fun listRepos(installationId: Long) = emptyList<RepoRef>()
            override suspend fun defaultBranchHead(installationId: Long, owner: String, repo: String) = "abc"
            override suspend fun downloadZipball(installationId: Long, owner: String, repo: String, ref: String) = byteArrayOf()
            override suspend fun verifyAndParseEvent(body: ByteArray, signature: String) = null
        }

    @Test
    fun `createDrafts creates bundle skill and submission`(): Unit = runBlocking {
        setupDb()
        val (_, principal) = createUser(42L, "alice")
        val request = SubmissionQueries.CreateDraftsRequest(
            repoOwner = "owner",
            repoName = "repo",
            ref = "abc123",
            skills = listOf(
                SubmissionQueries.SelectedSkill(
                    slug = "my-skill",
                    name = "My Skill",
                    description = "A skill",
                    license = "MIT",
                    tags = listOf("android"),
                    version = "1.0.0",
                ),
            ),
        )

        val response = SubmissionQueries.createDrafts(principal, request, fakeGh())
        assertEquals(1, response.submissionIds.size)

        transaction {
            val bundle = Bundles.selectAll().single()
            assertEquals("repo", bundle[Bundles.kind])
            assertEquals("owner/repo", bundle[Bundles.provenance])
            assertEquals(principal.userId, bundle[Bundles.ownerUserId])
            assertEquals(1L, bundle[Bundles.installationId])
            assertEquals("abc123", bundle[Bundles.sourceRef])

            val skill = Skills.selectAll().single()
            assertEquals("my-skill", skill[Skills.slug])
            assertEquals("unlisted", skill[Skills.status])
            assertEquals(false, skill[Skills.verified])

            val sub = Submissions.selectAll().single()
            assertEquals("draft", sub[Submissions.state])
            assertEquals(principal.userId, sub[Submissions.submitterId])
            assertNotNull(sub[Submissions.payload])
        }
    }

    @Test
    fun `createDrafts groups by state`(): Unit = runBlocking {
        setupDb()
        val (_, principal) = createUser(42L, "alice")
        val request = draftRequest("skill-one")
        SubmissionQueries.createDrafts(principal, request, fakeGh())

        val grouped = SubmissionQueries.mySubmissions(principal)
        assertEquals(1, grouped.size)
        assertEquals("draft", grouped[0].state)
        assertEquals(1, grouped[0].items.size)

        val detail = SubmissionQueries.getSubmission(principal, grouped[0].items[0].id)
        assertNotNull(detail)
        assertEquals("skill-one", detail.slug)
        assertEquals("draft", detail.state)
    }

    @Test
    fun `createDrafts 409 when repo owned by another user`(): Unit = runBlocking {
        setupDb()
        val (_, alice) = createUser(42L, "alice")
        val (_, bob) = createUser(43L, "bob")
        val request = draftRequest("skill-one")

        SubmissionQueries.createDrafts(alice, request, fakeGh())
        val ex = assertFailsWith<ApiConflictException> {
            SubmissionQueries.createDrafts(bob, request, fakeGh())
        }
        assertEquals("bundle_ownership_conflict", ex.code)
    }

    @Test
    fun `createDrafts 409 when slug used by another bundle`(): Unit = runBlocking {
        setupDb()
        val (_, alice) = createUser(42L, "alice")
        SubmissionQueries.createDrafts(alice, draftRequest("shared-slug", repoName = "repo-a"), fakeGh())
        val ex = assertFailsWith<ApiConflictException> {
            SubmissionQueries.createDrafts(alice, draftRequest("shared-slug", repoName = "repo-b"), fakeGh(listOf(Installation(2L, 42L, "owner", "User"))))
        }
        assertEquals("slug_conflict", ex.code)
    }

    @Test
    fun `createDrafts 422 when no github app installation for owner`(): Unit = runBlocking {
        setupDb()
        val (_, principal) = createUser(42L, "alice")
        val request = draftRequest("skill-one")
        val ex = assertFailsWith<ApiValidationException> {
            SubmissionQueries.createDrafts(principal, request, fakeGh(emptyList()))
        }
        assertEquals("github_app_not_installed", ex.code)
    }

    @Test
    fun `createDrafts 422 on bad slug`(): Unit = runBlocking {
        setupDb()
        val (_, principal) = createUser(42L, "alice")
        val request = draftRequest("BadSlug")
        assertFailsWith<ApiValidationException> {
            SubmissionQueries.createDrafts(principal, request, fakeGh())
        }
    }

    @Test
    fun `getSubmission returns null for other user`(): Unit = runBlocking {
        setupDb()
        val (_, alice) = createUser(42L, "alice")
        val (_, bob) = createUser(43L, "bob")
        val response = SubmissionQueries.createDrafts(alice, draftRequest("skill-one"), fakeGh())
        assertEquals(null, SubmissionQueries.getSubmission(bob, response.submissionIds[0]))
    }

    private fun draftRequest(slug: String, repoName: String = "repo") = SubmissionQueries.CreateDraftsRequest(
        repoOwner = "owner",
        repoName = repoName,
        ref = "abc123",
        skills = listOf(
            SubmissionQueries.SelectedSkill(
                slug = slug,
                name = "Skill",
                description = "Desc",
                license = "MIT",
                tags = emptyList(),
            ),
        ),
    )
}
