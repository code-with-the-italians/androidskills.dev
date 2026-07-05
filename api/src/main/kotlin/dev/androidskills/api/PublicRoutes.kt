package dev.androidskills.api

import dev.androidskills.storage.FileStore
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
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

  rateLimit(RateLimitName("public")) {
    route("api") {
      get("stats") { call.respond(q.stats()) }

      get("skills") { call.respond(q.search(parseSearchParams(call))) }
      get("skills/{slug}") { call.respond(q.skillDetail(call.parameters["slug"]!!)) }
      get("skills/{slug}/files") { call.respond(q.fileTree(call.parameters["slug"]!!)) }
      get("skills/{slug}/files/{path...}") {
        val slug = call.parameters["slug"]!!
        // {path...} is a tailcard: read every captured segment and rejoin so
        // nested paths like references/guide.md survive the DB path match.
        val path = call.parameters.getAll("path")?.joinToString("/").orEmpty()
        val raw = call.request.queryParameters["raw"] == "1"
        val res = q.fileContent(slug, path, fileStore)
        if (raw && !res.downloadOnly) {
          // Raw preview is ALWAYS text/plain (+ nosniff): a published skill
          // can include an .html file, and serving it as text/html would give
          // it same-origin script execution — catastrophic once auth/admin
          // routes share this origin. Source files render fine as text.
          call.response.headers.append("X-Content-Type-Options", "nosniff")
          call.respondText(
            res.content ?: "",
            contentType = ContentType.Text.Plain.withCharset(Charsets.UTF_8),
          )
        } else {
          call.respond(
            FileContentResponse(
              slug = res.slug,
              path = res.path,
              size = res.size,
              isBinary = res.isBinary,
              content = res.content,
              downloadOnly = res.downloadOnly,
              downloadUrl = null,
            )
          )
        }
      }
      get("skills/{slug}/versions") { call.respond(q.versions(call.parameters["slug"]!!)) }
      get("skills/{slug}/download") {
        val slug = call.parameters["slug"]!!
        val version = call.request.queryParameters["version"]
        val res = q.download(slug, version, fileStore)
        call.response.headers.append(
          HttpHeaders.ContentDisposition,
          "attachment; filename=\"${res.filename}\"",
        )
        call.respondBytes(res.bytes, contentType = ContentType.parse(res.contentType))
      }

      get("categories") { call.respond(q.categories()) }
      get("trends") { call.respond(q.trends()) }
      get("timeline") {
        val page = PublicQueries.parsePageStrict(call.request.queryParameters["page"])
        val size = PublicQueries.parsePageSizeStrict(call.request.queryParameters["pageSize"])
        call.respond(q.timeline(page, size))
      }
      get("bundles") {
        val page = PublicQueries.parsePageStrict(call.request.queryParameters["page"])
        val size = PublicQueries.parsePageSizeStrict(call.request.queryParameters["pageSize"])
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
}

private fun parseSearchParams(call: ApplicationCall): SearchParams {
  val qp = call.request.queryParameters
  fun multi(key: String): List<String> =
    (qp.getAll(key) ?: emptyList())
      .ifEmpty { qp.getAll("${key}[]") ?: emptyList() }
      .filter { it.isNotBlank() }

  val verified =
    when (qp["verified"]?.lowercase()) {
      null,
      "true",
      "1",
      "yes" -> true
      "false",
      "0",
      "no" -> false
      else ->
        throw ApiValidationException(
          mapOf("verified" to "must be one of true|false"),
          "Invalid 'verified'",
        )
    }

  val sizeRaw = qp["size"]
  val size =
    when {
      sizeRaw == null -> null
      sizeRaw in SIZE_FILTERS -> sizeRaw
      else ->
        throw ApiValidationException(
          mapOf("size" to "must be one of <2k|2-5k|5k+"),
          "Invalid 'size'",
        )
    }

  val sortRaw = qp["sort"]
  val sort =
    when {
      sortRaw == null -> "relevance"
      sortRaw in SORTS -> sortRaw
      else ->
        throw ApiValidationException(
          mapOf("sort" to "must be one of relevance|installs|updated|tokens"),
          "Invalid 'sort'",
        )
    }

  return SearchParams(
    q = qp["q"]?.trim()?.takeIf { it.isNotEmpty() },
    cats = multi("cat"),
    tags = multi("tag"),
    size = size,
    verified = verified,
    sort = sort,
    page = PublicQueries.parsePageStrict(qp["page"]),
    pageSize = PublicQueries.parsePageSizeStrict(qp["pageSize"]),
  )
}

private val SIZE_FILTERS = setOf("<2k", "2-5k", "5k+")
private val SORTS = setOf("relevance", "installs", "updated", "tokens")
