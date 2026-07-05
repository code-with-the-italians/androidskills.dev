package dev.androidskills

import dev.androidskills.db.Migrations
import java.nio.file.Files
import org.jetbrains.exposed.sql.Database as ExposedDatabase
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource

/**
 * SQLite connection. WAL + a generous busy_timeout are what let the single writer survive Kamal's
 * brief two-container deploy overlap (see docs/architecture.md).
 *
 * These PRAGMAs are applied via [SQLiteConfig] at connect time — SQLite refuses to switch journal
 * modes from inside a transaction, so they cannot be run through an Exposed `transaction { }`
 * block.
 *
 * The latest connected database is also installed as Exposed's `TransactionManager.defaultDatabase`
 * so handlers can call `transaction { }` without a handle (and tests can re-point it at an isolated
 * temp DB per run).
 */
object Database {
  private lateinit var db: ExposedDatabase

  fun init(config: AppConfig) {
    Files.createDirectories(config.dbPath.parent)

    val dataSource =
      SQLiteDataSource(
          SQLiteConfig().apply {
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setBusyTimeout(5000)
            enforceForeignKeys(true)
          }
        )
        .apply { url = "jdbc:sqlite:${config.dbPath}" }

    db = ExposedDatabase.connect(dataSource)
    TransactionManager.defaultDatabase = db

    Migrations.run(db)
  }

  fun check(): Boolean = runCatching { journalMode() }.isSuccess

  fun journalMode(): String =
    transaction(db) {
      var mode = "unknown"
      exec("PRAGMA journal_mode;") { rs -> if (rs.next()) mode = rs.getString(1) }
      mode
    }
}
