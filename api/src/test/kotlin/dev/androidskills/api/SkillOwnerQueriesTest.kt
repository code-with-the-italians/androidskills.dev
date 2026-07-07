package dev.androidskills.api

import dev.androidskills.Database
import dev.androidskills.TestSupport
import dev.androidskills.auth.GitHubUser
import dev.androidskills.auth.Principal
import dev.androidskills.auth.UsersRepo
import dev.androidskills.db.Bundles
import dev.androidskills.db.Jobs
import dev.androidskills.db.Role
import dev.androidskills.db.Skills
import dev.androidskills.db.UserStatus
import dev.androidskills.db.Users
import dev.androidskills.util.nowIso
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class SkillOwnerQueriesTest {

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

  private fun insertSkill(
    owner: Principal,
    slug: String,
    status: String = "published",
  ): Pair<String, String> {
    val now = nowIso()
    val bid = "00000000-0000-0000-0000-000000000001"
    val sid = "00000000-0000-0000-0000-000000000002"
    transaction {
      Bundles.insert {
        it[Bundles.id] = bid
        it[Bundles.kind] = "repo"
        it[Bundles.provenance] = "owner/repo"
        it[Bundles.ownerUserId] = owner.userId
        it[Bundles.installationId] = 1L
        it[Bundles.createdAt] = now
      }
      Skills.insert {
        it[Skills.id] = sid
        it[Skills.bundleId] = bid
        it[Skills.slug] = slug
        it[Skills.name] = "Skill"
        it[Skills.description] = "Desc"
        it[Skills.license] = "MIT"
        it[Skills.version] = "1.0.0"
        it[Skills.versionSource] = "manifest"
        it[Skills.status] = status
        it[Skills.verified] = true
        it[Skills.createdAt] = now
        it[Skills.updatedAt] = now
      }
    }
    return sid to bid
  }

  @Test
  fun `unpublish flips status and verified`() {
    setupDb()
    val (_, owner) = createUser(42L, "alice")
    insertSkill(owner, "my-skill")

    SkillOwnerQueries.unpublish(owner, "my-skill")

    transaction {
      val skill = Skills.selectAll().single()
      assertEquals("unlisted", skill[Skills.status])
      assertEquals(false, skill[Skills.verified])
    }
  }

  @Test
  fun `unpublish 403 for non-owner`() {
    setupDb()
    val (_, owner) = createUser(42L, "alice")
    val (_, other) = createUser(43L, "bob")
    insertSkill(owner, "my-skill")

    assertFailsWith<ApiForbiddenException> { SkillOwnerQueries.unpublish(other, "my-skill") }
  }

  @Test
  fun `unpublish 404 for missing skill`() {
    setupDb()
    val (_, owner) = createUser(42L, "alice")
    assertFailsWith<ApiNotFoundException> { SkillOwnerQueries.unpublish(owner, "missing") }
  }

  @Test
  fun `enqueueResync creates resync job`() {
    setupDb()
    val (_, owner) = createUser(42L, "alice")
    insertSkill(owner, "my-skill")

    SkillOwnerQueries.enqueueResync(owner, "my-skill", "newsha")

    transaction {
      val job = Jobs.selectAll().single()
      assertEquals("resync", job[Jobs.type])
      assertEquals("queued", job[Jobs.state])
    }
  }

  @Test
  fun `enqueueResync 403 for non-owner`() {
    setupDb()
    val (_, owner) = createUser(42L, "alice")
    val (_, other) = createUser(43L, "bob")
    insertSkill(owner, "my-skill")

    assertFailsWith<ApiForbiddenException> {
      SkillOwnerQueries.enqueueResync(other, "my-skill", "newsha")
    }
  }

  @Test
  fun `enqueueResync 404 for missing skill`() {
    setupDb()
    val (_, owner) = createUser(42L, "alice")
    assertFailsWith<ApiNotFoundException> {
      SkillOwnerQueries.enqueueResync(owner, "missing", "newsha")
    }
  }
}
