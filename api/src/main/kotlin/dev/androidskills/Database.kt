package dev.androidskills

import org.jetbrains.exposed.sql.transactions.transaction
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import org.jetbrains.exposed.sql.Database as ExposedDatabase

/**
 * SQLite connection. WAL + a generous busy_timeout are what let the single
 * writer survive Kamal's brief two-container deploy overlap (see docs/architecture.md).
 *
 * These PRAGMAs are applied via [SQLiteConfig] at connect time — SQLite refuses
 * to switch journal modes from inside a transaction, so they cannot be run through
 * an Exposed `transaction { }` block.
 */
object Database {
    private lateinit var db: ExposedDatabase

    fun init(config: AppConfig) {
        Files.createDirectories(config.dbPath.parent)

        val dataSource = SQLiteDataSource(
            SQLiteConfig().apply {
                setJournalMode(SQLiteConfig.JournalMode.WAL)
                setBusyTimeout(5000)
                enforceForeignKeys(true)
            },
        ).apply { url = "jdbc:sqlite:${config.dbPath}" }

        db = ExposedDatabase.connect(dataSource)

        transaction(db) {
            // Walking-skeleton marker; the real schema/migrations land with the read path.
            exec("CREATE TABLE IF NOT EXISTS schema_meta (key TEXT PRIMARY KEY, value TEXT);")
            exec("INSERT OR IGNORE INTO schema_meta(key, value) VALUES ('version', '0');")
        }
    }

    fun journalMode(): String = transaction(db) {
        var mode = "unknown"
        exec("PRAGMA journal_mode;") { rs -> if (rs.next()) mode = rs.getString(1) }
        mode
    }
}
