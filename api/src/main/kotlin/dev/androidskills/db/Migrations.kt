package dev.androidskills.db

import dev.androidskills.util.newId
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.Database as ExposedDatabase

/**
 * Forward-only migrations tracked by `schema_meta.version`.
 *
 * Each migration is a `Transaction` extension invoked once, guarded by the
 * recorded version. All migrations run inside a single transaction so a failure
 * rolls the schema back to the last fully-applied version. **New schema changes
 * append a new `if (version < N)` block + `migrateVN()` extension here — never
 * edit an applied migration in place.**
 */
object Migrations {

    fun run(db: ExposedDatabase) = transaction(db) {
        exec("CREATE TABLE IF NOT EXISTS schema_meta (key TEXT PRIMARY KEY, value TEXT);")
        val version = readVersion()
        if (version < 1) {
            migrateV1()
            writeVersion(1)
        }
    }

    /** v1: the full initial schema (spec §4) + default category taxonomy. */
    private fun Transaction.migrateV1() {
        // Order matters: parents before children for FK DDL.
        SchemaUtils.create(
            Users, Categories, Bundles, Skills, SkillFiles, Versions,
            Submissions, Stars, Sessions, Jobs, AuditLog, Reports,
        )
        DEFAULT_CATEGORIES.forEach { (slug, name) ->
            Categories.insertIgnore {
                it[Categories.id] = newId()
                it[Categories.slug] = slug
                it[Categories.name] = name
            }
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
