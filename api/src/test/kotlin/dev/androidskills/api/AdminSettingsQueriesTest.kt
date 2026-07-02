package dev.androidskills.api

import dev.androidskills.Database
import dev.androidskills.TestSupport
import dev.androidskills.db.AuditLog
import dev.androidskills.db.PlatformSettings
import dev.androidskills.db.Users
import dev.androidskills.util.appJson
import dev.androidskills.util.newId
import dev.androidskills.util.nowIso
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminSettingsQueriesTest {

    private val dir = TestSupport.tempDir()

    @BeforeTest
    fun setup() = Database.init(TestSupport.newConfig(dir))

    @AfterTest
    fun teardown() { dir.toFile().deleteRecursively() }

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
    fun `get returns default settings when absent`() {
        val settings = AdminSettingsQueries.get()
        assertEquals("manual", settings.reviewPolicy)
        assertEquals(1000, settings.tokenSoftCap)
        assertFalse(settings.llmEnabled)
    }

    @Test
    fun `put persists settings and writes audit`() {
        val admin = seedAdmin()
        val updated = AdminSettingsQueries.PlatformSettings(reviewPolicy = "auto_after_lint_threshold", tokenSoftCap = 500, llmEnabled = true)
        val result = AdminSettingsQueries.put(principal(admin), updated)
        assertEquals(updated, result)

        val stored = AdminSettingsQueries.get()
        assertEquals("auto_after_lint_threshold", stored.reviewPolicy)
        assertTrue(stored.llmEnabled)

        transaction {
            val audit = AuditLog.selectAll().where { AuditLog.action eq "settings.put" }.single()
            assertEquals("platform:settings", audit[AuditLog.target])
        }
    }
}
