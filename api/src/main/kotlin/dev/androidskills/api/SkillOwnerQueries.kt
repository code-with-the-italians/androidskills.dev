package dev.androidskills.api

import dev.androidskills.auth.Principal
import dev.androidskills.db.Bundles
import dev.androidskills.db.Jobs
import dev.androidskills.db.SkillStatus
import dev.androidskills.db.Skills
import dev.androidskills.util.appJson
import dev.androidskills.util.newId
import dev.androidskills.util.nowIso
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Owner-only actions on a published/unlisted skill (spec §9 Contributor). Non-owners get 403
 * because `/api/skills/{slug}` is already a public route; a missing slug returns 404.
 */
object SkillOwnerQueries {

  /** Returns the skill's bundle id if [principal] owns it; null if the skill does not exist. */
  fun ownedBundleId(principal: Principal, slug: String): String? = transaction {
    val skill =
      Skills.selectAll().where { Skills.slug eq slug }.singleOrNull() ?: return@transaction null
    val bundle = Bundles.selectAll().where { Bundles.id eq skill[Skills.bundleId] }.singleOrNull()
    bundle?.get(Bundles.id)?.takeIf { bundle[Bundles.ownerUserId] == principal.userId }
  }

  fun unpublish(principal: Principal, slug: String) {
    transaction {
      val bundleId = ownedBundleId(principal, slug)
      if (bundleId == null) {
        // Distinguish "slug doesn't exist" (404) from "exists but not owner" (403).
        val exists = Skills.selectAll().where { Skills.slug eq slug }.any()
        if (exists) throw ApiForbiddenException("Not the skill owner")
        throw ApiNotFoundException("Skill not found")
      }
      Skills.update({ Skills.slug eq slug }) {
        it[Skills.status] = SkillStatus.unlisted.name
        it[Skills.verified] = false
        it[Skills.updatedAt] = nowIso()
      }
    }
  }

  /**
   * Enqueues a resync job for an owned skill. [headSha] is the current HEAD of the bundle's repo
   * (fetched by the route via GitHubAppClient).
   */
  fun enqueueResync(principal: Principal, slug: String, headSha: String) {
    transaction {
      val bundleId = ownedBundleId(principal, slug)
      if (bundleId == null) {
        val exists = Skills.selectAll().where { Skills.slug eq slug }.any()
        if (exists) throw ApiForbiddenException("Not the skill owner")
        throw ApiNotFoundException("Skill not found")
      }
      val now = nowIso()
      val payload =
        appJson.encodeToString(ResyncPayload.serializer(), ResyncPayload(bundleId, headSha))
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
    }
  }

  @Serializable private data class ResyncPayload(val bundleId: String, val headSha: String)
}
