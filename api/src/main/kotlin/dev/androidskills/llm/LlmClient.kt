package dev.androidskills.llm

import kotlinx.serialization.Serializable

/** The manifest fields sent to the LLM for review (§6.1). */
@Serializable
data class SkillManifest(
  val name: String,
  val description: String,
  val tags: List<String> = emptyList(),
  /**
   * The tags of the currently-published skill, when this submission updates one already in the DB.
   * Passed so the reviewer validates its proposal against them and flags any change as an FYI
   * rather than silently replacing them. Null (omitted) for a brand-new skill.
   */
  val existingTags: List<String>? = null,
)

/**
 * Structured review output from the LLM (spec §6.2). The review never auto- publishes or flips
 * `verified`; it prepares the submission for a human (§6.4).
 *
 * @param category One of the existing categories' slugs (assigned by the LLM, never by the
 *   submitter per §3.3).
 * @param tagsProposed The tags the LLM proposes for discovery — proposing good tags is the
 *   reviewer's job, so this is populated even when the submitter gave none.
 * @param tagNotes Moderator-only notes about tag changes vs. an existing published skill's tags
 *   (add/drop rationale). Kept separate from [security] so they are never shown publicly — they are
 *   review annotations, not security observations.
 * @param security Automated security findings, each with a [SecurityFinding.severity]. Only
 *   `"flag"` findings fail a review; `"fyi"` findings are advisory context (and the only ones
 *   surfaced to users on the public skill page).
 * @param lintScore 0..100; recorded on the submission for the admin queue.
 */
@Serializable
data class ReviewResult(
  val category: String,
  val tagsProposed: List<String> = emptyList(),
  val security: SecurityResult,
  val lintScore: Int,
  val tagNotes: List<String> = emptyList(),
)

/**
 * Automated security assessment. [passed] reflects the LLM's own claim, but the authoritative
 * pass/fail for display is derived from finding severities (no `"flag"` ⇒ passes) — see
 * [dev.androidskills.ingest.ReviewOutputPayload.from].
 */
@Serializable
data class SecurityResult(val passed: Boolean, val findings: List<SecurityFinding> = emptyList())

/**
 * A single security observation.
 *
 * @param severity `"flag"` — a genuine, avoidable, or unexpected risk the admin must weigh; or
 *   `"fyi"` — a risk that is inherent to (and openly part of) the skill's declared purpose,
 *   surfaced as advisory context only.
 * @param message Human-readable description of the observation.
 */
@Serializable data class SecurityFinding(val severity: String, val message: String)

/**
 * Skill-review provider (§6). The real implementation ([OpenAiLlmClient], commit 3) speaks the
 * OpenAI-compatible chat-completions wire format with base-url / key / model all configurable, so
 * the provider stays a deploy-time choice (architecture.md). Absent locally → [StubLlmClient]
 * returns a benign result.
 */
interface LlmClient {
  val kind: String

  suspend fun review(input: SkillManifest): ReviewResult
}

/** Benign stub for local dev / tests (no external dependency). */
class StubLlmClient : LlmClient {
  override val kind = "stub"

  override suspend fun review(input: SkillManifest): ReviewResult =
    ReviewResult(
      category = "uncategorized",
      tagsProposed = input.tags,
      security = SecurityResult(passed = true),
      lintScore = 100,
    )
}
