package dev.androidskills.api

import dev.androidskills.GithubAppConfig
import dev.androidskills.TestSupport
import dev.androidskills.module
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthRoutesTest {

  private val dir = TestSupport.tempDir()

  @AfterTest
  fun teardown() {
    dir.toFile().deleteRecursively()
  }

  @Test
  fun `basic health is unchanged`() = testApplication {
    application { module(TestSupport.newConfig(dir)) }
    val response = client.get("/api/health")
    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.bodyAsText()
    assertTrue(body.contains("\"ok\":true"))
    assertTrue(body.contains("\"version\""))
    assertTrue(body.contains("\"db\""))
    assertTrue(body.contains("\"fileStore\""))
    assertTrue(body.contains("\"llm\""))
    assertTrue(body.contains("\"auth\""))
  }

  @Test
  fun `deep health returns ok for minimal config`() = testApplication {
    application { module(TestSupport.newConfig(dir)) }
    val response = client.get("/api/health/deep")
    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.bodyAsText()
    assertTrue(body.contains("\"status\":\"ok\""))
    assertTrue(body.contains("\"database\":true"))
    assertTrue(body.contains("\"fileStore\":true"))
    assertTrue(body.contains("\"githubApp\":true"))
    assertTrue(body.contains("\"llm\":true"))
  }

  @Test
  fun `deep health reports degraded when github app key is invalid`() = testApplication {
    val config =
      TestSupport.newConfig(dir)
        .copy(
          githubApp =
            GithubAppConfig(
              appId = 123456L,
              privateKeyPem = "not a valid pem",
              webhookSecret = "secret",
            )
        )
    application { module(config) }
    val response = client.get("/api/health/deep")
    assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    val body = response.bodyAsText()
    assertTrue(body.contains("\"status\":\"degraded\""))
    assertTrue(body.contains("\"githubApp\":false"))
  }

  @Test
  fun `deep health reports degraded when llm url is invalid`() = testApplication {
    val config =
      TestSupport.newConfig(dir)
        .copy(llmBaseUrl = "not a url", llmApiKey = "sk-secret", llmModel = "model")
    application { module(config) }
    val response = client.get("/api/health/deep")
    assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    val body = response.bodyAsText()
    assertTrue(body.contains("\"status\":\"degraded\""))
    assertTrue(body.contains("\"llm\":false"))
  }

  @Test
  fun `deep health does not leak secrets`() = testApplication {
    val config =
      TestSupport.newConfig(dir)
        .copy(
          llmBaseUrl = "https://api.example.com",
          llmApiKey = "sk-secret",
          llmModel = "model",
          githubApp =
            GithubAppConfig(
              appId = 123456L,
              privateKeyPem = "-----BEGIN RSA PRIVATE KEY-----\nkey\n-----END RSA PRIVATE KEY-----",
              webhookSecret = "wh-secret",
            ),
        )
    application { module(config) }
    val body = client.get("/api/health/deep").bodyAsText()
    assertFalse(body.contains("sk-secret"))
    assertFalse(body.contains("wh-secret"))
    assertFalse(body.contains("BEGIN RSA PRIVATE KEY"))
  }
}
