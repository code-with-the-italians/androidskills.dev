package dev.androidskills.api

import dev.androidskills.Database
import dev.androidskills.TestSupport
import dev.androidskills.db.Skills
import dev.androidskills.storage.LocalFsStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Exercises the public read surface (spec §9 Public) directly over seeded demo data — the
 * build-order step 2 "over seed data" milestone.
 */
class PublicReadTest {

  private val dir = TestSupport.tempDir()
  private val store = LocalFsStore(dir.resolve("files"))

  @BeforeTest
  fun setup() {
    Database.init(TestSupport.newConfig(dir))
    DemoData.seed(store)
  }

  @AfterTest
  fun teardown() {
    dir.toFile().deleteRecursively()
  }

  private fun all() =
    SearchParams(
      q = null,
      cats = emptyList(),
      tags = emptyList(),
      size = null,
      verified = false,
      sort = "relevance",
      page = 1,
      pageSize = 60,
    )

  @Test
  fun `stats reflect seeded data`() {
    val stats = PublicQueries.stats()
    assertEquals(6, stats.indexed) // all demo skills are published
    assertTrue(stats.contributors >= 1)
    assertNotNull(stats.lastUpdated)
  }

  @Test
  fun `stats lastUpdated ignores non-public skills`() {
    // lastUpdated must reflect only published skills, like indexed/contributors,
    // so a freshly-touched unlisted/flagged skill can't move the public stat.
    val before = PublicQueries.stats().lastUpdated
    // Touch a published skill with a future timestamp, then unlist it.
    transaction {
      Skills.update({ Skills.slug eq "retrofit-okhttp-config" }) {
        it[Skills.updatedAt] = "2099-12-31T23:59:59Z"
      }
    }
    // While still published, the future timestamp wins.
    assertEquals("2099-12-31T23:59:59Z", PublicQueries.stats().lastUpdated)
    // Now unlist it — it must no longer surface in lastUpdated.
    transaction {
      Skills.update({ Skills.slug eq "retrofit-okhttp-config" }) {
        it[Skills.status] = "unlisted"
        it[Skills.updatedAt] = "2100-12-31T23:59:59Z"
      }
    }
    val after = PublicQueries.stats().lastUpdated
    assertTrue(
      before == after || (after != null && after < "2100-12-31T23:59:59Z"),
      "unlisted skill must not set lastUpdated; got $after (before=$before)",
    )
  }

  @Test
  fun `categories list counts published skills`() {
    val cats = PublicQueries.categories()
    val total = cats.sumOf { it.count }
    assertEquals(6, total)
    assertTrue(cats.any { it.slug == "jetpack-compose" && it.count == 1 })
    assertTrue(cats.any { it.slug == "uncategorized" })
  }

  @Test
  fun `search default verified-only excludes unverified`() {
    val verifiedOnly = PublicQueries.search(all().copy(verified = true))
    assertTrue(verifiedOnly.items.all { it.verified })
    assertFalse(
      verifiedOnly.items.any { it.slug == "retrofit-okhttp-config" }
    ) // unverified seed skill

    val everything = PublicQueries.search(all().copy(verified = false))
    assertTrue(everything.items.any { it.slug == "retrofit-okhttp-config" })
  }

  @Test
  fun `search facets ignore their own dimension`() {
    val res = PublicQueries.search(all().copy(cats = listOf("jetpack-compose")))
    assertEquals(1, res.total) // only the compose skill
    // category facet must still show OTHER categories (own-dimension excluded)
    assertTrue(res.facets.categories.any { it.key != "jetpack-compose" })
  }

  @Test
  fun `search by query matches name`() {
    val res = PublicQueries.search(all().copy(q = "coroutine"))
    assertEquals(1, res.total)
    assertEquals("coroutine-test-patterns", res.items.first().slug)
  }

  @Test
  fun `search size filter buckets by total tokens`() {
    val small = PublicQueries.search(all().copy(size = "<2k"))
    assertTrue(small.items.isNotEmpty())
    assertTrue(small.items.none { it.tokenBand == "100k" })
  }

  @Test
  fun `search sort by installs orders descending`() {
    val res = PublicQueries.search(all().copy(sort = "installs"))
    val installs = res.items.map { it.installs }
    assertEquals(installs, installs.sortedDescending())
  }

  @Test
  fun `detail returns manifest fields and 404 for unknown`() {
    val d = PublicQueries.skillDetail("jetpack-compose-mvi")
    assertEquals("Jetpack Compose MVI Scaffold", d.name)
    assertEquals("Apache-2.0", d.license)
    assertEquals("manifest", d.versionSource)
    assertTrue(d.tags.contains("compose"))
    assertTrue(d.fileCount > 0)
    assertFailsWith<ApiNotFoundException> { PublicQueries.skillDetail("does-not-exist") }
  }

  @Test
  fun `file tree and content work for a known file`() {
    val tree = PublicQueries.fileTree("jetpack-compose-mvi")
    assertTrue(tree.files.any { it.path == "references/intent.md" })
    assertTrue(tree.tree.isNotEmpty())

    val content = PublicQueries.fileContent("jetpack-compose-mvi", "references/intent.md", store)
    assertFalse(content.downloadOnly)
    assertTrue(content.content?.contains("Intents") == true)
  }

  @Test
  fun `file content marks oversized as download-only`() {
    // Plant an oversized text file beyond PREVIEW_LIMIT.
    val slug = "jetpack-compose-mvi"
    val skillId = transaction {
      Skills.selectAll().where { Skills.slug eq slug }.single()[Skills.id]
    }
    val big = "x".repeat(PublicQueries.PREVIEW_LIMIT + 10)
    val key = "skills/$skillId/files/references/big.md"
    store.put(key, big.toByteArray())
    transaction {
      dev.androidskills.db.SkillFiles.insert {
        it[dev.androidskills.db.SkillFiles.id] = dev.androidskills.util.newId()
        it[dev.androidskills.db.SkillFiles.skillId] = skillId
        it[dev.androidskills.db.SkillFiles.path] = "references/big.md"
        it[dev.androidskills.db.SkillFiles.size] = big.length
        it[dev.androidskills.db.SkillFiles.r2Key] = key
      }
    }
    val res = PublicQueries.fileContent(slug, "references/big.md", store)
    assertTrue(res.downloadOnly)
    assertEquals(null, res.content)
  }

  @Test
  fun `versions list current flag and download increments installs`() {
    val v = PublicQueries.versions("jetpack-compose-mvi")
    assertTrue(v.versions.any { it.current })
    val before = transaction {
      Skills.selectAll().where { Skills.slug eq "jetpack-compose-mvi" }.single()[Skills.installs]
    }
    val dl = PublicQueries.download("jetpack-compose-mvi", null, store)
    assertTrue(dl.bytes.size > 4) // a real zip
    assertEquals("application/zip", dl.contentType)
    val after = transaction {
      Skills.selectAll().where { Skills.slug eq "jetpack-compose-mvi" }.single()[Skills.installs]
    }
    assertEquals(before + 1, after)
  }

  @Test
  fun `repeated downloads each increment installs`() {
    // Each successful download must add exactly 1. Uses the atomic SQL increment
    // (installs = installs + 1); the prior captured-value form could lose counts
    // across overlapping transactions. Two sequential downloads → +2.
    val slug = "coroutine-test-patterns"
    val before = transaction {
      Skills.selectAll().where { Skills.slug eq slug }.single()[Skills.installs]
    }
    PublicQueries.download(slug, null, store)
    PublicQueries.download(slug, null, store)
    val after = transaction {
      Skills.selectAll().where { Skills.slug eq slug }.single()[Skills.installs]
    }
    assertEquals(before + 2, after, "each download must add exactly one install")
  }

  @Test
  fun `bundles list and detail`() {
    val list = PublicQueries.bundles(1, 60)
    assertEquals(2, list.total)
    val first = list.items.first()
    val detail = PublicQueries.bundle(first.id)
    assertTrue(detail.skills.isNotEmpty())
    assertFailsWith<ApiNotFoundException> { PublicQueries.bundle("nope") }
  }

  @Test
  fun `author profile aggregates their skills`() {
    val alice = PublicQueries.author("alice")
    assertTrue(alice.skillCount >= 1)
    assertTrue(alice.totalInstalls >= 0)
    assertFailsWith<ApiNotFoundException> { PublicQueries.author("nobody") }
  }

  @Test
  fun `author with no public skills is hidden`() {
    // The public author directory exposes only users with >=1 published skill.
    // bob owns the seed's zip bundle (retrofit-okhttp-config + baseline-profile-gradle,
    // both published). Unlist every skill in his bundle and bob — a registered
    // user but no longer a public author — must 404, matching how the bundle
    // index hides zero-public bundles. alice keeps her published skills.
    val bobBundleId = transaction {
      val bob =
        dev.androidskills.db.Users.selectAll()
          .where { dev.androidskills.db.Users.handle eq "bob" }
          .single()[dev.androidskills.db.Users.id]
      dev.androidskills.db.Bundles.selectAll()
        .where { dev.androidskills.db.Bundles.ownerUserId eq bob }
        .single()[dev.androidskills.db.Bundles.id]
    }
    transaction {
      Skills.update({ Skills.bundleId eq bobBundleId }) { it[Skills.status] = "unlisted" }
    }
    assertFailsWith<ApiNotFoundException> { PublicQueries.author("bob") }
    assertTrue(PublicQueries.author("alice").skillCount >= 1)
  }

  @Test
  fun `timeline and trends return shaped data`() {
    val tl = PublicQueries.timeline(1, 60)
    assertTrue(tl.items.isNotEmpty())
    assertTrue(tl.items.any { it.type == "publish" })
    val tr = PublicQueries.trends()
    assertTrue(tr.tokenMix.size == 4)
    assertTrue(tr.tokenMix.sumOf { it.count } == 6)
  }

  @Test
  fun `report requires a reason and is accepted when provided`() {
    assertFailsWith<ApiValidationException> {
      PublicQueries.createReport("jetpack-compose-mvi", reason = "   ", reporterId = null)
    }
    val ok = PublicQueries.createReport("jetpack-compose-mvi", reason = "spam", reporterId = null)
    assertTrue(ok.accepted)
  }

  @Test
  fun `unlisted skills are not publicly reachable`() {
    transaction {
      Skills.update({ Skills.slug eq "room-migration-helper" }) { it[Skills.status] = "unlisted" }
    }
    assertFailsWith<ApiNotFoundException> { PublicQueries.skillDetail("room-migration-helper") }
    // and absent from search
    val res = PublicQueries.search(all())
    assertFalse(res.items.any { it.slug == "room-migration-helper" })
  }

  @Test
  fun `historical version with missing archive is 404 not wrong-files`() {
    // Plant a historical version whose archive object is absent.
    val slug = "jetpack-compose-mvi"
    val skillId = transaction {
      Skills.selectAll().where { Skills.slug eq slug }.single()[Skills.id]
    }
    transaction {
      dev.androidskills.db.Versions.insert {
        it[dev.androidskills.db.Versions.id] = dev.androidskills.util.newId()
        it[dev.androidskills.db.Versions.skillId] = skillId
        it[dev.androidskills.db.Versions.version] = "0.1.0"
        it[dev.androidskills.db.Versions.sourceRef] = "manifest:0.1.0"
        it[dev.androidskills.db.Versions.r2ZipKey] =
          "skills/$skillId/versions/0.1.0.zip" // not in store
        it[dev.androidskills.db.Versions.createdAt] =
          "2020-01-01T00:00:00Z" // older than the demo's 1.4.2
      }
    }
    // Requesting the historical version must NOT fall back to current files.
    assertFailsWith<ApiNotFoundException> { PublicQueries.download(slug, "0.1.0", store) }
    // The current version still downloads fine (built on demand / from stored zip).
    val cur = PublicQueries.download(slug, null, store)
    assertTrue(cur.bytes.isNotEmpty())
  }

  @Test
  fun `default download selects skills version not newest created_at`() {
    // The public contract: no ?version= downloads the CURRENT version
    // (skills.version), not "whichever versions row was inserted last".
    val slug = "jetpack-compose-mvi"
    val skillId = transaction {
      Skills.selectAll().where { Skills.slug eq slug }.single()[Skills.id]
    }
    // Backfill a historical row dated in the FUTURE — newer than the current
    // 1.4.2 row, with no stored archive. Selecting by newest created_at (the
    // old behaviour) would pick this and 404; the contract must still hand
    // back the current 1.4.2 archive.
    transaction {
      dev.androidskills.db.Versions.insert {
        it[dev.androidskills.db.Versions.id] = dev.androidskills.util.newId()
        it[dev.androidskills.db.Versions.skillId] = skillId
        it[dev.androidskills.db.Versions.version] = "0.1.0"
        it[dev.androidskills.db.Versions.sourceRef] = "manifest:0.1.0"
        it[dev.androidskills.db.Versions.r2ZipKey] =
          "skills/$skillId/versions/0.1.0.zip" // not in store
        it[dev.androidskills.db.Versions.createdAt] = "2099-01-01T00:00:00Z" // NEWER than current
      }
    }
    val dl = PublicQueries.download(slug, null, store)
    assertTrue(dl.filename.contains("1.4.2"), "expected current 1.4.2, got ${dl.filename}")
    assertTrue(dl.bytes.isNotEmpty())
  }

  @Test
  fun `fallback zip fails loudly on an unsafe stored path`() {
    // When the current version has no stored archive, download() builds one
    // from skill_files. An unsafe path (Zip Slip) must fail loudly, not be
    // skipped to produce a truncated 200 zip — and the failed download must
    // NOT bump installs (buildZip runs before the install counter).
    val slug = "jetpack-compose-mvi"
    val row = transaction { Skills.selectAll().where { Skills.slug eq slug }.single() }
    val skillId = row[Skills.id]
    val before = installsOf(slug)
    forceCurrentVersionFallback(skillId, row[Skills.version])

    val badKey = "skills/$skillId/files/escape.md"
    store.put(badKey, "evil".toByteArray())
    transaction {
      dev.androidskills.db.SkillFiles.insert {
        it[dev.androidskills.db.SkillFiles.id] = dev.androidskills.util.newId()
        it[dev.androidskills.db.SkillFiles.skillId] = skillId
        it[dev.androidskills.db.SkillFiles.path] = "../escape.md"
        it[dev.androidskills.db.SkillFiles.size] = 4
        it[dev.androidskills.db.SkillFiles.isBinary] = false
        it[dev.androidskills.db.SkillFiles.r2Key] = badKey
      }
    }
    assertFailsWith<ApiStorageException> { PublicQueries.download(slug, null, store) }
    assertEquals(before, installsOf(slug), "installs must not change on a failed download")
  }

  @Test
  fun `fallback zip fails loudly on missing file bytes`() {
    // A DB-referenced file whose bytes are gone is storage corruption — the
    // build must fail loudly, not silently omit the entry from the archive,
    // and the failed download must not bump installs.
    val slug = "jetpack-compose-mvi"
    val row = transaction { Skills.selectAll().where { Skills.slug eq slug }.single() }
    val skillId = row[Skills.id]
    val before = installsOf(slug)
    forceCurrentVersionFallback(skillId, row[Skills.version])

    transaction {
      dev.androidskills.db.SkillFiles.insert {
        it[dev.androidskills.db.SkillFiles.id] = dev.androidskills.util.newId()
        it[dev.androidskills.db.SkillFiles.skillId] = skillId
        it[dev.androidskills.db.SkillFiles.path] = "references/ghost.md"
        it[dev.androidskills.db.SkillFiles.size] = 10
        it[dev.androidskills.db.SkillFiles.isBinary] = false
        // r2_key points at an object that was never written to the store.
        it[dev.androidskills.db.SkillFiles.r2Key] = "skills/$skillId/files/references/ghost.md"
      }
    }
    assertFailsWith<ApiStorageException> { PublicQueries.download(slug, null, store) }
    assertEquals(before, installsOf(slug), "installs must not change on a failed download")
  }

  @Test
  fun `fallback zip fails loudly on an empty file set`() {
    // A skill with no recorded files can't form an archive: buildZip must throw
    // rather than return an empty 200 zip, and installs must not change.
    val slug = "jetpack-compose-mvi"
    val row = transaction { Skills.selectAll().where { Skills.slug eq slug }.single() }
    val skillId = row[Skills.id]
    val before = installsOf(slug)
    forceCurrentVersionFallback(skillId, row[Skills.version])
    // Wipe every recorded file for the skill (skillId is our own seeded UUID).
    transaction { exec("DELETE FROM skill_files WHERE skill_id = '$skillId'") }
    assertFailsWith<ApiStorageException> { PublicQueries.download(slug, null, store) }
    assertEquals(before, installsOf(slug), "installs must not change on a failed download")
  }

  private fun installsOf(slug: String): Int = transaction {
    Skills.selectAll().where { Skills.slug eq slug }.single()[Skills.installs]
  }

  /** Nulls the current version's r2_zip_key so download() falls back to buildZip. */
  private fun forceCurrentVersionFallback(skillId: String, currentVersion: String) = transaction {
    dev.androidskills.db.Versions.update({
      (dev.androidskills.db.Versions.skillId eq skillId) and
        (dev.androidskills.db.Versions.version eq currentVersion)
    }) {
      it[dev.androidskills.db.Versions.r2ZipKey] = null
    }
  }

  @Test
  fun `fallback zip includes the SKILL manifest so the bundle is complete`() {
    // Cursor review: buildZip streamed only skill_files, omitting the manifest.
    // A downloadable bundle must contain SKILL.md (§5/§11). Force the fallback
    // path and assert the zip has a SKILL.md entry carrying the readme body.
    val slug = "jetpack-compose-mvi"
    val row = transaction { Skills.selectAll().where { Skills.slug eq slug }.single() }
    forceCurrentVersionFallback(row[Skills.id], row[Skills.version])

    val dl = PublicQueries.download(slug, null, store)
    val entries =
      java.util.zip.ZipInputStream(dl.bytes.inputStream()).use { zis ->
        generateSequence { zis.nextEntry }.map { it.name }.toList()
      }
    assertTrue("SKILL.md" in entries, "zip missing SKILL.md; entries=$entries")
    assertTrue(entries.containsAll(listOf("references/intent.md", "examples/CounterScreen.kt")))
  }

  @Test
  fun `parsePageStrict rejects deep pagination beyond the ceiling`() {
    assertEquals(1, PublicQueries.parsePageStrict(null))
    assertEquals(1, PublicQueries.parsePageStrict("1"))
    assertEquals(10_000, PublicQueries.parsePageStrict("10000"))
    assertFailsWith<ApiValidationException> { PublicQueries.parsePageStrict("10001") }
    assertFailsWith<ApiValidationException> { PublicQueries.parsePageStrict("2147483647") }
    assertFailsWith<ApiValidationException> { PublicQueries.parsePageStrict("0") }
    assertFailsWith<ApiValidationException> { PublicQueries.parsePageStrict("abc") }
  }

  @Test
  fun `missing file bytes surface as a storage error not empty 200`() {
    val slug = "jetpack-compose-mvi"
    val key = transaction {
      val row =
        dev.androidskills.db.SkillFiles.selectAll()
          .where { dev.androidskills.db.SkillFiles.path eq "references/intent.md" }
          .single()
      row[dev.androidskills.db.SkillFiles.r2Key]
    }
    // Corrupt storage: delete the object the DB references.
    val path = dir.resolve("files").resolve(key)
    java.nio.file.Files.deleteIfExists(path)
    assertFailsWith<ApiStorageException> {
      PublicQueries.fileContent(slug, "references/intent.md", store)
    }
  }

  @Test
  fun `bundles only expose published skills`() {
    // Unlist one of bob's skills (zip bundle has retrofit + baseline-profile).
    transaction {
      Skills.update({ Skills.slug eq "retrofit-okhttp-config" }) { it[Skills.status] = "unlisted" }
    }
    val list = PublicQueries.bundles(1, 60)
    list.items.forEach { assertTrue(it.skillCount >= 1) } // no zero-public bundles leaked

    // Find bob's zip bundle and confirm detail hides the unlisted skill.
    val bobBundle = list.items.first { it.owner.handle == "bob" }
    val detail = PublicQueries.bundle(bobBundle.id)
    assertFalse(detail.skills.any { it.slug == "retrofit-okhttp-config" })

    // A bundle whose only skills are non-public is hidden (404).
    transaction {
      Skills.update({ Skills.bundleId eq bobBundle.id }) { it[Skills.status] = "unlisted" }
    }
    assertFailsWith<ApiNotFoundException> { PublicQueries.bundle(bobBundle.id) }
    val list2 = PublicQueries.bundles(1, 60)
    assertFalse(list2.items.any { it.id == bobBundle.id })
  }
}
