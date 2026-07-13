package dev.androidskills.api

import dev.androidskills.auth.Principal
import dev.androidskills.db.Bundles
import dev.androidskills.db.Categories
import dev.androidskills.db.SkillFiles
import dev.androidskills.db.Skills
import dev.androidskills.db.Submissions
import dev.androidskills.db.Users
import dev.androidskills.github.GitHubAppClient
import dev.androidskills.github.GitHubAppException
import dev.androidskills.ingest.ArchiveSource
import dev.androidskills.ingest.IngestPipeline
import dev.androidskills.ingest.ReviewOutputPayload
import dev.androidskills.ingest.StagedPayload
import dev.androidskills.ingest.SubmissionPayload
import dev.androidskills.storage.FileStore
import dev.androidskills.util.appJson
import dev.androidskills.util.nowIso
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** Admin review queue (spec §9 Admin, step 7). */
object AdminQueueQueries {

  @Serializable
  data class QueueItem(
    val id: String,
    val slug: String,
    val state: String,
    val submitterHandle: String,
    val lintScore: Int?,
    val review: ReviewOutputPayload?,
    val staged: StagedPayload?,
    val createdAt: String,
    val updatedAt: String,
  )

  @Serializable
  data class DecisionRequest(
    val decision: String, // approve|request_changes|reject|skip
    val note: String? = null,
    // The admin's final tags (from the queue's editable, LLM-pre-filled field). Applied on approve
    // — after promote, so they override the manifest tags. Null leaves the promoted tags untouched.
    val tags: List<String>? = null,
  )

  /**
   * Normalise admin-entered tags: trim, drop blanks, dedupe (case-insensitive), cap count/length.
   */
  private fun sanitizeTags(tags: List<String>): List<String> {
    val seen = HashSet<String>()
    return tags
      .asSequence()
      .map { it.trim().take(40) }
      .filter { it.isNotEmpty() && seen.add(it.lowercase()) }
      .take(12)
      .toList()
  }

  @Serializable data class SkillFileSummary(val path: String, val size: Int, val isBinary: Boolean)

  @Serializable
  data class QueueDetail(
    val id: String,
    val slug: String,
    val state: String,
    val submitterHandle: String,
    val lintScore: Int?,
    val note: String?,
    val payload: SubmissionPayload?,
    val files: List<SkillFileSummary>,
    val provenance: String,
    val sourceRef: String,
    val reviewIsMetadataOnly: Boolean,
    val createdAt: String,
    val updatedAt: String,
  )

  fun queue(filter: String? = null, q: String? = null): List<QueueItem> = transaction {
    val states =
      when (filter) {
        "in_review" -> listOf("in_review")
        "changes_requested" -> listOf("changes_requested")
        null,
        "all" -> listOf("in_review", "changes_requested")
        else ->
          throw ApiValidationException(
            mapOf("filter" to "must be all, in_review, or changes_requested")
          )
      }
    val query =
      (Submissions innerJoin Skills leftJoin Users)
        .selectAll()
        .where { Submissions.state inList states }
        .orderBy(Submissions.updatedAt to SortOrder.DESC)

    query
      .map { row ->
        val payload =
          row[Submissions.payload]?.let {
            runCatching { appJson.decodeFromString(SubmissionPayload.serializer(), it) }.getOrNull()
          }
        QueueItem(
          id = row[Submissions.id],
          slug = row[Skills.slug],
          state = row[Submissions.state],
          submitterHandle = row[Users.handle],
          lintScore = row[Submissions.lintScore],
          review = payload?.review,
          staged = payload?.staged,
          createdAt = row[Submissions.createdAt],
          updatedAt = row[Submissions.updatedAt],
        )
      }
      .filter { item ->
        q.isNullOrBlank() ||
          item.slug.contains(q, ignoreCase = true) ||
          item.submitterHandle.contains(q, ignoreCase = true)
      }
  }

  fun detail(id: String): QueueDetail? = transaction {
    val row =
      (Submissions innerJoin Skills leftJoin Users leftJoin Bundles)
        .selectAll()
        .where { Submissions.id eq id }
        .singleOrNull() ?: return@transaction null

    val payload =
      row[Submissions.payload]?.let {
        runCatching { appJson.decodeFromString(SubmissionPayload.serializer(), it) }.getOrNull()
      }
    val skillId = row[Skills.id]
    val files =
      SkillFiles.selectAll()
        .where { SkillFiles.skillId eq skillId }
        .map {
          SkillFileSummary(
            path = it[SkillFiles.path],
            size = it[SkillFiles.size],
            isBinary = it[SkillFiles.isBinary],
          )
        }

    val provenance = row[Bundles.provenance]
    val sourceRef =
      payload?.staged?.sourceRef?.let { "${it.repoOwner}/${it.repoName}@${it.ref}" } ?: ""

    QueueDetail(
      id = row[Submissions.id],
      slug = row[Skills.slug],
      state = row[Submissions.state],
      submitterHandle = row[Users.handle],
      lintScore = row[Submissions.lintScore],
      note = row[Submissions.note],
      payload = payload,
      files = files,
      provenance = provenance,
      sourceRef = sourceRef,
      reviewIsMetadataOnly = true, // step-5/6 review only sees name/description/tags
      createdAt = row[Submissions.createdAt],
      updatedAt = row[Submissions.updatedAt],
    )
  }

  /**
   * Admin decision on a submission (approve/request_changes/reject/skip). Approve fetches the
   * archive, runs [IngestPipeline.promote], and publishes the skill in the same transaction as the
   * audit log write.
   */
  suspend fun decision(
    principal: Principal,
    submissionId: String,
    request: DecisionRequest,
    store: FileStore,
    gh: GitHubAppClient,
  ) {
    if (!gh.configured)
      throw ApiBadGatewayException("GitHub App is not configured", "github_app_disabled")

    val submission =
      transaction {
        Submissions.selectAll().where { Submissions.id eq submissionId }.singleOrNull()
      } ?: throw ApiNotFoundException("Submission not found")

    val state = submission[Submissions.state]
    if (state !in listOf("in_review", "changes_requested")) {
      throw ApiConflictException(
        "Submission is not awaiting decision",
        code = "invalid_state_transition",
      )
    }

    val skillId =
      submission[Submissions.skillId] ?: throw ApiNotFoundException("Submission has no skill")
    val skill =
      transaction { Skills.selectAll().where { Skills.id eq skillId }.singleOrNull() }
        ?: throw ApiNotFoundException("Skill not found")
    val bundleId =
      submission[Submissions.bundleId] ?: throw ApiNotFoundException("Submission has no bundle")
    val bundle =
      transaction { Bundles.selectAll().where { Bundles.id eq bundleId }.singleOrNull() }
        ?: throw ApiNotFoundException("Bundle not found")
    val slug = skill[Skills.slug]

    when (request.decision) {
      "approve" -> {
        val payload =
          submission[Submissions.payload]?.let {
            runCatching { appJson.decodeFromString(SubmissionPayload.serializer(), it) }.getOrNull()
          }
            ?: throw ApiBadGatewayException(
              "Submission has no staged payload",
              "missing_staged_payload",
            )
        val sourceRef =
          payload.staged?.sourceRef
            ?: throw ApiBadGatewayException("Submission has no source ref", "missing_source_ref")

        // X1: the review pinned a specific sha; if the staged ref moved (new push
        // while in review), the admin must re-review before approving.
        payload.reviewedSourceRef?.let { pinned ->
          if (sourceRef.ref != pinned.ref) {
            throw ApiConflictException(
              "Submission content changed since review; re-review required",
              "review_sha_moved",
            )
          }
        }

        val installationId =
          bundle[Bundles.installationId]
            ?: throw ApiBadGatewayException("Bundle has no installation", "bundle_no_installation")
        val (owner, repo) =
          bundle[Bundles.provenance].split("/", limit = 2).let {
            if (it.size == 2) it[0] to it[1]
            else
              throw ApiBadGatewayException(
                "Bundle provenance malformed",
                "bundle_provenance_malformed",
              )
          }

        val zipball =
          try {
            gh.downloadZipball(installationId, owner, repo, sourceRef.ref)
          } catch (e: GitHubAppException) {
            throw ApiBadGatewayException(
              "Archive download failed: ${e.message}",
              "archive_download_failed",
            )
          }
        val result =
          IngestPipeline.promote(
            skillId,
            ArchiveSource.RepoZipball(zipball, owner, repo, sourceRef.ref),
            bundleId,
            store,
            submission[Submissions.submitterId],
            guard = {
              val current = transaction {
                Submissions.selectAll().where { Submissions.id eq submissionId }.singleOrNull()
              }
              if (current?.get(Submissions.state) !in listOf("in_review", "changes_requested")) {
                throw ApiConflictException(
                  "Submission state changed concurrently",
                  "concurrent_state_change",
                )
              }
            },
          )

        transaction {
          val current =
            Submissions.selectAll().where { Submissions.id eq submissionId }.singleOrNull()
          if (current?.get(Submissions.state) !in listOf("in_review", "changes_requested")) {
            throw ApiConflictException(
              "Submission state changed concurrently",
              "concurrent_state_change",
            )
          }
          val now = nowIso()
          val review = payload.review
          val categorySlug = review?.category
          val categoryId =
            categorySlug?.let { cat ->
              Categories.selectAll()
                .where { Categories.slug eq cat }
                .singleOrNull()
                ?.get(Categories.id)
            }

          // Advisory (fyi) notes surface on the public skill page; hard flags never reach a
          // published skill, so only the advisory notes are carried over.
          val advisoryNotes =
            review?.securityFindings?.filter { it.severity == "fyi" }?.map { it.message }
              ?: emptyList()

          Skills.update({ Skills.id eq skillId }) {
            it[Skills.status] = "published"
            it[Skills.verified] = true
            categoryId?.let { cid -> it[Skills.categoryId] = cid }
            // Admin's final tags win over the manifest tags that promote just wrote. Guard against
            // an empty/blank list wiping discovery tags — only a non-empty set overrides.
            request.tags
              ?.let(::sanitizeTags)
              ?.takeIf { it.isNotEmpty() }
              ?.let { clean -> it[Skills.tags] = appJson.encodeToString(clean) }
            it[Skills.security] =
              advisoryNotes.takeIf { it.isNotEmpty() }?.let(appJson::encodeToString)
            it[Skills.updatedAt] = now
          }
          Submissions.update({ Submissions.id eq submissionId }) {
            it[Submissions.state] = "published"
            it[Submissions.note] = request.note
            it[Submissions.updatedAt] = now
          }
          AuditLogQueries.write(
            actorId = principal.userId,
            action = "submission.approve",
            target = "submission:$submissionId",
            meta =
              AuditLogQueries.AuditMeta(
                note = request.note,
                after =
                  mapOf("status" to "published", "verified" to "true", "version" to result.version),
              ),
          )
        }
      }
      "request_changes" -> {
        transaction {
          val current =
            Submissions.selectAll().where { Submissions.id eq submissionId }.singleOrNull()
          if (current?.get(Submissions.state) !in listOf("in_review", "changes_requested")) {
            throw ApiConflictException(
              "Submission state changed concurrently",
              "concurrent_state_change",
            )
          }
          Submissions.update({ Submissions.id eq submissionId }) {
            it[Submissions.state] = "changes_requested"
            it[Submissions.note] = request.note
            it[Submissions.updatedAt] = nowIso()
          }
          AuditLogQueries.write(
            actorId = principal.userId,
            action = "submission.request_changes",
            target = "submission:$submissionId",
            meta = AuditLogQueries.AuditMeta(note = request.note),
          )
        }
      }
      "reject" -> {
        transaction {
          val current =
            Submissions.selectAll().where { Submissions.id eq submissionId }.singleOrNull()
          if (current?.get(Submissions.state) !in listOf("in_review", "changes_requested")) {
            throw ApiConflictException(
              "Submission state changed concurrently",
              "concurrent_state_change",
            )
          }
          Submissions.update({ Submissions.id eq submissionId }) {
            it[Submissions.state] = "rejected"
            it[Submissions.note] = request.note
            it[Submissions.updatedAt] = nowIso()
          }
          AuditLogQueries.write(
            actorId = principal.userId,
            action = "submission.reject",
            target = "submission:$submissionId",
            meta = AuditLogQueries.AuditMeta(note = request.note),
          )
        }
      }
      "skip" -> {
        // no-op
      }
      else ->
        throw ApiValidationException(
          mapOf("decision" to "must be approve, request_changes, reject, or skip")
        )
    }
  }
}
