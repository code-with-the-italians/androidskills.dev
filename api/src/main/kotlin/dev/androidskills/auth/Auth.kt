package dev.androidskills.auth

import dev.androidskills.AuthConfig
import dev.androidskills.api.ApiNotFoundException
import dev.androidskills.api.ApiUnauthorizedException
import dev.androidskills.db.Role
import io.ktor.http.Cookie
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey

/**
 * Per-request auth (spec §7). The session id lives in an httpOnly + Secure +
 * SameSite=Lax cookie; the row is looked up in the DB on every request so that
 * suspend/logout revoke immediately (architecture.md: why not stateless JWTs).
 *
 * [principal] is resolved lazily and memoized per call, so a handler that calls
 * both `requireSession()` and later `principal()` hits the DB once. The cookie
 * is *opaque* (a 256-bit random token) — it carries no claims and cannot be
 * forged without the row.
 */
internal const val SESSION_COOKIE = "as_session"
internal const val STATE_COOKIE = "as_oauth_state"
internal const val STATE_TTL_SECONDS = 10 * 60L
internal const val SESSION_MAX_AGE_SECONDS = 30 * 24 * 3_600L

private class PrincipalBox { var resolved = false; var value: Principal? = null }
private val PrincipalKey = AttributeKey<PrincipalBox>("asPrincipalBox")

/** Resolves and memoizes the caller's principal (null if anonymous/expired/suspended). */
suspend fun ApplicationCall.principal(): Principal? {
    val box = attributes.getOrNull(PrincipalKey) ?: PrincipalBox().also { attributes.put(PrincipalKey, it) }
    if (!box.resolved) {
        val token = request.cookies[SESSION_COOKIE]
        box.value = token?.let { SessionStore.lookup(it) }
        box.resolved = true
    }
    return box.value
}

/** Session required → 401 when anonymous/expired/suspended. */
suspend fun ApplicationCall.requireSession(): Principal =
    principal() ?: throw ApiUnauthorizedException("Authentication required")

/**
 * Admin required → **404** for everyone who isn't an admin, including anonymous
 * callers. Never 401/403: that would reveal the route exists (spec §7).
 */
suspend fun ApplicationCall.requireAdmin(): Principal =
    principal()?.requireAdmin() ?: throw ApiNotFoundException("Not found")

// ---- cookies --------------------------------------------------------------

internal fun setSessionCookie(call: ApplicationCall, token: String, cfg: AuthConfig) {
    call.response.cookies.append(sessionCookie(SESSION_COOKIE, token, cfg, SESSION_MAX_AGE_SECONDS.toInt(), null))
}

internal fun clearSessionCookie(call: ApplicationCall, cfg: AuthConfig) {
    call.response.cookies.append(sessionCookie(SESSION_COOKIE, "", cfg, 0, io.ktor.util.date.GMTDate(0)))
}

internal fun setStateCookie(call: ApplicationCall, state: String, cfg: AuthConfig) {
    call.response.cookies.append(sessionCookie(STATE_COOKIE, state, cfg, STATE_TTL_SECONDS.toInt(), null))
}

internal fun clearStateCookie(call: ApplicationCall, cfg: AuthConfig) {
    call.response.cookies.append(sessionCookie(STATE_COOKIE, "", cfg, 0, io.ktor.util.date.GMTDate(0)))
}

/** Builds a cookie with the spec's security flags: httpOnly + Secure + SameSite=Lax. */
private fun sessionCookie(
    name: String, value: String, cfg: AuthConfig, maxAge: Int, expires: io.ktor.util.date.GMTDate?,
): Cookie = Cookie(
    name = name,
    value = value,
    encoding = io.ktor.http.CookieEncoding.URI_ENCODING,
    maxAge = maxAge,
    expires = expires,
    domain = cfg.sessionCookieDomain,
    path = "/",
    secure = cfg.sessionCookieSecure,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Lax"),
)

/** 32 random bytes as lower-case hex = the OAuth `state` (CSRF token). */
internal fun newState(): String {
    val bytes = java.security.SecureRandom.getSeed(32)
    return bytes.joinToString("") { "%02x".format(it) }
}

/** Role ordering helper exposed for future contributor (`contributor`+) gates. */
fun roleAtLeast(principal: Principal, min: Role): Boolean = principal.role.atLeast(min)
