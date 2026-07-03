package dev.androidskills.api

import dev.androidskills.AppConfig
import dev.androidskills.Database
import dev.androidskills.TestSupport
import dev.androidskills.clientIp
import dev.androidskills.storage.LocalFsStore
import dev.androidskills.auth.DisabledOAuthClient
import dev.androidskills.auth.authRoutes
import dev.androidskills.github.DisabledGitHubAppClient
import dev.androidskills.gh.webhookRoutes
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitConfig
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RateLimitTest {

    private val dir = TestSupport.tempDir()

    @AfterTest
    fun teardown() {
        dir.toFile().deleteRecursively()
    }

    private fun Application.testModule(
        config: AppConfig,
        configureRateLimit: RateLimitConfig.() -> Unit,
        routeSetup: Route.() -> Unit,
    ) {
        install(ContentNegotiation) { json() }
        installApiErrorMapping()
        install(RateLimit) { configureRateLimit() }
        routing { routeSetup() }
    }

    @Test
    fun `public route returns 429 with standard error body`() = testApplication {
        val config = TestSupport.newConfig(dir)
        Database.init(config)
        application {
            testModule(
                config,
                {
                    register(RateLimitName("public")) {
                        rateLimiter(limit = 2, refillPeriod = 60.seconds)
                        requestKey { "shared" }
                    }
                },
                { publicRoutes(LocalFsStore(dir.resolve("files"))) },
            )
        }
        client.get("/api/skills")
        client.get("/api/skills")
        val third = client.get("/api/skills")
        assertEquals(HttpStatusCode.TooManyRequests, third.status)
        assertTrue(third.bodyAsText().contains("\"error\""))
        assertTrue(third.bodyAsText().contains("rate_limit"))
        assertTrue(third.headers["Retry-After"] != null)
    }

    @Test
    fun `health endpoint is exempt from rate limiting`() = testApplication {
        val config = TestSupport.newConfig(dir)
        Database.init(config)
        application {
            testModule(
                config,
                {
                    register(RateLimitName("public")) {
                        rateLimiter(limit = 1, refillPeriod = 60.seconds)
                        requestKey { "shared" }
                    }
                },
                {
                    get("/api/health") { call.respond(mapOf("ok" to true)) }
                    publicRoutes(LocalFsStore(dir.resolve("files")))
                },
            )
        }
        val first = client.get("/api/health")
        val second = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status)
    }

    @Test
    fun `webhook endpoint is exempt from ip rate limiting`() = testApplication {
        val config = TestSupport.newConfig(dir)
        Database.init(config)
        application {
            testModule(
                config,
                {
                    register(RateLimitName("public")) {
                        rateLimiter(limit = 1, refillPeriod = 60.seconds)
                        requestKey { "shared" }
                    }
                },
                { webhookRoutes(DisabledGitHubAppClient()) },
            )
        }
        val first = client.post("/gh/webhooks")
        val second = client.post("/gh/webhooks")
        assertEquals(HttpStatusCode.Unauthorized, first.status)
        assertEquals(HttpStatusCode.Unauthorized, second.status)
    }

    @Test
    fun `me route uses authenticated tier not auth tier`() = testApplication {
        val config = TestSupport.newConfig(dir)
        Database.init(config)
        application {
            testModule(
                config,
                {
                    register(RateLimitName("auth")) {
                        rateLimiter(limit = 1, refillPeriod = 60.seconds)
                        requestKey { "shared" }
                    }
                    register(RateLimitName("authenticated")) {
                        rateLimiter(limit = 3, refillPeriod = 60.seconds)
                        requestKey { "shared" }
                    }
                },
                { authRoutes(config, DisabledOAuthClient()) },
            )
        }
        // /api/me is in the authenticated tier, so 3 anonymous requests still
        // return 401; they are not blocked by the tighter auth bucket.
        repeat(3) {
            val res = client.get("/api/me")
            assertEquals(HttpStatusCode.Unauthorized, res.status)
        }
        // /api/auth/github/start is in the auth tier; second request is 429.
        val start1 = client.get("/api/auth/github/start")
        assertEquals(HttpStatusCode.ServiceUnavailable, start1.status)
        val start2 = client.get("/api/auth/github/start")
        assertEquals(HttpStatusCode.TooManyRequests, start2.status)
    }

    @Test
    fun `different x-forwarded-for ips have separate buckets`() = testApplication {
        val config = TestSupport.newConfig(dir)
        Database.init(config)
        application {
            testModule(
                config,
                {
                    register(RateLimitName("public")) {
                        rateLimiter(limit = 1, refillPeriod = 60.seconds)
                        requestKey { call -> clientIp(call, 1) }
                    }
                },
                { publicRoutes(LocalFsStore(dir.resolve("files"))) },
            )
        }
        val first = client.get("/api/skills") {
            headers.append(HttpHeaders.XForwardedFor, "1.2.3.4")
        }
        val second = client.get("/api/skills") {
            headers.append(HttpHeaders.XForwardedFor, "5.6.7.8")
        }
        val third = client.get("/api/skills") {
            headers.append(HttpHeaders.XForwardedFor, "1.2.3.4")
        }
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(HttpStatusCode.TooManyRequests, third.status)
    }
}
