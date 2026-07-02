package dev.androidskills

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Environment-driven config with local-dev defaults. The cloud-shaped values
 * (LLM endpoint, GitHub OAuth) are read from env / Kamal secrets in prod and
 * simply absent locally, which selects the stub/local/disabled implementations.
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
    val auth: AuthConfig = AuthConfig.disabled(),
    /** GitHub App (installation tokens, repo scan, webhooks). null → disabled (spec §13). */
    val githubApp: GithubAppConfig = GithubAppConfig.disabled(),
) {
    // P3-2: redact the LLM key (and rely on OAuthConfig/GithubAppConfig.toString) so
    // a logged AppConfig instance never spills a secret.
    override fun toString(): String =
        "AppConfig(version=$version, dbPath=$dbPath, fileStoreDir=$fileStoreDir, " +
            "llmBaseUrl=$llmBaseUrl, llmApiKey=${if (llmApiKey == null) "null" else "***"}, " +
            "llmModel=$llmModel, seedDemo=$seedDemo, auth=$auth, githubApp=$githubApp)"

    /**
     * Fail-fast validation for required paths and URLs. This runs after env parsing
     * so the app exits with a clear error instead of failing mysteriously later.
     */
    fun validate() {
        ensureWritable(dbPath.parent ?: error("DATA_DIR has no parent directory"), "DATA_DIR")
        ensureWritable(fileStoreDir, "DATA_DIR/files")
        runCatching { java.net.URI(auth.publicBaseUrl) }.getOrElse {
            throw IllegalStateException("PUBLIC_BASE_URL is not a valid URL: ${auth.publicBaseUrl}")
        }
    }

    companion object {
        fun fromEnv(): AppConfig = fromMap(System.getenv())

        fun fromMap(env: Map<String, String>): AppConfig {
            fun env(k: String) = env[k]?.takeIf { it.isNotBlank() }
            val dataDir = Paths.get(env("DATA_DIR") ?: "../data").toAbsolutePath().normalize()
            val publicBaseUrl = env("PUBLIC_BASE_URL") ?: "http://localhost:8080"

            val oauthClientId = env("GITHUB_OAUTH_CLIENT_ID")
            val oauthClientSecret = env("GITHUB_OAUTH_CLIENT_SECRET")
            requireAllOrNone(
                "GitHub OAuth",
                mapOf(
                    "GITHUB_OAUTH_CLIENT_ID" to oauthClientId,
                    "GITHUB_OAUTH_CLIENT_SECRET" to oauthClientSecret,
                ),
            )

            val llmBaseUrl = env("LLM_BASE_URL")
            val llmApiKey = env("LLM_API_KEY")
            val llmModel = env("LLM_MODEL")
            requireAllOrNone(
                "LLM",
                mapOf(
                    "LLM_BASE_URL" to llmBaseUrl,
                    "LLM_API_KEY" to llmApiKey,
                    "LLM_MODEL" to llmModel,
                ),
            )

            val githubAppId = env("GITHUB_APP_ID")
            val githubAppPrivateKey = env("GITHUB_APP_PRIVATE_KEY")
            val githubAppWebhookSecret = env("GITHUB_WEBHOOK_SECRET")
            requireAllOrNone(
                "GitHub App",
                mapOf(
                    "GITHUB_APP_ID" to githubAppId,
                    "GITHUB_APP_PRIVATE_KEY" to githubAppPrivateKey,
                    "GITHUB_WEBHOOK_SECRET" to githubAppWebhookSecret,
                ),
            )

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
                llmBaseUrl = llmBaseUrl,
                llmApiKey = llmApiKey,
                llmModel = llmModel,
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
                githubApp = GithubAppConfig.fromMap(env),
            )
        }
    }
}

private fun requireAllOrNone(feature: String, vars: Map<String, String?>) {
    val present = vars.count { it.value != null }
    if (present != 0 && present != vars.size) {
        val missing = vars.filter { it.value == null }.keys.joinToString(", ")
        throw IllegalStateException("$feature is partially configured; missing: $missing")
    }
}

private fun ensureWritable(path: Path, name: String) {
    var check: Path? = path
    while (check != null && !Files.exists(check)) {
        check = check.parent
    }
    if (check == null) {
        throw IllegalStateException("$name ($path) has no existing parent directory")
    }
    if (!Files.isDirectory(check) || !Files.isWritable(check)) {
        throw IllegalStateException("$name directory ($check) is not writable")
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
 *   on **first insert only** (a seed-only cold-start escape hatch; an existing
 *   user keeps their current role on re-login). Spec leaves admin promotion to
 *   the admin queue (step 7); this is just the bootstrap affordance. Unset once
 *   the first admin exists. Assumption.
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

/**
 * GitHub App configuration (spec §8, §13). [GithubAppConfig.disabled] when the
 * App id / private key / webhook secret are unset → the GitHub-App features
 * (repo list, scan, webhooks) report disabled, mirroring the §13 "unset →
 * disabled" pattern used for auth.
 *
 * **Single-App invariant (doc-only, no heuristic — review Q1):** this App and
 * the OAuth client in [AuthConfig] must describe the SAME GitHub App: login
 * (OAuth-App user flow) and installation tokens (this config) share one App
 * identity. The env names differ for historical reasons; do not assume two
 * apps. Renaming `GITHUB_OAUTH_CLIENT_*` → `GITHUB_APP_CLIENT_*` is deferred
 * (would churn step-3 config/docs for no functional gain).
 *
 * @param appId Numeric App id (`GITHUB_APP_ID`).
 * @param privateKeyPem PEM private key (`GITHUB_APP_PRIVATE_KEY`). Accepts PKCS#1
 *  (`-----BEGIN RSA PRIVATE KEY-----`, what GitHub exports) or PKCS#8; the
 *  loader normalises PKCS#1 → PKCS#8 so the exported key works as-is.
 * @param webhookSecret HMAC key for `/gh/webhooks` signature verification.
 */
data class GithubAppConfig(
    val appId: Long?,
    val privateKeyPem: String?,
    val webhookSecret: String?,
) {
    /** Configured only when all three are present (partial config is treated as disabled). */
    val configured: Boolean get() = appId != null && privateKeyPem != null && webhookSecret != null

    // P3-2: never leak the PEM or the webhook secret in a logged config instance.
    override fun toString(): String =
        "GithubAppConfig(appId=$appId, privateKeyPem=${if (privateKeyPem == null) "null" else "***"}, " +
            "webhookSecret=${if (webhookSecret == null) "null" else "***"}, configured=$configured)"

    companion object {
        fun disabled() = GithubAppConfig(appId = null, privateKeyPem = null, webhookSecret = null)

        fun fromEnv(): GithubAppConfig = fromMap(System.getenv())

        fun fromMap(env: Map<String, String>): GithubAppConfig {
            fun env(k: String) = env[k]?.takeIf { it.isNotBlank() }
            return GithubAppConfig(
                appId = env("GITHUB_APP_ID")?.toLongOrNull(),
                privateKeyPem = env("GITHUB_APP_PRIVATE_KEY"),
                webhookSecret = env("GITHUB_WEBHOOK_SECRET"),
            )
        }
    }
}
