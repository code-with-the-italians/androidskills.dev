package dev.androidskills.auth

import dev.androidskills.db.Role
import dev.androidskills.db.UserStatus
import dev.androidskills.db.Users
import dev.androidskills.util.newId
import dev.androidskills.util.nowIso
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** A GitHub user as returned by `GET /user` (only the fields we persist). */
data class GitHubUser(
    val githubId: Long,
    val handle: String,
    val name: String?,
    val avatarUrl: String?,
)

/**
 * Upserts a user from a GitHub identity (spec §7 callback). A new user is
 * inserted as `member` by default; [bootstrapAdminGithubId] is a **seed-only
 * escape hatch** that promotes a *new* user to `admin` on first insert. It is
 * intentionally INSERT-only: an existing user keeps whatever role the admin
 * queue (step 7, spec §3 rule 9) last gave them, so a bootstrap account that
 * was later demoted/suspended is **not** re-promoted on re-login. Unset it once
 * the first admin exists. Returns the user id.
 *
 * Handles are unique in `users`; GitHub handles are globally unique at a point in
 * time but can be recycled, so a collision with a *different* github_id is
 * resolved by suffixing `-{githubId}` rather than failing the login.
 */
object UsersRepo {
    /**
     * Upsert with a bounded retry for the one realistic race: two concurrent
     * logins resolving the same recycled handle both pass check-then-insert and
     * the second violates `uq_users_handle`. On that specific error we retry —
     * the next [uniqueHandle] call sees the winner's row. (P2-2b.)
     */
    fun upsertFromGitHub(user: GitHubUser, bootstrapAdminGithubId: Long?): String {
        var attempt = 0
        while (true) {
            try {
                return upsertOnce(user, bootstrapAdminGithubId)
            } catch (e: org.jetbrains.exposed.exceptions.ExposedSQLException) {
                if (++attempt > MAX_HANDLE_RETRIES || !isUniqueHandleViolation(e)) throw e
            }
        }
    }

    private const val MAX_HANDLE_RETRIES = 3

    private fun isUniqueHandleViolation(e: org.jetbrains.exposed.exceptions.ExposedSQLException): Boolean {
        val text = buildString {
            append(e.message); append(' ')
            var c: Throwable? = e.cause
            while (c != null) { append(c.message); append(' '); c = c.cause }
        }
        return text.contains("users.handle", ignoreCase = true)
    }

    private fun upsertOnce(user: GitHubUser, bootstrapAdminGithubId: Long?): String = transaction {
        val now = nowIso()
        val existing = Users.selectAll().where { Users.githubId eq user.githubId }.singleOrNull()
        if (existing != null) {
            val id = existing[Users.id]
            Users.update({ Users.id eq id }) {
                it[Users.handle] = uniqueHandle(user.handle, user.githubId, excludeId = id)
                it[Users.name] = user.name
                it[Users.avatarUrl] = user.avatarUrl
                // NOTE: role is NOT touched here — a bootstrap/demoted/suspended
                // user keeps their current role on re-login (P1-1).
                it[Users.updatedAt] = now
            }
            id
        } else {
            val id = newId()
            val role = if (bootstrapAdminGithubId != null && user.githubId == bootstrapAdminGithubId) Role.admin else Role.member
            Users.insert {
                it[Users.id] = id
                it[Users.githubId] = user.githubId
                it[Users.handle] = uniqueHandle(user.handle, user.githubId, excludeId = null)
                it[Users.name] = user.name
                it[Users.avatarUrl] = user.avatarUrl
                it[Users.role] = role.name
                it[Users.status] = UserStatus.active.name
                it[Users.createdAt] = now
                it[Users.updatedAt] = now
            }
            id
        }
    }

    /**
     * Resolves a `handle` that doesn't collide with another user. Walks a short
     * ladder (raw → {login}-{githubId} → …-2..10), then falls back to a full-UUID
     * suffix — which is **terminal and effectively unique**, so this never throws
     * (the prior `first{}` over a finite ladder could). [P2-2a.]
     *
     * The check-then-insert is inherently a TOCTOU race under concurrency; the
     * retry in [upsertFromGitHub] handles the losing side.
     */
    private fun uniqueHandle(handle: String, githubId: Long, excludeId: String?): String {
        val base = "$handle-$githubId"
        val candidates = sequence {
            yield(handle)
            yield(base)
            for (i in 2..10) yield("$base-$i")
        }
        return candidates.firstOrNull { h -> !handleTaken(h, excludeId) }
            ?: "$base-${newId()}" // full UUID: collision-proof, terminal
    }

    /** True if some *other* user already owns [handle] (excludeId is the self row). */
    private fun handleTaken(handle: String, excludeId: String?): Boolean =
        Users.selectAll().where { Users.handle eq handle }.any { it[Users.id] != excludeId }
}
