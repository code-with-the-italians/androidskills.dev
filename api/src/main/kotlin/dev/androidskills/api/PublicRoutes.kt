package dev.androidskills.api

import dev.androidskills.storage.FileStore
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** Public read routes (spec §9 Public). No auth; anonymous browsing. */
fun Route.publicRoutes(fileStore: FileStore) {
    val q = PublicQueries

    route("api") {
        get("stats") { call.respond(q.stats()) }

        get("skills") { call.respond(q.search(parseSearchParams(call))) }
        get("skills/{slug}") { call.respond(q.skillDetail(call.parameters["slug"]!!)) }
        get("skills/{slug}/files") { call.respond(q.fileTree(call.parameters["slug"]!!)) }
        get("skills/{slug}/files/{path...}") {
            val slug = call.parameters["slug"]!!
            val path = call.parameters["path"]!!
            val raw = call.request.queryParameters["raw"] == "1"
            val res = q.fileContent(slug, path, fileStore)
            if (raw && !res.downloadOnly) {
                call.respondText(res.content ?: "", contentType = guessTextContentType(res.path))
            } else {
                call.respond(
                    FileContentResponse(
                        slug = res.slug, path = res.path, size = res.size, isBinary = res.isBinary,
                        content = res.content, downloadOnly = res.downloadOnly, downloadUrl = null,
                    ),
                )
            }
        }
        get("skills/{slug}/versions") { call.respond(q.versions(call.parameters["slug"]!!)) }
        get("skills/{slug}/download") {
            val slug = call.parameters["slug"]!!
            val version = call.request.queryParameters["version"]
            val res = q.download(slug, version, fileStore)
            call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"${res.filename}\"")
            call.respondBytes(res.bytes, contentType = ContentType.parse(res.contentType))
        }

        get("categories") { call.respond(q.categories()) }
        get("trends") { call.respond(q.trends()) }
        get("timeline") {
            val page = PublicQueries.parsePage(call.request.queryParameters["page"])
            val size = PublicQueries.parsePageSize(call.request.queryParameters["pageSize"])
            call.respond(q.timeline(page, size))
        }
        get("bundles") {
            val page = PublicQueries.parsePage(call.request.queryParameters["page"])
            val size = PublicQueries.parsePageSize(call.request.queryParameters["pageSize"])
            call.respond(q.bundles(page, size))
        }
        get("bundles/{id}") { call.respond(q.bundle(call.parameters["id"]!!)) }
        get("authors/{handle}") { call.respond(q.author(call.parameters["handle"]!!)) }

        post("skills/{slug}/report") {
            val slug = call.parameters["slug"]!!
            val body = runCatching { call.receive<ReportRequest>() }.getOrDefault(ReportRequest())
            val result = q.createReport(slug, body.reason, reporterId = null) // anonymous by default
            call.respond(HttpStatusCode.Created, result)
        }
    }
}

private fun parseSearchParams(call: ApplicationCall): SearchParams {
    val qp = call.request.queryParameters
    fun multi(key: String): List<String> = (qp.getAll(key) ?: emptyList()).ifEmpty { qp.getAll("${key}[]") ?: emptyList() }
        .filter { it.isNotBlank() }
    val verified = when ((qp["verified"] ?: "true").lowercase()) {
        "false", "0", "no" -> false
        else -> true
    }
    return SearchParams(
        q = qp["q"]?.trim()?.takeIf { it.isNotEmpty() },
        cats = multi("cat"),
        tags = multi("tag"),
        size = qp["size"]?.takeIf { it in setOf("<2k", "2-5k", "5k+") },
        verified = verified,
        sort = qp["sort"]?.takeIf { it in setOf("relevance", "installs", "updated", "tokens") } ?: "relevance",
        page = PublicQueries.parsePage(qp["page"]),
        pageSize = PublicQueries.parsePageSize(qp["pageSize"]),
    )
}

private fun guessTextContentType(path: String): ContentType =
    when (path.substringAfterLast('.', "").lowercase()) {
        "json" -> ContentType.Application.Json
        "html", "htm" -> ContentType.Text.Html
        "css" -> ContentType.Text.CSS
        else -> ContentType.Text.Plain
    }
