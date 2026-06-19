package dev.androidskills

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Environment-driven config with local-dev defaults. The cloud-shaped values
 * (LLM endpoint, and later R2 credentials) are read from env / Kamal secrets in
 * prod and simply absent locally, which selects the stub/local implementations.
 * The HTTP port is owned by Ktor (application.conf / PORT), not this object.
 */
data class AppConfig(
    val version: String,
    val dbPath: Path,
    val fileStoreDir: Path,
    val llmBaseUrl: String?,
    val llmApiKey: String?,
    val llmModel: String?,
    /** When true, a small demo dataset is seeded into an empty DB (local/dev only). */
    val seedDemo: Boolean = false,
) {
    companion object {
        fun fromEnv(): AppConfig {
            fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }
            val dataDir = Paths.get(env("DATA_DIR") ?: "../data").toAbsolutePath().normalize()
            return AppConfig(
                version = env("APP_VERSION") ?: "0.0.1-local",
                dbPath = dataDir.resolve("androidskills.db"),
                fileStoreDir = dataDir.resolve("files"),
                llmBaseUrl = env("LLM_BASE_URL"),
                llmApiKey = env("LLM_API_KEY"),
                llmModel = env("LLM_MODEL"),
                seedDemo = env("SEED_DEMO")?.equals("1", ignoreCase = true) == true,
            )
        }
    }
}
