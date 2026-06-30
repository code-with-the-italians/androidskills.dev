package dev.androidskills.api

import dev.androidskills.auth.requireAdmin
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * Admin routes (spec §9 Admin). Non-admins get 404 via [requireAdmin].
 */
fun Route.adminRoutes() {
    route("api/admin") {
        route("queue") {
            get { listQueue(call) }
            get("{id}") { queueDetail(call) }
        }
    }
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
