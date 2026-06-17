package dev.androidskills

import java.nio.file.Files
import java.nio.file.Path

/** Shared test fixtures. Each test points the DB + FileStore at an isolated temp dir. */
object TestSupport {
    fun newConfig(dir: Path): AppConfig = AppConfig(
        version = "test",
        dbPath = dir.resolve("test.db"),
        fileStoreDir = dir.resolve("files"),
        llmBaseUrl = null,
        llmApiKey = null,
        llmModel = null,
    )

    fun tempDir(): Path = Files.createTempDirectory("asdb-test")
}
