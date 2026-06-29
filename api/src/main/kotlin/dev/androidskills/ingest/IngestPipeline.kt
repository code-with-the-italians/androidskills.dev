package dev.androidskills.ingest

import dev.androidskills.db.Bundles
import dev.androidskills.db.Jobs
import dev.androidskills.db.Skills
import dev.androidskills.db.SkillFiles
import dev.androidskills.db.Submissions
import dev.androidskills.db.Users
import dev.androidskills.db.Versions
import dev.androidskills.storage.FileStore
import dev.androidskills.storage.ZipBuilder
import dev.androidskills.util.appJson
import dev.androidskills.util.newId
import dev.androidskills.util.nowIso
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

/**
 * The §5 write path. Takes a discovered archive and persists it — mirrors files,
 * upserts skills/versions, creates submissions, enqueues review. **Stage-split**
 * (review issue 1): a new skill writes everything; a resync of an existing skill
 * writes ONLY the version-keyed zip + `versions` row + submission payload,
 * touching nothing the read API serves (skills metadata, readme_md, skill_files,
 * the files/ mirror) until step-7 approval promotes the staged version.
 *
 * **fs/tx boundary (issue C):** FileStore writes happen outside the DB transaction;
 * a late DB failure strands orphan objects (no reaper exists today).
 *
 * **`skills.status` = `unlisted`, `verified=false`** (decision Q1) — not slug-reachable
 * until step-7 approval. The review worker prepares (category/tags/lintScore) but
 * never flips status/verified.
 */
object IngestPipeline {

    data class IngestResult(val skills: List<IngestedSkill>)
    data class IngestedSkill(val skillId: String, val slug: String, val isNew: Boolean, val version: String)

    fun ingest(source: ArchiveSource, bundleId: String, store: FileStore, submitterId: String): IngestResult {
        val scanResult = Discovery.discover(source)
        if (scanResult is ScanResult.NoSkillsDir) {
            throw IllegalStateException("No top-level 'skills/' directory in archive")
        }
        val detected = (scanResult as ScanResult.Found).skills
        if (detected.isEmpty()) throw IllegalStateException("No valid skills discovered")

        val extracted = Discovery.extract(source) // raw file bytes for mirroring + zip
        val ingested = mutableListOf<IngestedSkill>()

        for (skill in detected) {
            val skillDir = "skills/${skill.slug}"
            val files = extracted.filter { Discovery.topDir(it.path) == skillDir }
            val skillMd = files.firstOrNull { it.path == "$skillDir/SKILL.md" }
            val body = skillMd?.bytes?.toString(Charsets.UTF_8) ?: ""

            val existing = transaction {
                Skills.selectAll()
                    .where { (Skills.bundleId eq bundleId) and (Skills.slug eq skill.slug) }
                    .singleOrNull()
            }

            val version = skill.version
            val isNew = existing == null

            // ---- FileStore writes (outside the DB transaction, per issue C) ----

            val skillId = existing?.get(Skills.id) ?: newId()
            val fileEntries = files.map { it.path.removePrefix("$skillDir/") to it.bytes }
            val zipKey = "skills/$skillId/versions/$version.zip"

            if (isNew) {
                // NEW SKILL: write everything (mirror + skill_files + skills + versions).
                for ((relPath, bytes) in fileEntries) {
                    val safePath = SkillPaths.safeRelativeOrNull(relPath) ?: continue
                    store.put("skills/$skillId/files/$safePath", bytes)
                }
            }
            // Always build the version zip (version-keyed, doesn't clobber anything live).
            val zipBytes = ZipBuilder.build(fileEntries.map { (p, b) -> p to b }, readmeMd = body.ifBlank { null })
            store.put(zipKey, zipBytes)

            // ---- DB transaction (references the FileStore objects above) ----

            transaction {
                val now = nowIso()
                if (isNew) {
                    // Insert the skill row (unlisted, unverified — Q1).
                    Skills.insert {
                        it[Skills.id] = skillId
                        it[Skills.bundleId] = bundleId
                        it[Skills.slug] = skill.slug
                        it[Skills.name] = skill.name
                        it[Skills.description] = skill.description
                        it[Skills.license] = skill.license
                        it[Skills.tags] = appJson.encodeToString(skill.tags)
                        it[Skills.version] = version
                        it[Skills.versionSource] = skill.versionSource
                        it[Skills.tokenUpfront] = skill.tokenUpfront
                        it[Skills.tokenOndemand] = skill.tokenOndemand
                        it[Skills.tokenBand] = skill.tokenBand
                        it[Skills.verified] = false
                        it[Skills.status] = "unlisted"
                        it[Skills.readmeMd] = body
                        it[Skills.createdAt] = now
                        it[Skills.updatedAt] = now
                    }
                    // Mirror skill_files (NEW SKILL ONLY — resync doesn't touch these).
                    for ((relPath, bytes) in fileEntries) {
                        val safePath = SkillPaths.safeRelativeOrNull(relPath) ?: continue
                        SkillFiles.insert {
                            it[SkillFiles.id] = newId()
                            it[SkillFiles.skillId] = skillId
                            it[SkillFiles.path] = safePath
                            it[SkillFiles.size] = bytes.size
                            it[SkillFiles.isBinary] = isProbablyBinary(bytes)
                            it[SkillFiles.r2Key] = "skills/$skillId/files/$safePath"
                        }
                    }
                }
                // Append the version row (INSERT OR IGNORE — idempotent for reclaim, issue 2).
                Versions.insertIgnore {
                    it[Versions.id] = newId()
                    it[Versions.skillId] = skillId
                    it[Versions.version] = version
                    it[Versions.sourceRef] = "${skill.versionSource}:$version"
                    it[Versions.r2ZipKey] = zipKey
                    it[Versions.createdAt] = now
                }

                // Create/update the submission (Q4: per-skill, update key = open in_review for (bundle, skill)).
                val subId = ensureSubmission(bundleId, skillId, submitterId, skill, version, source)

                // Enqueue a review job.
                Jobs.insert {
                    it[Jobs.id] = newId()
                    it[Jobs.type] = "review"
                    it[Jobs.payload] = appJson.encodeToString(
                        ReviewPayload.serializer(),
                        ReviewPayload(skillId, subId),
                    )
                    it[Jobs.state] = "queued"
                    it[Jobs.attempts] = 0
                    it[Jobs.runAfter] = now
                    it[Jobs.createdAt] = now
                    it[Jobs.updatedAt] = now
                }

                ingested += IngestedSkill(skillId, skill.slug, isNew, version)
            }
        }
        return IngestResult(ingested)
    }

    /** Reuse the open `in_review` submission for (bundle, skill), or create one. */
    private fun ensureSubmission(
        bundleId: String, skillId: String, submitterId: String,
        skill: DetectedSkill, version: String, source: ArchiveSource,
    ): String {
        val existing = Submissions.selectAll()
            .where {
                (Submissions.bundleId eq bundleId) and
                    (Submissions.skillId eq skillId) and
                    (Submissions.state eq "in_review")
            }
            .singleOrNull()
        if (existing != null) {
            val subId = existing[Submissions.id]
            val payload = appJson.encodeToString(
                StagedPayload.serializer(),
                StagedPayload(version, skill.versionSource, skill.name, skill.description, skill.license, skill.tags),
            )
            Submissions.update({ Submissions.id eq subId }) {
                it[Submissions.payload] = payload
                it[Submissions.updatedAt] = nowIso()
            }
            return subId
        }
        val subId = newId()
        val now = nowIso()
        val payload = appJson.encodeToString(
            StagedPayload.serializer(),
            StagedPayload(version, skill.versionSource, skill.name, skill.description, skill.license, skill.tags),
        )
        Submissions.insertIgnore {
            it[Submissions.id] = subId
            it[Submissions.bundleId] = bundleId
            it[Submissions.skillId] = skillId
            it[Submissions.submitterId] = submitterId
            it[Submissions.state] = "in_review"
            it[Submissions.payload] = payload
            it[Submissions.createdAt] = now
            it[Submissions.updatedAt] = now
        }
        return subId
    }

    private fun isProbablyBinary(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 2048)
        for (i in 0 until n) if (bytes[i] == 0.toByte()) return true
        return false
    }

    @kotlinx.serialization.Serializable
    private data class ReviewPayload(val skillId: String, val submissionId: String)

    @kotlinx.serialization.Serializable
    private data class StagedPayload(
        val version: String,
        val versionSource: String,
        val name: String,
        val description: String,
        val license: String?,
        val tags: List<String>,
    )
}
