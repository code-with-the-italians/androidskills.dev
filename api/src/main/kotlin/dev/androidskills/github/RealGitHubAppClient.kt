package dev.androidskills.github

import dev.androidskills.util.appJson
import dev.androidskills.util.constantTimeEquals
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Real GitHub App client (spec §8). Talks to `api.github.com` with an RS256 JWT (App-auth) and
 * installation tokens (repo-scoped). Installation tokens are cached with expiry (1h GitHub-side) so
 * we don't fetch one per API call.
 *
 * Constructed only when `GITHUB_APP_ID` + `GITHUB_APP_PRIVATE_KEY` + `GITHUB_WEBHOOK_SECRET` are
 * all present (spec §13). Otherwise the app wires [DisabledGitHubAppClient] and the routes report
 * disabled.
 *
 * **Crypto flags (step-5 review):** JWT `iat` = `now - 60s` (GitHub rejects future-dated `iat`);
 * HMAC verified via [constantTimeEquals] (timing-safe); installation tokens cached with their
 * `expires_at`.
 */
class RealGitHubAppClient(
  private val appId: Long,
  private val privateKeyPem: String,
  private val webhookSecret: String,
  private val http: HttpClient,
) : GitHubAppClient {

  override val configured: Boolean = true

  // ---- installation-token cache ----

  private data class CachedToken(val token: String, val expiresAt: Instant)

  private val tokenCache = mutableMapOf<Long, CachedToken>()
  private val cacheMutex = Mutex()

  private val githubApiBase = "https://api.github.com"

  private suspend fun installToken(installationId: Long): String =
    cacheMutex.withLock {
      val cached = tokenCache[installationId]
      if (cached != null && cached.expiresAt.isAfter(Instant.now().plusSeconds(60))) {
        return@withLock cached.token // still valid with a 60s margin
      }
      val jwt = AppJwt.build(appId, privateKeyPem)
      val resp: TokenResponse = ghCall {
        http
          .post("$githubApiBase/app/installations/$installationId/access_tokens") {
            bearerAuth(jwt)
            header("Accept", "application/vnd.github+json")
          }
          .body()
      }
      val expiresAt =
        runCatching { Instant.parse(resp.expiresAt) }.getOrDefault(Instant.now().plusSeconds(3300))
      val token = resp.token
      tokenCache[installationId] = CachedToken(token, expiresAt)
      token
    }

  // ---- interface impls ----

  override suspend fun installations(): List<Installation> {
    val jwt = AppJwt.build(appId, privateKeyPem)
    val resp: InstallationsResponse = ghCall {
      http
        .get("$githubApiBase/app/installations") {
          bearerAuth(jwt)
          header("Accept", "application/vnd.github+json")
        }
        .body()
    }
    return resp.installations.map {
      Installation(
        id = it.id,
        accountId = it.account.id,
        accountLogin = it.account.login,
        accountType = it.account.type,
      )
    }
  }

  override suspend fun listRepos(installationId: Long): List<RepoRef> {
    val token = installToken(installationId)
    val resp: ReposResponse = ghCall {
      http
        .get("$githubApiBase/installation/repositories") {
          bearerAuth(token)
          header("Accept", "application/vnd.github+json")
        }
        .body()
    }
    return resp.repositories.map {
      RepoRef(
        owner = it.owner.login,
        name = it.name,
        fullName = it.fullName,
        defaultBranch = it.defaultBranch,
      )
    }
  }

  override suspend fun defaultBranchHead(
    installationId: Long,
    owner: String,
    repo: String,
  ): String {
    val token = installToken(installationId)
    val resp: RepoResponse = ghCall {
      http
        .get("$githubApiBase/repos/$owner/$repo") {
          bearerAuth(token)
          header("Accept", "application/vnd.github+json")
        }
        .body()
    }
    return resp.defaultBranch ?: "main"
  }

  override suspend fun downloadZipball(
    installationId: Long,
    owner: String,
    repo: String,
    ref: String,
  ): ByteArray {
    val token = installToken(installationId)
    return ghCall {
      val resp =
        http.get("$githubApiBase/repos/$owner/$repo/zipball/$ref") {
          bearerAuth(token)
          header("Accept", "application/vnd.github+json")
        }
      if (resp.status != HttpStatusCode.OK) {
        throw GitHubAppException("zipball download failed: HTTP ${resp.status.value}")
      }
      val contentLength = resp.headers["Content-Length"]?.toLongOrNull()
      if (contentLength != null && contentLength > MAX_COMPRESSED_ZIPBALL) {
        throw GitHubAppException(
          "zipball exceeds ${MAX_COMPRESSED_ZIPBALL / (1024 * 1024)} MB compressed"
        )
      }
      val raw = resp.body<ByteArray>()
      if (raw.size > MAX_COMPRESSED_ZIPBALL) {
        throw GitHubAppException(
          "zipball exceeds ${MAX_COMPRESSED_ZIPBALL / (1024 * 1024)} MB compressed"
        )
      }
      raw
    }
  }

  override suspend fun verifyAndParseEvent(
    body: ByteArray,
    signature: String,
  ): GithubWebhookEvent? {
    // HMAC-SHA256 over the raw body with the webhook secret.
    val expected = "sha256=" + hex(hmacSha256(body, webhookSecret.toByteArray(Charsets.UTF_8)))
    if (!constantTimeEquals(expected, signature)) {
      throw GitHubAppException("webhook signature mismatch")
    }
    // Parse the event type from the JSON payload.
    val text = String(body, Charsets.UTF_8)
    val payload =
      runCatching { appJson.decodeFromString(WebhookPayload.serializer(), text) }
        .getOrElse {
          return null
        } // unparseable → treat as unhandled
    return when {
      payload.zen != null -> null // ping event
      payload.ref != null && payload.after != null && !payload.after!!.all { it == '0' } -> {
        // Push event: require ref + after, and after must not be all-zeros
        // (all-zeros = branch deleted, not a content push — Bugbot finding).
        val fullName = payload.repository?.fullName ?: return null
        val (owner, name) =
          fullName.split("/", limit = 2).let { if (it.size == 2) it[0] to it[1] else return null }
        val defaultBranch = payload.repository?.defaultBranch
        GithubWebhookEvent.Push(
          repoOwner = owner,
          repoName = name,
          after = payload.after,
          isDefaultBranch = payload.ref == "refs/heads/$defaultBranch",
        )
      }
      payload.installation != null -> {
        GithubWebhookEvent.InstallationAccess(
          installationId = payload.installation.id,
          accountLogin = payload.installation.account.login,
          action = payload.action ?: "unknown",
        )
      }
      else -> null // unhandled event type
    }
  }

  // ---- helpers ----

  /** Wraps a GitHub HTTP call: transport errors → GitHubAppException (cancellation preserved). */
  private suspend inline fun <T> ghCall(crossinline block: suspend () -> T): T =
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: GitHubAppException) {
      throw e
    } catch (e: Exception) {
      throw GitHubAppException("GitHub request failed: ${e.message ?: e.javaClass.simpleName}")
    }

  private fun hmacSha256(data: ByteArray, key: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run {
      init(SecretKeySpec(key, "HmacSHA256"))
      doFinal(data)
    }

  private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

  companion object {
    /** B5: compressed-body cap for downloadZipball. */
    const val MAX_COMPRESSED_ZIPBALL = 50 * 1024 * 1024

    fun httpClient(): HttpClient =
      HttpClient(CIO) {
        install(ContentNegotiation) { json(appJson) }
        expectSuccess = true
      }
  }
}

// ---- GitHub API response DTOs (snake_case via @SerialName) ----

@Serializable
private data class TokenResponse(
  val token: String,
  @SerialName("expires_at") val expiresAt: String,
)

@Serializable
private data class InstallationsResponse(val installations: List<InstallationDto> = emptyList())

@Serializable private data class InstallationDto(val id: Long, val account: AccountDto)

@Serializable private data class AccountDto(val id: Long, val login: String, val type: String)

@Serializable private data class ReposResponse(val repositories: List<RepoDto> = emptyList())

@Serializable
private data class RepoDto(
  val name: String,
  @SerialName("full_name") val fullName: String,
  @SerialName("default_branch") val defaultBranch: String? = null,
  val owner: OwnerDto,
)

@Serializable private data class OwnerDto(val login: String)

@Serializable
private data class RepoResponse(@SerialName("default_branch") val defaultBranch: String? = null)

@Serializable
private data class WebhookPayload(
  val zen: String? = null,
  val ref: String? = null,
  val after: String? = null,
  val repository: PushRepoDto? = null,
  val action: String? = null,
  val installation: InstallationDetailDto? = null,
)

@Serializable
private data class PushRepoDto(
  @SerialName("full_name") val fullName: String? = null,
  @SerialName("default_branch") val defaultBranch: String? = null,
)

@Serializable private data class InstallationDetailDto(val id: Long, val account: AccountDto)
