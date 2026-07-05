package dev.androidskills

import java.nio.file.Files
import java.nio.file.Path

/** Shared test fixtures. Each test points the DB + FileStore at an isolated temp dir. */
object TestSupport {
  fun newConfig(dir: Path, seedDemo: Boolean = false): AppConfig =
    AppConfig(
      version = "test",
      dbPath = dir.resolve("test.db"),
      fileStoreDir = dir.resolve("files"),
      llmBaseUrl = null,
      llmApiKey = null,
      llmModel = null,
      seedDemo = seedDemo,
      auth =
        AuthConfig(
          oauth = null,
          publicBaseUrl = "http://localhost:8080",
          sessionCookieDomain = null,
          // Localhost HTTP → relax Secure so the test client (and a dev browser) carry the cookie.
          sessionCookieSecure = false,
          bootstrapAdminGithubId = null,
        ),
    )

  fun tempDir(): Path = Files.createTempDirectory("asdb-test")
}
