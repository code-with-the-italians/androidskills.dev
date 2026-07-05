package dev.androidskills.api

import dev.androidskills.TestSupport
import dev.androidskills.module
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** End-to-end route wiring: serialization, the error envelope, and streaming download. */
class PublicRoutesHttpTest {

  private val dir = TestSupport.tempDir()

  @AfterTest
  fun teardown() {
    dir.toFile().deleteRecursively()
  }

  @Test
  fun skillsListDetailCategoriesAndErrorEnvelope() = testApplication {
    application { module(TestSupport.newConfig(dir, seedDemo = true)) }

    val list = client.get("/api/skills")
    assertEquals(HttpStatusCode.OK, list.status)
    assertTrue(list.bodyAsText().contains("\"items\""))

    val detail = client.get("/api/skills/jetpack-compose-mvi")
    assertEquals(HttpStatusCode.OK, detail.status)
    assertTrue(detail.bodyAsText().contains("Jetpack Compose MVI Scaffold"))

    val cats = client.get("/api/categories")
    assertEquals(HttpStatusCode.OK, cats.status)
    assertTrue(cats.bodyAsText().contains("jetpack-compose"))

    // Unknown skill → 404 with the spec error envelope (not 403, not a stack trace).
    val missing = client.get("/api/skills/does-not-exist")
    assertEquals(HttpStatusCode.NotFound, missing.status)
    val missingBody = missing.bodyAsText()
    assertTrue(missingBody.contains("\"error\""))
    assertTrue(missingBody.contains("\"not_found\""))

    // Report with no reason → 422 with a per-field reason.
    val report =
      client.post("/api/skills/jetpack-compose-mvi/report") {
        headers[HttpHeaders.ContentType] = ContentType.Application.Json.toString()
        setBody("{}")
      }
    assertEquals(HttpStatusCode.UnprocessableEntity, report.status)
    assertTrue(report.bodyAsText().contains("reason"))
  }

  @Test
  fun downloadStreamsZipWithAttachmentHeader() = testApplication {
    application { module(TestSupport.newConfig(dir, seedDemo = true)) }
    val res = client.get("/api/skills/jetpack-compose-mvi/download")
    assertEquals(HttpStatusCode.OK, res.status)
    assertTrue(res.headers[HttpHeaders.ContentType]!!.contains("application/zip"))
    val cd = res.headers[HttpHeaders.ContentDisposition]
    assertTrue(cd != null && cd.contains("attachment") && cd.contains(".zip"))
    assertTrue(res.bodyAsText().isNotEmpty())
  }
}
