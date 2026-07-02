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
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdminSkillQueriesTest {

    private val dir = TestSupport.tempDir()

    @BeforeTest
    fun setup() = Database.init(TestSupport.newConfig(dir))

    @AfterTest
    fun teardown() { dir.toFile().deleteRecursively() }

    private data class Seed(
        val adminId: String,
        val catId: String,
        val skillId: String,
    )

    private fun seed(): Seed {
        val adminId = newId()
        val bundleId = newId()
        val skillId = newId()
        val now = nowIso()
        val catId = transaction {
            Categories.selectAll().where { Categories.slug eq "kotlin-language" }.single()[Categories.id]
        }
        transaction {
            Users.insert {
                it[Users.id] = adminId
                it[Users.githubId] = 1
                it[Users.handle] = "admin"
                it[Users.role] = dev.androidskills.db.Role.admin.name
                it[Users.status] = dev.androidskills.db.UserStatus.active.name
                it[Users.createdAt] = now
                it[Users.updatedAt] = now
            }
            Bundles.insert {
                it[Bundles.id] = bundleId
                it[Bundles.kind] = "repo"
                it[Bundles.provenance] = "owner/repo"
                it[Bundles.ownerUserId] = adminId
                it[Bundles.createdAt] = now
            }
            Skills.insert {
                it[Skills.id] = skillId
                it[Skills.bundleId] = bundleId
                it[Skills.slug] = "mvi"
                it[Skills.name] = "MVI"
                it[Skills.description] = "d"
                it[Skills.version] = "1.0.0"
                it[Skills.versionSource] = "manifest"
                it[Skills.status] = "published"
                it[Skills.verified] = true
                it[Skills.featured] = false
                it[Skills.categoryId] = catId
                it[Skills.installs] = 5
                it[Skills.createdAt] = now
                it[Skills.updatedAt] = now
            }
        }
        return Seed(adminId, catId, skillId)
    }

    private fun principal(userId: String) = dev.androidskills.auth.Principal(
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

    @Test
    fun `list filters and searches skills`() {
        val s = seed()
        val items = AdminSkillQueries.list(filter = "published", search = "mv", sort = "updated", page = 1)
        assertEquals(1, items.size)
        assertEquals("mvi", items[0].slug)
        assertEquals("kotlin-language", items[0].categorySlug)
    }

    @Test
    fun `patch updates status and featured`() {
        val s = seed()
        AdminSkillQueries.patch(
            principal(s.adminId),
            s.skillId,
            AdminSkillQueries.AdminSkillPatch(status = "flagged", featured = true),
        )
        transaction {
            val skill = Skills.selectAll().where { Skills.id eq s.skillId }.single()
            assertEquals("flagged", skill[Skills.status])
            assertEquals(true, skill[Skills.featured])
            val audit = AuditLog.selectAll().where { AuditLog.target eq "skill:${s.skillId}" }.single()
            assertEquals("skill.patch", audit[AuditLog.action])
        }
    }

    @Test
    fun `patch rejects manifest fields`() {
        val s = seed()
        AdminSkillQueries.patch(
            principal(s.adminId),
            s.skillId,
            AdminSkillQueries.AdminSkillPatch(),
        )
        transaction {
            val skill = Skills.selectAll().where { Skills.id eq s.skillId }.single()
            assertEquals("MVI", skill[Skills.name])
        }
    }

    @Test
    fun `bulk features multiple skills`() {
        val s = seed()
        val response = AdminSkillQueries.bulk(
            principal(s.adminId),
            AdminSkillQueries.BulkActionRequest(ids = listOf(s.skillId), action = "feature"),
        )
        assertEquals(mapOf(s.skillId to true), response.results)
        assertTrue(response.failed.isEmpty())
        transaction {
            val skill = Skills.selectAll().where { Skills.id eq s.skillId }.single()
            assertEquals(true, skill[Skills.featured])
        }
    }

    @Test
    fun `bulk reports partial failures`() {
        val s = seed()
        val response = AdminSkillQueries.bulk(
            principal(s.adminId),
            AdminSkillQueries.BulkActionRequest(ids = listOf(s.skillId, "missing"), action = "feature"),
        )
        assertEquals(mapOf(s.skillId to true), response.results)
        assertEquals(1, response.failed.size)
        assertNotNull(response.failed["missing"])
    }

    @Test
    fun `bulk delete is a stub and returns failed`() {
        val s = seed()
        val response = AdminSkillQueries.bulk(
            principal(s.adminId),
            AdminSkillQueries.BulkActionRequest(ids = listOf(s.skillId), action = "delete"),
        )
        assertTrue(response.results.isEmpty())
        assertEquals(1, response.failed.size)
        assertNotNull(response.failed[s.skillId])
    }
}
