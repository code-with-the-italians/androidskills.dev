package dev.androidskills.jobs

import dev.androidskills.Database
import dev.androidskills.TestSupport
import dev.androidskills.db.Bundles
import dev.androidskills.db.Categories
import dev.androidskills.db.Jobs
import dev.androidskills.db.Skills
import dev.androidskills.db.Submissions
import dev.androidskills.db.Users
import dev.androidskills.github.GitHubAppClient
import dev.androidskills.github.GitHubAppException
import dev.androidskills.github.GithubWebhookEvent
import dev.androidskills.github.Installation
import dev.androidskills.github.RepoRef
import dev.androidskills.llm.LlmClient
import dev.androidskills.llm.ReviewResult
import dev.androidskills.llm.SecurityResult
import dev.androidskills.llm.SkillManifest
import dev.androidskills.storage.LocalFsStore
import dev.androidskills.util.appJson
import dev.androidskills.util.newId
import dev.androidskills.util.nowIso
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Tests the review + resync job handlers + retry/backoff + reclaim. Uses fakes (no network). */
class JobWorkerTest {

    private val dir = TestSupport.tempDir()
    private val store = LocalFsStore(dir.resolve("files"))

    @BeforeTest fun setup() = Database.init(TestSupport.newConfig(dir))
    @AfterTest fun teardown() { dir.toFile().deleteRecursively() }

    private class FakeLlm(val result: ReviewResult) : LlmClient {
        override val kind = "fake"
        override suspend fun review(input: SkillManifest) = result
    }

    private class FakeApp(
        val zipball: ByteArray = ByteArray(0),
        val failDownload: Boolean = false,
    ) : GitHubAppClient {
        override val configured = true
        override suspend fun installations() = listOf(Installation(1, 42, "alice", "User"))
        override suspend fun listRepos(installationId: Long) = listOf(RepoRef("alice", "repo", "alice/repo", "main"))
        override suspend fun defaultBranchHead(installationId: Long, owner: String, repo: String) = "mainsha"
        override suspend fun downloadZipball(installationId: Long, owner: String, repo: String, ref: String): ByteArray {
            if (failDownload) throw GitHubAppException("download failed")
            return zipball
        }
        override suspend fun verifyAndParseEvent(body: ByteArray, signature: String): GithubWebhookEvent? = null
    }

    private fun seedSkillAndSubmission(): Pair<String, String> {
        val userId = transaction {
            val id = newId(); val now = nowIso()
            Users.insert { it[Users.id] = id; it[Users.githubId] = 1; it[Users.handle] = "alice"; it[Users.createdAt] = now; it[Users.updatedAt] = now }; id
        }
        val bundleId = transaction {
            val id = newId(); val now = nowIso()
            Bundles.insert { it[Bundles.id] = id; it[Bundles.kind] = "zip"; it[Bundles.provenance] = "test"; it[Bundles.ownerUserId] = userId; it[Bundles.createdAt] = now }; id
        }
        val skillId = transaction {
            val id = newId(); val now = nowIso()
            Skills.insert {
                it[Skills.id] = id; it[Skills.bundleId] = bundleId; it[Skills.slug] = "test"
                it[Skills.name] = "Test"; it[Skills.description] = "d"; it[Skills.version] = "1.0.0"
                it[Skills.versionSource] = "manifest"; it[Skills.status] = "unlisted"; it[Skills.verified] = false
                it[Skills.createdAt] = now; it[Skills.updatedAt] = now
            }; id
        }
        val subId = transaction {
            val id = newId(); val now = nowIso()
            val staged = dev.androidskills.ingest.StagedPayload(
                version = "1.0.0", versionSource = "manifest", name = "Test", description = "d",
                license = "MIT", tags = listOf("kotlin"),
                sourceRef = dev.androidskills.ingest.StagedPayload.SourceRef("alice", "repo", "reviewedsha"),
            )
            val payload = appJson.encodeToString(
                dev.androidskills.ingest.SubmissionPayload.serializer(),
                dev.androidskills.ingest.SubmissionPayload(staged = staged),
            )
            Submissions.insert {
                it[Submissions.id] = id; it[Submissions.bundleId] = bundleId; it[Submissions.skillId] = skillId
                it[Submissions.submitterId] = userId; it[Submissions.state] = "in_review"
                it[Submissions.payload] = payload; it[Submissions.createdAt] = now; it[Submissions.updatedAt] = now
            }; id
        }
        return skillId to subId
    }

    private fun enqueueReview(skillId: String, subId: String): String {
        val jobId = newId()
        transaction {
            val now = nowIso()
            Jobs.insert {
                it[Jobs.id] = jobId; it[Jobs.type] = "review"
                it[Jobs.payload] = appJson.encodeToString(ReviewJobPayload.serializer(), ReviewJobPayload(skillId, subId))
                it[Jobs.state] = "queued"; it[Jobs.runAfter] = now; it[Jobs.createdAt] = now; it[Jobs.updatedAt] = now
            }
        }
        return jobId
    }

    @kotlinx.serialization.Serializable
    data class ReviewJobPayload(val skillId: String, val submissionId: String)

    @Test
    fun `review job applies results without flipping status`() {
        val (skillId, subId) = seedSkillAndSubmission()
        val jobId = enqueueReview(skillId, subId)
        val llm = FakeLlm(ReviewResult("kotlin-language", listOf("kotlin"), SecurityResult(true), 85))

        runBlocking { processOneJob(llm, store, FakeApp()) }

        val skill = transaction { Skills.selectAll().where { Skills.id eq skillId }.single() }
        val catId = skill[Skills.categoryId]
        assertNotNull(catId, "category should be assigned")
        val catSlug = transaction { Categories.selectAll().where { Categories.id eq catId }.single()[Categories.slug] }
        assertEquals("kotlin-language", catSlug)
        assertEquals("unlisted", skill[Skills.status], "§6.4: status NOT flipped")
        assertFalse(skill[Skills.verified], "§6.4: verified NOT flipped")

        val sub = transaction { Submissions.selectAll().where { Submissions.id eq subId }.single() }
        assertEquals(85, sub[Submissions.lintScore])
        assertTrue(sub[Submissions.payload]?.contains("kotlin-language") == true)

        val parsedPayload = appJson.decodeFromString(
            dev.androidskills.ingest.SubmissionPayload.serializer(),
            sub[Submissions.payload]!!,
        )
        assertEquals("reviewedsha", parsedPayload.reviewedSourceRef?.ref, "review must pin the staged source ref")

        val job = transaction { Jobs.selectAll().where { Jobs.id eq jobId }.single() }
        assertEquals("done", job[Jobs.state])
    }

    @Test
    fun `review with security failure records findings`() {
        val (skillId, subId) = seedSkillAndSubmission()
        enqueueReview(skillId, subId)
        val llm = FakeLlm(ReviewResult("uncategorized", emptyList(), SecurityResult(false, listOf("prompt injection")), 20))

        runBlocking { processOneJob(llm, store, FakeApp()) }

        val sub = transaction { Submissions.selectAll().where { Submissions.id eq subId }.single() }
        assertTrue(sub[Submissions.payload]?.contains("prompt injection") == true, "findings must be in payload")
        assertEquals(20, sub[Submissions.lintScore])
    }

    @Test
    fun `failing job retries with backoff then permanently fails`() {
        val (skillId, subId) = seedSkillAndSubmission()
        val jobId = enqueueReview(skillId, subId)
        // A failing LLM: always throws.
        val llm = object : LlmClient {
            override val kind = "fail"
            override suspend fun review(input: SkillManifest) = throw RuntimeException("LLM down")
        }

        // Attempt 1 → retry.
        runBlocking { processOneJob(llm, store, FakeApp()) }
        var job = transaction { Jobs.selectAll().where { Jobs.id eq jobId }.single() }
        assertEquals("queued", job[Jobs.state])
        assertEquals(1, job[Jobs.attempts])

        // Attempt 2 → retry.
        transaction { Jobs.update({ Jobs.id eq jobId }) { it[Jobs.runAfter] = nowIso() } } // make immediately runnable
        runBlocking { processOneJob(llm, store, FakeApp()) }
        job = transaction { Jobs.selectAll().where { Jobs.id eq jobId }.single() }
        assertEquals(2, job[Jobs.attempts])

        // Attempt 3 → permanently failed.
        transaction { Jobs.update({ Jobs.id eq jobId }) { it[Jobs.runAfter] = nowIso() } }
        runBlocking { processOneJob(llm, store, FakeApp()) }
        job = transaction { Jobs.selectAll().where { Jobs.id eq jobId }.single() }
        assertEquals("failed", job[Jobs.state])
        assertEquals(3, job[Jobs.attempts])
        assertNotNull(job[Jobs.lastError])
    }

    @Test
    fun `startup reclaim resets running to queued`() {
        val (skillId, subId) = seedSkillAndSubmission()
        val jobId = enqueueReview(skillId, subId)
        // Simulate a crash mid-job: set state=running.
        transaction { Jobs.update({ Jobs.id eq jobId }) { it[Jobs.state] = "running" } }
        // Reclaim.
        reclaimStaleJobs()
        val job = transaction { Jobs.selectAll().where { Jobs.id eq jobId }.single() }
        assertEquals("queued", job[Jobs.state])
    }
}
