package dev.androidskills.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.response.respond
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * Unified error envelope (spec §9): `{"error":{"code","message", optional "fields"}}`.
 *
 * Handlers and queries throw the typed exceptions below; [installApiErrorMapping]
 * (a StatusPages plugin) maps them to the right status. Non-admin access to
 * `/api/admin/...` routes will reuse [ApiNotFoundException] to return 404 (not 403),
 * never revealing the route exists (spec §7).
 */
@Serializable
data class ErrorBody(
    val code: String,
    val message: String,
    @SerialName("fields") val fields: Map<String, String>? = null,
)

@Serializable
data class ErrorResponse(val error: ErrorBody)

/** 404 — unknown slug, unknown route for non-admins, missing resource. */
class ApiNotFoundException(message: String = "Not found") : RuntimeException(message)

/** 422 — per-field validation failure (spec §10). */
class ApiValidationException(
    val fields: Map<String, String>,
    message: String = "Validation failed",
) : RuntimeException(message)

/** 409 — duplicate slug, first-come ownership conflict. */
class ApiConflictException(message: String, val code: String = "conflict") : RuntimeException(message)

/** 500 — a DB-referenced FileStore object is missing (storage corruption). */
class ApiStorageException(message: String) : RuntimeException(message)

fun Application.installApiErrorMapping() {
    install(StatusPages) {
        val log = LoggerFactory.getLogger("dev.androidskills.api.Errors")
        exception<ApiNotFoundException> { call, ex ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse(ErrorBody("not_found", ex.message ?: "Not found")))
        }
        exception<ApiValidationException> { call, ex ->
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ErrorResponse(ErrorBody("validation_failed", ex.message ?: "Validation failed", ex.fields)),
            )
        }
        exception<ApiConflictException> { call, ex ->
            call.respond(HttpStatusCode.Conflict, ErrorResponse(ErrorBody(ex.code, ex.message ?: "Conflict")))
        }
        exception<ApiStorageException> { call, ex ->
            log.error("Storage consistency error serving ${call.request.local.uri}", ex)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(ErrorBody("storage_error", ex.message ?: "File unavailable")),
            )
        }
        exception<Throwable> { call, ex ->
            log.error("Unhandled error serving ${call.request.local.uri}", ex)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(ErrorBody("internal", "Internal server error")),
            )
        }
    }
}
