package dev.androidskills.ingest

import dev.androidskills.llm.ReviewResult
import dev.androidskills.llm.SecurityFinding
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

/**
 * The merged payload on `submissions.payload` (B1: prevents a review overwrite from destroying the
 * staged version metadata that step-7 promotion needs). Each component writes only its key; the
 * other survives.
 */
@Serializable
data class SubmissionPayload(
  val staged: StagedPayload? = null,
  val review: ReviewOutputPayload? = null,
  val reviewedSourceRef: StagedPayload.SourceRef? =
    null, // pinned at review time; approve checks against staged.sourceRef
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
  val sourceRef: SourceRef? = null, // repo source so step-7 approval can fetch the archive
) {
  @Serializable data class SourceRef(val repoOwner: String, val repoName: String, val ref: String)
}

/** The review output — what the admin queue displays (category, findings, lintScore). */
@Serializable
data class ReviewOutputPayload(
  val category: String,
  val tagsProposed: List<String> = emptyList(),
  val securityPassed: Boolean,
  @Serializable(with = SecurityFindingsSerializer::class)
  val securityFindings: List<SecurityFinding> = emptyList(),
  val lintScore: Int,
) {
  companion object {
    fun from(result: ReviewResult) =
      ReviewOutputPayload(
        category = result.category,
        tagsProposed = result.tagsProposed,
        // Authoritative pass/fail, fail-closed: a review passes only when every finding is
        // explicitly advisory ("fyi", inherent to the skill's declared purpose). Anything else —
        // "flag", or an unrecognised severity — fails it.
        securityPassed = result.security.findings.none { it.severity != "fyi" },
        securityFindings = result.security.findings,
        lintScore = result.lintScore,
      )
  }
}

/**
 * Tolerant deserializer for [ReviewOutputPayload.securityFindings]: reads the current
 * `[{severity,message}]` shape and also legacy rows that stored findings as bare strings (mapped to
 * `severity = "flag"`, preserving their original blocking semantics), so a pre-change payload still
 * decodes instead of failing the whole [SubmissionPayload] and taking `staged` down with it.
 */
private object SecurityFindingsSerializer : KSerializer<List<SecurityFinding>> {
  private val listSerializer = ListSerializer(SecurityFinding.serializer())
  override val descriptor: SerialDescriptor = listSerializer.descriptor

  override fun serialize(encoder: Encoder, value: List<SecurityFinding>) =
    encoder.encodeSerializableValue(listSerializer, value)

  override fun deserialize(decoder: Decoder): List<SecurityFinding> {
    val json = (decoder as? JsonDecoder) ?: return decoder.decodeSerializableValue(listSerializer)
    return json.decodeJsonElement().jsonArray.map { el ->
      when (el) {
        is JsonObject ->
          SecurityFinding(
            severity = (el["severity"] as? JsonPrimitive)?.content ?: "flag",
            message = (el["message"] as? JsonPrimitive)?.content ?: "",
          )
        is JsonPrimitive -> SecurityFinding("flag", el.content)
        else -> SecurityFinding("flag", el.toString())
      }
    }
  }
}
