package dev.androidskills.api

import dev.androidskills.auth.Principal
import dev.androidskills.db.Bundles
import dev.androidskills.db.SkillFiles
import dev.androidskills.db.Skills
import dev.androidskills.db.Submissions
import dev.androidskills.db.Users
import dev.androidskills.ingest.ReviewOutputPayload
import dev.androidskills.ingest.StagedPayload
import dev.androidskills.ingest.SubmissionPayload
import dev.androidskills.util.appJson
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Admin review queue (spec §9 Admin, step 7 commit 1).
 */
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
    data class SkillFileSummary(
        val path: String,
        val size: Int,
        val isBinary: Boolean,
    )

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
        val states = when (filter) {
            "in_review" -> listOf("in_review")
            "changes_requested" -> listOf("changes_requested")
            null, "all" -> listOf("in_review", "changes_requested")
            else -> throw ApiValidationException(mapOf("filter" to "must be all, in_review, or changes_requested"))
        }
        val query = (Submissions innerJoin Skills leftJoin Users)
            .selectAll()
            .where { Submissions.state inList states }
            .orderBy(Submissions.updatedAt to SortOrder.DESC)

        query.map { row ->
            val payload = row[Submissions.payload]?.let {
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
        }.filter { item ->
            q.isNullOrBlank() || item.slug.contains(q, ignoreCase = true) || item.submitterHandle.contains(q, ignoreCase = true)
        }
    }

    fun detail(id: String): QueueDetail? = transaction {
        val row = (Submissions innerJoin Skills leftJoin Users leftJoin Bundles)
            .selectAll()
            .where { Submissions.id eq id }
            .singleOrNull() ?: return@transaction null

        val payload = row[Submissions.payload]?.let {
            runCatching { appJson.decodeFromString(SubmissionPayload.serializer(), it) }.getOrNull()
        }
        val skillId = row[Skills.id]
        val files = SkillFiles.selectAll()
            .where { SkillFiles.skillId eq skillId }
            .map {
                SkillFileSummary(
                    path = it[SkillFiles.path],
                    size = it[SkillFiles.size],
                    isBinary = it[SkillFiles.isBinary],
                )
            }

        val provenance = row[Bundles.provenance]
        val sourceRef = payload?.staged?.sourceRef?.let { "${it.repoOwner}/${it.repoName}@${it.ref}" } ?: ""

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
}
