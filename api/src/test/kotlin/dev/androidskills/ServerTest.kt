package dev.androidskills

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerTest {
    private val dir = TestSupport.tempDir()

    @AfterTest
    fun teardown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun healthEndpointReturnsOk() = testApplication {
        application { module(TestSupport.newConfig(dir)) }
        val response = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"ok\":true"))
    }
}
