package dev.androidskills.github

import dev.androidskills.util.constantTimeEquals
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Crypto-bearing pieces: AppJwt shape + RealGitHubAppClient over MockEngine (no network). */
class RealGitHubAppClientTest {

    private val rsaKp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val testPem = pemWrap("PRIVATE KEY", rsaKp.private.encoded)
    private val webhookSecret = "test-webhook-secret"

    private fun pemWrap(kind: String, der: ByteArray): String =
        "-----BEGIN $kind-----\n${Base64.getEncoder().encodeToString(der).chunked(64).joinToString("\n")}\n-----END $kind-----\n"

    private fun newClient(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = "{}",
        contentType: String = "application/json",
    ): Pair<RealGitHubAppClient, MockEngine> {
        val engine = MockEngine { request ->
            respond(body, status, headersOf(HttpHeaders.ContentType, contentType))
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(dev.androidskills.util.appJson) }
            expectSuccess = true
        }
        return RealGitHubAppClient(appId = 123L, privateKeyPem = testPem, webhookSecret = webhookSecret, http = http) to engine
    }

    // ---- AppJwt ----

    @Test
    fun `jwt is RS256 with correct header and claims`() {
        val jwt = AppJwt.build(123L, testPem)
        val parts = jwt.split(".")
        assertEquals(3, parts.size, "JWT has 3 parts")
        val header = String(Base64.getUrlDecoder().decode(parts[0]))
        assertTrue(header.contains("\"alg\":\"RS256\""), "alg=RS256; got $header")
        assertTrue(header.contains("\"typ\":\"JWT\""), "typ=JWT")
        val claims = String(Base64.getUrlDecoder().decode(parts[1]))
        assertTrue(claims.contains("\"iss\":\"123\""), "iss=appId; got $claims")
        // iat must NOT be in the future (clock-skew guard).
        val now = System.currentTimeMillis() / 1000
        val iat = Regex(""""iat":(\d+)""").find(claims)?.groupValues?.get(1)?.toLong()
        assertTrue(iat != null && iat <= now, "iat ($iat) must not be future-dated (now=$now)")
        val exp = Regex(""""exp":(\d+)""").find(claims)?.groupValues?.get(1)?.toLong()
        assertTrue(exp != null && exp > now && exp <= now + 600, "exp within 10 min")
    }

    @Test
    fun `jwt signature verifies against the RSA public key`() {
        val jwt = AppJwt.build(123L, testPem)
        val parts = jwt.split(".")
        val signingInput = "${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)
        val sig = Base64.getUrlDecoder().decode(parts[2])
        val ok = Signature.getInstance("SHA256withRSA").run {
            initVerify(rsaKp.public); update(signingInput); verify(sig)
        }
        assertTrue(ok, "JWT signature must verify")
    }

    // ---- HMAC verify ----

    @Test
    fun `valid signature is accepted`() = runBlocking {
        val (gh, _) = newClient()
        val body = """{"zen":"keep it semantically awesome"}""".toByteArray()
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").run {
            init(javax.crypto.spec.SecretKeySpec(webhookSecret.toByteArray(), "HmacSHA256"))
            doFinal(body)
        }
        val sig = "sha256=" + mac.joinToString("") { "%02x".format(it) }
        // ping event → null (verified but unhandled).
        assertNull(gh.verifyAndParseEvent(body, sig))
    }

    @Test
    fun `bad signature throws`() {
        val (gh, _) = newClient()
        assertFailsWith<GitHubAppException> {
            runBlocking { gh.verifyAndParseEvent("{}".toByteArray(), "sha256=deadbeef") }
        }
    }

    @Test
    fun `push event with matching default branch produces a Push event`() = runBlocking {
        val (gh, _) = newClient()
        val payload = """{"ref":"refs/heads/main","after":"abc123","repository":{"full_name":"alice/toolkit","default_branch":"main"}}"""
        val body = payload.toByteArray()
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").run {
            init(javax.crypto.spec.SecretKeySpec(webhookSecret.toByteArray(), "HmacSHA256"))
            doFinal(body)
        }
        val sig = "sha256=" + mac.joinToString("") { "%02x".format(it) }
        val event = gh.verifyAndParseEvent(body, sig) as GithubWebhookEvent.Push
        assertEquals("alice", event.repoOwner)
        assertEquals("toolkit", event.repoName)
        assertEquals("abc123", event.after)
        assertTrue(event.isDefaultBranch)
    }

    // ---- API endpoints (MockEngine) ----

    @Test
    fun `installations parses account info`() = runBlocking {
        val (gh, _) = newClient(body = """{"installations":[{"id":1,"account":{"id":42,"login":"alice","type":"User"}}]}""")
        val list = gh.installations()
        assertEquals(1, list.size)
        assertEquals(42L, list[0].accountId)
        assertEquals("alice", list[0].accountLogin)
    }

    @Test
    fun `listRepos parses repository list`() = runBlocking {
        // The client needs an installation token first — mock a token endpoint response,
        // then the repos endpoint. MockEngine handles both in order via request matching.
        var tokenCall = false
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.contains("access_tokens") -> {
                    tokenCall = true
                    respond("""{"token":"tok-123","expires_at":"2099-01-01T00:00:00Z"}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                request.url.encodedPath.contains("installation/repositories") -> {
                    respond("""{"repositories":[{"name":"toolkit","full_name":"alice/toolkit","default_branch":"main","owner":{"login":"alice"}}]}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(dev.androidskills.util.appJson) }; expectSuccess = true }
        val gh = RealGitHubAppClient(123L, testPem, webhookSecret, http)
        val repos = gh.listRepos(1L)
        assertTrue(tokenCall, "installation token was fetched")
        assertEquals(1, repos.size)
        assertEquals("alice/toolkit", repos[0].fullName)
    }

    @Test
    fun `downloadZipball returns raw bytes`() = runBlocking {
        val zipBytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x00, 0x00) // zip magic + padding
        var tokenCalled = false
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.contains("access_tokens") -> {
                    tokenCalled = true
                    respond("""{"token":"tok","expires_at":"2099-01-01T00:00:00Z"}""",
                        HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                request.url.encodedPath.contains("zipball") -> {
                    respond(zipBytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/zip"))
                }
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(dev.androidskills.util.appJson) }; expectSuccess = true }
        val gh = RealGitHubAppClient(123L, testPem, webhookSecret, http)
        val result = gh.downloadZipball(1L, "alice", "repo", "main")
        assertTrue(tokenCalled, "installation token was fetched")
        assertTrue(result.isNotEmpty(), "got bytes back")
    }
}
