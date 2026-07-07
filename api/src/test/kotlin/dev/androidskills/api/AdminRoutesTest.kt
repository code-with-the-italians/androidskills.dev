package dev.androidskills.api

import dev.androidskills.Database
import dev.androidskills.TestSupport
import dev.androidskills.auth.GitHubUser
import dev.androidskills.auth.OAuthClient
import dev.androidskills.auth.OAuthTokens
import dev.androidskills.auth.SessionStore
import dev.androidskills.auth.UsersRepo
import dev.androidskills.db.Role
import dev.androidskills.db.Users
import dev.androidskills.module
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class AdminRoutesTest {

  private val dir = TestSupport.tempDir()

  @AfterTest
  fun teardown() {
    dir.toFile().deleteRecursively()
  }

  private fun setupUser(role: Role, githubId: Long = 1L): String {
    Database.init(TestSupport.newConfig(dir))
    val uid = UsersRepo.upsertFromGitHub(GitHubUser(githubId, "alice", "Alice", null), null)
    transaction { Users.update({ Users.id eq uid }) { it[Users.role] = role.name } }
    return SessionStore.create(uid, 3600)
  }

  private fun fakeOAuth() =
    object : OAuthClient {
      override val configured = true

      override fun authorizeUrl(state: String, redirectUri: String) = ""

      override suspend fun exchange(code: String, redirectUri: String) = OAuthTokens("tok")

      override suspend fun userInfo(accessToken: String) = GitHubUser(1L, "alice", "Alice", null)
    }

  @Test
  fun `unknown admin route returns 404 identical to gated real route`() = testApplication {
    val token = setupUser(Role.member)
    application { module(TestSupport.newConfig(dir), oauth = fakeOAuth()) }
    val real = client.get("/api/admin/queue") { header("Cookie", "as_session=$token") }
    val unknown = client.get("/api/admin/xxx") { header("Cookie", "as_session=$token") }
    assertEquals(HttpStatusCode.NotFound, real.status)
    assertEquals(HttpStatusCode.NotFound, unknown.status)
    assertEquals(real.bodyAsText(), unknown.bodyAsText())
  }

  @Test
  fun `admin queue returns 404 for non admin`() = testApplication {
    val token = setupUser(Role.member)
    application { module(TestSupport.newConfig(dir), oauth = fakeOAuth()) }
    val res = client.get("/api/admin/queue") { header("Cookie", "as_session=$token") }
    assertEquals(HttpStatusCode.NotFound, res.status)
  }

  @Test
  fun `admin queue returns 200 for admin`() = testApplication {
    val token = setupUser(Role.admin)
    application { module(TestSupport.newConfig(dir), oauth = fakeOAuth()) }
    val res = client.get("/api/admin/queue") { header("Cookie", "as_session=$token") }
    assertEquals(HttpStatusCode.OK, res.status)
    assertTrue(res.bodyAsText().startsWith("["))
  }

  @Test
  fun `admin queue returns 404 for anonymous`() = testApplication {
    application { module(TestSupport.newConfig(dir), oauth = fakeOAuth()) }
    assertEquals(HttpStatusCode.NotFound, client.get("/api/admin/queue").status)
  }
}
