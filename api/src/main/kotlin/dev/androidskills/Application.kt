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
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
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

    if (config.seedDemo && transaction { Skills.selectAll().count() == 0L }) {
        dev.androidskills.api.DemoData.seed(fileStore)
    }

    // Close the GitHub HTTP client on shutdown (clients are long-lived; one per app).
    if (ghHttp != null) monitor.subscribe(ApplicationStopped) { ghHttp.close() }

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

@Serializable
data class HealthResponse(
    val ok: Boolean,
    val version: String,
    val db: String,
    val fileStore: String,
    val llm: String,
    val auth: String,
)
