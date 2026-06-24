package dev.androidskills.api

import dev.androidskills.Database
import dev.androidskills.TestSupport
import dev.androidskills.auth.UsersRepo
import dev.androidskills.auth.GitHubUser
import dev.androidskills.github.GitHubAppClient
import dev.androidskills.github.GitHubAppException
import dev.androidskills.github.Installation
import dev.androidskills.github.RepoRef
import dev.androidskills.github.GithubWebhookEvent
import dev.androidskills.module
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contributor routes via a fake GitHubAppClient (no network). Covers: 503 when
 * disabled; the Q3 personal-installation filter; /scan happy path + no_skills_dir
 * 422 + 401 anon; GitHub upstream failures → 502.
 */
class ContributorRoutesTest {

    private val dir = TestSupport.tempDir()

    @AfterTest
    fun teardown() { dir.toFile().deleteRecursively() }

    /** Controllable App client: no network. */
    private class FakeApp(
        val installations: List<Installation> = emptyList(),
        val repos: Map<Long, List<RepoRef>> = emptyMap(),
        val zipballs: Map<String, ByteArray> = emptyMap(),
        val heads: Map<String, String> = emptyMap(),
        val failInstallations: Boolean = false,
    ) : GitHubAppClient {
        override val configured = true
        override suspend fun installations(): List<Installation> {
            if (failInstallations) throw GitHubAppException("boom")
            return installations
        }
        override suspend fun listRepos(installationId: Long): List<RepoRef> =
            repos[installationId] ?: emptyList()
        override suspend fun defaultBranchHead(installationId: Long, owner: String, repo: String): String =
            heads["$owner/$repo"] ?: "mainsha"
        override suspend fun downloadZipball(installationId: Long, owner: String, repo: String, ref: String): ByteArray =
            zipballs["$owner/$repo@$ref"] ?: throw GitHubAppException("no zipball")
        override suspend fun verifyAndParseEvent(body: ByteArray, signature: String): GithubWebhookEvent? = null
    }

    private suspend fun loginPrincipal(): Pair<Long, ByteArray> {
        // Seed a user + session, return (githubId, sessionCookie) for the fake client's filter.
        Database.init(TestSupport.newConfig(dir))
        val userId = UsersRepo.upsertFromGitHub(GitHubUser(githubId = 42, handle = "alice", name = "Alice", avatarUrl = null), null)
        val token = dev.androidskills.auth.SessionStore.create(userId, 3600)
        return 42L to token.toByteArray()
    }

    private fun runWith(app: GitHubAppClient, block: suspend (client: HttpClient, cookie: String) -> Unit) {
        val (_, tokenBytes) = kotlinx.coroutines.runBlocking { loginPrincipal() }
        val cookie = String(tokenBytes)
        testApplication {
            application { module(TestSupport.newConfig(dir), githubApp = app) }
            val client = createClient { install(HttpCookies) }
            block(client, cookie)
        }
    }

    @Test
    fun reposReturns503WhenDisabled() = runWith(dev.androidskills.github.DisabledGitHubAppClient()) { client, _ ->
        val res = client.get("/api/me/repos")
        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
        assertTrue(res.bodyAsText().contains("github_app_disabled"))
    }

    @Test
    fun reposListsPersonalInstallationReposOnly() = runWith(FakeApp(
        installations = listOf(
            Installation(1, accountId = 42, accountLogin = "alice", accountType = "User"),     // mine
            Installation(2, accountId = 999, accountLogin = "other", accountType = "User"),    // not mine (Q3)
        ),
        repos = mapOf(
            1L to listOf(RepoRef("alice", "toolkit", "alice/toolkit", "main")),
            2L to listOf(RepoRef("other", "secret", "other/secret", "main")),
        ),
    )) { client, cookie ->
        val res = client.get("/api/me/repos") { header("Cookie", "as_session=$cookie") }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue(body.contains("alice/toolkit"), body)
        assertTrue(!body.contains("other/secret"), "personal-only filter (Q3); body=$body")
    }

    @Test
    fun scanDetectsSkills() = runWith(FakeApp(
        installations = listOf(Installation(1, accountId = 42, accountLogin = "alice", accountType = "User")),
        heads = mapOf("alice/toolkit" to "abc1234567890abcd"),
        zipballs = mapOf("alice/toolkit@abc1234567890abcd" to repoZipballWithSkills()),
    )) { client, cookie ->
        val res = client.post("/api/me/repos/alice/toolkit/scan") { header("Cookie", "as_session=$cookie") }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue(body.contains("\"slug\":\"mvi\""), body)
        assertTrue(body.contains("\"commitSha\":\"abc1234567890abcd\""), body)
    }

    @Test
    fun scanReturns422NoSkillsDir() = runWith(FakeApp(
        installations = listOf(Installation(1, accountId = 42, accountLogin = "alice", accountType = "User")),
        heads = mapOf("alice/toolkit" to "sha1"),
        zipballs = mapOf("alice/toolkit@sha1" to repoZipballNoSkills()),
    )) { client, cookie ->
        val res = client.post("/api/me/repos/alice/toolkit/scan") { header("Cookie", "as_session=$cookie") }
        assertEquals(HttpStatusCode.UnprocessableEntity, res.status)
        assertTrue(res.bodyAsText().contains("no_skills_dir"))
    }

    @Test
    fun reposRequiresSession() = runWith(FakeApp(
        installations = emptyList(), repos = emptyMap(),
    )) { client, _ ->
        // No session cookie → 401.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me/repos").status)
    }

    @Test
    fun upstreamGitHubFailureMapsTo502() = runWith(FakeApp(
        installations = emptyList(), repos = emptyMap(), failInstallations = true,
    )) { client, cookie ->
        val res = client.get("/api/me/repos") { header("Cookie", "as_session=$cookie") }
        assertEquals(HttpStatusCode.BadGateway, res.status)
        assertTrue(res.bodyAsText().contains("github_installations_failed"))
    }

    // ---- in-memory zip fixtures (gotcha #1: repo zipballs have the wrapper dir) ----

    private fun repoZipballWithSkills(): ByteArray = zip(
        "alice-toolkit-abc/SKILL.md" to "---\nname: Ignored\ndescription: d\nlicense: MIT\n---\n", // root — ignored
        "alice-toolkit-abc/skills/mvi/SKILL.md" to skillMd("MVI", "Predictable MVI baseline."),
    )

    private fun repoZipballNoSkills(): ByteArray = zip(
        "alice-toolkit-abc/README.md" to "readme",
        "alice-toolkit-abc/SKILL.md" to skillMd("Ignored", "d"), // root, ignored → NoSkillsDir
    )

    private fun skillMd(name: String, desc: String) =
        "---\nname: $name\ndescription: $desc\nlicense: Apache-2.0\ntags: [android]\n---\n# $name\n"

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name)); zos.write(content.toByteArray()); zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
