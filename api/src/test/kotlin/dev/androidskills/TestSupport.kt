package dev.androidskills

import dev.androidskills.auth.GitHubUser
import dev.androidskills.auth.SessionStore
import dev.androidskills.auth.UsersRepo
import dev.androidskills.db.Role
import dev.androidskills.db.Sessions
import dev.androidskills.db.UserStatus
import dev.androidskills.db.Users
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** Shared test fixtures. Each test points the DB + FileStore at an isolated temp dir. */
object TestSupport {
  data class SeededSession(val userId: String, val handle: String, val session: String)

  fun newConfig(dir: Path, seedDemo: Boolean = false): AppConfig =
    AppConfig(
      version = "test",
      dbPath = dir.resolve("test.db"),
      fileStoreDir = dir.resolve("files"),
      llmBaseUrl = null,
      llmApiKey = null,
      llmModel = null,
      seedDemo = seedDemo,
      auth =
        AuthConfig(
          oauth = null,
          publicBaseUrl = "http://localhost:8080",
          sessionCookieDomain = null,
          // Localhost HTTP → relax Secure so the test client (and a dev browser) carry the cookie.
          sessionCookieSecure = false,
          bootstrapAdminGithubId = null,
        ),
    )

  fun tempDir(): Path = Files.createTempDirectory("asdb-test")

  /** Seed a user directly into the current Exposed default database. */
  fun seedUser(
    dir: Path? = null,
    githubId: Long? = null,
    handle: String = "testuser",
    role: Role = Role.member,
    status: UserStatus = UserStatus.active,
  ): SeededSession {
    val uid = UsersRepo.upsertFromGitHub(
      GitHubUser(
        githubId = githubId ?: (System.currentTimeMillis() + handle.hashCode()),
        handle = handle,
        name = handle.replaceFirstChar { it.uppercase() },
        avatarUrl = null,
      ),
      bootstrapAdminGithubId = null,
    )
    transaction {
      Users.update({ Users.id eq uid }) {
        it[Users.role] = role.name
        it[Users.status] = status.name
      }
    }
    val token = SessionStore.create(uid, 3600)
    return SeededSession(uid, transaction { Users.selectAll().where { Users.id eq uid }.single()[Users.handle] }, token)
  }

  fun seedAdmin(dir: Path? = null): SeededSession =
    seedUser(dir = dir, handle = "admin", role = Role.admin)
}
