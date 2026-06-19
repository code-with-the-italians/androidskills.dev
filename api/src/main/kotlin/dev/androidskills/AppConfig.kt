package dev.androidskills

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Environment-driven config with local-dev defaults. The cloud-shaped values
 * (LLM endpoint, R2 credentials, GitHub OAuth) are read from env / Kamal secrets
 * in prod and simply absent locally, which selects the stub/local/disabled
 * implementations. The HTTP port is owned by Ktor (application.conf / PORT),
 * not this object.
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
    val auth: AuthConfig = AuthConfig.disabled(),
) {
    // P3-2: redact the LLM key (and rely on OAuthConfig.toString) so a logged
    // AppConfig instance never spills a secret.
    override fun toString(): String =
        "AppConfig(version=$version, dbPath=$dbPath, fileStoreDir=$fileStoreDir, " +
            "llmBaseUrl=$llmBaseUrl, llmApiKey=${if (llmApiKey == null) "null" else "***"}, " +
            "llmModel=$llmModel, seedDemo=$seedDemo, auth=$auth)"

    companion object {
        fun fromEnv(): AppConfig {
            fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }
            val dataDir = Paths.get(env("DATA_DIR") ?: "../data").toAbsolutePath().normalize()
            val publicBaseUrl = env("PUBLIC_BASE_URL") ?: "http://localhost:8080"
            val oauthClientId = env("GITHUB_OAUTH_CLIENT_ID")
            val oauthClientSecret = env("GITHUB_OAUTH_CLIENT_SECRET")
            val oauth = if (oauthClientId != null && oauthClientSecret != null) {
                OAuthConfig(oauthClientId, oauthClientSecret)
            } else null
            // Secure cookies by default, but relax for plain-HTTP localhost so a dev
            // browser can actually log in locally (spec §7 wants Secure; the test
            // client and prod-over-HTTPS are unaffected). Overridable via env.
            // P3-3: parse the URL host — startsWith("http://localhost") wrongly
            // treated "http://localhost.evil.com" as localhost (Secure=false).
            val host = runCatching { java.net.URI(publicBaseUrl).host }.getOrNull()
            val localHosts = setOf("localhost", "127.0.0.1", "0.0.0.0", "[::1]", "::1")
            val secureDefault = host == null || host !in localHosts
            return AppConfig(
                version = env("APP_VERSION") ?: "0.0.1-local",
                dbPath = dataDir.resolve("androidskills.db"),
                fileStoreDir = dataDir.resolve("files"),
                llmBaseUrl = env("LLM_BASE_URL"),
                llmApiKey = env("LLM_API_KEY"),
                llmModel = env("LLM_MODEL"),
                seedDemo = env("SEED_DEMO")?.equals("1", ignoreCase = true) == true,
                auth = AuthConfig(
                    oauth = oauth,
                    publicBaseUrl = publicBaseUrl,
                    sessionCookieDomain = env("SESSION_COOKIE_DOMAIN"),
                    sessionCookieSecure = env("SESSION_COOKIE_SECURE")?.let {
                        it.equals("1", ignoreCase = true) || it.equals("true", ignoreCase = true)
                    } ?: secureDefault,
                    bootstrapAdminGithubId = env("BOOTSTRAP_ADMIN_GITHUB_ID")?.toLongOrNull(),
                ),
            )
        }
    }
}

/**
 * Auth configuration (spec §7, §13). [oauth] is `null` when GitHub OAuth creds
 * are unset → auth is *disabled*: the start/callback routes report auth
 * unavailable, no sessions can be created, and `/api/me` is always 401.
 *
 * @param publicBaseUrl Canonical site URL; used for the OAuth `redirect_uri`.
 * @param sessionCookieDomain Optional cookie `Domain`; null = host-only.
 * @param sessionCookieSecure Adds the `Secure` flag. Relaxed for localhost dev.
 * @param bootstrapAdminGithubId Optional GitHub numeric id promoted to `admin`
 *   on upsert — the explicit first-admin bootstrap (spec leaves admin promotion
 *   to the admin queue in step 7; this is the cold-start affordance). Assumption.
 */
data class AuthConfig(
    val oauth: OAuthConfig?,
    val publicBaseUrl: String,
    val sessionCookieDomain: String?,
    val sessionCookieSecure: Boolean,
    val bootstrapAdminGithubId: Long?,
) {
    companion object {
        fun disabled() = AuthConfig(
            oauth = null,
            publicBaseUrl = "http://localhost:8080",
            sessionCookieDomain = null,
            sessionCookieSecure = false,
            bootstrapAdminGithubId = null,
        )
    }
}

data class OAuthConfig(val clientId: String, val clientSecret: String) {
    // P3-2: never leak the secret if a config instance is logged.
    override fun toString() = "OAuthConfig(clientId=$clientId, clientSecret=***)"
}
