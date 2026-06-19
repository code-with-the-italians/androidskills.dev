package dev.androidskills.auth

import dev.androidskills.AppConfig
import dev.androidskills.Database
import dev.androidskills.TestSupport
import dev.androidskills.api.installApiErrorMapping
import dev.androidskills.db.Role
import dev.androidskills.db.UserStatus
import dev.androidskills.db.Users
import dev.androidskills.module
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A controllable OAuthClient: maps codes→tokens→users in-process (no network). */
class FakeOAuthClient(
    private val codeToToken: Map<String, String> = mapOf("code-OK" to "tok-OK"),
    private val tokenToUser: Map<String, GitHubUser> = mapOf(
        "tok-OK" to GitHubUser(42, "alice", "Alice", "https://av/alice.png"),
    ),
) : OAuthClient {
    override val configured = true
    override fun authorizeUrl(state: String, redirectUri: String) =
        "https://github.com/login/oauth/authorize?client_id=test&state=$state&redirect_uri=$redirectUri"
    override suspend fun exchange(code: String, redirectUri: String): OAuthTokens =
        OAuthTokens(codeToToken[code] ?: throw OAuthException("unknown code $code"))
    override suspend fun userInfo(accessToken: String): GitHubUser =
        tokenToUser[accessToken] ?: throw OAuthException("unknown token $accessToken")
}

class AuthRoutesTest {
    private val dir = TestSupport.tempDir()

    @AfterTest
    fun teardown() { dir.toFile().deleteRecursively() }

    private fun cookieValue(setCookieHeaders: List<String>?, name: String): String? {
        for (h in setCookieHeaders ?: return null) {
            val first = h.substringBefore(";")
            if (first.startsWith("$name=")) return first.substringAfter("$name=")
        }
        return null
    }

    @Test
    fun meIs401WhenAnonymous() = testApp(oauth = null) { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me").status)
    }

    @Test
    fun startReportsDisabledWhenOAuthUnconfigured() = testApp(oauth = null) { client ->
        val res = client.get("/api/auth/github/start")
        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
        assertTrue(res.bodyAsText().contains("auth_disabled"))
    }

    @Test
    fun fullOAuthFlowLoginThenLogout() = testApp(oauth = FakeOAuthClient()) { client ->
        // 1. start → 302 + state cookie set, redirect to GitHub authorize URL.
        val start = client.get("/api/auth/github/start")
        assertEquals(HttpStatusCode.Found, start.status)
        val state = cookieValue(start.headers.getAll("Set-Cookie"), STATE_COOKIE)
        assertTrue(state != null && state.length == 64, "state cookie missing: $state")
        val location = start.headers["Location"] ?: error("missing Location")
        assertTrue(location.startsWith("https://github.com/login/oauth/authorize"))
        assertTrue(location.contains("state=$state"))

        // 2. callback with matching state → upsert + session cookie + redirect to site root.
        val cb = client.get("/api/auth/github/callback?code=code-OK&state=$state")
        assertEquals(HttpStatusCode.Found, cb.status)
        assertEquals("http://localhost:8080", cb.headers["Location"])

        // 3. /api/me now resolves Alice (session cookie carried by HttpCookies).
        val me = client.get("/api/me")
        assertEquals(HttpStatusCode.OK, me.status)
        val body = me.bodyAsText()
        assertTrue(body.contains("\"handle\":\"alice\""), body)
        assertTrue(body.contains("\"role\":\"member\""), body)
        assertTrue(body.contains("\"status\":\"active\""), body)

        // 4. logout deletes the session and clears the cookie.
        assertEquals(HttpStatusCode.OK, client.post("/api/auth/logout").status)
        // 5. /api/me is 401 again.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me").status)
    }

    @Test
    fun callbackRejectsMismatchedState() = testApp(oauth = FakeOAuthClient()) { client ->
        client.get("/api/auth/github/start") // establish a state cookie
        val res = client.get("/api/auth/github/callback?code=code-OK&state=wrong-state")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText().contains("bad_request"))
    }

    @Test
    fun callbackReturns400Not500WhenOAuthFails() = testApp(oauth = FakeOAuthClient()) { client ->
        // A normal OAuth failure (bad/expired/revoked code → OAuthException from
        // exchange/userInfo) is a client/auth error, NOT a server outage: must be a
        // controlled 400, not the generic 500 from the global Throwable handler.
        val start = client.get("/api/auth/github/start")
        val state = cookieValue(start.headers.getAll("Set-Cookie"), STATE_COOKIE)!!
        val res = client.get("/api/auth/github/callback?code=bad-or-revoked-code&state=$state")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        val body = res.bodyAsText()
        assertTrue(body.contains("\"code\":\"bad_request\""), body)
        // No session cookie was set on failure …
        val setCookies = res.headers.getAll("Set-Cookie") ?: emptyList()
        assertTrue(setCookies.none { it.startsWith("$SESSION_COOKIE=") && !it.contains("Max-Age=0") },
            "no session cookie should be set on OAuth failure: $setCookies")
        // … and the single-use state cookie WAS cleared (P1-2: no stale CSRF cookie left behind).
        assertTrue(setCookies.any { it.startsWith("$STATE_COOKIE=") && it.contains("Max-Age=0") },
            "state cookie must be cleared on OAuth failure: $setCookies")
    }

    @Test
    fun suspendedUserIsLoggedOutMidSession() = testApp(oauth = FakeOAuthClient()) { client ->
        // Log Alice in.
        val start = client.get("/api/auth/github/start")
        val state = cookieValue(start.headers.getAll("Set-Cookie"), STATE_COOKIE)!!
        client.get("/api/auth/github/callback?code=code-OK&state=$state")
        assertEquals(HttpStatusCode.OK, client.get("/api/me").status)

        // An admin suspends Alice out-of-band → her very next request must 401 (spec §7).
        transaction {
            Users.update({ Users.githubId eq 42L }) { it[Users.status] = UserStatus.suspended.name }
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me").status)
    }

    // ---- admin → 404 invariant (spec §7: non-admins and anons never learn the route exists) ----

    @Test
    fun adminGateReturns404ForAnonMemberAndOkForAdmin() {
        // Set up three identities up front (committed to the DB file).
        val config = TestSupport.newConfig(dir)
        Database.init(config)
        val memberId = UsersRepo.upsertFromGitHub(GitHubUser(1, "mem", "Mem", null), null)
        val adminId = UsersRepo.upsertFromGitHub(GitHubUser(2, "adm", "Adm", null), bootstrapAdminGithubId = 2L)
        // Sanity: roles as expected.
        transaction {
            assertEquals(Role.member.name, Users.selectAll().where { Users.id eq memberId }.single()[Users.role])
            assertEquals(Role.admin.name, Users.selectAll().where { Users.id eq adminId }.single()[Users.role])
        }
        val memberToken = SessionStore.create(memberId, 3600)
        val adminToken = SessionStore.create(adminId, 3600)

        testApplication {
            application { adminGateApp() }
            // Anonymous → 404 (NOT 401/403).
            val anon = client.get("/api/admin/_gate")
            assertEquals(HttpStatusCode.NotFound, anon.status)
            assertFalse(anon.bodyAsText().contains("role"))
            // Authenticated member → 404.
            val mem = client.get("/api/admin/_gate") { header("Cookie", "$SESSION_COOKIE=$memberToken") }
            assertEquals(HttpStatusCode.NotFound, mem.status)
            // Admin → 200.
            val adm = client.get("/api/admin/_gate") { header("Cookie", "$SESSION_COOKIE=$adminToken") }
            assertEquals(HttpStatusCode.OK, adm.status)
            assertTrue(adm.bodyAsText().contains("\"role\":\"admin\""))
        }
    }

    private fun Application.adminGateApp() {
        install(ContentNegotiation) { json() }
        installApiErrorMapping()
        routing {
            get("api/admin/_gate") {
                val p = call.requireAdmin()
                call.respond(mapOf("role" to p.role.name))
            }
        }
    }

    /** Builds the real module() with an injected [oauth] and a cookie-persisting client. */
    private fun testApp(oauth: OAuthClient?, block: suspend (client: HttpClient) -> Unit) {
        val config = TestSupport.newConfig(dir)
        Database.init(config)
        testApplication {
            application { module(config, oauth = oauth) }
            val client = createClient {
                followRedirects = false
                install(HttpCookies)
            }
            block(client)
        }
    }
}
