package dev.androidskills.github

/**
 * GitHub App client (spec §8): installation enumeration, repo listing, archive
 * download, and webhook-event verification. Kept behind an interface so step 4
 * runs and tests with zero cloud setup — tests inject a fake, prod injects
 * [RealGitHubAppClient] (built from `GITHUB_APP_*` env), and when the App is
 * unconfigured the routes get [DisabledGitHubAppClient] (`configured = false`).
 *
 * **Scope of step 4 (review issue B):** the client supports the *read* surface
 * (list repos, scan a repo's default branch). The ingest write path (mirror,
 * upsert, enqueue review) is deferred to step 5; this client only fetches the
 * archive bytes that `ingest/Discovery.discover()` parses read-only.
 *
 * **Deviation A (review):** `downloadZipball` fetches GitHub's `/zipball/{ref}` —
 * JDK-native `java.util.zip` extraction — not `/tarball/{ref}`. The spec §5.1
 * says "tarball" meaning "the archive"; zip avoids a commons-compress dep and
 * unifies extraction with the uploaded-zip path. The compressed body is
 * size-bounded by the caller (see `Discovery`).
 */
interface GitHubAppClient {
    val configured: Boolean

    /** The App's installations (`GET /app/installations`, App-JWT auth). */
    suspend fun installations(): List<Installation>

    /** Repos accessible to an installation (`GET /installation/repositories`). */
    suspend fun listRepos(installationId: Long): List<RepoRef>

    /** The commit sha of [owner]/[repo]'s default branch HEAD. */
    suspend fun defaultBranchHead(installationId: Long, owner: String, repo: String): String

    /**
     * The repo archive at [ref] as a zipball (`GET /repos/{o}/{r}/zipball/{ref}`).
     * Bounded compressed-body read — see `Discovery` step 1.
     */
    suspend fun downloadZipball(installationId: Long, owner: String, repo: String, ref: String): ByteArray

    /**
     * Verifies the `X-Hub-Signature-256` HMAC over [body] with the configured
     * webhook secret (constant-time), then parses the event.
     *
     * Returns the event for types step 4 acts on (`push`, `installation`*, …);
     * returns **null for a verified payload of an unhandled type** (e.g. `ping`) —
     * the caller acknowledges it (202), since the signature was valid.
     *
     * **Throws [GitHubAppException] on a bad/missing signature** — that's distinct
     * from an unhandled type, and the caller returns 401 so a misconfigured secret
     * surfaces in GitHub's delivery panel.
     */
    @Throws(GitHubAppException::class)
    suspend fun verifyAndParseEvent(body: ByteArray, signature: String): GithubWebhookEvent?
}

/** A GitHub App installation. `accountType` is "User" or "Organization". */
data class Installation(
    val id: Long,
    val accountId: Long,
    val accountLogin: String,
    val accountType: String,
)

data class RepoRef(
    val owner: String,
    val name: String,
    val fullName: String,
    val defaultBranch: String?,
)

/** Webhook events step 4 acts on. `resync` execution is step 5; step 4 only enqueues. */
sealed interface GithubWebhookEvent {
    /** `push` to a tracked repo's default branch. `before` is ignored; [after] is the new HEAD. */
    data class Push(val repoOwner: String, val repoName: String, val after: String, val isDefaultBranch: Boolean) : GithubWebhookEvent
    /** `installation` / `installation_repositories` access changes (lightweight tracking in step 4). */
    data class InstallationAccess(val installationId: Long, val accountLogin: String, val action: String) : GithubWebhookEvent
}

/** Raised by the GitHub client on auth/transport/payload failures (translated in the impl). */
class GitHubAppException(message: String) : RuntimeException(message)

/** No App creds configured (spec §13: "unset → disabled"). */
class DisabledGitHubAppClient : GitHubAppClient {
    override val configured: Boolean = false
    override suspend fun installations(): List<Installation> = throw disabled()
    override suspend fun listRepos(installationId: Long): List<RepoRef> = throw disabled()
    override suspend fun defaultBranchHead(installationId: Long, owner: String, repo: String): String = throw disabled()
    override suspend fun downloadZipball(installationId: Long, owner: String, repo: String, ref: String): ByteArray = throw disabled()
    // An unconfigured App can't verify anything — treat as a signature failure (401).
    override suspend fun verifyAndParseEvent(body: ByteArray, signature: String): GithubWebhookEvent? = throw disabled()
    private fun disabled() = GitHubAppException("GitHub App is not configured")
}
