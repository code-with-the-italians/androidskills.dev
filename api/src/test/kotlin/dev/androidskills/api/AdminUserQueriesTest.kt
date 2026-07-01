package dev.androidskills.api

import dev.androidskills.Database
import dev.androidskills.TestSupport
import dev.androidskills.db.AuditLog
import dev.androidskills.db.Role
import dev.androidskills.db.UserStatus
import dev.androidskills.db.Users
import dev.androidskills.util.newId
import dev.androidskills.util.nowIso
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AdminUserQueriesTest {

    private val dir = TestSupport.tempDir()

    @BeforeTest
    fun setup() = Database.init(TestSupport.newConfig(dir))

    @AfterTest
    fun teardown() { dir.toFile().deleteRecursively() }

    private data class Seed(val adminId: String, val userId: String)

    private fun seed(): Seed {
        val adminId = newId()
        val userId = newId()
        val now = nowIso()
        transaction {
            Users.insert {
                it[Users.id] = adminId
                it[Users.githubId] = 1
                it[Users.handle] = "admin"
                it[Users.role] = Role.admin.name
                it[Users.status] = UserStatus.active.name
                it[Users.createdAt] = now
                it[Users.updatedAt] = now
            }
            Users.insert {
                it[Users.id] = userId
                it[Users.githubId] = 2
                it[Users.handle] = "bob"
                it[Users.role] = Role.member.name
                it[Users.status] = UserStatus.active.name
                it[Users.createdAt] = now
                it[Users.updatedAt] = now
            }
        }
        return Seed(adminId, userId)
    }

    private fun principal(userId: String) = dev.androidskills.auth.Principal(
        sessionId = "s",
        userId = userId,
        githubId = 1,
        handle = "admin",
        name = null,
        avatarUrl = null,
        role = Role.admin,
        status = UserStatus.active,
        createdAt = nowIso(),
    )

    @Test
    fun `list users with search`() {
        val s = seed()
        val items = AdminUserQueries.list(search = "bob", page = 1)
        assertEquals(1, items.size)
        assertEquals("bob", items[0].handle)
    }

    @Test
    fun `patch role and status`() {
        val s = seed()
        AdminUserQueries.patch(
            principal(s.adminId),
            s.userId,
            AdminUserQueries.AdminUserPatch(role = "contributor", status = "suspended"),
        )
        transaction {
            val row = Users.selectAll().where { Users.id eq s.userId }.single()
            assertEquals(Role.contributor.name, row[Users.role])
            assertEquals(UserStatus.suspended.name, row[Users.status])
            val audit = AuditLog.selectAll().where { AuditLog.target eq "user:${s.userId}" }.single()
            assertEquals("user.patch", audit[AuditLog.action])
        }
    }

    @Test
    fun `self guard prevents admin modifying own role`() {
        val s = seed()
        val ex = assertFailsWith<ApiConflictException> {
            AdminUserQueries.patch(
                principal(s.adminId),
                s.adminId,
                AdminUserQueries.AdminUserPatch(role = "member"),
            )
        }
        assertEquals("admin_self_guard", ex.code)
    }

    @Test
    fun `last admin guard blocks demoting second to last admin`() {
        val s = seed()
        val secondAdmin = newId()
        transaction {
            Users.insert {
                it[Users.id] = secondAdmin
                it[Users.githubId] = 3
                it[Users.handle] = "other-admin"
                it[Users.role] = Role.admin.name
                it[Users.status] = UserStatus.active.name
                it[Users.createdAt] = nowIso()
                it[Users.updatedAt] = nowIso()
            }
        }
        // Demote the original admin (not self) while the other admin exists -> should succeed.
        AdminUserQueries.patch(
            principal(secondAdmin),
            s.adminId,
            AdminUserQueries.AdminUserPatch(role = "member"),
        )
        transaction {
            val row = Users.selectAll().where { Users.id eq s.adminId }.single()
            assertEquals(Role.member.name, row[Users.role])
        }

        // With only one admin left, demoting that last admin should fail.
        val ex = assertFailsWith<ApiConflictException> {
            AdminUserQueries.patch(
                principal(s.adminId), // now member, but principal is constructed as admin for the call
                secondAdmin,
                AdminUserQueries.AdminUserPatch(role = "member"),
            )
        }
        assertEquals("last_admin_guard", ex.code)
    }

    @Test
    fun `status-only patch on last admin does not trigger last admin guard`() {
        val s = seed()
        val secondAdmin = newId()
        transaction {
            Users.insert {
                it[Users.id] = secondAdmin
                it[Users.githubId] = 3
                it[Users.handle] = "other-admin"
                it[Users.role] = Role.admin.name
                it[Users.status] = UserStatus.active.name
                it[Users.createdAt] = nowIso()
                it[Users.updatedAt] = nowIso()
            }
        }
        // Reinstating a suspended admin should not hit last_admin_guard.
        transaction {
            Users.update({ Users.id eq secondAdmin }) {
                it[Users.status] = UserStatus.suspended.name
                it[Users.updatedAt] = nowIso()
            }
        }
        AdminUserQueries.patch(
            principal(s.adminId),
            secondAdmin,
            AdminUserQueries.AdminUserPatch(status = "active"),
        )
        transaction {
            val row = Users.selectAll().where { Users.id eq secondAdmin }.single()
            assertEquals(UserStatus.active.name, row[Users.status])
        }
    }

    @Test
    fun `csv export includes all users not just first page`() {
        val s = seed()
        transaction {
            repeat(55) { i ->
                Users.insert {
                    it[Users.id] = newId()
                    it[Users.githubId] = 100L + i
                    it[Users.handle] = "user-$i"
                    it[Users.role] = Role.member.name
                    it[Users.status] = UserStatus.active.name
                    it[Users.createdAt] = nowIso()
                    it[Users.updatedAt] = nowIso()
                }
            }
        }
        val csv = AdminUserQueries.exportCsv()
        assertTrue(csv.contains("user-0"), "CSV should include user beyond page 1")
        assertTrue(csv.contains("user-54"), "CSV should include the last seeded user")
        assertEquals(58, csv.lines().filter { it.isNotBlank() }.size)
    }

    @Test
    fun `csv export escapes formula injection`() {
        val s = seed()
        transaction {
            Users.update({ Users.id eq s.userId }) {
                it[Users.handle] = "@bad"
            }
        }
        val csv = AdminUserQueries.exportCsv()
        assertTrue(csv.contains("'@bad"))
        assertTrue(csv.contains("id,handle,role,status,skillCount,createdAt"))
    }
}
