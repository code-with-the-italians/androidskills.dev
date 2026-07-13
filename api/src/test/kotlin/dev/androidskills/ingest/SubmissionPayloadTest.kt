package dev.androidskills.ingest

import dev.androidskills.util.appJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SubmissionPayloadTest {

  /**
   * A payload written before the review-shape change (findings as bare strings, `tagsValidated`
   * instead of `tagsProposed`) must still decode — and crucially must NOT take `staged` down with
   * it, or the admin could no longer approve a pre-deploy submission.
   */
  @Test
  fun `legacy review payload still decodes and preserves staged`() {
    val legacy =
      """
      {
        "staged": {
          "version": "1.0.0", "versionSource": "manifest", "name": "N", "description": "D",
          "license": "MIT", "tags": ["android"],
          "sourceRef": { "repoOwner": "o", "repoName": "r", "ref": "abc" }
        },
        "review": {
          "category": "developer-workflow",
          "tagsValidated": ["provenance"],
          "securityPassed": false,
          "securityFindings": ["prompt injection risk", "data exfiltration risk"],
          "lintScore": 68
        }
      }
      """
        .trimIndent()

    val payload = appJson.decodeFromString(SubmissionPayload.serializer(), legacy)

    // staged survives — approve depends on it.
    assertNotNull(payload.staged)
    assertEquals("abc", payload.staged?.sourceRef?.ref)

    // Legacy string findings map to blocking "flag" findings, preserving their original semantics.
    val review = assertNotNull(payload.review)
    assertEquals(2, review.securityFindings.size)
    assertEquals("flag", review.securityFindings[0].severity)
    assertEquals("prompt injection risk", review.securityFindings[0].message)
    // The renamed field is absent in the legacy row → defaults to empty (not a decode failure).
    assertEquals(emptyList(), review.tagsProposed)
  }

  /** Round-trips the current structured shape (severity-carrying findings). */
  @Test
  fun `current review payload round-trips`() {
    val original =
      ReviewOutputPayload(
        category = "developer-workflow",
        tagsProposed = listOf("provenance"),
        securityPassed = true,
        securityFindings =
          listOf(dev.androidskills.llm.SecurityFinding("fyi", "inherent to purpose")),
        lintScore = 70,
      )
    val json = appJson.encodeToString(ReviewOutputPayload.serializer(), original)
    val back = appJson.decodeFromString(ReviewOutputPayload.serializer(), json)
    assertEquals(original, back)
  }
}
