package dev.androidskills.api

import dev.androidskills.Database
import dev.androidskills.TestSupport
import dev.androidskills.db.AuditLog
import dev.androidskills.db.Bundles
import dev.androidskills.db.Categories
import dev.androidskills.db.Skills
import dev.androidskills.db.Users
import dev.androidskills.util.newId
import dev.androidskills.util.nowIso
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class AdminCategoryQueriesTest {

  private val dir = TestSupport.tempDir()

  @BeforeTest fun setup() = Database.init(TestSupport.newConfig(dir))

  @AfterTest
  fun teardown() {
    dir.toFile().deleteRecursively()
  }

  private fun principal(userId: String) =
    dev.androidskills.auth.Principal(
      sessionId = "s",
      userId = userId,
      githubId = 1,
      handle = "admin",
      name = null,
      avatarUrl = null,
      role = dev.androidskills.db.Role.admin,
      status = dev.androidskills.db.UserStatus.active,
      createdAt = nowIso(),
    )

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
  fun `create and list categories`() {
    val admin = seedAdmin()
    val cat = AdminCategoryQueries.create(principal(admin), "new-category", "New Category")
    assertEquals("new-category", cat.slug)
    assertEquals("New Category", cat.name)
    assertTrue(AdminCategoryQueries.list().any { it.slug == "new-category" })
  }

  @Test
  fun `rename category`() {
    val admin = seedAdmin()
    val cat = AdminCategoryQueries.create(principal(admin), "old-slug", "Old")
    val updated =
      AdminCategoryQueries.rename(
        principal(admin),
        "old-slug",
        AdminCategoryQueries.RenameRequest(slug = "renamed-slug", name = "Renamed"),
      )
    assertEquals("renamed-slug", updated.slug)
    assertEquals("Renamed", updated.name)
    transaction {
      assertNull(Categories.selectAll().where { Categories.slug eq "old-slug" }.singleOrNull())
    }
  }

  @Test
  fun `merge reassigns skills and deletes source`() {
    val admin = seedAdmin()
    val fromId = transaction {
      Categories.selectAll().where { Categories.slug eq "build-ci" }.single()[Categories.id]
    }
    val toId = transaction {
      Categories.selectAll().where { Categories.slug eq "kotlin-language" }.single()[Categories.id]
    }
    val bundleId = newId()
    val skillId = newId()
    val now = nowIso()
    transaction {
      Bundles.insert {
        it[Bundles.id] = bundleId
        it[Bundles.kind] = "repo"
        it[Bundles.provenance] = "owner/repo"
        it[Bundles.ownerUserId] = admin
        it[Bundles.createdAt] = now
      }
      Skills.insert {
        it[Skills.id] = skillId
        it[Skills.bundleId] = bundleId
        it[Skills.slug] = "sample"
        it[Skills.name] = "Sample"
        it[Skills.description] = "d"
        it[Skills.version] = "1.0.0"
        it[Skills.versionSource] = "manifest"
        it[Skills.status] = "published"
        it[Skills.categoryId] = fromId
        it[Skills.createdAt] = now
        it[Skills.updatedAt] = now
      }
    }
    AdminCategoryQueries.merge(principal(admin), "build-ci", "kotlin-language")
    transaction {
      val skill = Skills.selectAll().where { Skills.id eq skillId }.single()
      assertEquals(toId, skill[Skills.categoryId])
      assertNull(Categories.selectAll().where { Categories.slug eq "build-ci" }.singleOrNull())
      val audit = AuditLog.selectAll().where { AuditLog.action eq "category.merge" }.single()
      assertEquals("category:build-ci", audit[AuditLog.target])
    }
  }

  @Test
  fun `merge into self throws conflict`() {
    val admin = seedAdmin()
    val ex =
      assertFailsWith<ApiConflictException> {
        AdminCategoryQueries.merge(principal(admin), "kotlin-language", "kotlin-language")
      }
    assertEquals("merge_self", ex.code)
  }
}
