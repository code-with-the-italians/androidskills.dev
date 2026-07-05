package dev.androidskills.auth

import dev.androidskills.AppConfig
import dev.androidskills.api.ApiBadRequestException
import dev.androidskills.api.ErrorBody
import dev.androidskills.api.ErrorResponse
import dev.androidskills.util.constantTimeEquals
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory

/**
 * Auth routes (spec §9 Auth):
 * - GET /api/auth/github/start → 302 to GitHub (sets CSRF state cookie)
 * - GET /api/auth/github/callback → exchange code, upsert user, set session cookie, redirect
 * - POST /api/auth/logout → delete session row + clear cookie
 * - GET /api/me → current user (401 if anonymous)
 *
 * When OAuth is unconfigured (spec §13), the start/callback endpoints report auth unavailable (503)
 * rather than crashing; `/api/me` is simply always 401.
 */
private val logger = LoggerFactory.getLogger("dev.androidskills.auth.AuthRoutes")

fun Route.authRoutes(config: AppConfig, oauth: OAuthClient) {
  val auth = config.auth
  val redirectUri = "${auth.publicBaseUrl.trimEnd('/')}/api/auth/github/callback"

  route("api") {
    rateLimit(RateLimitName("auth")) {
      get("auth/github/start") {
        if (!oauth.configured) {
          call.respond(
            HttpStatusCode.ServiceUnavailable,
            ErrorResponse(ErrorBody("auth_disabled", "GitHub OAuth is not configured")),
          )
          return@get
        }
        val state = newState()
        setStateCookie(call, state, auth)
        call.respondRedirect(oauth.authorizeUrl(state, redirectUri))
      }

      get("auth/github/callback") {
        if (!oauth.configured) {
          call.respond(
            HttpStatusCode.ServiceUnavailable,
            ErrorResponse(ErrorBody("auth_disabled", "GitHub OAuth is not configured")),
          )
          return@get
        }
        val code = call.request.queryParameters["code"]
        val stateParam = call.request.queryParameters["state"]
        val stateCookie = call.request.cookies[stateCookieName(auth)]
        try {
          // CSRF: the state echoed by GitHub must match the cookie we set on start.
          if (code.isNullOrBlank() || stateParam.isNullOrBlank() || stateCookie.isNullOrBlank()) {
            throw ApiBadRequestException("Missing OAuth code/state")
          }
          if (!constantTimeEquals(stateParam, stateCookie)) {
            throw ApiBadRequestException("OAuth state mismatch")
          }
          val userId =
            try {
              val tokens = oauth.exchange(code, redirectUri)
              val ghUser = oauth.userInfo(tokens.accessToken)
              UsersRepo.upsertFromGitHub(ghUser, auth.bootstrapAdminGithubId)
            } catch (e: OAuthException) {
              // GitHubOAuthClient translates every auth/network failure into
              // OAuthException, so a bad/expired code, a revoked token, or a
              // transient GitHub outage is a controlled 400 — never an opaque
              // 500. (DB/server errors still propagate as genuine 5xx.)
              logger.warn("GitHub OAuth callback failed: {}", e.message)
              throw ApiBadRequestException("GitHub sign-in failed. Please try again.")
            }
          val session = SessionStore.create(userId, SESSION_MAX_AGE_SECONDS)
          setSessionCookie(call, session, auth)
          // Clear the single-use state cookie BEFORE the redirect is committed —
          // appending Set-Cookie after respondRedirect is engine-dependent and
          // may be dropped on a real engine (NEW-2). (The finally below covers
          // the throw paths; success clears here, before responding.)
          clearStateCookie(call, auth)
          // Redirect to the site root; the SPA/Astro picks up the session cookie.
          call.respondRedirect(auth.publicBaseUrl)
        } finally {
          // Throws (validation 400, auth-failure 400, or an unexpected 5xx):
          // StatusPages responds AFTER this, so the Set-Cookie lands. The
          // success path cleared the cookie itself, above (before its redirect).
          clearStateCookie(call, auth)
        }
      }

      post("auth/logout") {
        val principal = call.requireSession()
        SessionStore.delete(principal.sessionId)
        clearSessionCookie(call, auth)
        call.respond(mapOf("ok" to true))
      }
    }
    rateLimit(RateLimitName("authenticated")) {
      get("me") {
        val principal = call.requireSession()
        call.respond(principal.toMe())
      }
    }
  }
}
