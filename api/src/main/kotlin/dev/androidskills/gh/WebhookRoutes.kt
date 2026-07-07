package dev.androidskills.gh

import dev.androidskills.db.Bundles
import dev.androidskills.db.Jobs
import dev.androidskills.github.GitHubAppClient
import dev.androidskills.github.GitHubAppException
import dev.androidskills.github.GithubWebhookEvent
import dev.androidskills.util.appJson
import dev.androidskills.util.newId
import dev.androidskills.util.nowIso
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

/**
 * Webhook route (spec §8). Receives GitHub App events at the webhook path.
 *
 * **Signature handling (gotcha D):** bad or missing signature → 401 (GitHub's convention; the URL
 * isn't secret and 200-everything suppresses GitHub's redelivery and blinds the delivery panel).
 * Accepted → 202. The anti-enumeration framing used for admin routes (404-not-403) doesn't apply
 * here — the URL is configured in the App settings and an attacker can't forge the HMAC anyway.
 *
 * **Push → resync enqueue** with (bundle_id, head_sha) idempotency (Q5): GitHub can redeliver the
 * same push under different delivery IDs, so dedupe on the payload key, not the delivery id.
 * Single-writer SQLite makes check-then- insert safe. Resync execution is step 5 — step 4 only
 * enqueues.
 *
 * **installation / installation_repositories:** record the installation id on matching bundles for
 * access tracking (full access modelling is step 6).
 */
private val logger = LoggerFactory.getLogger("dev.androidskills.gh.WebhookRoutes")

fun Route.webhookRoutes(githubApp: GitHubAppClient) {
  post("/gh/webhooks") { handle(call, githubApp) }
}

private suspend fun handle(call: ApplicationCall, gh: GitHubAppClient) {
  val body = call.receiveStream().readBytes() // raw bytes — HMAC over exactly what GitHub sent
  val signature = call.request.headers["X-Hub-Signature-256"].orEmpty()

  val event =
    try {
      gh.verifyAndParseEvent(body, signature)
    } catch (e: GitHubAppException) {
      // Bad/missing signature (or unconfigured secret). GitHub's convention is 401 so a
      // misconfigured secret surfaces in the delivery panel; the URL isn't secret (gotcha D).
      call.respond(
        HttpStatusCode.Unauthorized,
        mapOf(
          "error" to
            mapOf("code" to "invalid_signature", "message" to "Invalid or missing signature")
        ),
      )
      return
    }
  // null = verified payload of an event type we don't act on (e.g. `ping`). Acknowledge it.
  if (event == null) {
    call.respond(HttpStatusCode.Accepted, mapOf("ok" to true))
    return
  }

  when (event) {
    is GithubWebhookEvent.Push -> {
      // Q2b: don't enqueue resync when the App client isn't configured — otherwise
      // every push to a tracked repo piles up guaranteed-fail jobs + noisy WARN logs.
      if (gh.configured && event.isDefaultBranch) enqueueResync(event)
    }
    is GithubWebhookEvent.InstallationAccess -> trackInstallation(event)
  }
  call.respond(HttpStatusCode.Accepted, mapOf("ok" to true))
}

/** Enqueue a resync job for the pushed repo, idempotent on (bundle, head_sha). */
private fun enqueueResync(push: GithubWebhookEvent.Push) = transaction {
  val provenance = "${push.repoOwner}/${push.repoName}"
  val bundle =
    Bundles.selectAll()
      .where { (Bundles.kind eq "repo") and (Bundles.provenance eq provenance) }
      .singleOrNull()
  if (bundle == null) {
    // Repo isn't tracked (never submitted). Don't enqueue — nothing to resync.
    return@transaction
  }
  val bundleId = bundle[Bundles.id]
  // Idempotency (Q5): GitHub may redeliver under different delivery IDs; dedupe
  // on the payload's (bundle, after) key. A queued/running job for the same head
  // means a resync is already in flight.
  val payload =
    appJson.encodeToString(ResyncPayload.serializer(), ResyncPayload(bundleId, push.after))
  val alreadyQueued =
    Jobs.selectAll()
      .where {
        (Jobs.type eq "resync") and
          (Jobs.payload eq payload) and
          (Jobs.state inList listOf("queued", "running"))
      }
      .any()
  if (alreadyQueued) return@transaction
  val now = nowIso()
  Jobs.insert {
    it[Jobs.id] = newId()
    it[Jobs.type] = "resync"
    it[Jobs.payload] = payload
    it[Jobs.state] = "queued"
    it[Jobs.attempts] = 0
    it[Jobs.runAfter] = now
    it[Jobs.createdAt] = now
    it[Jobs.updatedAt] = now
  }
  logger.info("enqueued resync for bundle={} sha={}", bundleId, push.after.take(7))
}

/** Record the installation id on bundles owned by the installation's account. */
private fun trackInstallation(ev: GithubWebhookEvent.InstallationAccess) = transaction {
  // Lightweight step-4 tracking: tag repo bundles for this account with the
  // installation id so future scans/webhooks resolve to it. Full access modelling
  // (org membership, repo add/remove diffs) is step 6.
  Bundles.update({ Bundles.provenance like "${ev.accountLogin}/%" }) {
    it[Bundles.installationId] = ev.installationId
  }
  Unit
}

@kotlinx.serialization.Serializable
private data class ResyncPayload(val bundleId: String, val headSha: String)
