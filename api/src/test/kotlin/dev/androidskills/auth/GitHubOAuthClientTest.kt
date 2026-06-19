package dev.androidskills.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * P1-2: the real client must translate every auth/network failure into
 * [OAuthException] so the route can map it to a controlled 400. Uses a MockEngine
 * (no network) to assert both the translation and the happy path.
 */
class GitHubOAuthClientTest {

    private var client: HttpClient? = null

    @AfterTest
    fun teardown() { client?.close() }

    private fun newClient(status: HttpStatusCode, body: String): GitHubOAuthClient {
        val http = HttpClient(MockEngine { _ ->
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            install(ContentNegotiation) { json(dev.androidskills.util.appJson) }
            expectSuccess = true
        }
        client = http
        return GitHubOAuthClient(clientId = "cid", clientSecret = "secret", http = http)
    }

    @Test
    fun `non-2xx response is translated to OAuthException`() = kotlinx.coroutines.runBlocking {
        // expectSuccess throws ResponseException on 401; the client must catch and
        // rethrow as OAuthException (not leak to the route as a 500).
        val gh = newClient(HttpStatusCode.Unauthorized, """{"message":"Bad credentials"}""")
        val ex = assertFailsWith<OAuthException> { gh.userInfo("tok") }
        assertTrue(ex.message!!.contains("GitHub request failed"))
    }

    @Test
    fun `successful user lookup returns the GitHub identity`() = kotlinx.coroutines.runBlocking {
        val gh = newClient(
            HttpStatusCode.OK,
            """{"id":42,"login":"alice","name":"Alice","avatar_url":"https://av/alice.png"}""",
        )
        val user = gh.userInfo("tok")
        assertEquals(42L, user.githubId)
        assertEquals("alice", user.handle)
        assertEquals("Alice", user.name)
    }

    @Test
    fun `token endpoint error response surfaces as OAuthException`() = kotlinx.coroutines.runBlocking {
        // GitHub returns 200 with an error body for a bad code.
        val gh = newClient(HttpStatusCode.OK, """{"error":"bad_verification_code","error_description":"expired"}""")
        val ex = assertFailsWith<OAuthException> { gh.exchange("code", "https://app/cb") }
        assertTrue(ex.message!!.contains("bad_verification_code"))
    }

    @Test
    fun exchangeSucceedsWhenReadUserScopeIsGranted() = kotlinx.coroutines.runBlocking {
        val gh = newClient(HttpStatusCode.OK, """{"access_token":"tok-OK","token_type":"bearer","scope":"read:user"}""")
        val tokens = gh.exchange("code", "https://app/cb")
        assertEquals("tok-OK", tokens.accessToken)
    }

    @Test
    fun exchangeRefusesATokenMissingTheReadUserScope() = kotlinx.coroutines.runBlocking {
        // P3-1: a downscoped/empty token can't call GET /user; refuse it now
        // rather than letting the next request 401.
        val gh = newClient(HttpStatusCode.OK, """{"access_token":"tok","token_type":"bearer","scope":""}""")
        val ex = assertFailsWith<OAuthException> { gh.exchange("code", "https://app/cb") }
        assertTrue(ex.message!!.contains("read:user"), ex.message!!)
    }
}
