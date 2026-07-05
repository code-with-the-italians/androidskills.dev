package dev.androidskills.api

import dev.androidskills.auth.requireAdmin
import dev.androidskills.github.GitHubAppClient
import dev.androidskills.storage.FileStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** Admin routes (spec §9 Admin). Non-admins get 404 via [requireAdmin]. */
fun Route.adminRoutes(fileStore: FileStore, githubApp: GitHubAppClient) {
  rateLimit(RateLimitName("admin")) {
    route("api/admin") {
      route("stats") { get { stats(call) } }
      route("queue") {
        get { listQueue(call) }
        get("{id}") { queueDetail(call) }
        post("{id}/decision") { queueDecision(call, fileStore, githubApp) }
      }
      route("skills") {
        get { listSkills(call) }
        patch("{id}") { patchSkill(call) }
        post("bulk") { bulkSkills(call) }
      }
      route("users") {
        get { listUsers(call) }
        get("export") { exportUsers(call) }
        patch("{id}") { patchUser(call) }
      }
      route("categories") {
        get { listCategories(call) }
        post { createCategory(call) }
        patch("{slug}") { renameCategory(call) }
        post("{slug}/merge") { mergeCategory(call) }
      }
      route("settings") {
        get { getSettings(call) }
        put { putSettings(call) }
      }
      route("audit") { get { listAudit(call) } }
    }
  }
}

private suspend fun stats(call: ApplicationCall) {
  call.requireAdmin()
  call.respond(AdminStatsQueries.get())
}

private suspend fun listQueue(call: ApplicationCall) {
  val principal = call.requireAdmin()
  val filter = call.request.queryParameters["filter"]
  val q = call.request.queryParameters["q"]
  call.respond(AdminQueueQueries.queue(filter, q))
}

private suspend fun queueDetail(call: ApplicationCall) {
  val principal = call.requireAdmin()
  val id = call.parameters["id"] ?: throw ApiBadRequestException("Missing submission id")
  val detail = AdminQueueQueries.detail(id) ?: throw ApiNotFoundException("Submission not found")
  call.respond(detail)
}

private suspend fun queueDecision(
  call: ApplicationCall,
  fileStore: FileStore,
  githubApp: GitHubAppClient,
) {
  val principal = call.requireAdmin()
  val id = call.parameters["id"] ?: throw ApiBadRequestException("Missing submission id")
  val request =
    runCatching { call.receive<AdminQueueQueries.DecisionRequest>() }
      .getOrElse { throw ApiValidationException(mapOf("body" to "invalid JSON")) }
  AdminQueueQueries.decision(principal, id, request, fileStore, githubApp)
  call.respond(HttpStatusCode.OK, mapOf("ok" to true))
}

private suspend fun listSkills(call: ApplicationCall) {
  val principal = call.requireAdmin()
  val filter = call.request.queryParameters["filter"]
  val search = call.request.queryParameters["q"]
  val sort = call.request.queryParameters["sort"]
  val page = call.request.queryParameters["page"]?.toIntOrNull()
  call.respond(AdminSkillQueries.list(filter, search, sort, page))
}

private suspend fun patchSkill(call: ApplicationCall) {
  val principal = call.requireAdmin()
  val id = call.parameters["id"] ?: throw ApiBadRequestException("Missing skill id")
  val patch =
    runCatching { call.receive<AdminSkillQueries.AdminSkillPatch>() }
      .getOrElse { throw ApiValidationException(mapOf("body" to "invalid JSON")) }
  AdminSkillQueries.patch(principal, id, patch)
  call.respond(HttpStatusCode.OK, mapOf("ok" to true))
}

private suspend fun bulkSkills(call: ApplicationCall) {
  val principal = call.requireAdmin()
  val request =
    runCatching { call.receive<AdminSkillQueries.BulkActionRequest>() }
      .getOrElse { throw ApiValidationException(mapOf("body" to "invalid JSON")) }
  call.respond(AdminSkillQueries.bulk(principal, request))
}

private suspend fun listUsers(call: ApplicationCall) {
  val principal = call.requireAdmin()
  val filter = call.request.queryParameters["filter"]
  val search = call.request.queryParameters["q"]
  val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
  call.respond(AdminUserQueries.list(filter, search, page))
}

private suspend fun exportUsers(call: ApplicationCall) {
  val principal = call.requireAdmin()
  val filter = call.request.queryParameters["filter"]
  val search = call.request.queryParameters["q"]
  val csv = AdminUserQueries.exportCsv(filter, search)
  call.response.headers.append("Content-Disposition", "attachment; filename=\"users.csv\"")
  call.respondText(csv, io.ktor.http.ContentType.Text.CSV, HttpStatusCode.OK)
}

private suspend fun patchUser(call: ApplicationCall) {
  val principal = call.requireAdmin()
  val id = call.parameters["id"] ?: throw ApiBadRequestException("Missing user id")
  val patch =
    runCatching { call.receive<AdminUserQueries.AdminUserPatch>() }
      .getOrElse { throw ApiValidationException(mapOf("body" to "invalid JSON")) }
  AdminUserQueries.patch(principal, id, patch)
  call.respond(HttpStatusCode.OK, mapOf("ok" to true))
}

private suspend fun listCategories(call: ApplicationCall) {
  call.requireAdmin()
  call.respond(AdminCategoryQueries.list())
}

private suspend fun createCategory(call: ApplicationCall) {
  val principal = call.requireAdmin()
  val params = call.request.queryParameters
  val slug = params["slug"] ?: throw ApiBadRequestException("Missing slug")
  val name = params["name"] ?: throw ApiBadRequestException("Missing name")
  call.respond(AdminCategoryQueries.create(principal, slug, name))
}

private suspend fun renameCategory(call: ApplicationCall) {
  val principal = call.requireAdmin()
  val slug = call.parameters["slug"] ?: throw ApiBadRequestException("Missing slug")
  val request =
    runCatching { call.receive<AdminCategoryQueries.RenameRequest>() }
      .getOrElse { throw ApiValidationException(mapOf("body" to "invalid JSON")) }
  call.respond(AdminCategoryQueries.rename(principal, slug, request))
}

private suspend fun mergeCategory(call: ApplicationCall) {
  val principal = call.requireAdmin()
  val from = call.parameters["slug"] ?: throw ApiBadRequestException("Missing slug")
  val to = call.request.queryParameters["to"] ?: throw ApiBadRequestException("Missing 'to' query")
  AdminCategoryQueries.merge(principal, from, to)
  call.respond(HttpStatusCode.OK, mapOf("ok" to true))
}

private suspend fun getSettings(call: ApplicationCall) {
  call.requireAdmin()
  call.respond(AdminSettingsQueries.get())
}

private suspend fun putSettings(call: ApplicationCall) {
  val principal = call.requireAdmin()
  val settings =
    runCatching { call.receive<AdminSettingsQueries.PlatformSettings>() }
      .getOrElse { throw ApiValidationException(mapOf("body" to "invalid JSON")) }
  call.respond(AdminSettingsQueries.put(principal, settings))
}

private suspend fun listAudit(call: ApplicationCall) {
  call.requireAdmin()
  val action = call.request.queryParameters["action"]
  val target = call.request.queryParameters["target"]
  val page = call.request.queryParameters["page"]?.toIntOrNull()
  call.respond(AuditLogQueries.list(action, target, page))
}
