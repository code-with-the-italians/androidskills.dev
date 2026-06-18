package dev.androidskills.api

import dev.androidskills.Database
import dev.androidskills.TestSupport
import dev.androidskills.db.Skills
import dev.androidskills.storage.LocalFsStore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the public read surface (spec §9 Public) directly over seeded demo
 * data — the build-order step 2 "over seed data" milestone.
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

    private fun all() = SearchParams(
        q = null, cats = emptyList(), tags = emptyList(), size = null,
        verified = false, sort = "relevance", page = 1, pageSize = 60,
    )

    @Test
    fun `stats reflect seeded data`() {
        val stats = PublicQueries.stats()
        assertEquals(6, stats.indexed) // all demo skills are published
        assertTrue(stats.contributors >= 1)
        assertNotNull(stats.lastUpdated)
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
        assertFalse(verifiedOnly.items.any { it.slug == "retrofit-okhttp-config" }) // unverified seed skill

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
        val skillId = transaction { Skills.selectAll().where { Skills.slug eq slug }.single()[Skills.id] }
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
        val before = transaction { Skills.selectAll().where { Skills.slug eq "jetpack-compose-mvi" }.single()[Skills.installs] }
        val dl = PublicQueries.download("jetpack-compose-mvi", null, store)
        assertTrue(dl.bytes.size > 4) // a real zip
        assertEquals("application/zip", dl.contentType)
        val after = transaction { Skills.selectAll().where { Skills.slug eq "jetpack-compose-mvi" }.single()[Skills.installs] }
        assertEquals(before + 1, after)
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
            Skills.update({ Skills.slug eq "room-migration-helper" }) {
                it[Skills.status] = "unlisted"
            }
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
        val skillId = transaction { Skills.selectAll().where { Skills.slug eq slug }.single()[Skills.id] }
        transaction {
            dev.androidskills.db.Versions.insert {
                it[dev.androidskills.db.Versions.id] = dev.androidskills.util.newId()
                it[dev.androidskills.db.Versions.skillId] = skillId
                it[dev.androidskills.db.Versions.version] = "0.1.0"
                it[dev.androidskills.db.Versions.sourceRef] = "manifest:0.1.0"
                it[dev.androidskills.db.Versions.r2ZipKey] = "skills/$skillId/versions/0.1.0.zip" // not in store
                it[dev.androidskills.db.Versions.createdAt] = "2020-01-01T00:00:00Z" // older than the demo's 1.4.2
            }
        }
        // Requesting the historical version must NOT fall back to current files.
        assertFailsWith<ApiNotFoundException> {
            PublicQueries.download(slug, "0.1.0", store)
        }
        // The current version still downloads fine (built on demand / from stored zip).
        val cur = PublicQueries.download(slug, null, store)
        assertTrue(cur.bytes.isNotEmpty())
    }

    @Test
    fun `missing file bytes surface as a storage error not empty 200`() {
        val slug = "jetpack-compose-mvi"
        val key = transaction {
            val row = dev.androidskills.db.SkillFiles.selectAll()
                .where { dev.androidskills.db.SkillFiles.path eq "references/intent.md" }.single()
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
