package dev.androidskills.auth

import dev.androidskills.AuthConfig
import dev.androidskills.api.ApiNotFoundException
import dev.androidskills.api.ApiUnauthorizedException
import io.ktor.http.Cookie
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey

/**
 * Per-request auth (spec §7). The session id lives in an httpOnly + Secure + SameSite=Lax cookie;
 * the row is looked up in the DB on every request so that suspend/logout revoke immediately
 * (architecture.md: why not stateless JWTs).
 *
 * [principal] is resolved lazily and memoized per call, so a handler that calls both
 * `requireSession()` and later `principal()` hits the DB once. The cookie is *opaque* (a 256-bit
 * random token) — it carries no claims and cannot be forged without the row.
 */
internal const val SESSION_COOKIE = "as_session"
internal const val STATE_COOKIE = "as_oauth_state"
internal const val STATE_TTL_SECONDS = 10 * 60L
internal const val SESSION_MAX_AGE_SECONDS = 30 * 24 * 3_600L

private class PrincipalBox {
  var resolved = false
  var value: Principal? = null
}

private val PrincipalKey = AttributeKey<PrincipalBox>("asPrincipalBox")

/** Resolves and memoizes the caller's principal (null if anonymous/expired/suspended). */
suspend fun ApplicationCall.principal(): Principal? {
  val box =
    attributes.getOrNull(PrincipalKey) ?: PrincipalBox().also { attributes.put(PrincipalKey, it) }
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
 * Admin required → **404** for everyone who isn't an admin, including anonymous callers. Never
 * 401/403: that would reveal the route exists (spec §7).
 */
suspend fun ApplicationCall.requireAdmin(): Principal =
  principal()?.requireAdmin() ?: throw ApiNotFoundException("Not found")

// ---- cookies --------------------------------------------------------------

internal fun setSessionCookie(call: ApplicationCall, token: String, cfg: AuthConfig) {
  call.response.cookies.append(
    sessionCookie(SESSION_COOKIE, token, cfg, SESSION_MAX_AGE_SECONDS.toInt(), null)
  )
}

internal fun clearSessionCookie(call: ApplicationCall, cfg: AuthConfig) {
  call.response.cookies.append(
    sessionCookie(SESSION_COOKIE, "", cfg, 0, io.ktor.util.date.GMTDate(0))
  )
}

/**
 * The OAuth `state` cookie name. Over HTTPS we use the `__Host-` prefix, which the cookie jar
 * treats as host-only + Secure + Path=/ + NO Domain — pinning the CSRF token to this exact host so
 * a sibling-subdomain foothold or MITM can't plant it (login-CSRF, P2-3). Over plain-HTTP dev we
 * drop the prefix (a `__Host-` cookie requires Secure and wouldn't be stored/sent) but still keep
 * the cookie host-only (no Domain).
 */
internal fun stateCookieName(cfg: AuthConfig): String =
  if (cfg.sessionCookieSecure) "__Host-as_oauth_state" else STATE_COOKIE

internal fun setStateCookie(call: ApplicationCall, state: String, cfg: AuthConfig) {
  call.response.cookies.append(
    stateCookie(stateCookieName(cfg), state, cfg, STATE_TTL_SECONDS.toInt(), null)
  )
}

internal fun clearStateCookie(call: ApplicationCall, cfg: AuthConfig) {
  call.response.cookies.append(
    stateCookie(stateCookieName(cfg), "", cfg, 0, io.ktor.util.date.GMTDate(0))
  )
}

/**
 * Session cookie: httpOnly + Secure + SameSite=Lax; keeps [AuthConfig.sessionCookieDomain] when
 * set.
 */
private fun sessionCookie(
  name: String,
  value: String,
  cfg: AuthConfig,
  maxAge: Int,
  expires: io.ktor.util.date.GMTDate?,
): Cookie =
  Cookie(
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

/**
 * State cookie: ALWAYS host-only — Domain is never set, so it can't be widened to a parent/sibling
 * domain (P2-3 login-CSRF). Secure mirrors the session cookie; the `__Host-` prefix (when Secure)
 * makes the host-only contract browser-enforced.
 */
private fun stateCookie(
  name: String,
  value: String,
  cfg: AuthConfig,
  maxAge: Int,
  expires: io.ktor.util.date.GMTDate?,
): Cookie =
  Cookie(
    name = name,
    value = value,
    encoding = io.ktor.http.CookieEncoding.URI_ENCODING,
    maxAge = maxAge,
    expires = expires,
    domain = null, // host-only always — never Domain on the CSRF state cookie
    path = "/",
    secure = cfg.sessionCookieSecure,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Lax"),
  )

private val stateRng = java.security.SecureRandom()

/**
 * 32 random bytes as lower-case hex = the OAuth `state` (CSRF token). Mirrors `SessionStore`'s
 * token generation: [java.security.SecureRandom.nextBytes] is the intended API for emitting opaque
 * secrets; `getSeed`/`generateSeed` produce *seed material* for seeding other RNGs, not
 * session-style tokens.
 */
internal fun newState(): String {
  val bytes = ByteArray(32)
  stateRng.nextBytes(bytes)
  return bytes.joinToString("") { "%02x".format(it) }
}
