package dev.androidskills.api

import dev.androidskills.Database
import dev.androidskills.TestSupport
import dev.androidskills.db.AuditLog
import dev.androidskills.db.Bundles
import dev.androidskills.db.Categories
import dev.androidskills.db.SkillFiles
import dev.androidskills.db.Skills
import dev.androidskills.db.Submissions
import dev.androidskills.db.Users
import dev.androidskills.db.Versions
import dev.androidskills.github.GitHubAppClient
import dev.androidskills.github.GithubWebhookEvent
import dev.androidskills.github.Installation
import dev.androidskills.github.RepoRef
import dev.androidskills.ingest.ReviewOutputPayload
import dev.androidskills.ingest.StagedPayload
import dev.androidskills.ingest.SubmissionPayload
import dev.androidskills.llm.SecurityFinding
import dev.androidskills.storage.LocalFsStore
import dev.androidskills.util.appJson
import dev.androidskills.util.newId
import dev.androidskills.util.nowIso
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Tests for [AdminQueueQueries.decision] and the promote-based approval flow. Uses fakes: no GitHub
 * or LLM network calls.
 */
class AdminQueueDecisionTest {

  private val dir = TestSupport.tempDir()
  private val store = LocalFsStore(dir.resolve("files"))

  @BeforeTest fun setup() = Database.init(TestSupport.newConfig(dir))

  @AfterTest
  fun teardown() {
    dir.toFile().deleteRecursively()
  }

  private class FakeApp(val zipball: ByteArray) : GitHubAppClient {
    override val configured = true

    override suspend fun installations() = emptyList<Installation>()

    override suspend fun listRepos(installationId: Long) = emptyList<RepoRef>()

    override suspend fun defaultBranchHead(installationId: Long, owner: String, repo: String) =
      "main"

    override suspend fun downloadZipball(
      installationId: Long,
      owner: String,
      repo: String,
      ref: String,
    ): ByteArray = zipball

    override suspend fun verifyAndParseEvent(
      body: ByteArray,
      signature: String,
    ): GithubWebhookEvent? = null
  }

  private fun makeZipball(slug: String, version: String = "1.0.0"): ByteArray {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zos ->
      zos.putNextEntry(ZipEntry("repo-main/skills/$slug/SKILL.md"))
      zos.write(
        """
                ---
                name: $slug
                description: A test skill.
                license: Apache-2.0
                tags: [android, kotlin]
                metadata:
                  version: $version
                ---
                # $slug
                
                Usage.
                """
          .trimIndent()
          .toByteArray()
      )
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("repo-main/skills/$slug/README.md"))
      zos.write("# Readme".toByteArray())
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("repo-main/skills/$slug/src/main.kt"))
      zos.write("fun main() {}".toByteArray())
      zos.closeEntry()
    }
    return baos.toByteArray()
  }

  private data class Seed(
    val adminId: String,
    val adminHandle: String,
    val submitterId: String,
    val bundleId: String,
    val skillId: String,
    val submissionId: String,
  )

  private fun seed(
    slug: String = "test-skill",
    ref: String = "abc123",
    payload: SubmissionPayload? = null,
  ): Seed {
    val adminId = newId()
    val submitterId = newId()
    val bundleId = newId()
    val skillId = newId()
    val submissionId = newId()
    val now = nowIso()

    transaction {
      Users.insert {
        it[Users.id] = adminId
        it[Users.githubId] = 1
        it[Users.handle] = "admin"
        it[Users.role] = dev.androidskills.db.Role.admin.name
        it[Users.status] = dev.androidskills.db.UserStatus.active.name
        it[Users.createdAt] = now
        it[Users.updatedAt] = now
      }
      Users.insert {
        it[Users.id] = submitterId
        it[Users.githubId] = 2
        it[Users.handle] = "alice"
        it[Users.role] = dev.androidskills.db.Role.contributor.name
        it[Users.status] = dev.androidskills.db.UserStatus.active.name
        it[Users.createdAt] = now
        it[Users.updatedAt] = now
      }
      Bundles.insert {
        it[Bundles.id] = bundleId
        it[Bundles.kind] = "repo"
        it[Bundles.provenance] = "owner/repo"
        it[Bundles.installationId] = 42L
        it[Bundles.ownerUserId] = submitterId
        it[Bundles.createdAt] = now
      }
      Skills.insert {
        it[Skills.id] = skillId
        it[Skills.bundleId] = bundleId
        it[Skills.slug] = slug
        it[Skills.sourceDir] = "skills/$slug"
        it[Skills.name] = "Old name"
        it[Skills.description] = "Old description"
        it[Skills.version] = "0.0.0"
        it[Skills.versionSource] = "manifest"
        it[Skills.status] = "unlisted"
        it[Skills.verified] = false
        it[Skills.createdAt] = now
        it[Skills.updatedAt] = now
      }
      val staged =
        StagedPayload(
          version = "1.0.0",
          versionSource = "manifest",
          name = "Old name",
          description = "Old description",
          license = "Apache-2.0",
          tags = listOf("android", "kotlin"),
          sourceRef = StagedPayload.SourceRef("owner", "repo", ref),
        )
      Submissions.insert {
        it[Submissions.id] = submissionId
        it[Submissions.bundleId] = bundleId
        it[Submissions.skillId] = skillId
        it[Submissions.submitterId] = submitterId
        it[Submissions.state] = "in_review"
        it[Submissions.payload] =
          appJson.encodeToString(
            SubmissionPayload.serializer(),
            payload
              ?: SubmissionPayload(
                staged = staged,
                review =
                  ReviewOutputPayload(
                    category = "kotlin-language",
                    tagsProposed = listOf("android", "kotlin"),
                    securityPassed = true,
                    securityFindings = emptyList(),
                    lintScore = 85,
                  ),
              ),
          )
        it[Submissions.createdAt] = now
        it[Submissions.updatedAt] = now
      }
    }
    return Seed(adminId, "admin", submitterId, bundleId, skillId, submissionId)
  }

  private fun principal(userId: String, handle: String) =
    dev.androidskills.auth.Principal(
      sessionId = "ignored",
      userId = userId,
      githubId = 1,
      handle = handle,
      name = null,
      avatarUrl = null,
      role = dev.androidskills.db.Role.admin,
      status = dev.androidskills.db.UserStatus.active,
      createdAt = nowIso(),
    )

  @Test
  fun `approve applies the admin's edited tags over the manifest`() {
    val slug = "test-skill"
    val s = seed(slug)
    val zip = makeZipball(slug)

    runBlocking {
      AdminQueueQueries.decision(
        principal(s.adminId, s.adminHandle),
        s.submissionId,
        AdminQueueQueries.DecisionRequest(
          "approve",
          tags = listOf("Custom-A", " custom-a ", "custom-b"),
        ),
        store,
        FakeApp(zip),
      )
    }

    transaction {
      val skill = Skills.selectAll().where { Skills.id eq s.skillId }.single()
      assertEquals("published", skill[Skills.status])
      // Admin tags win over promote's manifest tags, sanitized (trim + case-insensitive dedupe).
      assertEquals(
        listOf("Custom-A", "custom-b"),
        appJson.decodeFromString<List<String>>(skill[Skills.tags]),
      )
    }
  }

  @Test
  fun `approve with a blank tags list keeps the manifest tags`() {
    val slug = "test-skill"
    val s = seed(slug)
    val zip = makeZipball(slug)

    runBlocking {
      AdminQueueQueries.decision(
        principal(s.adminId, s.adminHandle),
        s.submissionId,
        AdminQueueQueries.DecisionRequest("approve", tags = listOf("", "  ")),
        store,
        FakeApp(zip),
      )
    }

    transaction {
      val skill = Skills.selectAll().where { Skills.id eq s.skillId }.single()
      // Blank/empty tags must not wipe the tags promote wrote from the manifest.
      assertEquals(
        listOf("android", "kotlin"),
        appJson.decodeFromString<List<String>>(skill[Skills.tags]),
      )
    }
  }

  @Test
  fun `approve stores only fyi notes as the skill's public security notes`() {
    val slug = "test-skill"
    val ref = "abc123"
    val custom =
      SubmissionPayload(
        staged =
          StagedPayload(
            version = "1.0.0",
            versionSource = "manifest",
            name = "Old name",
            description = "Old description",
            license = "Apache-2.0",
            tags = listOf("android", "kotlin"),
            sourceRef = StagedPayload.SourceRef("owner", "repo", ref),
          ),
        review =
          ReviewOutputPayload(
            category = "kotlin-language",
            tagsProposed = listOf("android"),
            tagNotes = listOf("dropped 'ui' — moderator-only, must not be published"),
            securityPassed = true,
            securityFindings =
              listOf(
                SecurityFinding("fyi", "Reads files in your project."),
                SecurityFinding("flag", "excluded — flags never reach a published skill"),
              ),
            lintScore = 80,
          ),
      )
    val s = seed(slug, ref, custom)

    runBlocking {
      AdminQueueQueries.decision(
        principal(s.adminId, s.adminHandle),
        s.submissionId,
        AdminQueueQueries.DecisionRequest("approve"),
        store,
        FakeApp(makeZipball(slug)),
      )
    }

    transaction {
      val security = Skills.selectAll().where { Skills.id eq s.skillId }.single()[Skills.security]
      // Only the fyi note is carried to the public skill; the flag is dropped.
      assertEquals(
        listOf("Reads files in your project."),
        appJson.decodeFromString<List<String>>(assertNotNull(security)),
      )
    }
  }

  @Test
  fun `approve promotes files and publishes skill`() {
    val slug = "test-skill"
    val s = seed(slug)
    val zip = makeZipball(slug)

    runBlocking {
      AdminQueueQueries.decision(
        principal(s.adminId, s.adminHandle),
        s.submissionId,
        AdminQueueQueries.DecisionRequest("approve"),
        store,
        FakeApp(zip),
      )
    }

    transaction {
      val skill = Skills.selectAll().where { Skills.id eq s.skillId }.single()
      assertEquals("published", skill[Skills.status])
      assertEquals(true, skill[Skills.verified])
      assertEquals(slug, skill[Skills.name])
      assertEquals("A test skill.", skill[Skills.description])
      assertEquals("1.0.0", skill[Skills.version])
      assertEquals("manifest", skill[Skills.versionSource])

      val category = skill[Skills.categoryId]
      assertNotNull(category)
      val categorySlug =
        Categories.selectAll().where { Categories.id eq category }.single()[Categories.slug]
      assertEquals("kotlin-language", categorySlug)

      val submission = Submissions.selectAll().where { Submissions.id eq s.submissionId }.single()
      assertEquals("published", submission[Submissions.state])

      val files =
        SkillFiles.selectAll().where { SkillFiles.skillId eq s.skillId }.map { it[SkillFiles.path] }
      assertEquals(3, files.size)
      assertTrue(files.contains("SKILL.md"))
      assertTrue(files.contains("README.md"))
      assertTrue(files.contains("src/main.kt"))

      val versions =
        Versions.selectAll().where { Versions.skillId eq s.skillId }.map { it[Versions.version] }
      assertEquals(listOf("1.0.0"), versions)

      val audit =
        AuditLog.selectAll().where { AuditLog.target eq "submission:${s.submissionId}" }.single()
      assertEquals("submission.approve", audit[AuditLog.action])
      assertEquals(s.adminId, audit[AuditLog.actorId])

      // Regression guards: readme persisted and the stored version zip is complete.
      assertTrue(
        skill[Skills.readmeMd]?.contains("# $slug") == true,
        "readme_md should be populated",
      )
      val versionRow = Versions.selectAll().where { Versions.skillId eq s.skillId }.single()
      val zipKey = versionRow[Versions.r2ZipKey]!!
      val zipBytes = store.get(zipKey)
      assertNotNull(zipBytes, "version zip should be stored")
      val entries = readZipEntries(zipBytes)
      assertTrue(entries.contains("SKILL.md"))
      assertTrue(entries.contains("README.md"))
      assertTrue(entries.contains("src/main.kt"))
    }
  }

  private fun readZipEntries(bytes: ByteArray): Set<String> {
    val names = mutableSetOf<String>()
    java.util.zip.ZipInputStream(bytes.inputStream()).use { zis ->
      var entry = zis.nextEntry
      while (entry != null) {
        names += entry.name
        entry = zis.nextEntry
      }
    }
    return names
  }

  @Test
  fun `re-promotion with a new version stores both versions and updates live version`() {
    val slug = "test-skill"
    val s = seed(slug)

    // Approve v1.0.0
    runBlocking {
      AdminQueueQueries.decision(
        principal(s.adminId, s.adminHandle),
        s.submissionId,
        AdminQueueQueries.DecisionRequest("approve"),
        store,
        FakeApp(makeZipball(slug)),
      )
    }

    // Update staged payload to v2.0.0 and approve again with a manifest that also says v2.0.0.
    val stagedV2 =
      StagedPayload(
        version = "2.0.0",
        versionSource = "manifest",
        name = "test-skill",
        description = "A test skill v2.",
        license = "Apache-2.0",
        tags = listOf("android"),
        sourceRef = StagedPayload.SourceRef("owner", "repo", "def456"),
      )
    transaction {
      Submissions.update({ Submissions.id eq s.submissionId }) {
        it[Submissions.state] = "in_review"
        it[Submissions.payload] =
          appJson.encodeToString(
            SubmissionPayload.serializer(),
            SubmissionPayload(
              staged = stagedV2,
              review =
                ReviewOutputPayload(
                  category = "kotlin-language",
                  tagsProposed = listOf("android"),
                  securityPassed = true,
                  securityFindings = emptyList(),
                  lintScore = 90,
                ),
            ),
          )
        it[Submissions.updatedAt] = nowIso()
      }
    }

    runBlocking {
      AdminQueueQueries.decision(
        principal(s.adminId, s.adminHandle),
        s.submissionId,
        AdminQueueQueries.DecisionRequest("approve"),
        store,
        FakeApp(makeZipball(slug, version = "2.0.0")),
      )
    }

    transaction {
      val skill = Skills.selectAll().where { Skills.id eq s.skillId }.single()
      assertEquals("2.0.0", skill[Skills.version])
      // Description stays tied to the manifest body, which our fixture keeps as "A test skill."
      assertEquals("A test skill.", skill[Skills.description])

      val versions =
        Versions.selectAll()
          .where { Versions.skillId eq s.skillId }
          .orderBy(Versions.version)
          .map { it[Versions.version] to it[Versions.r2ZipKey] }
          .toMap()
      assertEquals(setOf("1.0.0", "2.0.0"), versions.keys)
      assertTrue(versions["1.0.0"]!!.endsWith("/1.0.0.zip"))
      assertTrue(versions["2.0.0"]!!.endsWith("/2.0.0.zip"))
    }
  }

  @Test
  fun `approve replaces existing skill_files`() {
    val slug = "test-skill"
    val s = seed(slug)

    transaction {
      SkillFiles.insert {
        it[SkillFiles.id] = newId()
        it[SkillFiles.skillId] = s.skillId
        it[SkillFiles.path] = "stale.txt"
        it[SkillFiles.r2Key] = "old"
        it[SkillFiles.size] = 1
        it[SkillFiles.isBinary] = false
      }
    }

    val zip = makeZipball(slug)
    runBlocking {
      AdminQueueQueries.decision(
        principal(s.adminId, s.adminHandle),
        s.submissionId,
        AdminQueueQueries.DecisionRequest("approve"),
        store,
        FakeApp(zip),
      )
    }

    transaction {
      val files =
        SkillFiles.selectAll()
          .where { SkillFiles.skillId eq s.skillId }
          .map { it[SkillFiles.path] }
          .toSet()
      assertTrue(!files.contains("stale.txt"), "stale file should be replaced")
      assertTrue(files.contains("SKILL.md"))
    }
  }

  @Test
  fun `approve fails with sourcedir_mismatch if contributor moved or renamed the skill dir`() {
    val s = seed("test-skill")
    // The zip contains skills/wrong-slug, not the expected skills/test-skill dir.
    val zip = makeZipball("wrong-slug")

    val ex =
      assertFailsWith<ApiConflictException> {
        runBlocking {
          AdminQueueQueries.decision(
            principal(s.adminId, s.adminHandle),
            s.submissionId,
            AdminQueueQueries.DecisionRequest("approve"),
            store,
            FakeApp(zip),
          )
        }
      }
    assertEquals("sourcedir_mismatch", ex.code)
  }

  @Test
  fun `request_changes updates submission state and writes audit`() {
    val slug = "test-skill"
    val s = seed(slug)

    runBlocking {
      AdminQueueQueries.decision(
        principal(s.adminId, s.adminHandle),
        s.submissionId,
        AdminQueueQueries.DecisionRequest("request_changes", "needs docs"),
        store,
        FakeApp(ByteArray(0)),
      )
    }

    transaction {
      val submission = Submissions.selectAll().where { Submissions.id eq s.submissionId }.single()
      assertEquals("changes_requested", submission[Submissions.state])
      assertEquals("needs docs", submission[Submissions.note])

      val audit =
        AuditLog.selectAll().where { AuditLog.target eq "submission:${s.submissionId}" }.single()
      assertEquals("submission.request_changes", audit[AuditLog.action])
    }
  }

  @Test
  fun `reject updates submission state and writes audit`() {
    val slug = "test-skill"
    val s = seed(slug)

    runBlocking {
      AdminQueueQueries.decision(
        principal(s.adminId, s.adminHandle),
        s.submissionId,
        AdminQueueQueries.DecisionRequest("reject"),
        store,
        FakeApp(ByteArray(0)),
      )
    }

    transaction {
      val submission = Submissions.selectAll().where { Submissions.id eq s.submissionId }.single()
      assertEquals("rejected", submission[Submissions.state])

      val audit =
        AuditLog.selectAll().where { AuditLog.target eq "submission:${s.submissionId}" }.single()
      assertEquals("submission.reject", audit[AuditLog.action])
    }
  }

  @Test
  fun `approve rejects if source ref moved since review`() {
    val slug = "test-skill"
    val s = seed(slug, ref = "abc123")

    // Simulate the review pinning abc123, then a later push moving staged ref to def456.
    transaction {
      val row = Submissions.selectAll().where { Submissions.id eq s.submissionId }.single()
      val p = appJson.decodeFromString(SubmissionPayload.serializer(), row[Submissions.payload]!!)!!
      val moved =
        p.copy(
          reviewedSourceRef = StagedPayload.SourceRef("owner", "repo", "abc123"),
          staged = p.staged!!.copy(sourceRef = StagedPayload.SourceRef("owner", "repo", "def456")),
        )
      Submissions.update({ Submissions.id eq s.submissionId }) {
        it[Submissions.payload] = appJson.encodeToString(SubmissionPayload.serializer(), moved)
        it[Submissions.updatedAt] = nowIso()
      }
    }

    val ex =
      assertFailsWith<ApiConflictException> {
        runBlocking {
          AdminQueueQueries.decision(
            principal(s.adminId, s.adminHandle),
            s.submissionId,
            AdminQueueQueries.DecisionRequest("approve"),
            store,
            FakeApp(makeZipball(slug)),
          )
        }
      }
    assertEquals("review_sha_moved", ex.code)

    transaction {
      val skill = Skills.selectAll().where { Skills.id eq s.skillId }.single()
      assertEquals("unlisted", skill[Skills.status])
      assertEquals(false, skill[Skills.verified])
    }
  }

  @Test
  fun `approve preserves existing category when review category is unresolvable`() {
    val slug = "test-skill"
    val catId = transaction {
      Categories.selectAll().where { Categories.slug eq "kotlin-language" }.single()[Categories.id]
    }
    val s = seed(slug)
    transaction {
      Skills.update({ Skills.id eq s.skillId }) {
        it[Skills.categoryId] = catId
        it[Skills.updatedAt] = nowIso()
      }
    }

    // review category is unknown → should not wipe the existing category.
    transaction {
      val row = Submissions.selectAll().where { Submissions.id eq s.submissionId }.single()
      val p = appJson.decodeFromString(SubmissionPayload.serializer(), row[Submissions.payload]!!)!!
      val noCat = p.copy(review = p.review!!.copy(category = "unknown-category"))
      Submissions.update({ Submissions.id eq s.submissionId }) {
        it[Submissions.payload] = appJson.encodeToString(SubmissionPayload.serializer(), noCat)
        it[Submissions.updatedAt] = nowIso()
      }
    }

    runBlocking {
      AdminQueueQueries.decision(
        principal(s.adminId, s.adminHandle),
        s.submissionId,
        AdminQueueQueries.DecisionRequest("approve"),
        store,
        FakeApp(makeZipball(slug)),
      )
    }

    transaction {
      val skill = Skills.selectAll().where { Skills.id eq s.skillId }.single()
      assertEquals(catId, skill[Skills.categoryId])
    }
  }
}
