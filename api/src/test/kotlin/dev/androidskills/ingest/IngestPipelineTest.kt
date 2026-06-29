package dev.androidskills.ingest

import dev.androidskills.Database
import dev.androidskills.TestSupport
import dev.androidskills.db.Bundles
import dev.androidskills.db.Jobs
import dev.androidskills.db.SkillFiles
import dev.androidskills.db.Skills
import dev.androidskills.db.Submissions
import dev.androidskills.db.Users
import dev.androidskills.db.Versions
import dev.androidskills.storage.LocalFsStore
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The staging-split write path: new skill writes everything; resync stages only. */
class IngestPipelineTest {

    private val dir = TestSupport.tempDir()
    private val store = LocalFsStore(dir.resolve("files"))

    @BeforeTest fun setup() = Database.init(TestSupport.newConfig(dir))
    @AfterTest fun teardown() { dir.toFile().deleteRecursively() }

    private fun seedBundle(): Pair<String, String> {
        val userId = transaction {
            val id = dev.androidskills.util.newId()
            val now = dev.androidskills.util.nowIso()
            Users.insert {
                it[Users.id] = id; it[Users.githubId] = 1; it[Users.handle] = "alice"
                it[Users.createdAt] = now; it[Users.updatedAt] = now
            }
            id
        }
        val bundleId = transaction {
            val id = dev.androidskills.util.newId()
            val now = dev.androidskills.util.nowIso()
            Bundles.insert {
                it[Bundles.id] = id; it[Bundles.kind] = "zip"; it[Bundles.provenance] = "test-upload"
                it[Bundles.ownerUserId] = userId; it[Bundles.createdAt] = now
            }
            id
        }
        return bundleId to userId
    }

    private fun skillZip(slug: String, name: String, desc: String, version: String): ByteArray {
        val body = "---\nname: $name\ndescription: $desc\nlicense: MIT\nmetadata:\n  version: $version\ntags: [android]\n---\n# $name\n"
        return zip("skills/$slug/SKILL.md" to body, "skills/$slug/references/guide.md" to "A guide.\n")
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name)); zos.write(content.toByteArray()); zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun skillRow(slug: String) = transaction {
        Skills.selectAll().where { Skills.slug eq slug }.single()
    }

    @Test
    fun `new skill - writes everything`() {
        val (bundleId, userId) = seedBundle()
        val zipBytes = skillZip("test-mvi", "MVI Scaffold", "A baseline.", "1.0.0")
        val result = IngestPipeline.ingest(
            ArchiveSource.UploadedZip(zipBytes, "hash1"), bundleId, store, userId,
        )
        assertEquals(1, result.skills.size)
        val skill = result.skills[0]
        assertTrue(skill.isNew)
        assertEquals("1.0.0", skill.version)

        // DB: skills row is unlisted + unverified (Q1).
        val row = skillRow("test-mvi")
        assertEquals("unlisted", row[Skills.status])
        assertFalse(row[Skills.verified])
        assertEquals("1.0.0", row[Skills.version])
        assertEquals("manifest", row[Skills.versionSource])

        // DB: skill_files mirrored (new skill only).
        val files = transaction { SkillFiles.selectAll().where { SkillFiles.skillId eq skill.skillId }.toList() }
        assertTrue(files.size >= 2, "expected mirrored files; got ${files.size}")

        // DB: versions row + r2_zip_key.
        val versions = transaction { Versions.selectAll().where { Versions.skillId eq skill.skillId }.toList() }
        assertEquals(1, versions.size)
        assertTrue(versions[0][Versions.r2ZipKey]?.contains("1.0.0.zip") == true)

        // DB: review job enqueued.
        val jobs = transaction { Jobs.selectAll().where { Jobs.type eq "review" }.toList() }
        assertEquals(1, jobs.size)

        // FileStore: version zip exists.
        assertTrue(store.exists(versions[0][Versions.r2ZipKey]!!))

        // DB: submission created (in_review).
        val subs = transaction { Submissions.selectAll().where { Submissions.skillId eq skill.skillId }.toList() }
        assertEquals(1, subs.size)
        assertEquals("in_review", subs[0][Submissions.state])
    }

    @Test
    fun `resync stages - does not overwrite live content`() {
        val (bundleId, userId) = seedBundle()
        val skillId = IngestPipeline.ingest(
            ArchiveSource.UploadedZip(skillZip("resync-test", "Original", "Original desc.", "1.0.0"), "h1"),
            bundleId, store, userId,
        ).skills[0].skillId

        // Simulate step-7 approval: publish the skill.
        transaction {
            Skills.update({ Skills.id eq skillId }) {
                it[Skills.status] = "published"; it[Skills.verified] = true
            }
        }
        val originalName = skillRow("resync-test")[Skills.name]
        val originalDesc = skillRow("resync-test")[Skills.description]
        val originalReadme = skillRow("resync-test")[Skills.readmeMd]

        // Resync: push new content under a new version.
        IngestPipeline.ingest(
            ArchiveSource.UploadedZip(skillZip("resync-test", "MALICIOUS", "Hacked!", "2.0.0"), "h2"),
            bundleId, store, userId,
        )

        val after = skillRow("resync-test")
        // Issue 1: the LIVE skill row is untouched — name/desc/readme NOT overwritten.
        assertEquals("Original", after[Skills.name], "live name must not change")
        assertEquals("Original desc.", after[Skills.description], "live desc must not change")
        assertEquals(originalReadme, after[Skills.readmeMd], "live readme must not change")
        assertEquals("published", after[Skills.status], "status must not change")
        assertTrue(after[Skills.verified], "verified must not change")

        // The live skill_files mirror is unchanged (new files NOT mirrored).
        val filesBefore = transaction { SkillFiles.selectAll().where { SkillFiles.skillId eq skillId }.toList() }
        val originalFileCount = filesBefore.size // from the first ingest

        // A NEW version row was appended (staged).
        val versions = transaction { Versions.selectAll().where { Versions.skillId eq skillId }.orderBy(Versions.createdAt, org.jetbrains.exposed.sql.SortOrder.ASC).toList() }
        assertEquals(2, versions.size, "expected 2 version rows (original + staged)")
        assertEquals("2.0.0", versions[1][Versions.version])

        // The live files/ mirror was NOT touched (no new skill_files rows).
        val filesAfter = transaction { SkillFiles.selectAll().where { SkillFiles.skillId eq skillId }.toList() }
        assertEquals(originalFileCount, filesAfter.size, "skill_files must not change on resync")

        // The staged version zip exists in the store.
        val stagedKey = versions[1][Versions.r2ZipKey]
        assertTrue(stagedKey != null && store.exists(stagedKey), "staged zip must exist")

        // A review job was enqueued for the new version.
        val reviewJobs = transaction { Jobs.selectAll().where { Jobs.type eq "review" }.toList() }
        assertEquals(2, reviewJobs.size, "expected 2 review jobs (one per ingest)")
    }

    @Test
    fun `idempotent version upsert - no UNIQUE violation`() {
        val (bundleId, userId) = seedBundle()
        val zipBytes = skillZip("idemp-test", "Idempotent", "d", "1.0.0")
        // Ingest the same version twice.
        IngestPipeline.ingest(ArchiveSource.UploadedZip(zipBytes, "h1"), bundleId, store, userId)
        IngestPipeline.ingest(ArchiveSource.UploadedZip(zipBytes, "h1"), bundleId, store, userId)
        // No exception thrown → INSERT OR IGNORE worked.
        val skillId = skillRow("idemp-test")[Skills.id]
        val versions = transaction { Versions.selectAll().where { Versions.skillId eq skillId }.toList() }
        assertEquals(1, versions.size, "same version must not duplicate")
    }

    @Test
    fun `submission reuse - open in_review reused on resync`() {
        val (bundleId, userId) = seedBundle()
        IngestPipeline.ingest(
            ArchiveSource.UploadedZip(skillZip("sub-test", "Sub", "d", "1.0.0"), "h1"),
            bundleId, store, userId,
        )
        val skillId = skillRow("sub-test")[Skills.id]
        val subsBefore = transaction { Submissions.selectAll().where { Submissions.skillId eq skillId }.toList() }
        assertEquals(1, subsBefore.size)

        // Resync: reuse the open in_review submission, don't create a second one.
        IngestPipeline.ingest(
            ArchiveSource.UploadedZip(skillZip("sub-test", "Sub v2", "d2", "2.0.0"), "h2"),
            bundleId, store, userId,
        )
        val subsAfter = transaction { Submissions.selectAll().where { Submissions.skillId eq skillId }.toList() }
        assertEquals(1, subsAfter.size, "open in_review submission must be reused, not duplicated")
    }
}
