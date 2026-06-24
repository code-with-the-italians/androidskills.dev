package dev.androidskills.gh

import dev.androidskills.Database
import dev.androidskills.TestSupport
import dev.androidskills.db.Bundles
import dev.androidskills.db.Jobs
import dev.androidskills.github.GitHubAppClient
import dev.androidskills.github.GithubWebhookEvent
import dev.androidskills.module
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebhookRoutesTest {

    private val dir = TestSupport.tempDir()

    @BeforeTest fun setup() = Database.init(TestSupport.newConfig(dir))
    @AfterTest fun teardown() { dir.toFile().deleteRecursively() }

    /** App client whose verifyAndParseEvent returns a scripted event (or null = bad sig). */
    private class FakeApp(val event: GithubWebhookEvent?) : GitHubAppClient {
        override val configured = true
        override suspend fun installations() = emptyList<dev.androidskills.github.Installation>()
        override suspend fun listRepos(installationId: Long) = emptyList<dev.androidskills.github.RepoRef>()
        override suspend fun defaultBranchHead(installationId: Long, owner: String, repo: String) = ""
        override suspend fun downloadZipball(installationId: Long, owner: String, repo: String, ref: String) = ByteArray(0)
        override suspend fun verifyAndParseEvent(body: ByteArray, signature: String): GithubWebhookEvent? = event
    }

    private fun seedBundle(provenance: String, ownerHandle: String = "alice"): String {
        // Minimal user + bundle so push dedupe + installation tracking have a row to hit.
        val userId = dev.androidskills.auth.UsersRepo.upsertFromGitHub(
            dev.androidskills.auth.GitHubUser(1, ownerHandle, "Alice", null), null,
        )
        val id = dev.androidskills.util.newId()
        val now = dev.androidskills.util.nowIso()
        transaction {
            Bundles.insert {
                it[Bundles.id] = id
                it[Bundles.kind] = "repo"
                it[Bundles.provenance] = provenance
                it[Bundles.ownerUserId] = userId
                it[Bundles.createdAt] = now
            }
        }
        return id
    }

    @Test
    fun badSignatureReturns401() = testApplication {
        application { module(TestSupport.newConfig(dir), githubApp = FakeApp(event = null)) }
        val res = client.post("/gh/webhooks") {
            header("X-Hub-Signature-256", "sha256=bad")
            header("Content-Type", ContentType.Application.Json.toString())
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertTrue(res.bodyAsText().contains("invalid_signature"))
    }

    @Test
    fun unconfiguredAppReturns401() = testApplication {
        // DisabledGitHubAppClient.verifyAndParseEvent returns null → 401 (indistinguishable
        // from a bad sig; a misconfigured secret surfaces in GitHub's delivery panel).
        application { module(TestSupport.newConfig(dir), githubApp = dev.androidskills.github.DisabledGitHubAppClient()) }
        val res = client.post("/gh/webhooks") { setBody("{}") }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun pushToTrackedRepoEnqueuesResync202() {
        val bundleId = seedBundle("alice/toolkit")
        testApplication {
            application {
                module(TestSupport.newConfig(dir), githubApp = FakeApp(
                    event = GithubWebhookEvent.Push("alice", "toolkit", after = "abc1234567", isDefaultBranch = true),
                ))
            }
            val res = client.post("/gh/webhooks") { setBody("{}") }
            assertEquals(HttpStatusCode.Accepted, res.status)
        }
        val job = transaction {
            Jobs.selectAll().where { Jobs.type eq "resync" }.singleOrNull()
        }
        assertNotNull(job, "resync job enqueued")
        assertEquals("queued", job!![Jobs.state])
        assertTrue(job[Jobs.payload].contains(bundleId))
        assertTrue(job[Jobs.payload].contains("abc1234567"))
    }

    @Test
    fun duplicatePushIsIdempotent() {
        // Q5: GitHub redelivering the same push (same bundle + head sha) must not enqueue twice.
        seedBundle("alice/toolkit")
        val event = GithubWebhookEvent.Push("alice", "toolkit", after = "same123sha", isDefaultBranch = true)
        repeat(3) {
            testApplication {
                application { module(TestSupport.newConfig(dir), githubApp = FakeApp(event = event)) }
                client.post("/gh/webhooks") { setBody("{}") }
            }
        }
        val count = transaction { Jobs.selectAll().where { Jobs.type eq "resync" }.toList().size }
        assertEquals(1, count, "duplicate push must not re-enqueue (Q5 idempotency)")
    }

    @Test
    fun pushToUntrackedRepoIsAcceptedButNoJob() {
        // A repo with no bundle (never submitted) → 202, no job (nothing to resync).
        testApplication {
            application {
                module(TestSupport.newConfig(dir), githubApp = FakeApp(
                    event = GithubWebhookEvent.Push("stranger", "repo", after = "sha", isDefaultBranch = true),
                ))
            }
            val res = client.post("/gh/webhooks") { setBody("{}") }
            assertEquals(HttpStatusCode.Accepted, res.status)
        }
        val job = transaction { Jobs.selectAll().where { Jobs.type eq "resync" }.singleOrNull() }
        assertNull(job)
    }

    @Test
    fun pushToNonDefaultBranchDoesNotEnqueue() {
        // §8: only push to the tracked repo's default branch triggers a resync.
        seedBundle("alice/toolkit")
        testApplication {
            application {
                module(TestSupport.newConfig(dir), githubApp = FakeApp(
                    event = GithubWebhookEvent.Push("alice", "toolkit", after = "featsha", isDefaultBranch = false),
                ))
            }
            client.post("/gh/webhooks") { setBody("{}") }
        }
        assertNull(transaction { Jobs.selectAll().where { Jobs.type eq "resync" }.singleOrNull() })
    }

    @Test
    fun installationEventTagsBundleWithInstallationId() {
        val bundleId = seedBundle("alice/old-repo", ownerHandle = "alice")
        testApplication {
            application {
                module(TestSupport.newConfig(dir), githubApp = FakeApp(
                    event = GithubWebhookEvent.InstallationAccess(installationId = 777L, accountLogin = "alice", action = "created"),
                ))
            }
            client.post("/gh/webhooks") { setBody("{}") }
        }
        val installed = transaction { Bundles.selectAll().where { Bundles.id eq bundleId }.single()[Bundles.installationId] }
        assertEquals(777L, installed)
    }
}
