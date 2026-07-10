package dev.androidskills

import dev.androidskills.api.adminRoutes
import dev.androidskills.api.contributorRoutes
import dev.androidskills.api.installApiErrorMapping
import dev.androidskills.api.publicRoutes
import dev.androidskills.auth.DisabledOAuthClient
import dev.androidskills.auth.GitHubOAuthClient
import dev.androidskills.auth.OAuthClient
import dev.androidskills.auth.authRoutes
import dev.androidskills.db.Skills
import dev.androidskills.gh.webhookRoutes
import dev.androidskills.github.GitHubAppClient
import dev.androidskills.github.PemLoader
import dev.androidskills.llm.LlmClient
import dev.androidskills.llm.StubLlmClient
import dev.androidskills.storage.FileStore
import dev.androidskills.storage.LocalFsStore
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlin.time.Duration.Companion.seconds
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
 * @param oauth Optional injected OAuth client (tests pass a fake). When null, a real
 *   [GitHubOAuthClient] is built from env creds, or [DisabledOAuthClient] when creds are absent
 *   (spec §13: "unset → auth disabled").
 */
fun Application.module(
  config: AppConfig = AppConfig.fromEnv(),
  oauth: OAuthClient? = null,
  githubApp: GitHubAppClient? = null,
) {
  config.validate()
  Database.init(config)

  install(ContentNegotiation) { json() }
  install(CallLogging)
  installApiErrorMapping()

  val trustedProxyCount = System.getenv("TRUSTED_PROXY_COUNT")?.toIntOrNull()?.coerceAtLeast(0) ?: 1
  install(RateLimit) {
    register(RateLimitName("public")) {
      rateLimiter(limit = 120, refillPeriod = 60.seconds)
      requestKey { call -> clientIp(call, trustedProxyCount) }
    }
    register(RateLimitName("auth")) {
      rateLimiter(limit = 10, refillPeriod = 60.seconds)
      requestKey { call -> clientIp(call, trustedProxyCount) }
    }
    register(RateLimitName("authenticated")) {
      rateLimiter(limit = 60, refillPeriod = 60.seconds)
      requestKey { call -> clientIp(call, trustedProxyCount) }
    }
    register(RateLimitName("admin")) {
      rateLimiter(limit = 60, refillPeriod = 60.seconds)
      requestKey { call -> clientIp(call, trustedProxyCount) }
    }
  }

  val fileStore: FileStore = LocalFsStore(config.fileStoreDir)
  val (llm, llmHttp) = resolveLlm(config)
  val (resolvedOauth, ghHttp) = resolveOauth(config.auth, oauth)
  val (resolvedGithubApp, ghAppHttp) = resolveGithubApp(config.githubApp, githubApp)

  // P3-3: surface the resolved cookie posture once at boot — Secure/Domain drive
  // auth correctness and a mis-set SESSION_COOKIE_DOMAIN is a silent footgun.
  log.info(
    "androidskills {} starting: dataDir={}, db={}, fileStore={}, oauth={}, githubApp={}, llm={}, health={}",
    config.version,
    config.fileStoreDir.parent,
    Database.journalMode(),
    fileStore.kind,
    if (resolvedOauth.configured) "configured" else "disabled",
    if (resolvedGithubApp.configured) "configured" else "disabled",
    if (llm is dev.androidskills.llm.StubLlmClient) "disabled" else "enabled",
    "${config.auth.publicBaseUrl}/api/health",
  )
  log.info(
    "auth: oauth={}, sessionCookie secure={}, domain={}",
    if (resolvedOauth.configured) "configured" else "disabled",
    config.auth.sessionCookieSecure,
    config.auth.sessionCookieDomain ?: "(host-only)",
  )

  // Real skills catalogue (staging) takes precedence over the demo fixture if both are set.
  if (config.seedReal && transaction { Skills.selectAll().count() == 0L }) {
    dev.androidskills.api.RealSeed.seed(fileStore)
  }
  if (config.seedDemo && transaction { Skills.selectAll().count() == 0L }) {
    dev.androidskills.api.DemoData.seed(fileStore)
  }

  // Close the GitHub HTTP client on shutdown (clients are long-lived; one per app).
  if (ghHttp != null) monitor.subscribe(ApplicationStopped) { ghHttp.close() }
  if (ghAppHttp != null) monitor.subscribe(ApplicationStopped) { ghAppHttp.close() }
  if (llmHttp != null) monitor.subscribe(ApplicationStopped) { llmHttp.close() }

  // Sweep expired sessions so the table (and its Litestream replica) doesn't
  // grow unbounded — lookups already ignore expired rows, but they're never
  // deleted otherwise (P2-1). A startup sweep + hourly run on a self-cancelling
  // scope; cancelled on ApplicationStopped.
  startSessionPurge(this)
  dev.androidskills.jobs.startJobWorker(this, llm, fileStore, resolvedGithubApp)

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
        )
      )
    }
    get("/api/health/deep") {
      val dbOk = Database.check()
      val fileStoreOk = fileStore.check()
      val githubAppOk =
        if (config.githubApp.configured) {
          PemLoader.validatePem(config.githubApp.privateKeyPem)
        } else true
      val llmOk =
        if (config.llmBaseUrl != null) {
          config.llmApiKey != null &&
            config.llmModel != null &&
            runCatching { java.net.URI(config.llmBaseUrl) }.isSuccess
        } else true
      val checks = DeepHealthChecks(dbOk, fileStoreOk, githubAppOk, llmOk)
      val ok = dbOk && fileStoreOk && githubAppOk && llmOk
      val status = if (ok) "ok" else "degraded"
      val code = if (ok) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
      call.respond(code, DeepHealthResponse(status, checks))
    }
    get("/api/openapi.yaml") {
      val spec =
        this::class
          .java
          .classLoader
          .getResourceAsStream("openapi.yaml")
          ?.use { it.readAllBytes() }
          ?.decodeToString() ?: throw IllegalStateException("openapi.yaml missing from classpath")
      call.respondText(spec, ContentType("application", "yaml"))
    }
    get("/api/openapi-admin.yaml") {
      val spec =
        this::class
          .java
          .classLoader
          .getResourceAsStream("openapi-admin.yaml")
          ?.use { it.readAllBytes() }
          ?.decodeToString()
          ?: throw IllegalStateException("openapi-admin.yaml missing from classpath")
      call.respondText(spec, ContentType("application", "yaml"))
    }
    publicRoutes(fileStore)
    authRoutes(config, resolvedOauth)
    contributorRoutes(resolvedGithubApp)
    adminRoutes(fileStore, resolvedGithubApp)
    webhookRoutes(resolvedGithubApp)
  }
}

private fun resolveLlm(config: AppConfig): Pair<LlmClient, HttpClient?> {
  val baseUrl = config.llmBaseUrl ?: return StubLlmClient() to null
  val apiKey = config.llmApiKey ?: return StubLlmClient() to null
  val model = config.llmModel ?: return StubLlmClient() to null
  val http = dev.androidskills.llm.OpenAiLlmClient.httpClient()
  return dev.androidskills.llm.OpenAiLlmClient(baseUrl, apiKey, model, http) to http
}

private fun resolveGithubApp(
  cfg: GithubAppConfig,
  injected: GitHubAppClient?,
): Pair<GitHubAppClient, HttpClient?> {
  injected?.let {
    return it to null
  }
  if (!cfg.configured) return dev.androidskills.github.DisabledGitHubAppClient() to null
  val http = dev.androidskills.github.RealGitHubAppClient.httpClient()
  val client =
    dev.androidskills.github.RealGitHubAppClient(
      appId = cfg.appId!!,
      privateKeyPem = cfg.privateKeyPem!!,
      webhookSecret = cfg.webhookSecret!!,
      http = http,
    )
  return client to http
}

private fun resolveOauth(auth: AuthConfig, injected: OAuthClient?): Pair<OAuthClient, HttpClient?> {
  injected?.let {
    return it to null
  }
  val c = auth.oauth ?: return DisabledOAuthClient() to null
  val http = GitHubOAuthClient.httpClient()
  return GitHubOAuthClient(c.clientId, c.clientSecret, http) to http
}

internal fun clientIp(call: ApplicationCall, trustedProxyCount: Int): String {
  if (trustedProxyCount == 0) return call.request.local.remoteHost
  val xff = call.request.headers["X-Forwarded-For"]
  if (!xff.isNullOrBlank()) {
    val parts = xff.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isNotEmpty()) {
      val idx = parts.size - trustedProxyCount
      return if (idx in parts.indices) parts[idx] else parts.last()
    }
  }
  return call.request.local.remoteHost
}

private fun startSessionPurge(app: Application) {
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  val job =
    scope.launch {
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

@Serializable data class DeepHealthResponse(val status: String, val checks: DeepHealthChecks)

@Serializable
data class DeepHealthChecks(
  val database: Boolean,
  val fileStore: Boolean,
  val githubApp: Boolean,
  val llm: Boolean,
)
