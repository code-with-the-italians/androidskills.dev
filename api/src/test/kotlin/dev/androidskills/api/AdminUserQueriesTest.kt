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
    fun `last admin guard prevents demotion`() {
        val s = seed()
        // Remove the second admin if any; here only one admin exists.
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
