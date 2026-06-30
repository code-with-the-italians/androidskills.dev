package dev.androidskills.api

import dev.androidskills.AppConfig
import dev.androidskills.Database
import dev.androidskills.TestSupport
import dev.androidskills.auth.GitHubUser
import dev.androidskills.auth.Principal
import dev.androidskills.auth.SessionStore
import dev.androidskills.auth.UsersRepo
import dev.androidskills.db.Bundles
import dev.androidskills.db.Jobs
import dev.androidskills.db.Role
import dev.androidskills.db.Sessions
import dev.androidskills.db.Skills
import dev.androidskills.db.Submissions
import dev.androidskills.db.UserStatus
import dev.androidskills.db.Users
import dev.androidskills.db.Versions
import dev.androidskills.github.GitHubAppClient
import dev.androidskills.github.GitHubAppException
import dev.androidskills.github.Installation
import dev.androidskills.github.RepoRef
import dev.androidskills.util.nowIso
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
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
            // Bob has his own installation on the same owner account, but the bundle is already Alice's.
            SubmissionQueries.createDrafts(bob, request, fakeGh(listOf(Installation(2L, 43L, "owner", "User"))))
        }
        assertEquals("bundle_ownership_conflict", ex.code)
    }

    @Test
    fun `createDrafts 422 when installation belongs to another account`(): Unit = runBlocking {
        setupDb()
        // SEC1: an installation for owner "owner" exists, but it belongs to account 42 (not Bob's 43).
        val (_, bob) = createUser(43L, "bob")
        val request = draftRequest("skill-one")
        val ex = assertFailsWith<ApiValidationException> {
            SubmissionQueries.createDrafts(bob, request, fakeGh(listOf(Installation(1L, 42L, "owner", "User"))))
        }
        assertEquals("github_app_not_installed", ex.code)
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

    @Test
    fun `submit moves draft to in_review and enqueues review job`(): Unit = runBlocking {
        setupDb()
        val (_, principal) = createUser(42L, "alice")
        val response = SubmissionQueries.createDrafts(principal, draftRequest("skill-one"), fakeGh())
        val subId = response.submissionIds[0]

        SubmissionQueries.submit(principal, subId)

        transaction {
            val sub = Submissions.selectAll().where { Submissions.id eq subId }.single()
            assertEquals("in_review", sub[Submissions.state])
            val jobs = Jobs.selectAll().where { Jobs.type eq "review" }.toList()
            assertEquals(1, jobs.size)
            assertEquals("queued", jobs[0][Jobs.state])
        }
    }

    @Test
    fun `submit 422 on invalid manifest`(): Unit = runBlocking {
        setupDb()
        val (_, principal) = createUser(42L, "alice")
        val request = SubmissionQueries.CreateDraftsRequest(
            repoOwner = "owner", repoName = "repo", ref = "abc123",
            skills = listOf(SubmissionQueries.SelectedSkill(
                slug = "skill-one", name = "", description = "", license = "",
            )),
        )
        val response = SubmissionQueries.createDrafts(principal, request, fakeGh())
        assertFailsWith<ApiValidationException> {
            SubmissionQueries.submit(principal, response.submissionIds[0])
        }
    }

    @Test
    fun `submit 409 when not draft`(): Unit = runBlocking {
        setupDb()
        val (_, principal) = createUser(42L, "alice")
        val response = SubmissionQueries.createDrafts(principal, draftRequest("skill-one"), fakeGh())
        SubmissionQueries.submit(principal, response.submissionIds[0])
        assertFailsWith<ApiConflictException> {
            SubmissionQueries.submit(principal, response.submissionIds[0])
        }
    }

    @Test
    fun `withdraw moves in_review back to draft`(): Unit = runBlocking {
        setupDb()
        val (_, principal) = createUser(42L, "alice")
        val response = SubmissionQueries.createDrafts(principal, draftRequest("skill-one"), fakeGh())
        val subId = response.submissionIds[0]
        SubmissionQueries.submit(principal, subId)

        SubmissionQueries.withdraw(principal, subId)

        transaction {
            val sub = Submissions.selectAll().where { Submissions.id eq subId }.single()
            assertEquals("draft", sub[Submissions.state])
            val jobs = Jobs.selectAll().where { Jobs.type eq "review" }.toList()
            assertEquals(0, jobs.size)
        }
    }

    @Test
    fun `deleteDraft removes submission and shell skill`(): Unit = runBlocking {
        setupDb()
        val (_, principal) = createUser(42L, "alice")
        val response = SubmissionQueries.createDrafts(principal, draftRequest("skill-one"), fakeGh())
        val subId = response.submissionIds[0]

        SubmissionQueries.deleteDraft(principal, subId)

        transaction {
            assertEquals(0, Submissions.selectAll().count())
            assertEquals(0, Skills.selectAll().count())
        }
    }

    @Test
    fun `deleteDraft keeps skill when versions exist`(): Unit = runBlocking {
        setupDb()
        val (_, principal) = createUser(42L, "alice")
        val response = SubmissionQueries.createDrafts(principal, draftRequest("skill-one"), fakeGh())
        val subId = response.submissionIds[0]
        val skillId = transaction { Submissions.selectAll().where { Submissions.id eq subId }.single()[Submissions.skillId] }
            ?: error("missing skill")
        transaction {
            Versions.insert {
                it[Versions.id] = "00000000-0000-0000-0000-000000000001"
                it[Versions.skillId] = skillId
                it[Versions.version] = "1.0.0"
                it[Versions.sourceRef] = "sha"
                it[Versions.createdAt] = nowIso()
            }
        }

        SubmissionQueries.deleteDraft(principal, subId)

        transaction {
            assertEquals(0, Submissions.selectAll().count())
            assertEquals(1, Skills.selectAll().count())
        }
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

    @Test
    fun `submit 404 for other user`(): Unit = runBlocking {
        setupDb()
        val (_, alice) = createUser(42L, "alice")
        val (_, bob) = createUser(43L, "bob")
        val response = SubmissionQueries.createDrafts(alice, draftRequest("skill-one"), fakeGh())
        assertFailsWith<ApiNotFoundException> {
            SubmissionQueries.submit(bob, response.submissionIds[0])
        }
    }

    @Test
    fun `withdraw 404 for other user`(): Unit = runBlocking {
        setupDb()
        val (_, alice) = createUser(42L, "alice")
        val (_, bob) = createUser(43L, "bob")
        val response = SubmissionQueries.createDrafts(alice, draftRequest("skill-one"), fakeGh())
        SubmissionQueries.submit(alice, response.submissionIds[0])
        assertFailsWith<ApiNotFoundException> {
            SubmissionQueries.withdraw(bob, response.submissionIds[0])
        }
    }

    @Test
    fun `deleteDraft 404 for other user`(): Unit = runBlocking {
        setupDb()
        val (_, alice) = createUser(42L, "alice")
        val (_, bob) = createUser(43L, "bob")
        val response = SubmissionQueries.createDrafts(alice, draftRequest("skill-one"), fakeGh())
        assertFailsWith<ApiNotFoundException> {
            SubmissionQueries.deleteDraft(bob, response.submissionIds[0])
        }
    }

    @Test
    fun `deleteDraft 409 when not draft`(): Unit = runBlocking {
        setupDb()
        val (_, principal) = createUser(42L, "alice")
        val response = SubmissionQueries.createDrafts(principal, draftRequest("skill-one"), fakeGh())
        SubmissionQueries.submit(principal, response.submissionIds[0])
        assertFailsWith<ApiConflictException> {
            SubmissionQueries.deleteDraft(principal, response.submissionIds[0])
        }
    }
}
