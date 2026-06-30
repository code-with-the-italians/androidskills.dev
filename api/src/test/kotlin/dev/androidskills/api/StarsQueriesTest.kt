package dev.androidskills.api

import dev.androidskills.Database
import dev.androidskills.TestSupport
import dev.androidskills.auth.GitHubUser
import dev.androidskills.auth.Principal
import dev.androidskills.auth.UsersRepo
import dev.androidskills.db.Bundles
import dev.androidskills.db.Categories
import dev.androidskills.db.Role
import dev.androidskills.db.Skills
import dev.androidskills.db.Stars
import dev.androidskills.db.UserStatus
import dev.androidskills.db.Users
import dev.androidskills.util.nowIso
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StarsQueriesTest {

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
        return uid to Principal(
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

    private fun insertPublishedSkill(ownerId: String, slug: String, categoryId: String? = null): String {
        val now = nowIso()
        val sid = "00000000-0000-0000-0000-${slug.padEnd(12, '0').take(12)}"
        transaction {
            Bundles.insertIgnore {
                it[Bundles.id] = "00000000-0000-0000-0000-${slug.padEnd(12, '0').take(12)}"
                it[Bundles.kind] = "repo"
                it[Bundles.provenance] = "owner/$slug"
                it[Bundles.ownerUserId] = ownerId
                it[Bundles.createdAt] = now
            }
            Skills.insert {
                it[Skills.id] = sid
                it[Skills.bundleId] = "00000000-0000-0000-0000-${slug.padEnd(12, '0').take(12)}"
                it[Skills.slug] = slug
                it[Skills.name] = slug.replace("-", " ")
                it[Skills.description] = "Desc"
                it[Skills.license] = "MIT"
                it[Skills.version] = "1.0.0"
                it[Skills.versionSource] = "manifest"
                it[Skills.categoryId] = categoryId
                it[Skills.status] = "published"
                it[Skills.verified] = true
                it[Skills.createdAt] = now
                it[Skills.updatedAt] = now
            }
        }
        return sid
    }

    @Test
    fun `addStar and listStars`() {
        setupDb()
        val (uid, principal) = createUser(42L, "alice")
        val catId = transaction {
            Categories.insert {
                it[Categories.id] = "00000000-0000-0000-0000-000000000000"
                it[Categories.slug] = "ui"
                it[Categories.name] = "UI"
            }
            "00000000-0000-0000-0000-000000000000"
        }
        insertPublishedSkill(uid, "skill-one", catId)
        insertPublishedSkill(uid, "skill-two", null)

        StarsQueries.addStar(principal, "skill-one")
        StarsQueries.addStar(principal, "skill-two")

        val stars = StarsQueries.listStars(principal)
        assertEquals(2, stars.size)
        val bySlug = stars.associateBy { it.slug }
        assertEquals("UI", bySlug["skill-one"]?.category)
        assertEquals(null, bySlug["skill-two"]?.category)
    }

    @Test
    fun `addStar is idempotent`() {
        setupDb()
        val (uid, principal) = createUser(42L, "alice")
        insertPublishedSkill(uid, "skill-one")

        StarsQueries.addStar(principal, "skill-one")
        StarsQueries.addStar(principal, "skill-one")

        assertEquals(1, transaction { Stars.selectAll().count() }.toInt())
    }

    @Test
    fun `removeStar deletes star`() {
        setupDb()
        val (uid, principal) = createUser(42L, "alice")
        insertPublishedSkill(uid, "skill-one")
        StarsQueries.addStar(principal, "skill-one")

        StarsQueries.removeStar(principal, "skill-one")

        assertEquals(0, transaction { Stars.selectAll().count() }.toInt())
    }

    @Test
    fun `addStar 404 for missing skill`() {
        setupDb()
        val (uid, principal) = createUser(42L, "alice")
        assertFailsWith<ApiNotFoundException> {
            StarsQueries.addStar(principal, "missing")
        }
    }
}
