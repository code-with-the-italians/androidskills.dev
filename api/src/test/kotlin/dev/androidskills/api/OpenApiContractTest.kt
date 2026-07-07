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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.yaml.snakeyaml.Yaml

class OpenApiContractTest {
  private val dir = TestSupport.tempDir()
  private val json = Json { ignoreUnknownKeys = true }

  @AfterTest
  fun teardown() {
    dir.toFile().deleteRecursively()
  }

  @Test
  fun `openapi yaml endpoint is served and excludes admin paths`() = testApplication {
    application { module(TestSupport.newConfig(dir)) }
    val response = client.get("/api/openapi.yaml")
    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(ContentType("application", "yaml"), response.contentType())
    val body = response.bodyAsText()
    assertContains(body, "openapi: 3.0.3")
    assertContains(body, "/api/skills:")
    assertContains(body, "/api/skills/{slug}:")
    assertContains(body, "/api/me:")
    assertContains(body, "SkillCard:")
    assertContains(body, "SkillDetail:")
    assertContains(body, "MeResponse:")
    // Admin paths are intentionally not documented in the public spec.
    assertFalse(body.contains("/api/admin/queue:"))
  }

  @Test
  fun `public responses match the openapi spec`() = testApplication {
    val spec = loadSpec()
    val schemas = spec["components"] as Map<*, *>
    val schemaDefs = schemas["schemas"] as Map<*, *>

    application { module(TestSupport.newConfig(dir, seedDemo = true)) }

    suspend fun getJson(path: String): JsonElement {
      val response = client.get(path)
      assertEquals(HttpStatusCode.OK, response.status, "expected 200 for $path")
      return json.parseToJsonElement(response.bodyAsText())
    }

    fun JsonElement.toObject(): Map<*, *> = (toRaw() as Map<*, *>)
    fun JsonElement.toList(): List<*> = (toRaw() as List<*>)

    // /api/stats -> StatsResponse
    val stats = getJson("/api/stats").toObject()
    assertShape(schemaDefs, "StatsResponse", stats)

    // /api/skills -> SkillSearchPage
    val search = getJson("/api/skills").toObject()
    assertShape(schemaDefs, "SkillSearchPage", search)
    val items = search["items"] as List<*>
    assertTrue(items.isNotEmpty(), "demo data should return at least one skill")
    items.forEach { assertShape(schemaDefs, "SkillCard", it as Map<*, *>) }

    // /api/skills/{slug} -> SkillDetail
    val slug = (items[0] as Map<*, *>)["slug"] as String
    val detail = getJson("/api/skills/$slug").toObject()
    assertShape(schemaDefs, "SkillDetail", detail)

    // /api/categories -> [CategoryWithCount]
    val categories = getJson("/api/categories").toList()
    assertTrue(categories.isNotEmpty(), "demo categories should be present")
    categories.forEach { assertShape(schemaDefs, "CategoryWithCount", it as Map<*, *>) }
  }

  private fun loadSpec(): Map<String, Any> {
    val text =
      javaClass.classLoader
        .getResourceAsStream("openapi.yaml")
        ?.use { it.readAllBytes() }
        ?.decodeToString() ?: throw IllegalStateException("openapi.yaml missing from classpath")
    return Yaml().load(text) as Map<String, Any>
  }

  private fun assertShape(schemaDefs: Map<*, *>, schemaName: String, value: Map<*, *>) {
    val schema =
      schemaDefs[schemaName] as? Map<*, *>
        ?: throw AssertionError("Schema $schemaName not found in OpenAPI spec")
    val required = (schema["required"] as? List<*>) ?: emptyList<String>()
    val properties = (schema["properties"] as? Map<*, *>) ?: emptyMap<Any, Any>()
    for (key in required) {
      assertTrue(
        value.containsKey(key),
        "response is missing required field '$key' for schema '$schemaName'",
      )
      val nullable = (properties[key] as? Map<*, *>)?.get("nullable") as? Boolean ?: false
      if (!nullable) {
        assertNotNull(value[key], "required field '$key' for schema '$schemaName' is null")
      }
    }
  }

  private fun JsonElement.toRaw(): Any? =
    when (this) {
      is JsonObject -> entries.associate { it.key to it.value.toRaw() }
      is JsonArray -> map { it.toRaw() }
      is JsonPrimitive -> content
    }
}
