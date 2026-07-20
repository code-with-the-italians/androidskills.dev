package dev.androidskills

import dev.androidskills.db.Bundles
import dev.androidskills.db.Categories
import dev.androidskills.db.DEFAULT_CATEGORIES
import dev.androidskills.db.PlatformSettings
import dev.androidskills.db.SkillFiles
import dev.androidskills.db.Skills
import dev.androidskills.db.Users
import dev.androidskills.db.Versions
import dev.androidskills.util.nowIso
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class MigrationTest {
  private val dir = TestSupport.tempDir()

  @AfterTest
  fun teardown() {
    dir.toFile().deleteRecursively()
  }

  @Test
  fun `migrates to v1 and creates every table`() {
    Database.init(TestSupport.newConfig(dir))
    transaction {
      val tables =
        exec("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name") { rs ->
          val out = mutableListOf<String>()
          while (rs.next()) out += rs.getString(1)
          out
        } ?: emptyList()
      listOf(
          "users",
          "bundles",
          "categories",
          "skills",
          "skill_files",
          "versions",
          "submissions",
          "stars",
          "sessions",
          "jobs",
          "audit_log",
          "reports",
          "schema_meta",
        )
        .forEach { assertTrue("$it table missing: $tables") { tables.contains(it) } }
    }
  }

  @Test
  fun `fresh SQLite migration matches the shared v5 compatibility snapshot`() {
    val snapshot =
      Json.parseToJsonElement(
          requireNotNull(javaClass.getResourceAsStream("/schema-v5-compatibility.json"))
            .bufferedReader()
            .readText()
        )
        .jsonObject
    Database.init(TestSupport.newConfig(dir))
    transaction {
      snapshot.getValue("tables").jsonObject.forEach { (table, expected) ->
        val columns =
          exec("PRAGMA table_info($table)") { rs ->
            buildList {
              while (rs.next()) add(rs.getString("name"))
            }
          } ?: emptyList()
        assertEquals(expected.jsonArray.map { it.jsonPrimitive.content }, columns, table)
      }
      val indexes =
        exec("SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_autoindex%' AND name NOT LIKE 'uq_%'") {
          rs -> buildSet { while (rs.next()) add(rs.getString(1)) }
        } ?: emptySet()
      assertEquals(snapshot.getValue("indexes").jsonArray.map { it.jsonPrimitive.content }.toSet(), indexes)
      assertEquals(
        snapshot.getValue("categories").jsonObject.keys,
        Categories.selectAll().map { it[Categories.slug] }.toSet(),
      )
    }
  }

  @Test
  fun `schema_meta version is 5 after migration`() {
    Database.init(TestSupport.newConfig(dir))
    transaction {
      val v =
        exec("SELECT value FROM schema_meta WHERE key='version'") { rs ->
          rs.next()
          rs.getString(1).toInt()
        }
      assertEquals(5, v)
    }
  }

  @Test
  fun `v5 adds the skills security column`() {
    Database.init(TestSupport.newConfig(dir))
    transaction {
      val cols =
        exec("PRAGMA table_info(skills)") { rs ->
          val out = mutableListOf<String>()
          while (rs.next()) out += rs.getString(2)
          out
        } ?: emptyList()
      assertTrue("security column missing") { "security" in cols }
    }
  }

  @Test
  fun `v4 adds source_dir and enforces bundle plus source_dir uniqueness`() {
    Database.init(TestSupport.newConfig(dir))
    val now = nowIso()
    val uid = "10000000-0000-0000-0000-000000000001"
    val bid = "10000000-0000-0000-0000-000000000002"
    transaction {
      val cols =
        exec("PRAGMA table_info(skills)") { rs ->
          val out = mutableListOf<String>()
          while (rs.next()) out += rs.getString(2)
          out
        } ?: emptyList()
      assertTrue("source_dir column missing") { "source_dir" in cols }

      Users.insert {
        it[Users.id] = uid
        it[Users.githubId] = 900
        it[Users.handle] = "srcdir-user"
        it[Users.createdAt] = now
        it[Users.updatedAt] = now
      }
      Bundles.insert {
        it[Bundles.id] = bid
        it[Bundles.kind] = "zip"
        it[Bundles.provenance] = "upload-srcdir"
        it[Bundles.ownerUserId] = uid
        it[Bundles.createdAt] = now
      }
      Skills.insert {
        it[Skills.id] = "10000000-0000-0000-0000-000000000003"
        it[Skills.bundleId] = bid
        it[Skills.slug] = "srcdir-a"
        it[Skills.sourceDir] = "skills/dup"
        it[Skills.name] = "A"
        it[Skills.description] = "x"
        it[Skills.version] = "1.0.0"
        it[Skills.versionSource] = "manifest"
        it[Skills.createdAt] = now
        it[Skills.updatedAt] = now
      }
    }
    // Same bundle + same source_dir but a different slug must be rejected by
    // uq_skills_bundle_sourcedir — identity is the pair, not the slug.
    assertFailsWith<Exception> {
      transaction {
        Skills.insert {
          it[Skills.id] = "10000000-0000-0000-0000-000000000004"
          it[Skills.bundleId] = bid
          it[Skills.slug] = "srcdir-b"
          it[Skills.sourceDir] = "skills/dup"
          it[Skills.name] = "B"
          it[Skills.description] = "x"
          it[Skills.version] = "1.0.0"
          it[Skills.versionSource] = "manifest"
          it[Skills.createdAt] = now
          it[Skills.updatedAt] = now
        }
      }
    }

    // Pre-v4 legacy rows carry a NULL source_dir; SQLite treats NULLs as distinct, so several
    // coexist in one bundle under uq_skills_bundle_sourcedir (they are reconciled on next ingest).
    transaction {
      listOf("legacy-a", "legacy-b").forEachIndexed { i, s ->
        Skills.insert {
          it[Skills.id] = "20000000-0000-0000-0000-00000000000$i"
          it[Skills.bundleId] = bid
          it[Skills.slug] = s
          // source_dir omitted (NULL)
          it[Skills.name] = s
          it[Skills.description] = "x"
          it[Skills.version] = "1.0.0"
          it[Skills.versionSource] = "manifest"
          it[Skills.createdAt] = now
          it[Skills.updatedAt] = now
        }
      }
    }
    assertEquals(3, transaction { Skills.selectAll().where { Skills.bundleId eq bid }.count() })
  }

  @Test
  fun `seeds the default category taxonomy including uncategorized`() {
    Database.init(TestSupport.newConfig(dir))
    transaction {
      val slugs = Categories.selectAll().map { it[Categories.slug] }.toSet()
      DEFAULT_CATEGORIES.forEach { (slug, _) ->
        assertTrue("missing seeded category $slug") { slug in slugs }
      }
      assertTrue("uncategorized" in slugs)
    }
  }

  @Test
  fun `re-running init is idempotent`() {
    val cfg = TestSupport.newConfig(dir)
    Database.init(cfg)
    Database.init(cfg) // second open must not duplicate or re-run migrations
    transaction {
      assertEquals(DEFAULT_CATEGORIES.size, Categories.selectAll().toList().size)
      val v =
        exec("SELECT value FROM schema_meta WHERE key='version'") { rs ->
          rs.next()
          rs.getString(1).toInt()
        }
      assertEquals(5, v)
    }
  }

  @Test
  fun `v3 creates platform_settings with default settings row`() {
    Database.init(TestSupport.newConfig(dir))
    transaction {
      val tables =
        exec("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name") { rs ->
          val out = mutableListOf<String>()
          while (rs.next()) out += rs.getString(1)
          out
        } ?: emptyList()
      assertTrue("platform_settings table missing") { "platform_settings" in tables }

      val row =
        PlatformSettings.selectAll().where { PlatformSettings.key eq "settings" }.singleOrNull()
      assertNotNull(row)
      val json = row!![PlatformSettings.value]
      assertTrue(json.contains("reviewPolicy"))
      assertTrue(json.contains("tokenSoftCap"))
    }
  }

  @Test
  fun `users table has settings_json and deleted_at columns`() {
    Database.init(TestSupport.newConfig(dir))
    transaction {
      val cols =
        exec("PRAGMA table_info(users)") { rs ->
          val out = mutableListOf<String>()
          while (rs.next()) out += rs.getString(2)
          out
        } ?: emptyList()
      assertTrue("settings_json column missing") { "settings_json" in cols }
      assertTrue("deleted_at column missing") { "deleted_at" in cols }
    }
  }

  @Test
  fun `domain model round-trips and enforces unique slug`() {
    Database.init(TestSupport.newConfig(dir))
    val now = nowIso()
    val uid = "00000000-0000-0000-0000-000000000001"
    val bid = "00000000-0000-0000-0000-000000000002"
    val sid = "00000000-0000-0000-0000-000000000003"
    transaction {
      Users.insert {
        it[Users.id] = uid
        it[Users.githubId] = 1
        it[Users.handle] = "alice"
        it[Users.createdAt] = now
        it[Users.updatedAt] = now
      }
      Bundles.insert {
        it[Bundles.id] = bid
        it[Bundles.kind] = "zip"
        it[Bundles.provenance] = "upload-abc"
        it[Bundles.ownerUserId] = uid
        it[Bundles.createdAt] = now
      }
      Skills.insert {
        it[Skills.id] = sid
        it[Skills.bundleId] = bid
        it[Skills.slug] = "jetpack-mvi"
        it[Skills.name] = "Jetpack MVI"
        it[Skills.description] = "MVI scaffold"
        it[Skills.version] = "1.0.0"
        it[Skills.versionSource] = "manifest"
        it[Skills.tags] = """["android","kotlin"]"""
        it[Skills.tokenBand] = "1k"
        it[Skills.createdAt] = now
        it[Skills.updatedAt] = now
      }
      SkillFiles.insert {
        it[SkillFiles.id] = "00000000-0000-0000-0000-000000000004"
        it[SkillFiles.skillId] = sid
        it[SkillFiles.path] = "references/guide.md"
        it[SkillFiles.size] = 42
        it[SkillFiles.r2Key] = "skills/$sid/files/references/guide.md"
      }
      Versions.insert {
        it[Versions.id] = "00000000-0000-0000-0000-000000000005"
        it[Versions.skillId] = sid
        it[Versions.version] = "1.0.0"
        it[Versions.sourceRef] = "sha-1"
        it[Versions.createdAt] = now
      }
    }
    // Duplicate slug must be rejected by the unique index.
    assertFailsWith<Exception> {
      transaction {
        Skills.insert {
          it[Skills.id] = "00000000-0000-0000-0000-000000000006"
          it[Skills.bundleId] = bid
          it[Skills.slug] = "jetpack-mvi" // duplicate
          it[Skills.name] = "Dup"
          it[Skills.description] = "x"
          it[Skills.version] = "1.0.0"
          it[Skills.versionSource] = "manifest"
          it[Skills.createdAt] = now
          it[Skills.updatedAt] = now
        }
      }
    }
  }

  @Test
  fun `foreign keys are enforced`() {
    Database.init(TestSupport.newConfig(dir))
    assertFailsWith<Exception> {
      transaction {
        Skills.insert {
          it[Skills.id] = "00000000-0000-0000-0000-000000000010"
          it[Skills.bundleId] = "no-such-bundle"
          it[Skills.slug] = "orphan"
          it[Skills.name] = "Orphan"
          it[Skills.description] = "x"
          it[Skills.version] = "1.0.0"
          it[Skills.versionSource] = "manifest"
          it[Skills.createdAt] = nowIso()
          it[Skills.updatedAt] = nowIso()
        }
      }
    }
  }
}
