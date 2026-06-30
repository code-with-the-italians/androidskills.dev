package dev.androidskills.ingest

import dev.androidskills.llm.ReviewResult
import kotlinx.serialization.Serializable

/**
 * The merged payload on `submissions.payload` (B1: prevents a review overwrite from
 * destroying the staged version metadata that step-7 promotion needs). Each component
 * writes only its key; the other survives.
 */
@Serializable
data class SubmissionPayload(
    val staged: StagedPayload? = null,
    val review: ReviewOutputPayload? = null,
)

/** The staged version's metadata — what step-7 promotion promotes to the live skill. */
@Serializable
data class StagedPayload(
    val version: String,
    val versionSource: String,
    val name: String,
    val description: String,
    val license: String?,
    val tags: List<String>,
)

/** The review output — what the admin queue displays (category, findings, lintScore). */
@Serializable
data class ReviewOutputPayload(
    val category: String,
    val tagsValidated: List<String>,
    val securityPassed: Boolean,
    val securityFindings: List<String>,
    val lintScore: Int,
) {
    companion object {
        fun from(result: ReviewResult) = ReviewOutputPayload(
            category = result.category,
            tagsValidated = result.tagsValidated,
            securityPassed = result.security.passed,
            securityFindings = result.security.findings,
            lintScore = result.lintScore,
        )
    }
}
