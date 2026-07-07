package dev.androidskills.api

import dev.androidskills.Database
import dev.androidskills.TestSupport
import dev.androidskills.db.SkillFiles
import dev.androidskills.db.Skills
import dev.androidskills.module
import dev.androidskills.util.newId
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Security-shaped HTTP checks: raw file previews must not execute as HTML on the site origin (fix:
 * text/plain + nosniff), and malformed public query params must 422 rather than be silently coerced
 * into a default (typed error envelope, §10).
 */
class PublicRoutesSecurityTest {

  private val dir = TestSupport.tempDir()

  @AfterTest
  fun teardown() {
    dir.toFile().deleteRecursively()
  }

  @Test
  fun rawHtmlPreviewIsServedAsTextPlainWithNoSniff() = testApplication {
    // Seed + plant the malicious HTML file up front (committed to the DB file
    // on disk) so the lazily-started app reads it from its own fresh connection.
    val config = TestSupport.newConfig(dir, seedDemo = true)
    Database.init(config)
    DemoData.seed(dev.androidskills.storage.LocalFsStore(config.fileStoreDir))
    val slug = "jetpack-compose-mvi"
    val skillId = transaction {
      Skills.selectAll().where { Skills.slug eq slug }.single()[Skills.id]
    }
    val relPath = "references/sneaky.html"
    val key = "skills/$skillId/files/$relPath"
    val payload = "<script>alert('xss')</script>"
    java.nio.file.Files.createDirectories(config.fileStoreDir.resolve(key).parent)
    java.nio.file.Files.write(config.fileStoreDir.resolve(key), payload.toByteArray())
    transaction {
      SkillFiles.insert {
        it[SkillFiles.id] = newId()
        it[SkillFiles.skillId] = skillId
        it[SkillFiles.path] = relPath
        it[SkillFiles.size] = payload.length
        it[SkillFiles.isBinary] = false
        it[SkillFiles.r2Key] = key
      }
    }

    application { module(config) }
    val res = client.get("/api/skills/$slug/files/$relPath?raw=1")
    assertEquals(HttpStatusCode.OK, res.status)
    val ct = res.headers[HttpHeaders.ContentType]!!
    assertTrue("expected text/plain, got $ct") { ct.startsWith("text/plain", ignoreCase = true) }
    assertEquals("nosniff", res.headers["X-Content-Type-Options"])
    // Body carries the markup verbatim as text; the browser will not execute it.
    assertTrue(res.bodyAsText().contains("<script>"))
  }

  @Test
  fun malformedQueryParamsReturn422() = testApplication {
    application { module(TestSupport.newConfig(dir, seedDemo = true)) }
    client.get("/api/health") // ensure app is up
    suspend fun assert422(qs: String) {
      val r = client.get("/api/skills?$qs")
      assertEquals(HttpStatusCode.UnprocessableEntity, r.status, "$qs -> ${r.status}")
      val body = r.bodyAsText()
      assertTrue(body.contains("\"error\""), "$qs should return the error envelope: $body")
    }

    assert422("verified=maybe")
    assert422("size=huge")
    assert422("sort=popularity")
    assert422("page=0")
    assert422("page=abc")
    assert422("pageSize=9999")

    // Sanity: well-formed params still work.
    assertEquals(
      HttpStatusCode.OK,
      client.get("/api/skills?verified=false&sort=installs&page=1").status,
    )
  }
}
