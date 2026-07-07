package dev.androidskills.api

import dev.androidskills.Database
import dev.androidskills.TestSupport
import dev.androidskills.db.Bundles
import dev.androidskills.db.Skills
import dev.androidskills.db.Submissions
import dev.androidskills.db.Users
import dev.androidskills.ingest.ReviewOutputPayload
import dev.androidskills.ingest.StagedPayload
import dev.androidskills.ingest.SubmissionPayload
import dev.androidskills.util.appJson
import dev.androidskills.util.nowIso
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class AdminQueueQueriesTest {

  private val dir = TestSupport.tempDir()

  @AfterTest
  fun teardown() {
    dir.toFile().deleteRecursively()
  }

  private fun setupDb() {
    Database.init(TestSupport.newConfig(dir))
  }

  private fun insertInReviewSubmission(slug: String = "queued-skill"): String {
    val now = nowIso()
    val uid = dev.androidskills.util.newId()
    val bid = dev.androidskills.util.newId()
    val sid = dev.androidskills.util.newId()
    val subId = dev.androidskills.util.newId()
    transaction {
      Users.insert {
        it[Users.id] = uid
        it[Users.githubId] = (System.currentTimeMillis() + slug.hashCode())
        it[Users.handle] = "submitter-$slug"
        it[Users.createdAt] = now
        it[Users.updatedAt] = now
      }
      Bundles.insert {
        it[Bundles.id] = bid
        it[Bundles.kind] = "repo"
        it[Bundles.provenance] = "owner/$slug"
        it[Bundles.ownerUserId] = uid
        it[Bundles.installationId] = 1L
        it[Bundles.createdAt] = now
      }
      Skills.insert {
        it[Skills.id] = sid
        it[Skills.bundleId] = bid
        it[Skills.slug] = slug
        it[Skills.name] = "Queued Skill"
        it[Skills.description] = "Desc"
        it[Skills.license] = "MIT"
        it[Skills.tags] = "[]"
        it[Skills.version] = "1.0.0"
        it[Skills.versionSource] = "manifest"
        it[Skills.categoryId] = null
        it[Skills.status] = "unlisted"
        it[Skills.verified] = false
        it[Skills.createdAt] = now
        it[Skills.updatedAt] = now
      }
      val payload =
        SubmissionPayload(
          staged =
            StagedPayload(
              version = "1.0.0",
              versionSource = "manifest",
              name = "Queued Skill",
              description = "Desc",
              license = "MIT",
              tags = listOf("android"),
              sourceRef = StagedPayload.SourceRef("owner", "repo", "abc123"),
            ),
          review =
            ReviewOutputPayload(
              category = "ui",
              tagsValidated = listOf("android"),
              securityPassed = true,
              securityFindings = emptyList(),
              lintScore = 85,
            ),
        )
      Submissions.insert {
        it[Submissions.id] = subId
        it[Submissions.bundleId] = bid
        it[Submissions.skillId] = sid
        it[Submissions.submitterId] = uid
        it[Submissions.state] = "in_review"
        it[Submissions.lintScore] = 85
        it[Submissions.payload] = appJson.encodeToString(SubmissionPayload.serializer(), payload)
        it[Submissions.createdAt] = now
        it[Submissions.updatedAt] = now
      }
    }
    return subId
  }

  @Test
  fun `queue lists in_review submissions`() {
    setupDb()
    insertInReviewSubmission("skill-one")
    insertInReviewSubmission("skill-two")
    val items = AdminQueueQueries.queue()
    assertEquals(2, items.size)
    val slugs = items.map { it.slug }.toSet()
    assertTrue("skill-one" in slugs)
    assertTrue("skill-two" in slugs)
    val item = items.first { it.slug == "skill-one" }
    assertEquals(85, item.lintScore)
    assertEquals("submitter-skill-one", item.submitterHandle)
    assertEquals("ui", item.review?.category)
    assertEquals(
      "owner/repo@abc123",
      item.staged?.sourceRef?.let { "${it.repoOwner}/${it.repoName}@${it.ref}" },
    )
  }

  @Test
  fun `queue filters by state`() {
    setupDb()
    val subId = insertInReviewSubmission("in-review")
    transaction {
      Submissions.update({ Submissions.id eq subId }) {
        it[Submissions.state] = "changes_requested"
      }
    }
    assertEquals(1, AdminQueueQueries.queue("changes_requested").size)
    assertEquals(0, AdminQueueQueries.queue("in_review").size)
  }

  @Test
  fun `queue searches by slug or handle`() {
    setupDb()
    insertInReviewSubmission("alpha-skill")
    insertInReviewSubmission("beta-skill")
    assertEquals(2, AdminQueueQueries.queue(q = "skill").size)
    assertEquals(1, AdminQueueQueries.queue(q = "alpha").size)
    assertEquals(2, AdminQueueQueries.queue(q = "submitter-").size)
    assertEquals(0, AdminQueueQueries.queue(q = "gamma").size)
  }

  @Test
  fun `detail returns submission and source ref`() {
    setupDb()
    val slug = "skill-one"
    val subId = insertInReviewSubmission(slug)
    val detail = AdminQueueQueries.detail(subId)
    assertNotNull(detail)
    assertEquals("skill-one", detail!!.slug)
    assertEquals("in_review", detail.state)
    assertEquals("owner/repo@abc123", detail.sourceRef)
    assertEquals("owner/$slug", detail.provenance)
    assertTrue(detail.reviewIsMetadataOnly)
    assertEquals(85, detail.lintScore)
  }

  @Test
  fun `detail returns null for missing id`() {
    setupDb()
    assertEquals(null, AdminQueueQueries.detail("no-such-id"))
  }
}
