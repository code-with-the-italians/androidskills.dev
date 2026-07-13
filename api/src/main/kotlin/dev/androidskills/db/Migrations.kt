package dev.androidskills.db

import dev.androidskills.util.appJson
import dev.androidskills.util.newId
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.Database as ExposedDatabase
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Forward-only migrations tracked by `schema_meta.version`.
 *
 * Each migration is a `Transaction` extension invoked once, guarded by the recorded version. All
 * migrations run inside a single transaction so a failure rolls the schema back to the last
 * fully-applied version. **New schema changes append a new `if (version < N)` block + `migrateVN()`
 * extension here — never edit an applied migration in place.**
 */
object Migrations {

  fun run(db: ExposedDatabase) =
    transaction(db) {
      exec("CREATE TABLE IF NOT EXISTS schema_meta (key TEXT PRIMARY KEY, value TEXT);")
      val version = readVersion()
      if (version < 1) {
        migrateV1()
        writeVersion(1)
      }
      if (version < 2) {
        migrateV2()
        writeVersion(2)
      }
      if (version < 3) {
        migrateV3()
        writeVersion(3)
      }
      if (version < 4) {
        migrateV4()
        writeVersion(4)
      }
      if (version < 5) {
        migrateV5()
        writeVersion(5)
      }
    }

  /** v1: the full initial schema (spec §4) + default category taxonomy. */
  private fun Transaction.migrateV1() {
    // Order matters: parents before children for FK DDL.
    SchemaUtils.create(
      Users,
      Categories,
      Bundles,
      Skills,
      SkillFiles,
      Versions,
      Submissions,
      Stars,
      Sessions,
      Jobs,
      AuditLog,
      Reports,
    )
    DEFAULT_CATEGORIES.forEach { (slug, name) ->
      Categories.insertIgnore {
        it[Categories.id] = newId()
        it[Categories.slug] = slug
        it[Categories.name] = name
      }
    }
  }

  /**
   * v2: contributor settings + soft-delete support (step 6). Idempotent so a fresh v1 table created
   * by the current [Users] object (which already has these columns) doesn't fail on re-ALTER.
   */
  private fun Transaction.migrateV2() {
    addColumnIfNotExists("users", "settings_json", "TEXT")
    addColumnIfNotExists("users", "deleted_at", "TEXT")
  }

  /** v3: platform settings for admin config (step 7). */
  private fun Transaction.migrateV3() {
    exec(
      """
            CREATE TABLE IF NOT EXISTS platform_settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
        """
    )
    val default = buildJsonObject {
      put("reviewPolicy", "manual")
      put("tokenSoftCap", 1000)
      put("llmEnabled", false)
    }
    exec(
      "INSERT OR IGNORE INTO platform_settings(key, value) VALUES ('settings', '${appJson.encodeToString(default)}');"
    )
  }

  /**
   * v4: `skills.source_dir` — the resync/promote identity, decoupled from `slug`. Legacy rows keep
   * `source_dir` NULL rather than a guessed path: the real archive dir was never stored and can't
   * be reconstructed (a nested skill's true path is not `skills/<slug>`), so inventing one would
   * break later identity lookups. A NULL row is reconciled to its real path on the next resync /
   * promote / re-submit by matching the archive skill whose leaf slug equals the row's slug (see
   * `IngestPipeline` / `SubmissionQueries`). SQLite treats NULLs as distinct in a unique index, so
   * multiple legacy rows per bundle coexist under `(bundle_id, source_dir)`; new rows always carry
   * a non-null, per-bundle-unique source_dir.
   */
  private fun Transaction.migrateV4() {
    addColumnIfNotExists("skills", "source_dir", "TEXT")
    exec(
      "CREATE UNIQUE INDEX IF NOT EXISTS uq_skills_bundle_sourcedir ON skills(bundle_id, source_dir)"
    )
  }

  /** v5: `skills.security` — advisory (fyi) review notes surfaced on the public skill page. */
  private fun Transaction.migrateV5() {
    addColumnIfNotExists("skills", "security", "TEXT")
  }

  private fun Transaction.addColumnIfNotExists(table: String, column: String, type: String) {
    val exists =
      exec("SELECT 1 FROM pragma_table_info('$table') WHERE name='$column'") { rs -> rs.next() }
    if (exists != true) {
      exec("ALTER TABLE $table ADD COLUMN $column $type;")
    }
  }

  private fun Transaction.readVersion(): Int =
    exec("SELECT value FROM schema_meta WHERE key='version'") { rs ->
      if (rs.next()) rs.getString(1)?.toIntOrNull() ?: 0 else 0
    } ?: 0

  private fun Transaction.writeVersion(version: Int) {
    exec("INSERT OR REPLACE INTO schema_meta(key, value) VALUES ('version', '$version')")
  }
}
