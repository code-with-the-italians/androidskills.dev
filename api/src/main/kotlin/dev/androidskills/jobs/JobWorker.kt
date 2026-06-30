package dev.androidskills.jobs

import dev.androidskills.db.Bundles
import dev.androidskills.db.Categories
import dev.androidskills.db.Jobs
import dev.androidskills.db.Skills
import dev.androidskills.db.Submissions
import dev.androidskills.github.GitHubAppClient
import dev.androidskills.ingest.ArchiveSource
import dev.androidskills.ingest.IngestPipeline
import dev.androidskills.llm.LlmClient
import dev.androidskills.llm.LlmTransportException
import dev.androidskills.llm.SkillManifest
import dev.androidskills.storage.FileStore
import dev.androidskills.util.appJson
import dev.androidskills.util.nowIso
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * The §6 worker: a single coroutine polling `jobs WHERE state='queued' AND
 * run_after<=now` (single worker; SQLite single-writer). Handles `review` and
 * `resync` jobs.
 *
 * **Lifecycle:** started with the app; self-cancels on `ApplicationStopped`
 * (same pattern as `startSessionPurge`).
 *
 * **Robustness (review Q3/Q5):**
 * - Per-job **60s** wall-clock timeout (a slow LLM call can't stall the queue).
 * - Bad JSON advances the LLM ladder (in the client); transport failure (429/5xx)
 *   fails the job to backoff (step-3 P1-2 lesson).
 * - **Startup reclaim:** on boot, `state='running'` → `'queued'` (safe because
 *   there's only one worker; a `running` row at boot means the prior one died).
 * - **Backoff:** `run_after = now + 2^(attempts-1) * 30s` (30/60/120); max 3.
 *
 * **Review never auto-publishes or auto-verifies** (§6.4) — it prepares the
 * submission (category/tags/lintScore) for a human approver (step 7).
 */
private val logger = LoggerFactory.getLogger("dev.androidskills.jobs.JobWorker")

private const val POLL_INTERVAL_MS = 10_000L
private const val JOB_TIMEOUT_MS = 60_000L
private const val MAX_ATTEMPTS = 3
private const val BACKOFF_BASE_SECONDS = 30.0

internal fun startJobWorker(
    app: Application,
    llm: LlmClient,
    store: FileStore,
    gh: GitHubAppClient,
) {
    // Startup reclaim: any job stuck in 'running' means the prior worker died.
    runCatching { reclaimStaleJobs() }.onFailure { app.log.warn("Job reclaim failed", it) }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val job = scope.launch {
        while (isActive) {
            runCatching { processOneJob(llm, store, gh) }
                .onFailure { logger.warn("Job processing error", it) }
            delay(POLL_INTERVAL_MS)
        }
    }
    app.monitor.subscribe(ApplicationStopped) {
        job.cancel()
        scope.cancel()
    }
}

internal suspend fun processOneJob(llm: LlmClient, store: FileStore, gh: GitHubAppClient) {
    val jobRow = claimNextJob() ?: return // nothing queued
    val jobId = jobRow[Jobs.id]
    val jobType = jobRow[Jobs.type]
    val payload = jobRow[Jobs.payload]
    val attempts = jobRow[Jobs.attempts]

    val ok = try {
        withTimeoutOrNull(JOB_TIMEOUT_MS) {
            when (jobType) {
                "review" -> runReviewJob(payload, llm)
                "resync" -> runResyncJob(payload, store, gh)
                else -> throw IllegalStateException("Unknown job type: $jobType")
            }
        } != null
    } catch (e: Exception) {
        logger.warn("job {} ({}) failed: {}", jobId.take(8), jobType, e.message)
        false
    }

    if (ok) {
        markDone(jobId)
        logger.info("job {} ({}) done", jobId.take(8), jobType)
    } else {
        // Timeout or failure → retry with backoff or give up.
        val newAttempts = attempts + 1
        if (newAttempts >= MAX_ATTEMPTS) {
            markFailed(jobId, newAttempts, "max attempts ($MAX_ATTEMPTS) reached")
            logger.warn("job {} ({}) permanently failed after {} attempts", jobId.take(8), jobType, newAttempts)
        } else {
            val backoffSec = Math.pow(2.0, newAttempts - 1.0) * BACKOFF_BASE_SECONDS
            markRetry(jobId, newAttempts, backoffSec)
            logger.info("job {} ({}) will retry (attempt {}) in {}s", jobId.take(8), jobType, newAttempts, backoffSec.toInt())
        }
    }
}

// ---- Review job (§6) ----

private suspend fun runReviewJob(payload: String, llm: LlmClient) {
    val req = appJson.decodeFromString(ReviewPayload.serializer(), payload)

    // Bugbot B1-fix: load the submission to get the staged metadata (resync writes
    // the NEW version's name/desc/tags into payload.staged; the live skill row is
    // the OLD content). Fall back to the live skill row for a first-ingest review
    // (no staged payload — the skill row IS the new content).
    val sub = transaction {
        Submissions.selectAll().where { Submissions.id eq req.submissionId }.singleOrNull()
    } ?: throw IllegalStateException("Submission ${req.submissionId} not found for review")
    val subPayload = sub[Submissions.payload]?.let {
        runCatching { appJson.decodeFromString(dev.androidskills.ingest.SubmissionPayload.serializer(), it) }.getOrNull()
    }

    val manifest = if (subPayload?.staged != null) {
        // Resync: use the STAGED metadata, not the stale live skill row.
        SkillManifest(
            name = subPayload.staged.name,
            description = subPayload.staged.description,
            tags = subPayload.staged.tags,
        )
    } else {
        // First ingest: the live skill row IS the new content.
        val skill = transaction {
            Skills.selectAll().where { Skills.id eq req.skillId }.singleOrNull()
        } ?: throw IllegalStateException("Skill ${req.skillId} not found for review")
        SkillManifest(
            name = skill[Skills.name],
            description = skill[Skills.description],
            tags = try { appJson.decodeFromString<List<String>>(skill[Skills.tags]) } catch (_: Exception) { emptyList() },
        )
    }

    val result = llm.review(manifest)

    // Apply results (§6.3): category, tags, lintScore, security findings.
    transaction {
        // B4: tags are applied unconditionally (only category_id depends on slug resolution).
        val catId = Categories.selectAll().where { Categories.slug eq result.category }.singleOrNull()?.get(Categories.id)
        Skills.update({ Skills.id eq req.skillId }) {
            if (catId != null) it[Skills.categoryId] = catId
            it[Skills.tags] = appJson.encodeToString(result.tagsValidated)
            it[Skills.updatedAt] = nowIso()
        }
        // B1: merge review output into the submission payload WITHOUT destroying staged.
        val currentRow = Submissions.selectAll().where { Submissions.id eq req.submissionId }.singleOrNull()
        val currentPayload = currentRow?.get(Submissions.payload)?.let {
            runCatching { appJson.decodeFromString(dev.androidskills.ingest.SubmissionPayload.serializer(), it) }.getOrNull()
        } ?: dev.androidskills.ingest.SubmissionPayload()
        val merged = currentPayload.copy(review = dev.androidskills.ingest.ReviewOutputPayload.from(result))
        Submissions.update({ Submissions.id eq req.submissionId }) {
            it[Submissions.lintScore] = result.lintScore
            it[Submissions.payload] = appJson.encodeToString(dev.androidskills.ingest.SubmissionPayload.serializer(), merged)
            it[Submissions.updatedAt] = nowIso()
        }
    }
    // §6.4: never flip skills.status or verified.
}

// ---- Resync job (§5 + §8) ----

private suspend fun runResyncJob(payload: String, store: FileStore, gh: GitHubAppClient) {
    val req = appJson.decodeFromString(ResyncPayload.serializer(), payload)
    if (!gh.configured) {
        throw IllegalStateException("GitHub App not configured — cannot resync")
    }
    val bundle = transaction {
        Bundles.selectAll().where { Bundles.id eq req.bundleId }.singleOrNull()
    } ?: throw IllegalStateException("Bundle ${req.bundleId} not found for resync")

    val ownerUserId = bundle[Bundles.ownerUserId]
    val provenance = bundle[Bundles.provenance]
    val (owner, repo) = provenance.split("/", limit = 2).let {
        if (it.size == 2) it[0] to it[1] else throw IllegalStateException("Invalid provenance: $provenance")
    }
    val installationId = bundle[Bundles.installationId]
        ?: throw IllegalStateException("Bundle $provenance has no installation_id")

    val zipball = gh.downloadZipball(installationId, owner, repo, req.headSha)
    IngestPipeline.ingest(
        ArchiveSource.RepoZipball(zipball, owner, repo, req.headSha),
        req.bundleId, store, ownerUserId,
    )
}

// ---- Job state transitions ----

private fun claimNextJob() = transaction {
    val now = nowIso()
    val op = SqlExpressionBuilder.run { Jobs.runAfter lessEq now }
    val job = Jobs.selectAll()
        .where { (Jobs.state eq "queued") and op }
        .orderBy(Jobs.createdAt to org.jetbrains.exposed.sql.SortOrder.ASC)
        .firstOrNull()
    if (job != null) {
        Jobs.update({ Jobs.id eq job[Jobs.id] }) {
            it[Jobs.state] = "running"
            it[Jobs.updatedAt] = nowIso()
        }
    }
    job
}

private fun markDone(jobId: String) = transaction {
    Jobs.update({ Jobs.id eq jobId }) {
        it[Jobs.state] = "done"
        it[Jobs.updatedAt] = nowIso()
    }
}

private fun markFailed(jobId: String, attempts: Int, error: String) = transaction {
    Jobs.update({ Jobs.id eq jobId }) {
        it[Jobs.state] = "failed"
        it[Jobs.attempts] = attempts
        it[Jobs.lastError] = error
        it[Jobs.updatedAt] = nowIso()
    }
}

private fun markRetry(jobId: String, attempts: Int, backoffSeconds: Double) = transaction {
    val runAfter = Instant.now().plusSeconds(backoffSeconds.toLong()).toString()
    Jobs.update({ Jobs.id eq jobId }) {
        it[Jobs.state] = "queued"
        it[Jobs.attempts] = attempts
        it[Jobs.runAfter] = runAfter
        it[Jobs.updatedAt] = nowIso()
    }
}

internal fun reclaimStaleJobs() = transaction {
    Jobs.update({ Jobs.state eq "running" }) {
        it[Jobs.state] = "queued"
        it[Jobs.updatedAt] = nowIso()
    }
}

// ---- Payload DTOs ----

@kotlinx.serialization.Serializable
private data class ReviewPayload(val skillId: String, val submissionId: String)

@kotlinx.serialization.Serializable
private data class ResyncPayload(val bundleId: String, val headSha: String)
