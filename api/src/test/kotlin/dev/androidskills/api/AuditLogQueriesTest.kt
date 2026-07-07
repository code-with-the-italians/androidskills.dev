package dev.androidskills.api

import dev.androidskills.Database
import dev.androidskills.TestSupport
import dev.androidskills.db.Users
import dev.androidskills.util.newId
import dev.androidskills.util.nowIso
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

class AuditLogQueriesTest {

  private val dir = TestSupport.tempDir()

  @BeforeTest fun setup() = Database.init(TestSupport.newConfig(dir))

  @AfterTest
  fun teardown() {
    dir.toFile().deleteRecursively()
  }

  private fun seedAdmin(): String {
    val id = newId()
    val now = nowIso()
    transaction {
      Users.insert {
        it[Users.id] = id
        it[Users.githubId] = 1
        it[Users.handle] = "admin"
        it[Users.role] = dev.androidskills.db.Role.admin.name
        it[Users.status] = dev.androidskills.db.UserStatus.active.name
        it[Users.createdAt] = now
        it[Users.updatedAt] = now
      }
    }
    return id
  }

  @Test
  fun `write and list audit entries`() {
    val admin = seedAdmin()
    AuditLogQueries.write(
      admin,
      "skill.patch",
      "skill:abc",
      AuditLogQueries.AuditMeta(note = "note"),
    )
    AuditLogQueries.write(admin, "user.patch", "user:xyz", null)

    val entries = AuditLogQueries.list()
    assertEquals(2, entries.size)

    val filtered = AuditLogQueries.list(action = "skill")
    assertEquals(1, filtered.size)
    assertEquals("skill.patch", filtered[0].action)
    assertEquals("admin", filtered[0].actorHandle)
  }
}
