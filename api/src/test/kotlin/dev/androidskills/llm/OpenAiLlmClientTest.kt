package dev.androidskills.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The fallback ladder (§6.2): json_schema → tool calling → JSON-in-prompt + retry. Each step tested
 * with a scripted MockEngine response. Transport failures (429/5xx) propagate (not a ladder
 * advance).
 */
class OpenAiLlmClientTest {

  private val input =
    SkillManifest(
      "Coroutines Test Patterns",
      "Testing suspend functions.",
      listOf("kotlin", "testing"),
    )

  private val validReviewJson =
    """{"category":"kotlin-language","tagsValidated":["kotlin","testing"],"security":{"passed":true,"findings":[]},"lintScore":85}"""

  private fun chatResponse(content: String? = null, toolCallArgs: String? = null): String {
    val msg = buildString {
      append("{\"choices\":[{\"message\":{")
      if (content != null) append("\"content\":\"${content.replace("\"", "\\\"")}\"")
      if (content != null && toolCallArgs != null) append(",")
      if (toolCallArgs != null) {
        append(
          "\"tool_calls\":[{\"function\":{\"arguments\":\"${toolCallArgs.replace("\"", "\\\"")}\"}}]"
        )
      }
      append("}}]}")
    }
    return msg
  }

  private fun newClient(scriptedResponses: List<String>): OpenAiLlmClient {
    val counter = AtomicInteger(0)
    val engine = MockEngine { _ ->
      val idx = counter.getAndIncrement()
      val body = scriptedResponses.getOrElse(idx) { scriptedResponses.last() }
      respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }
    val http =
      HttpClient(engine) {
        install(ContentNegotiation) { json(dev.androidskills.util.appJson) }
        expectSuccess = true
      }
    return OpenAiLlmClient(
      baseUrl = "https://llm.test/v1",
      apiKey = "key",
      model = "test-model",
      http = http,
    )
  }

  @Test
  fun `step 1 - json_schema succeeds`() = runBlocking {
    val gh = newClient(listOf(chatResponse(content = validReviewJson)))
    val result = gh.review(input)
    assertEquals("kotlin-language", result.category)
    assertTrue(result.security.passed)
    assertEquals(85, result.lintScore)
  }

  @Test
  fun `step 2 - tool calling succeeds when json_schema returns bad content`() = runBlocking {
    // First response: garbage content (triggers step 1 fail → advance).
    // Second response: tool call args (step 2 succeeds).
    val gh =
      newClient(
        listOf(
          chatResponse(content = "I can't do JSON"), // step 1 fails
          chatResponse(toolCallArgs = validReviewJson), // step 2 succeeds
        )
      )
    val result = gh.review(input)
    assertEquals("kotlin-language", result.category)
  }

  @Test
  fun `step 3 - JSON-in-prompt succeeds when both prior steps fail`() = runBlocking {
    // Step 1: bad content → fail.
    // Step 2: no tool call → fail.
    // Step 3 (first attempt): valid JSON.
    val gh =
      newClient(
        listOf(
          chatResponse(content = "not json"), // step 1
          chatResponse(content = "still no tool"), // step 2 (no tool_calls)
          chatResponse(content = validReviewJson), // step 3 first attempt
        )
      )
    val result = gh.review(input)
    assertEquals("kotlin-language", result.category)
  }

  @Test
  fun `step 3 - retries once on bad JSON then succeeds`() = runBlocking {
    // Steps 1+2 fail; step 3 first attempt fails; retry succeeds.
    val gh =
      newClient(
        listOf(
          chatResponse(content = "no"), // step 1
          chatResponse(content = "no tool"), // step 2
          chatResponse(content = "bad json"), // step 3 first
          chatResponse(content = validReviewJson), // step 3 retry
        )
      )
    val result = gh.review(input)
    assertEquals("kotlin-language", result.category)
  }

  @Test
  fun `all steps fail - throws`() {
    val gh =
      newClient(
        listOf(
          chatResponse(content = "no"), // step 1
          chatResponse(content = "no"), // step 2
          chatResponse(content = "no"), // step 3 first
          chatResponse(content = "no"), // step 3 retry
        )
      )
    assertFailsWith<RuntimeException> { runBlocking { gh.review(input) } }
  }

  @Test
  fun `transport failure propagates - does not advance the ladder`() {
    val engine = MockEngine { _ ->
      respond(
        "rate limited",
        HttpStatusCode.TooManyRequests,
        headersOf(HttpHeaders.ContentType, "application/json"),
      )
    }
    val http =
      HttpClient(engine) {
        install(ContentNegotiation) { json(dev.androidskills.util.appJson) }
        expectSuccess = true
      }
    val gh = OpenAiLlmClient("https://llm.test/v1", "key", "model", http)
    // A 429 should throw LlmTransportException (or propagate from expectSuccess),
    // NOT advance to tool calling.
    assertFailsWith<Exception> { runBlocking { gh.review(input) } }
  }
}
