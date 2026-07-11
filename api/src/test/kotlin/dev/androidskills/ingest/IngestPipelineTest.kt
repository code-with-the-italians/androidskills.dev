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
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** The staging-split write path: new skill writes everything; resync stages only. */
class IngestPipelineTest {

  private val dir = TestSupport.tempDir()
  private val store = LocalFsStore(dir.resolve("files"))

  @BeforeTest fun setup() = Database.init(TestSupport.newConfig(dir))

  @AfterTest
  fun teardown() {
    dir.toFile().deleteRecursively()
  }

  private fun seedBundle(): Pair<String, String> {
    val userId = transaction {
      val id = dev.androidskills.util.newId()
      val now = dev.androidskills.util.nowIso()
      Users.insert {
        it[Users.id] = id
        it[Users.githubId] = 1
        it[Users.handle] = "alice"
        it[Users.createdAt] = now
        it[Users.updatedAt] = now
      }
      id
    }
    val bundleId = transaction {
      val id = dev.androidskills.util.newId()
      val now = dev.androidskills.util.nowIso()
      Bundles.insert {
        it[Bundles.id] = id
        it[Bundles.kind] = "zip"
        it[Bundles.provenance] = "test-upload"
        it[Bundles.ownerUserId] = userId
        it[Bundles.createdAt] = now
      }
      id
    }
    return bundleId to userId
  }

  private fun skillZip(slug: String, name: String, desc: String, version: String): ByteArray {
    val body =
      "---\nname: $name\ndescription: $desc\nlicense: MIT\nmetadata:\n  version: $version\ntags: [android]\n---\n# $name\n"
    return zip("skills/$slug/SKILL.md" to body, "skills/$slug/references/guide.md" to "A guide.\n")
  }

  private fun zip(vararg entries: Pair<String, String>): ByteArray {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zos ->
      entries.forEach { (name, content) ->
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray())
        zos.closeEntry()
      }
    }
    return baos.toByteArray()
  }

  private fun skillRow(slug: String) = transaction {
    Skills.selectAll().where { Skills.slug eq slug }.single()
  }

  @Test
  fun `ingests a skill from a non-skills layout (any-depth discovery)`() {
    val (bundleId, userId) = seedBundle()
    // android/skills-style: skill under a category dir, not under a top-level skills/.
    val body =
      "---\nname: adaptive\ndescription: Adaptive UI.\nlicense: Apache-2.0\ntags: [android]\n---\n# Adaptive\n"
    val zipBytes =
      zip(
        "owner-repo-sha/jetpack-compose/adaptive/SKILL.md" to body,
        "owner-repo-sha/jetpack-compose/adaptive/references/grid.md" to "grid notes\n",
      )
    val result =
      IngestPipeline.ingest(
        ArchiveSource.RepoZipball(zipBytes, "owner", "repo", "abcdef1234567890"),
        bundleId,
        store,
        userId,
      )
    assertEquals(1, result.skills.size)
    assertEquals("adaptive", result.skills[0].slug)
    val skillId = result.skills[0].skillId
    val files = transaction {
      SkillFiles.selectAll().where { SkillFiles.skillId eq skillId }.toList()
    }
    assertTrue(
      files.size >= 2,
      "SKILL.md + references mirrored under the real dir; got ${files.size}",
    )
    assertTrue(skillRow("adaptive")[Skills.readmeMd]?.contains("Adaptive") == true)
  }

  @Test
  fun `new skill - writes everything`() {
    val (bundleId, userId) = seedBundle()
    val zipBytes = skillZip("test-mvi", "MVI Scaffold", "A baseline.", "1.0.0")
    val result =
      IngestPipeline.ingest(ArchiveSource.UploadedZip(zipBytes, "hash1"), bundleId, store, userId)
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
    val files = transaction {
      SkillFiles.selectAll().where { SkillFiles.skillId eq skill.skillId }.toList()
    }
    assertTrue(files.size >= 2, "expected mirrored files; got ${files.size}")

    // DB: versions row + r2_zip_key.
    val versions = transaction {
      Versions.selectAll().where { Versions.skillId eq skill.skillId }.toList()
    }
    assertEquals(1, versions.size)
    assertTrue(versions[0][Versions.r2ZipKey]?.contains("1.0.0.zip") == true)

    // DB: review job enqueued.
    val jobs = transaction { Jobs.selectAll().where { Jobs.type eq "review" }.toList() }
    assertEquals(1, jobs.size)

    // FileStore: version zip exists.
    assertTrue(store.exists(versions[0][Versions.r2ZipKey]!!))

    // DB: submission created (in_review).
    val subs = transaction {
      Submissions.selectAll().where { Submissions.skillId eq skill.skillId }.toList()
    }
    assertEquals(1, subs.size)
    assertEquals("in_review", subs[0][Submissions.state])
  }

  @Test
  fun `resync stages - does not overwrite live content`() {
    val (bundleId, userId) = seedBundle()
    val skillId =
      IngestPipeline.ingest(
          ArchiveSource.UploadedZip(
            skillZip("resync-test", "Original", "Original desc.", "1.0.0"),
            "h1",
          ),
          bundleId,
          store,
          userId,
        )
        .skills[0]
        .skillId

    // Simulate step-7 approval: publish the skill.
    transaction {
      Skills.update({ Skills.id eq skillId }) {
        it[Skills.status] = "published"
        it[Skills.verified] = true
      }
    }
    val originalReadme = skillRow("resync-test")[Skills.readmeMd]

    // Resync: push new content under a new version.
    IngestPipeline.ingest(
      ArchiveSource.UploadedZip(skillZip("resync-test", "MALICIOUS", "Hacked!", "2.0.0"), "h2"),
      bundleId,
      store,
      userId,
    )

    val after = skillRow("resync-test")
    // Issue 1: the LIVE skill row is untouched — name/desc/readme NOT overwritten.
    assertEquals("Original", after[Skills.name], "live name must not change")
    assertEquals("Original desc.", after[Skills.description], "live desc must not change")
    assertEquals(originalReadme, after[Skills.readmeMd], "live readme must not change")
    assertEquals("published", after[Skills.status], "status must not change")
    assertTrue(after[Skills.verified], "verified must not change")

    // The live skill_files mirror is unchanged (new files NOT mirrored).
    val filesBefore = transaction {
      SkillFiles.selectAll().where { SkillFiles.skillId eq skillId }.toList()
    }
    val originalFileCount = filesBefore.size // from the first ingest

    // A NEW version row was appended (staged).
    val versions = transaction {
      Versions.selectAll()
        .where { Versions.skillId eq skillId }
        .orderBy(Versions.createdAt, org.jetbrains.exposed.sql.SortOrder.ASC)
        .toList()
    }
    assertEquals(2, versions.size, "expected 2 version rows (original + staged)")
    assertEquals("2.0.0", versions[1][Versions.version])

    // The live files/ mirror was NOT touched (no new skill_files rows).
    val filesAfter = transaction {
      SkillFiles.selectAll().where { SkillFiles.skillId eq skillId }.toList()
    }
    assertEquals(originalFileCount, filesAfter.size, "skill_files must not change on resync")

    // The staged version zip exists in the store.
    val stagedKey = versions[1][Versions.r2ZipKey]
    assertTrue(stagedKey != null && store.exists(stagedKey), "staged zip must exist")

    // A review job was enqueued for the new version.
    val reviewJobs = transaction { Jobs.selectAll().where { Jobs.type eq "review" }.toList() }
    assertEquals(
      1,
      reviewJobs.size,
      "B3: second ingest reuses the submission; the review job is deduplicated",
    )
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
    val versions = transaction {
      Versions.selectAll().where { Versions.skillId eq skillId }.toList()
    }
    assertEquals(1, versions.size, "same version must not duplicate")
  }

  @Test
  fun `submission reuse - open in_review reused on resync`() {
    val (bundleId, userId) = seedBundle()
    IngestPipeline.ingest(
      ArchiveSource.UploadedZip(skillZip("sub-test", "Sub", "d", "1.0.0"), "h1"),
      bundleId,
      store,
      userId,
    )
    val skillId = skillRow("sub-test")[Skills.id]
    val subsBefore = transaction {
      Submissions.selectAll().where { Submissions.skillId eq skillId }.toList()
    }
    assertEquals(1, subsBefore.size)

    // Resync: reuse the open in_review submission, don't create a second one.
    IngestPipeline.ingest(
      ArchiveSource.UploadedZip(skillZip("sub-test", "Sub v2", "d2", "2.0.0"), "h2"),
      bundleId,
      store,
      userId,
    )
    val subsAfter = transaction {
      Submissions.selectAll().where { Submissions.skillId eq skillId }.toList()
    }
    assertEquals(1, subsAfter.size, "open in_review submission must be reused, not duplicated")
  }

  @Test
  fun `promote guard runs before file store and DB writes`() {
    val (bundleId, userId) = seedBundle()
    val skillId = dev.androidskills.util.newId()
    val now = dev.androidskills.util.nowIso()
    transaction {
      Skills.insert {
        it[Skills.id] = skillId
        it[Skills.bundleId] = bundleId
        it[Skills.slug] = "guard-test"
        it[Skills.sourceDir] = "skills/guard-test"
        it[Skills.name] = "Old"
        it[Skills.description] = "Old"
        it[Skills.version] = "0.0.0"
        it[Skills.versionSource] = "manifest"
        it[Skills.status] = "unlisted"
        it[Skills.verified] = false
        it[Skills.createdAt] = now
        it[Skills.updatedAt] = now
      }
    }

    val zipBytes = skillZip("guard-test", "Guard Test", "New", "1.0.0")
    val ex =
      kotlin.test.assertFailsWith<dev.androidskills.api.ApiConflictException> {
        IngestPipeline.promote(
          skillId,
          ArchiveSource.UploadedZip(zipBytes, "hash"),
          bundleId,
          store,
          userId,
          guard = { throw dev.androidskills.api.ApiConflictException("blocked", "blocked") },
        )
      }
    assertEquals("blocked", ex.code)

    assertFalse(store.exists("skills/$skillId/versions/1.0.0.zip"))
    val row = transaction { Skills.selectAll().where { Skills.id eq skillId }.single() }
    assertEquals("Old", row[Skills.name])
    assertEquals("0.0.0", row[Skills.version])
    val files = transaction {
      SkillFiles.selectAll().where { SkillFiles.skillId eq skillId }.count()
    }
    assertEquals(0, files)
  }

  @Test
  fun `promote updates an existing skill shell`() {
    val (bundleId, userId) = seedBundle()
    val skillId = dev.androidskills.util.newId()
    val now = dev.androidskills.util.nowIso()
    transaction {
      Skills.insert {
        it[Skills.id] = skillId
        it[Skills.bundleId] = bundleId
        it[Skills.slug] = "promote-test"
        it[Skills.sourceDir] = "skills/promote-test"
        it[Skills.name] = "Old"
        it[Skills.description] = "Old"
        it[Skills.version] = "0.0.0"
        it[Skills.versionSource] = "manifest"
        it[Skills.status] = "unlisted"
        it[Skills.verified] = false
        it[Skills.createdAt] = now
        it[Skills.updatedAt] = now
      }
    }

    val zipBytes = skillZip("promote-test", "Promote Test", "New", "1.0.0")
    IngestPipeline.promote(
      skillId,
      ArchiveSource.UploadedZip(zipBytes, "hash"),
      bundleId,
      store,
      userId,
    )

    val row = transaction { Skills.selectAll().where { Skills.id eq skillId }.single() }
    assertEquals("Promote Test", row[Skills.name])
    assertEquals("New", row[Skills.description])
    assertEquals("1.0.0", row[Skills.version])
    val files = transaction {
      SkillFiles.selectAll().where { SkillFiles.skillId eq skillId }.map { it[SkillFiles.path] }
    }
    assertTrue(files.contains("SKILL.md"))
    assertTrue(files.contains("references/guide.md"))
  }

  @Test
  fun `resync reconciles a pre-v4 legacy row by leaf slug and heals source_dir`() {
    val (bundleId, userId) = seedBundle()
    // A pre-v4 row: created before source_dir existed, so it is NULL. Published, slug = leaf.
    val legacyId = dev.androidskills.util.newId()
    val now = dev.androidskills.util.nowIso()
    transaction {
      Skills.insert {
        it[Skills.id] = legacyId
        it[Skills.bundleId] = bundleId
        it[Skills.slug] = "adaptive"
        // source_dir intentionally omitted (NULL) — the legacy state.
        it[Skills.name] = "Adaptive"
        it[Skills.description] = "Old desc"
        it[Skills.license] = "MIT"
        it[Skills.version] = "1.0.0"
        it[Skills.versionSource] = "manifest"
        it[Skills.status] = "published"
        it[Skills.verified] = true
        it[Skills.createdAt] = now
        it[Skills.updatedAt] = now
      }
    }

    val result =
      IngestPipeline.ingest(
        ArchiveSource.UploadedZip(
          skillZip("adaptive", "Adaptive", "New desc.", "2.0.0"),
          "h-legacy",
        ),
        bundleId,
        store,
        userId,
      )

    // The legacy row is adopted (no duplicate) and its real path is stamped in.
    assertEquals(1, result.skills.size)
    assertEquals(legacyId, result.skills[0].skillId)
    assertFalse(result.skills[0].isNew)
    assertEquals(
      1,
      transaction { Skills.selectAll().where { Skills.bundleId eq bundleId }.count() },
    )
    assertEquals("skills/adaptive", skillRow("adaptive")[Skills.sourceDir])
  }

  @Test
  fun `promote reconciles a pre-v4 legacy row by leaf slug`() {
    val (bundleId, userId) = seedBundle()
    val skillId = dev.androidskills.util.newId()
    val now = dev.androidskills.util.nowIso()
    transaction {
      Skills.insert {
        it[Skills.id] = skillId
        it[Skills.bundleId] = bundleId
        it[Skills.slug] = "legacy-promote"
        // source_dir intentionally omitted (NULL) — the legacy state.
        it[Skills.name] = "Old"
        it[Skills.description] = "Old"
        it[Skills.version] = "0.0.0"
        it[Skills.versionSource] = "manifest"
        it[Skills.status] = "unlisted"
        it[Skills.verified] = false
        it[Skills.createdAt] = now
        it[Skills.updatedAt] = now
      }
    }

    IngestPipeline.promote(
      skillId,
      ArchiveSource.UploadedZip(
        skillZip("legacy-promote", "Legacy Promote", "New", "1.0.0"),
        "hash",
      ),
      bundleId,
      store,
      userId,
    )

    val row = transaction { Skills.selectAll().where { Skills.id eq skillId }.single() }
    assertEquals("Legacy Promote", row[Skills.name])
    assertEquals("skills/legacy-promote", row[Skills.sourceDir])
  }

  @Test
  fun `resync does not adopt a legacy row when the leaf slug is ambiguous`() {
    val (bundleId, userId) = seedBundle()
    val legacyId = dev.androidskills.util.newId()
    val now = dev.androidskills.util.nowIso()
    transaction {
      Skills.insert {
        it[Skills.id] = legacyId
        it[Skills.bundleId] = bundleId
        it[Skills.slug] = "dup"
        // source_dir omitted (NULL) — legacy state.
        it[Skills.name] = "Legacy Dup"
        it[Skills.description] = "old"
        it[Skills.version] = "1.0.0"
        it[Skills.versionSource] = "manifest"
        it[Skills.status] = "published"
        it[Skills.verified] = true
        it[Skills.createdAt] = now
        it[Skills.updatedAt] = now
      }
    }
    // Two archive dirs share the leaf "dup" — the legacy source dir is genuinely ambiguous.
    val bodyA = "---\nname: dup\ndescription: A.\nlicense: MIT\ntags: [android]\n---\n# A\n"
    val bodyB = "---\nname: dup\ndescription: B.\nlicense: MIT\ntags: [android]\n---\n# B\n"
    val zipBytes =
      zip("owner-repo-sha/skills/dup/SKILL.md" to bodyA, "owner-repo-sha/lib/dup/SKILL.md" to bodyB)
    IngestPipeline.ingest(
      ArchiveSource.RepoZipball(zipBytes, "owner", "repo", "abcdef1234567890"),
      bundleId,
      store,
      userId,
    )

    // The legacy row is left untouched (still NULL, not misattributed); both dirs become new rows.
    val legacyAfter = transaction { Skills.selectAll().where { Skills.id eq legacyId }.single() }
    assertEquals(null, legacyAfter[Skills.sourceDir])
    assertEquals(
      3,
      transaction { Skills.selectAll().where { Skills.bundleId eq bundleId }.count() },
    )
  }
}
