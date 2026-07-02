package dev.androidskills.github

import java.security.Signature
import java.util.Base64

/**
 * Builds a GitHub App JWT (RS256) — the authentication token for App-API calls
 * (`GET /app/installations`, `POST /app/installations/{id}/access_tokens`).
 *
 * Per GitHub's docs: `iss` = App id, `iat` = now (**minus 60s** to absorb clock
 * skew — GitHub rejects future-dated `iat`), `exp` = +9 min (max 10). RS256-signed
 * with the App's RSA private key (loaded via [PemLoader]).
 *
 * Hand-rolled (~30 lines, no dep): header + claims → JSON → base64url → sign.
 * The JWT format is `{header}.{claims}.{signature}`, each part base64url-no-pad.
 */
internal object AppJwt {
    private val B64 = Base64.getUrlEncoder().withoutPadding()

    /** Builds + signs the JWT. [appId] is the numeric App id; [pem] is the PEM private key. */
    fun build(appId: Long, pem: String): String {
        val now = System.currentTimeMillis() / 1000
        val header = """{"alg":"RS256","typ":"JWT"}"""
        // iat = now - 60 (clock skew); exp = now + 540 (9 minutes, under the 10-min max).
        val claims = """{"iss":"$appId","iat":${now - 60},"exp":${now + 540}}"""
        val signingInput = "${b64(header)}.${b64(claims)}"
        val signature = sign(signingInput.toByteArray(Charsets.US_ASCII), pem)
        return "$signingInput.${b64Bytes(signature)}"
    }

    private fun sign(data: ByteArray, pem: String): ByteArray {
        val key = PemLoader.loadPrivateKey(pem)
        return Signature.getInstance("SHA256withRSA").run {
            initSign(key)
            update(data)
            sign()
        }
    }

    private fun b64(json: String): String = B64.encodeToString(json.toByteArray(Charsets.US_ASCII))
    private fun b64Bytes(bytes: ByteArray): String = B64.encodeToString(bytes)
}
