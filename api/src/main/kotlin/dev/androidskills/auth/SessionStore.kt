package dev.androidskills.auth

import dev.androidskills.db.Role
import dev.androidskills.db.Sessions
import dev.androidskills.db.UserStatus
import dev.androidskills.db.Users
import dev.androidskills.util.nowIso
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.SecureRandom
import java.time.Instant

/**
 * DB-backed session store (spec §7). A session id is a 256-bit opaque token
 * carried by the cookie; the row maps it to a user and an expiry. Because the
 * lookup is a DB read on every request, suspend (status change) and logout
 * (row delete) revoke access *immediately* — the explicit reason for not using
 * stateless JWTs (architecture.md).
 *
 * [lookup] returns null for any of: unknown id, expired, missing user, or a
 * suspended user. That keeps the "suspended → reject" rule (§7) in one place.
 */
object SessionStore {
    private val rng = SecureRandom()

    /** Creates a session row for [userId]; returns the opaque token (cookie value). */
    fun create(userId: String, maxAgeSeconds: Long): String {
        val token = randomToken()
        val now = Instant.now()
        transaction {
            Sessions.insert {
                it[Sessions.id] = token
                it[Sessions.userId] = userId
                it[Sessions.createdAt] = nowIso()
                it[Sessions.expiresAt] = now.plusSeconds(maxAgeSeconds).toString()
            }
        }
        return token
    }

    fun lookup(token: String): Principal? = transaction {
        val row = Sessions.selectAll().where { Sessions.id eq token }.singleOrNull() ?: return@transaction null
        val now = Instant.now()
        val expiresAt = runCatching { Instant.parse(row[Sessions.expiresAt]) }.getOrNull()
            ?: return@transaction null
        if (expiresAt.isBefore(now)) return@transaction null
        val user = Users.selectAll().where { Users.id eq row[Sessions.userId] }.singleOrNull()
            ?: return@transaction null
        // A suspended user is never admitted — even mid-session.
        val status = runCatching { UserStatus.parse(user[Users.status]) }.getOrNull()
            ?: return@transaction null
        if (status != UserStatus.active) return@transaction null
        // A corrupted role is treated the same as a corrupted status: reject the
        // session rather than silently degrading to `member` (an unexpected role
        // value means the row shouldn't be trusted to authorize anything).
        val role = runCatching { Role.parse(user[Users.role]) }.getOrNull()
            ?: return@transaction null
        Principal(
            sessionId = token,
            userId = user[Users.id],
            handle = user[Users.handle],
            name = user[Users.name],
            avatarUrl = user[Users.avatarUrl],
            role = role,
            status = status,
            createdAt = user[Users.createdAt],
        )
    }

    fun delete(token: String): Int {
        // `deleteWhere`'s lambda receives ISqlExpressionBuilder (the interface),
        // which does NOT expose `eq`; only the SqlExpressionBuilder object does,
        // so we build the Op in that scope. (update()'s predicate, by contrast,
        // receives SqlExpressionBuilder directly and needs no wrapper.)
        val op = org.jetbrains.exposed.sql.SqlExpressionBuilder.run { Sessions.id eq token }
        return transaction { Sessions.deleteWhere { op } }
    }

    /** Opportunistic cleanup of expired rows; safe to call periodically. */
    fun purgeExpired(now: Instant = Instant.now()): Int {
        val nowStr = now.toString()
        val op = org.jetbrains.exposed.sql.SqlExpressionBuilder.run { Sessions.expiresAt lessEq nowStr }
        return transaction { Sessions.deleteWhere { op } }
    }

    /** 256-bit (32-byte) token, lower-case hex. */
    private fun randomToken(): String {
        val bytes = ByteArray(32)
        rng.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
