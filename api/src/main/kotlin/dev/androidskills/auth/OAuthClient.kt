package dev.androidskills.auth

/**
 * GitHub OAuth (spec §7, §8: "OAuth via the GitHub App's user flow"). Kept behind
 * an interface so step 3 runs and tests with zero cloud setup — tests inject a
 * fake, prod injects [GitHubOAuthClient], and when creds are absent the routes
 * get [DisabledOAuthClient] (`configured = false`).
 */
interface OAuthClient {
    val configured: Boolean

    /** The GitHub authorize URL to redirect the browser to (with [state] echoed back). */
    fun authorizeUrl(state: String, redirectUri: String): String

    /** Exchanges the `code` GitHub redirected with for an access token. */
    suspend fun exchange(code: String, redirectUri: String): OAuthTokens

    /** Fetches the GitHub user identity behind an access token. */
    suspend fun userInfo(accessToken: String): GitHubUser
}

data class OAuthTokens(
    val accessToken: String,
    val tokenType: String = "bearer",
    val scope: String? = null,
)

/** Raised when GitHub returns an error or an unexpected payload. */
class OAuthException(message: String) : RuntimeException(message)

/** No creds configured (spec §13: "unset → auth disabled"). */
class DisabledOAuthClient : OAuthClient {
    override val configured: Boolean = false
    override fun authorizeUrl(state: String, redirectUri: String): String =
        throw OAuthException("GitHub OAuth is not configured")
    override suspend fun exchange(code: String, redirectUri: String): OAuthTokens =
        throw OAuthException("GitHub OAuth is not configured")
    override suspend fun userInfo(accessToken: String): GitHubUser =
        throw OAuthException("GitHub OAuth is not configured")
}
