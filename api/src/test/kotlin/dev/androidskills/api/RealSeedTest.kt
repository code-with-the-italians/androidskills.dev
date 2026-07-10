package dev.androidskills.api

import dev.androidskills.Database
import dev.androidskills.TestSupport
import dev.androidskills.db.Bundles
import dev.androidskills.db.SkillStatus
import dev.androidskills.db.Skills
import dev.androidskills.db.Users
import dev.androidskills.storage.LocalFsStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Validates the real skills catalogue seed (resources/seed/real-skills.json) — 121 skills across
 * six repos, published + verified, categorised, with no fabricated install counts.
 */
class RealSeedTest {

  private val dir = TestSupport.tempDir()
  private val store = LocalFsStore(dir.resolve("files"))

  @BeforeTest
  fun setup() {
    Database.init(TestSupport.newConfig(dir))
    RealSeed.seed(store)
  }

  @AfterTest
  fun teardown() {
    dir.toFile().deleteRecursively()
  }

  @Test
  fun `seeds the full catalogue as published, categorised skills with zero installs`() {
    transaction {
      val rows = Skills.selectAll().toList()
      assertEquals(121, rows.size)
      assertEquals(121, rows.count { it[Skills.status] == SkillStatus.published.name })
      assertEquals(121, rows.count { it[Skills.verified] })
      assertEquals(0, rows.count { it[Skills.categoryId] == null }) // every mapped slug resolves
      assertEquals(0, rows.count { it[Skills.installs] != 0 }) // no fabricated popularity
    }
  }

  @Test
  fun `seeds four distinct authors and six repo bundles`() {
    transaction {
      assertEquals(4L, Users.selectAll().count())
      assertEquals(6L, Bundles.selectAll().count())
    }
  }

  @Test
  fun `known real skills from each source are present`() {
    transaction {
      for (slug in
        listOf("adaptive", "material-3", "debugging-recompositions", "compose-animations")) {
        assertNotNull(
          Skills.selectAll().where { Skills.slug eq slug }.singleOrNull(),
          "missing seeded skill: $slug",
        )
      }
    }
  }

  @Test
  fun `re-running on a populated db is a no-op`() {
    RealSeed.seed(store)
    assertEquals(121L, transaction { Skills.selectAll().count() })
  }
}
