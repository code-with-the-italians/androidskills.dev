package dev.androidskills

import dev.androidskills.api.installApiErrorMapping
import dev.androidskills.api.publicRoutes
import dev.androidskills.auth.DisabledOAuthClient
import dev.androidskills.auth.GitHubOAuthClient
import dev.androidskills.auth.OAuthClient
import dev.androidskills.auth.authRoutes
import dev.androidskills.db.Skills
import dev.androidskills.llm.LlmClient
import dev.androidskills.llm.StubLlmClient
import dev.androidskills.storage.FileStore
import dev.androidskills.storage.LocalFsStore
import io.ktor.client.HttpClient
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Ktor application module (referenced from application.conf). The entrypoint is
 * io.ktor.server.netty.EngineMain (see main.kt).
 *
 * @param oauth Optional injected OAuth client (tests pass a fake). When null, a
 *   real [GitHubOAuthClient] is built from env creds, or [DisabledOAuthClient]
 *   when creds are absent (spec §13: "unset → auth disabled").
 */
fun Application.module(config: AppConfig = AppConfig.fromEnv(), oauth: OAuthClient? = null) {
    Database.init(config)

    install(ContentNegotiation) { json() }
    install(CallLogging)
    installApiErrorMapping()

    val fileStore: FileStore = LocalFsStore(config.fileStoreDir)
    val llm: LlmClient = StubLlmClient()
    val (resolvedOauth, ghHttp) = resolveOauth(config.auth, oauth)

    // P3-3: surface the resolved cookie posture once at boot — Secure/Domain drive
    // auth correctness and a mis-set SESSION_COOKIE_DOMAIN is a silent footgun.
    log.info(
        "auth: oauth={}, sessionCookie secure={}, domain={}",
        if (resolvedOauth.configured) "configured" else "disabled",
        config.auth.sessionCookieSecure,
        config.auth.sessionCookieDomain ?: "(host-only)",
    )

    if (config.seedDemo && transaction { Skills.selectAll().count() == 0L }) {
        dev.androidskills.api.DemoData.seed(fileStore)
    }

    // Close the GitHub HTTP client on shutdown (clients are long-lived; one per app).
    if (ghHttp != null) monitor.subscribe(ApplicationStopped) { ghHttp.close() }

    // Sweep expired sessions so the table (and its Litestream replica) doesn't
    // grow unbounded — lookups already ignore expired rows, but they're never
    // deleted otherwise (P2-1). A startup sweep + hourly run on a self-cancelling
    // scope; cancelled on ApplicationStopped.
    startSessionPurge(this)

    routing {
        get("/api/health") {
            call.respond(
                HealthResponse(
                    ok = true,
                    version = config.version,
                    db = Database.journalMode(),
                    fileStore = fileStore.kind,
                    llm = llm.kind,
                    auth = if (resolvedOauth.configured) "oauth" else "disabled",
                ),
            )
        }
        publicRoutes(fileStore)
        authRoutes(config, resolvedOauth)
    }
}

private fun resolveOauth(auth: AuthConfig, injected: OAuthClient?): Pair<OAuthClient, HttpClient?> {
    injected?.let { return it to null }
    val c = auth.oauth ?: return DisabledOAuthClient() to null
    val http = GitHubOAuthClient.httpClient()
    return GitHubOAuthClient(c.clientId, c.clientSecret, http) to http
}

private fun startSessionPurge(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val job = scope.launch {
        runCatching { dev.androidskills.auth.SessionStore.purgeExpired() } // startup sweep
        while (isActive) {
            delay(SESSION_PURGE_INTERVAL_MS)
            runCatching { dev.androidskills.auth.SessionStore.purgeExpired() }
                .onFailure { app.log.warn("Session purge failed", it) }
        }
    }
    app.monitor.subscribe(ApplicationStopped) {
        job.cancel()
        scope.cancel()
    }
}

private const val SESSION_PURGE_INTERVAL_MS = 60L * 60 * 1000 // 1 hour

@Serializable
data class HealthResponse(
    val ok: Boolean,
    val version: String,
    val db: String,
    val fileStore: String,
    val llm: String,
    val auth: String,
)
