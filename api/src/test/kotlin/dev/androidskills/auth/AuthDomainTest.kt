package dev.androidskills.auth

import dev.androidskills.Database
import dev.androidskills.TestSupport
import dev.androidskills.db.Role
import dev.androidskills.db.Sessions
import dev.androidskills.db.UserStatus
import dev.androidskills.db.Users
import dev.androidskills.util.nowIso
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DB-level auth: session lifecycle (incl. expiry + suspended revocation) and user upsert. */
class AuthDomainTest {
    private val dir = TestSupport.tempDir()

    @BeforeTest
    fun setup() = Database.init(TestSupport.newConfig(dir))

    @AfterTest
    fun teardown() { dir.toFile().deleteRecursively() }

    private fun seedUser(githubId: Long = 1L, handle: String = "alice"): String =
        UsersRepo.upsertFromGitHub(GitHubUser(githubId, handle, "Alice", "https://av/$handle.png"), bootstrapAdminGithubId = null)

    /** Plants a user row with an EXACT handle (bypassing uniqueHandle) to stage collisions. */
    private fun plantUser(handle: String, githubId: Long): String = transaction {
        val id = dev.androidskills.util.newId()
        val now = dev.androidskills.util.nowIso()
        Users.insert {
            it[Users.id] = id; it[Users.githubId] = githubId; it[Users.handle] = handle
            it[Users.name] = "planted"; it[Users.role] = Role.member.name
            it[Users.status] = UserStatus.active.name
            it[Users.createdAt] = now; it[Users.updatedAt] = now
        }
        id
    }

    @Test
    fun `session create then lookup returns a member principal`() {
        val userId = seedUser()
        val token = SessionStore.create(userId, 3600)
        assertEquals(64, token.length) // 32 bytes hex
        val p = SessionStore.lookup(token)
        assertNotNull(p)
        assertEquals(userId, p.userId)
        assertEquals("alice", p.handle)
        assertEquals(Role.member, p.role)
        assertEquals(UserStatus.active, p.status)
    }

    @Test
    fun `lookup returns null for unknown token`() {
        assertNull(SessionStore.lookup("deadbeef".repeat(8)))
    }

    @Test
    fun `expired session is rejected`() {
        val userId = seedUser()
        val token = SessionStore.create(userId, 3600)
        transaction {
            Sessions.update({ org.jetbrains.exposed.sql.SqlExpressionBuilder.run { Sessions.id eq token } }) {
                it[Sessions.expiresAt] = Instant.now().minusSeconds(60).toString()
            }
        }
        assertNull(SessionStore.lookup(token))
    }

    @Test
    fun `suspended user is rejected mid-session`() {
        // The DB-backed revocation invariant (spec §7): suspend → session immediately invalid.
        val userId = seedUser()
        val token = SessionStore.create(userId, 3600)
        assertNotNull(SessionStore.lookup(token))
        transaction {
            Users.update({ org.jetbrains.exposed.sql.SqlExpressionBuilder.run { Users.id eq userId } }) {
                it[Users.status] = UserStatus.suspended.name
            }
        }
        assertNull(SessionStore.lookup(token), "a suspended user must not resolve to a principal")
    }

    @Test
    fun `delete invalidates the session`() {
        val userId = seedUser()
        val token = SessionStore.create(userId, 3600)
        SessionStore.delete(token)
        assertNull(SessionStore.lookup(token))
    }

    @Test
    fun `purgeExpired removes only expired rows`() {
        val userId = seedUser()
        val live = SessionStore.create(userId, 3600)
        val expired = SessionStore.create(userId, 3600)
        transaction {
            Sessions.update({ org.jetbrains.exposed.sql.SqlExpressionBuilder.run { Sessions.id eq expired } }) {
                it[Sessions.expiresAt] = Instant.now().minusSeconds(60).toString()
            }
        }
        val removed = SessionStore.purgeExpired()
        assertEquals(1, removed)
        assertNotNull(SessionStore.lookup(live))
        assertNull(SessionStore.lookup(expired))
    }

    @Test
    fun `upsert inserts new as member and updates existing`() {
        val id1 = UsersRepo.upsertFromGitHub(GitHubUser(7, "bob", "Bob", null), null)
        val row = transaction { Users.selectAll().where { Users.githubId eq 7L }.single() }
        assertEquals(Role.member.name, row[Users.role])
        // Re-upsert with changed name/handle → same id, updated fields.
        val id2 = UsersRepo.upsertFromGitHub(GitHubUser(7, "bob", "Bobby", "https://av/bob.png"), null)
        assertEquals(id1, id2)
        val row2 = transaction { Users.selectAll().where { Users.githubId eq 7L }.single() }
        assertEquals("Bobby", row2[Users.name])
    }

    @Test
    fun `bootstrap admin github id is promoted on upsert`() {
        val id = UsersRepo.upsertFromGitHub(GitHubUser(99, "root", "Root", null), bootstrapAdminGithubId = 99L)
        val role = transaction { Users.selectAll().where { Users.id eq id }.single()[Users.role] }
        assertEquals(Role.admin.name, role)
        // A different user stays member.
        val other = UsersRepo.upsertFromGitHub(GitHubUser(100, "other", "Other", null), bootstrapAdminGithubId = 99L)
        val otherRole = transaction { Users.selectAll().where { Users.id eq other }.single()[Users.role] }
        assertEquals(Role.member.name, otherRole)
    }

    @Test
    fun `bootstrap admin is not re-promoted after being demoted (INSERT-only)`() {
        // P1-1: the bootstrap id must promote only on FIRST insert. Once the
        // admin queue (step 7) demotes the bootstrap account, a re-login must
        // NOT re-promote it just because BOOTSTRAP_ADMIN_GITHUB_ID is still set
        // (it's a Kamal secret nobody rotates).
        val id = UsersRepo.upsertFromGitHub(GitHubUser(99, "root", "Root", null), bootstrapAdminGithubId = 99L)
        assertEquals(Role.admin.name, transaction { Users.selectAll().where { Users.id eq id }.single()[Users.role] })
        // Admin queue demotes root to member.
        transaction {
            Users.update({ org.jetbrains.exposed.sql.SqlExpressionBuilder.run { Users.id eq id } }) {
                it[Users.role] = Role.member.name
            }
        }
        // Re-login (same bootstrap id still set) → stays member, NOT re-promoted.
        val id2 = UsersRepo.upsertFromGitHub(GitHubUser(99, "root", "Root", null), bootstrapAdminGithubId = 99L)
        assertEquals(id, id2)
        assertEquals(Role.member.name, transaction { Users.selectAll().where { Users.id eq id2 }.single()[Users.role] },
            "a demoted bootstrap user must not be re-promoted on re-login")
    }

    @Test
    fun `handle collision with a different github id is disambiguated not failed`() {
        UsersRepo.upsertFromGitHub(GitHubUser(1, "recycled", "A", null), null)
        // A second, different github id claims the same handle → must not crash the login.
        val id2 = UsersRepo.upsertFromGitHub(GitHubUser(2, "recycled", "B", null), null)
        val handle = transaction { Users.selectAll().where { Users.id eq id2 }.single()[Users.handle] }
        assertNotEquals("recycled", handle)
        assertTrue(handle.startsWith("recycled-"), "expected a disambiguated handle, got $handle")
    }

    @Test
    fun `uniqueHandle never crashes even when raw and suffixed forms are taken`() {
        // Both "taken" and "taken-3" are held by other users. The disambiguation
        // ladder must walk past them (…-2, …-4, …) instead of throwing and 500-ing
        // the login. (Bugbot finding: `first` on a 2-element sequence threw.)
        seedUser(githubId = 1, handle = "taken")
        seedUser(githubId = 2, handle = "taken-3")
        val id = UsersRepo.upsertFromGitHub(GitHubUser(3, "taken", "C", null), null)
        val handle = transaction { Users.selectAll().where { Users.id eq id }.single()[Users.handle] }
        // New user (githubId 3) must still log in: not "taken", not "taken-3".
        assertTrue(handle.startsWith("taken-3") || handle.startsWith("taken-"), "got $handle")
        assertNotEquals("taken", handle)
        assertNotEquals("taken-3", handle)
    }

    @Test
    fun `session lookup rejects a corrupted role value`() {
        // Asymmetric with status: a corrupted role used to fall back to `member`,
        // handing a valid session to a row that shouldn't be trusted. Reject it.
        val userId = seedUser()
        val token = SessionStore.create(userId, 3600)
        assertNotNull(SessionStore.lookup(token))
        transaction {
            Users.update({ org.jetbrains.exposed.sql.SqlExpressionBuilder.run { Users.id eq userId } }) {
                it[Users.role] = "superuser" // not a valid Role
            }
        }
        assertNull(SessionStore.lookup(token), "a corrupted role must not yield a member session")
    }

    @Test
    fun `newState is 64 hex chars and each is unique`() {
        val a = newState(); val b = newState(); val c = newState()
        assertTrue(a.matches(Regex("^[0-9a-f]{64}$")), "got $a")
        assertEquals(setOf(a, b, c).size, 3, "state must not repeat")
    }

    @Test
    fun `uniqueHandle falls back to a full uuid when the whole ladder is taken`() {
        // Exhaust every candidate on the ladder for githubId=3 (handle "taken"):
        // "taken", "taken-3", "taken-3-2".."taken-3-10" = 11 handles, all held by
        // other users. firstOrNull must then be null and the full-UUID fallback is
        // used — never a NoSuchElementException → 500 mid-login. (P2-2a.)
        plantUser("taken", 10)
        plantUser("taken-3", 11)
        for (i in 2..10) plantUser("taken-3-$i", 20L + i)
        val id = UsersRepo.upsertFromGitHub(GitHubUser(3, "taken", "C", null), null)
        val handle = transaction { Users.selectAll().where { Users.id eq id }.single()[Users.handle] }
        assertTrue(handle.startsWith("taken-3-"), "got $handle")
        assertNotEquals("taken-3", handle)
        // The uuid fallback is longer than any ladder rung.
        assertTrue(handle.length > "taken-3-10".length, "expected uuid suffix, got $handle")
    }

    @Test
    fun `concurrent logins with the same handle all succeed with distinct handles`() {
        // P2-2b: the check-then-insert race under concurrency. Three identities
        // claim "shared" at once; one wins the raw handle, the others trip
        // uq_users_handle and must retry into {handle}-{githubId}. None should 500.
        val handles = kotlinx.coroutines.runBlocking {
            val gids = listOf(100L, 101L, 102L)
            kotlinx.coroutines.coroutineScope {
                gids.map { gid ->
                    async(Dispatchers.IO) {
                        UsersRepo.upsertFromGitHub(GitHubUser(gid, "shared", "S", null), null) to gid
                    }
                }.awaitAll()
            }
        }
        assertEquals(3, handles.size)
        val byHandle = transaction { Users.selectAll().where { Users.githubId inList listOf(100L, 101L, 102L) }
            .associate { it[Users.githubId] to it[Users.handle] } }
        assertEquals(3, byHandle.size, "all three logins must create a user")
        assertEquals(3, byHandle.values.distinct().size, "handles must be distinct: $byHandle")
        assertTrue(byHandle.values.all { it.startsWith("shared") }, "$byHandle")
    }
}
