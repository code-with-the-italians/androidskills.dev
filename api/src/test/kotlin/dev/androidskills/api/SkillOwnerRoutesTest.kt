package dev.androidskills.api

import dev.androidskills.AppConfig
import dev.androidskills.Database
import dev.androidskills.TestSupport
import dev.androidskills.auth.GitHubUser
import dev.androidskills.auth.OAuthClient
import dev.androidskills.auth.OAuthException
import dev.androidskills.auth.OAuthTokens
import dev.androidskills.auth.SessionStore
import dev.androidskills.auth.UsersRepo
import dev.androidskills.db.Bundles
import dev.androidskills.db.Jobs
import dev.androidskills.db.Skills
import dev.androidskills.github.GitHubAppClient
import dev.androidskills.github.GitHubAppException
import dev.androidskills.github.Installation
import dev.androidskills.github.RepoRef
import dev.androidskills.module
import dev.androidskills.util.nowIso
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillOwnerRoutesTest {

    private val dir = TestSupport.tempDir()

    @AfterTest
    fun teardown() {
        dir.toFile().deleteRecursively()
    }

    private fun setupUserAndSkill(): Pair<String, String> {
        Database.init(TestSupport.newConfig(dir))
        val uid = UsersRepo.upsertFromGitHub(GitHubUser(42L, "alice", "Alice", null), null)
        val token = SessionStore.create(uid, 3600)
        val now = nowIso()
        val bid = "00000000-0000-0000-0000-000000000001"
        val sid = "00000000-0000-0000-0000-000000000002"
        transaction {
            Bundles.insert {
                it[Bundles.id] = bid
                it[Bundles.kind] = "repo"
                it[Bundles.provenance] = "owner/repo"
                it[Bundles.ownerUserId] = uid
                it[Bundles.installationId] = 1L
                it[Bundles.createdAt] = now
            }
            Skills.insert {
                it[Skills.id] = sid
                it[Skills.bundleId] = bid
                it[Skills.slug] = "my-skill"
                it[Skills.name] = "Skill"
                it[Skills.description] = "Desc"
                it[Skills.license] = "MIT"
                it[Skills.version] = "1.0.0"
                it[Skills.versionSource] = "manifest"
                it[Skills.status] = "published"
                it[Skills.verified] = true
                it[Skills.createdAt] = now
                it[Skills.updatedAt] = now
            }
        }
        return uid to token
    }

    private fun fakeGh() = object : GitHubAppClient {
        override val configured = true
        override suspend fun installations() = listOf(Installation(1L, 42L, "owner", "User"))
        override suspend fun listRepos(installationId: Long) = emptyList<RepoRef>()
        override suspend fun defaultBranchHead(installationId: Long, owner: String, repo: String) = "newsha123"
        override suspend fun downloadZipball(installationId: Long, owner: String, repo: String, ref: String) = byteArrayOf()
        override suspend fun verifyAndParseEvent(body: ByteArray, signature: String) = null
    }

    private fun fakeOAuth() = object : OAuthClient {
        override val configured = true
        override fun authorizeUrl(state: String, redirectUri: String) = ""
        override suspend fun exchange(code: String, redirectUri: String) = OAuthTokens("tok")
        override suspend fun userInfo(accessToken: String) = GitHubUser(42L, "alice", "Alice", null)
    }

    @Test
    fun `resync route enqueues job for owner`() = testApplication {
        val (_, token) = setupUserAndSkill()
        application {
            module(
                config = TestSupport.newConfig(dir),
                oauth = fakeOAuth(),
                githubApp = fakeGh(),
            )
        }
        val res = client.post("/api/skills/my-skill/resync") {
            header("Cookie", "as_session=$token")
        }
        assertEquals(HttpStatusCode.Accepted, res.status)
        assertTrue(res.bodyAsText().contains("\"ok\":true"))
        transaction {
            val job = Jobs.selectAll().single()
            assertEquals("resync", job[Jobs.type])
            assertEquals("queued", job[Jobs.state])
        }
    }

    @Test
    fun `resync route 403 for non-owner`() = testApplication {
        val (_, owner) = setupUserAndSkill()
        // A second user logs in and tries to resync the same skill.
        val otherUid = UsersRepo.upsertFromGitHub(GitHubUser(43L, "bob", "Bob", null), null)
        val otherToken = SessionStore.create(otherUid, 3600)
        application {
            module(
                config = TestSupport.newConfig(dir),
                oauth = fakeOAuth(),
                githubApp = fakeGh(),
            )
        }
        val res = client.post("/api/skills/my-skill/resync") {
            header("Cookie", "as_session=$otherToken")
        }
        assertEquals(HttpStatusCode.Forbidden, res.status)
    }

    @Test
    fun `resync route 404 for missing skill`() = testApplication {
        val (_, token) = setupUserAndSkill()
        application {
            module(
                config = TestSupport.newConfig(dir),
                oauth = fakeOAuth(),
                githubApp = fakeGh(),
            )
        }
        val res = client.post("/api/skills/no-such-skill/resync") {
            header("Cookie", "as_session=$token")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun `unpublish route 403 for non-owner`() = testApplication {
        setupUserAndSkill()
        val otherUid = UsersRepo.upsertFromGitHub(GitHubUser(43L, "bob", "Bob", null), null)
        val otherToken = SessionStore.create(otherUid, 3600)
        application {
            module(
                config = TestSupport.newConfig(dir),
                oauth = fakeOAuth(),
                githubApp = fakeGh(),
            )
        }
        val res = client.post("/api/skills/my-skill/unpublish") {
            header("Cookie", "as_session=$otherToken")
        }
        assertEquals(HttpStatusCode.Forbidden, res.status)
    }

    @Test
    fun `unpublish route 404 for missing skill`() = testApplication {
        val (_, token) = setupUserAndSkill()
        application {
            module(
                config = TestSupport.newConfig(dir),
                oauth = fakeOAuth(),
                githubApp = fakeGh(),
            )
        }
        val res = client.post("/api/skills/no-such-skill/unpublish") {
            header("Cookie", "as_session=$token")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun `resync route 503 when github app disabled`() = testApplication {
        val (_, token) = setupUserAndSkill()
        application {
            module(
                config = TestSupport.newConfig(dir),
                oauth = fakeOAuth(),
                githubApp = object : GitHubAppClient {
                    override val configured = false
                    override suspend fun installations() = throw GitHubAppException("")
                    override suspend fun listRepos(installationId: Long) = emptyList<RepoRef>()
                    override suspend fun defaultBranchHead(installationId: Long, owner: String, repo: String) = ""
                    override suspend fun downloadZipball(installationId: Long, owner: String, repo: String, ref: String) = byteArrayOf()
                    override suspend fun verifyAndParseEvent(body: ByteArray, signature: String) = null
                },
            )
        }
        val res = client.post("/api/skills/my-skill/resync") {
            header("Cookie", "as_session=$token")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
    }
}
