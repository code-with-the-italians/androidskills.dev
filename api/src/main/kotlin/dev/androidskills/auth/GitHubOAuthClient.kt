package dev.androidskills.auth

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Real GitHub OAuth client over a Ktor CIO HTTP client. Talks to the GitHub
 * authorize, access-token, and user endpoints. Constructed only when OAuth
 * creds are present (spec §13); otherwise the app wires [DisabledOAuthClient].
 *
 * The [HttpClient] is owned by the caller (Application) and shared/closed with
 * the app lifecycle — clients are expensive to create and meant to be long-lived.
 */
class GitHubOAuthClient(
    private val clientId: String,
    private val clientSecret: String,
    private val http: HttpClient,
) : OAuthClient {

    override val configured: Boolean = true

    override fun authorizeUrl(state: String, redirectUri: String): String =
        URLBuilder().takeFrom("https://github.com/login/oauth/authorize").apply {
            parameters.append("client_id", clientId)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("scope", "read:user")
            parameters.append("state", state)
        }.buildString()

    override suspend fun exchange(code: String, redirectUri: String): OAuthTokens {
        val form = listOf(
            "client_id" to clientId,
            "client_secret" to clientSecret,
            "code" to code,
            "redirect_uri" to redirectUri,
        ).formUrlEncode()
        val resp: AccessTokenResponse = http.post("https://github.com/login/oauth/access_token") {
            header("Accept", "application/json")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(form)
        }.body()
        if (resp.error != null || resp.accessToken.isNullOrBlank()) {
            throw OAuthException("GitHub token exchange failed: ${resp.error ?: "no access_token"} (${resp.errorDescription ?: ""})")
        }
        return OAuthTokens(resp.accessToken, resp.tokenType ?: "bearer", resp.scope)
    }

    override suspend fun userInfo(accessToken: String): GitHubUser {
        val resp: GitHubUserResponse = http.get("https://api.github.com/user") {
            header("Accept", "application/vnd.github+json")
            header("Authorization", "Bearer $accessToken")
        }.body()
        if (resp.id <= 0L || resp.login.isBlank()) {
            throw OAuthException("GitHub user lookup returned no identity")
        }
        return GitHubUser(
            githubId = resp.id,
            handle = resp.login,
            name = resp.name,
            avatarUrl = resp.avatarUrl,
        )
    }

    companion object {
        /** A ready [HttpClient] configured for GitHub JSON responses. */
        fun httpClient(): HttpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(dev.androidskills.util.appJson)
            }
            expectSuccess = true
        }
    }
}

@Serializable
private data class AccessTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    val scope: String? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

@Serializable
private data class GitHubUserResponse(
    val id: Long,
    val login: String,
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)
