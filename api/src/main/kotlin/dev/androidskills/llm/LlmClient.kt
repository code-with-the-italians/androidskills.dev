package dev.androidskills.llm

import kotlinx.serialization.Serializable

@Serializable
data class SkillManifest(
    val name: String,
    val description: String,
    val tags: List<String> = emptyList(),
)

@Serializable
data class ReviewResult(
    val category: String,
    val findings: List<String> = emptyList(),
    val validatedTags: List<String> = emptyList(),
)

/**
 * Skill-review provider. The real implementation speaks the OpenAI-compatible
 * chat-completions wire format with base-url / key / model all configurable, so
 * the provider stays a deploy-time choice (see docs/architecture.md). Stubbed
 * locally so the review pipeline runs with no external dependency.
 */
interface LlmClient {
    val kind: String
    suspend fun review(input: SkillManifest): ReviewResult
}

class StubLlmClient : LlmClient {
    override val kind = "stub"
    override suspend fun review(input: SkillManifest): ReviewResult =
        ReviewResult(
            category = "uncategorized",
            findings = emptyList(),
            validatedTags = input.tags,
        )
}
