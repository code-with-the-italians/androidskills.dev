package dev.androidskills.auth

import dev.androidskills.AppConfig
import dev.androidskills.api.ApiBadRequestException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory

/**
 * Auth routes (spec §9 Auth):
 *  - GET  /api/auth/github/start    → 302 to GitHub (sets CSRF state cookie)
 *  - GET  /api/auth/github/callback → exchange code, upsert user, set session cookie, redirect
 *  - POST /api/auth/logout          → delete session row + clear cookie
 *  - GET  /api/me                   → current user (401 if anonymous)
 *
 * When OAuth is unconfigured (spec §13), the start/callback endpoints report
 * auth unavailable (503) rather than crashing; `/api/me` is simply always 401.
 */
private val logger = LoggerFactory.getLogger("dev.androidskills.auth.AuthRoutes")

fun Route.authRoutes(config: AppConfig, oauth: OAuthClient) {
    val auth = config.auth
    val redirectUri = "${auth.publicBaseUrl.trimEnd('/')}/api/auth/github/callback"

    route("api") {
        get("auth/github/start") {
            if (!oauth.configured) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to mapOf("code" to "auth_disabled", "message" to "GitHub OAuth is not configured")))
                return@get
            }
            val state = newState()
            setStateCookie(call, state, auth)
            call.respondRedirect(oauth.authorizeUrl(state, redirectUri))
        }

        get("auth/github/callback") {
            if (!oauth.configured) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to mapOf("code" to "auth_disabled", "message" to "GitHub OAuth is not configured")))
                return@get
            }
            val code = call.request.queryParameters["code"]
            val stateParam = call.request.queryParameters["state"]
            val stateCookie = call.request.cookies[STATE_COOKIE]
            // CSRF: the state echoed by GitHub must match the cookie we set on start.
            if (code.isNullOrBlank() || stateParam.isNullOrBlank() || stateCookie.isNullOrBlank()) {
                throw ApiBadRequestException("Missing OAuth code/state")
            }
            if (!constantTimeEquals(stateParam, stateCookie)) {
                throw ApiBadRequestException("OAuth state mismatch")
            }
            val userId = try {
                val tokens = oauth.exchange(code, redirectUri)
                val ghUser = oauth.userInfo(tokens.accessToken)
                UsersRepo.upsertFromGitHub(ghUser, auth.bootstrapAdminGithubId)
            } catch (e: OAuthException) {
                // A normal OAuth failure (bad/expired code, GitHub error, revoked)
                // is a client/auth error, not a server outage. Map to a controlled
                // 400 with a stable code and clear the state cookie; log the detail.
                logger.warn("GitHub OAuth callback failed: {}", e.message)
                clearStateCookie(call, auth)
                throw ApiBadRequestException("GitHub sign-in failed. Please try again.")
            }
            val session = SessionStore.create(userId, SESSION_MAX_AGE_SECONDS)
            clearStateCookie(call, auth)
            setSessionCookie(call, session, auth)
            // Redirect to the site root; the SPA/Astro picks up the session cookie.
            call.respondRedirect(auth.publicBaseUrl)
        }

        post("auth/logout") {
            val principal = call.requireSession()
            SessionStore.delete(principal.sessionId)
            clearSessionCookie(call, auth)
            call.respond(mapOf("ok" to true))
        }

        get("me") {
            val principal = call.requireSession()
            call.respond(principal.toMe())
        }
    }
}

/** Constant-time string compare to avoid timing leaks on the state check. */
private fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
    return diff == 0
}
