package dev.androidskills.auth

import dev.androidskills.api.ApiNotFoundException
import dev.androidskills.db.Role
import dev.androidskills.db.UserStatus
import kotlinx.serialization.Serializable

/**
 * The authenticated caller, resolved per-request from the session cookie (spec §7). Kept
 * intentionally slim: it's the gate every protected route reads, so it carries exactly what
 * authorization needs (role/status) plus identity for `/api/me` and audit logging. Heavy profile
 * data stays in the `users` row.
 */
data class Principal(
  val sessionId: String,
  val userId: String,
  val githubId: Long,
  val handle: String,
  val name: String?,
  val avatarUrl: String?,
  val role: Role,
  val status: UserStatus,
  val createdAt: String,
)

/**
 * Authorization gates. The split keeps the security-relevant invariant in one place: **admin routes
 * never reveal their existence**.
 * - `requireSession()` → 401 when anonymous (contributor/`/api/me` routes).
 * - `requireAdmin()` → 404 when *anyone* other than an admin calls, including anonymous callers.
 *   Returning 401 (or 403) would leak that the route exists and merely needs higher privilege (spec
 *   §7: "non-admins get 404, not 403").
 *
 * `requireAdmin` therefore does **not** chain `requireSession` — an anonymous caller must also
 * receive 404.
 */
fun Principal.requireAdmin(): Principal =
  if (role == Role.admin) this else throw ApiNotFoundException("Not found")

@Serializable
data class MeResponse(
  val id: String,
  val handle: String,
  val name: String?,
  val avatarUrl: String?,
  val role: String,
  val status: String,
  val createdAt: String,
)

internal fun Principal.toMe() =
  MeResponse(
    id = userId,
    handle = handle,
    name = name,
    avatarUrl = avatarUrl,
    role = role.name,
    status = status.name,
    createdAt = createdAt,
  )
