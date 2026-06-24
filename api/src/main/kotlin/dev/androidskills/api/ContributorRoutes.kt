package dev.androidskills.api

import dev.androidskills.github.GitHubAppClient
import dev.androidskills.github.GitHubAppException
import dev.androidskills.ingest.ArchiveSource
import dev.androidskills.ingest.DetectedSkill
import dev.androidskills.ingest.Discovery
import dev.androidskills.ingest.ScanResult
import dev.androidskills.auth.requireSession
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * Contributor routes (spec §9): repo list + on-demand scan.
 *
 * Both are `S` (session required). The GitHub-App features report disabled (503
 * `github_app_disabled`) when the App creds are absent (§13), mirroring auth.
 *
 * **Step-4 scope (Q3, gotcha #2):** `/api/me/repos` lists **personal-account**
 * installations only (`installation.accountId == principal.githubId`).
 * Org-membership filtering needs a signal the App JWT can't provide (the user
 * OAuth token, which step 3 doesn't persist, or an installation token with
 * `members:read`) — deferred to step 6 where org-ownership actually matters.
 */
fun Route.contributorRoutes(githubApp: GitHubAppClient) {
    route("api/me") {
        get("repos") { repos(call, githubApp) }
        post("repos/{owner}/{repo}/scan") { scan(call, githubApp) }
    }
}

private suspend fun repos(call: ApplicationCall, gh: GitHubAppClient) {
    if (!gh.configured) {
        call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(ErrorBody("github_app_disabled", "GitHub App is not configured")))
        return
    }
    val principal = call.requireSession()
    // Personal installations only (Q3). Org installs arrive in step 6.
    val mine = try {
        gh.installations().filter { it.accountId == principal.githubId }
    } catch (e: GitHubAppException) {
        throw ApiBadGatewayException("GitHub installations request failed: ${e.message}", "github_installations_failed")
    }
    val repos = mine.flatMap { inst ->
        try { gh.listRepos(inst.id) } catch (e: GitHubAppException) { throw ApiBadGatewayException("GitHub list-repos failed: ${e.message}", "github_list_repos_failed") }
    }
    call.respond(ReposResponse(repos.map { it.toDto() }))
}

private suspend fun scan(call: ApplicationCall, gh: GitHubAppClient) {
    if (!gh.configured) {
        call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(ErrorBody("github_app_disabled", "GitHub App is not configured")))
        return
    }
    val principal = call.requireSession() // gates access; ownership of the repo is checked at submit (step 6)
    val owner = call.parameters["owner"] ?: throw ApiBadRequestException("Missing owner")
    val repo = call.parameters["repo"] ?: throw ApiBadRequestException("Missing repo")

    val installationId = try {
        gh.installations().firstOrNull { it.accountId == principal.githubId }?.id
            ?: throw ApiNotFoundException("No installation for user '${principal.handle}'")
    } catch (e: GitHubAppException) {
        throw ApiBadGatewayException("GitHub installations request failed: ${e.message}", "github_installations_failed")
    }
    val head = try { gh.defaultBranchHead(installationId, owner, repo) }
        catch (e: GitHubAppException) { throw ApiBadGatewayException("GitHub ref lookup failed: ${e.message}", "github_ref_failed") }
    val zipball = try { gh.downloadZipball(installationId, owner, repo, head) }
        catch (e: GitHubAppException) { throw ApiBadGatewayException("GitHub archive download failed: ${e.message}", "github_archive_failed") }

    val result = Discovery.discover(ArchiveSource.RepoZipball(zipball, head))
    val response = when (result) {
        is ScanResult.Found -> ScanResponse(slug = "$owner/$repo", commitSha = head, skills = result.skills.map { it.toDto() })
        ScanResult.NoSkillsDir -> throw ApiValidationException(
            mapOf("repo" to "no top-level 'skills/' directory found; SKILL.md must live under skills/"),
            "no_skills_dir",
        )
    }
    call.respond(response)
}

@Serializable data class RepoDto(val owner: String, val name: String, val fullName: String, val defaultBranch: String?)
@Serializable data class ReposResponse(val repos: List<RepoDto>)
@Serializable data class DetectedSkillDto(
    val slug: String, val name: String, val description: String, val license: String?,
    val tags: List<String>, val version: String, val versionSource: String,
    val tokenUpfront: Int, val tokenOndemand: Int, val tokenBand: String,
    val parseErrors: List<DetectedFieldError>, val fileCount: Int,
)
@Serializable data class DetectedFieldError(val field: String, val reason: String)
@Serializable data class ScanResponse(val slug: String, val commitSha: String, val skills: List<DetectedSkillDto>)

private fun dev.androidskills.github.RepoRef.toDto() = RepoDto(owner, name, fullName, defaultBranch)
private fun DetectedSkill.toDto() = DetectedSkillDto(
    slug, name, description, license, tags, version, versionSource,
    tokenUpfront, tokenOndemand, tokenBand,
    parseErrors.map { DetectedFieldError(it.field, it.reason) }, fileCount,
)
