package dev.androidskills.llm

import kotlinx.serialization.Serializable

/** The manifest fields sent to the LLM for review (§6.1). */
@Serializable
data class SkillManifest(
    val name: String,
    val description: String,
    val tags: List<String> = emptyList(),
)

/**
 * Structured review output from the LLM (spec §6.2). The review never auto-
 * publishes or flips `verified`; it prepares the submission for a human (§6.4).
 *
 * @param category One of the existing categories' slugs (assigned by the LLM,
 *   never by the submitter per §3.3).
 * @param tagsValidated The tags the LLM accepted (subset of the submitted tags).
 * @param security Automated security findings. `passed == false` blocks auto-
 *   verify and surfaces findings to the admin queue (step 7).
 * @param lintScore 0..100; recorded on the submission for the admin queue.
 */
@Serializable
data class ReviewResult(
    val category: String,
    val tagsValidated: List<String> = emptyList(),
    val security: SecurityResult,
    val lintScore: Int,
)

@Serializable
data class SecurityResult(
    val passed: Boolean,
    val findings: List<String> = emptyList(),
)

/**
 * Skill-review provider (§6). The real implementation ([OpenAiLlmClient], commit 3)
 * speaks the OpenAI-compatible chat-completions wire format with base-url / key /
 * model all configurable, so the provider stays a deploy-time choice (architecture.md).
 * Absent locally → [StubLlmClient] returns a benign result.
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
            tagsValidated = input.tags,
            security = SecurityResult(passed = true),
            lintScore = 100,
        )
}
