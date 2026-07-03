package dev.androidskills

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ClientIpTest {

    @Test
    fun `uses last x-forwarded-for entry with one trusted proxy`() = testApplication {
        routing {
            get("/ip") { call.respondText(clientIp(call, 1)) }
        }
        val res = client.get("/ip") {
            headers.append(HttpHeaders.XForwardedFor, "1.2.3.4, 5.6.7.8")
        }
        assertEquals("5.6.7.8", res.bodyAsText())
    }

    @Test
    fun `uses entry before trusted proxies with count two`() = testApplication {
        routing {
            get("/ip") { call.respondText(clientIp(call, 2)) }
        }
        val res = client.get("/ip") {
            headers.append(HttpHeaders.XForwardedFor, "1.2.3.4, 5.6.7.8, 9.10.11.12")
        }
        assertEquals("5.6.7.8", res.bodyAsText())
    }

    @Test
    fun `falls back to remote host when X-Forwarded-For is missing`() = testApplication {
        routing {
            get("/ip") { call.respondText(clientIp(call, 1)) }
        }
        val res = client.get("/ip")
        // Test engine reports remoteHost as "localhost"; key point is that a
        // missing X-Forwarded-For does not allow a client-spoofed X-Real-Ip.
        assertEquals("localhost", res.bodyAsText())
    }
}
