package dev.androidskills.api

import dev.androidskills.Database
import dev.androidskills.TestSupport
import dev.androidskills.auth.GitHubUser
import dev.androidskills.auth.Principal
import dev.androidskills.auth.SessionStore
import dev.androidskills.auth.UsersRepo
import dev.androidskills.db.Role
import dev.androidskills.db.UserStatus
import dev.androidskills.db.Users
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class UserQueriesTest {

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
    val row = transaction { Users.selectAll().where { Users.id eq uid }.single() }
    return uid to
      Principal(
        sessionId = "",
        userId = uid,
        githubId = githubId,
        handle = handle,
        name = handle,
        avatarUrl = null,
        role = Role.parse(row[Users.role]),
        status = UserStatus.parse(row[Users.status]),
        createdAt = row[Users.createdAt],
      )
  }

  @Test
  fun `getSettings returns defaults when unset`() {
    setupDb()
    val (_, principal) = createUser(42L, "alice")
    val settings = UserQueries.getSettings(principal)
    assertEquals(true, settings.emailNotifications)
    assertEquals(true, settings.publicProfile)
  }

  @Test
  fun `updateSettings persists and getSettings reads back`() {
    setupDb()
    val (_, principal) = createUser(42L, "alice")
    UserQueries.updateSettings(
      principal,
      UserQueries.UserSettings(emailNotifications = false, publicProfile = false),
    )
    val settings = UserQueries.getSettings(principal)
    assertEquals(false, settings.emailNotifications)
    assertEquals(false, settings.publicProfile)
  }

  @Test
  fun `deleteAccount soft deletes and blocks admin`() {
    setupDb()
    val (_, admin) = createUser(42L, "alice-admin")
    transaction { Users.update({ Users.id eq admin.userId }) { it[Users.role] = Role.admin.name } }
    assertFailsWith<ApiConflictException> { UserQueries.deleteAccount(admin) }
    assertNull(
      transaction {
        Users.selectAll().where { Users.id eq admin.userId }.singleOrNull()?.get(Users.deletedAt)
      }
    )

    val (_, member) = createUser(43L, "bob")
    UserQueries.deleteAccount(member)
    assertNotNull(
      transaction {
        Users.selectAll().where { Users.id eq member.userId }.singleOrNull()?.get(Users.deletedAt)
      }
    )
  }

  @Test
  fun `session lookup excludes deleted users`() {
    setupDb()
    val (uid, principal) = createUser(42L, "alice")
    val token = SessionStore.create(uid, 3600)
    assertNotNull(SessionStore.lookup(token))

    UserQueries.deleteAccount(principal)
    assertNull(SessionStore.lookup(token))
  }

  @Test
  fun `deleteAccount revokes existing sessions`() {
    setupDb()
    val (uid, principal) = createUser(42L, "alice")
    val token = SessionStore.create(uid, 3600)
    assertNotNull(SessionStore.lookup(token))

    UserQueries.deleteAccount(principal)

    assertNull(SessionStore.lookup(token))
  }

  @Test
  fun `upsertFromGitHub reactivates soft deleted account`() {
    setupDb()
    val (uid, principal) = createUser(42L, "alice")
    UserQueries.deleteAccount(principal)
    assertNotNull(
      transaction {
        Users.selectAll().where { Users.id eq uid }.singleOrNull()?.get(Users.deletedAt)
      }
    )

    UsersRepo.upsertFromGitHub(GitHubUser(42L, "alice", "Alice", null), null)
    assertNull(
      transaction {
        Users.selectAll().where { Users.id eq uid }.singleOrNull()?.get(Users.deletedAt)
      }
    )
  }
}
