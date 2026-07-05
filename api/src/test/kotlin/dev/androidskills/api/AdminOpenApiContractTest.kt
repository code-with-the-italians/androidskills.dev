package dev.androidskills.api

import dev.androidskills.TestSupport
import dev.androidskills.module
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json

class AdminOpenApiContractTest {
  private val json = Json { ignoreUnknownKeys = true }

  private fun withDir(block: (Path) -> Unit) {
    val dir = TestSupport.tempDir()
    try {
      block(dir)
    } finally {
      dir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `admin openapi yaml is served separately from public spec`() = withDir { dir ->
    testApplication {
      application { module(TestSupport.newConfig(dir)) }
      val response = client.get("/api/openapi-admin.yaml")
      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.bodyAsText()
      assertContains(body, "openapi: 3.0.3")
      assertContains(body, "/api/admin/stats:")
      assertContains(body, "/api/admin/queue:")
      assertContains(body, "AdminStatsResponse:")
      assertContains(body, "AdminQueueItem:")
      assertFalse(body.contains("/api/skills:"))
    }
  }

  @Test
  fun `admin stats returns 404 for anonymous`() = withDir { dir ->
    testApplication {
      application { module(TestSupport.newConfig(dir)) }
      val response = client.get("/api/admin/stats")
      assertEquals(HttpStatusCode.NotFound, response.status)
    }
  }

  @Test
  fun `admin stats returns 404 for invalid session`() = withDir { dir ->
    testApplication {
      application { module(TestSupport.newConfig(dir)) }
      val response = client.get("/api/admin/stats") { header("Cookie", "as_session=invalidtoken") }
      assertEquals(HttpStatusCode.NotFound, response.status)
    }
  }
}
