package dev.androidskills.api

import dev.androidskills.TestSupport
import dev.androidskills.module
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class OpenApiContractTest {
    private val dir = TestSupport.tempDir()

    @AfterTest
    fun teardown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `openapi yaml endpoint is served with expected paths`() = testApplication {
        application { module(TestSupport.newConfig(dir)) }
        val response = client.get("/api/openapi.yaml")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType("application", "yaml"), response.contentType())
        val body = response.bodyAsText()
        assertContains(body, "openapi: 3.0.3")
        assertContains(body, "/api/skills:")
        assertContains(body, "/api/skills/{slug}:")
        assertContains(body, "/api/me:")
        assertContains(body, "/api/admin/queue:")
        assertContains(body, "/gh/webhooks:")
        assertContains(body, "SkillCard:")
        assertContains(body, "SkillDetail:")
        assertContains(body, "MeResponse:")
    }
}
