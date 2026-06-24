package dev.androidskills.github

import dev.androidskills.GithubAppConfig
import dev.androidskills.util.constantTimeEquals
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/** Q2: the PEM loader must accept the key GitHub exports (PKCS#1) as well as PKCS#8. */
class PemLoaderTest {

    @Test
    fun `loads PKCS8 PEM`() {
        val (priv, pub) = rsaKeypair()
        val pem = pemWrap("PRIVATE KEY", Base64.getEncoder().encodeToString(priv.encoded))
        val loaded = PemLoader.loadPrivateKey(pem)
        assertEquals("RSA", loaded.algorithm)
        assertSignsAndVerifies(loaded, pub)
    }

    @Test
    fun `loads PKCS1 PEM (what GitHub exports)`() {
        // Re-encode the PKCS#8 key as PKCS#1 by stripping the wrapper, then wrap
        // as the GitHub-style "BEGIN RSA PRIVATE KEY" PEM. The loader must reverse
        // that — without the wrapper it'd throw KeyFactory's "PKCS8 not found".
        val (priv, pub) = rsaKeypair()
        val pkcs1 = stripPkcs8ToPkcs1(priv.encoded)
        val pem = pemWrap("RSA PRIVATE KEY", Base64.getEncoder().encodeToString(pkcs1))
        val loaded = PemLoader.loadPrivateKey(pem)
        assertSignsAndVerifies(loaded, pub)
        // Same underlying key (modulus match) as the PKCS#8 original.
        val rsaOriginal = priv as java.security.interfaces.RSAPrivateKey
        val rsaLoaded = loaded as java.security.interfaces.RSAPrivateKey
        assertEquals(rsaOriginal.modulus, rsaLoaded.modulus)
    }

    @Test
    fun `rejects non-PEM input`() {
        assertFailsWith<GitHubAppException> { PemLoader.loadPrivateKey("not a key") }
    }

    @Test
    fun `rejects unsupported PEM type`() {
        val pem = pemWrap("ENCRYPTED PRIVATE KEY", "aaaa")
        assertFailsWith<GitHubAppException> { PemLoader.loadPrivateKey(pem) }
    }

    // ---- helpers ----

    private fun rsaKeypair(): Pair<java.security.PrivateKey, java.security.PublicKey> {
        val kp = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048, java.security.SecureRandom())
        }.generateKeyPair()
        return kp.private to kp.public
    }

    private fun pemWrap(kind: String, base64: String): String =
        "-----BEGIN $kind-----\n${base64.chunked(64).joinToString("\n")}\n-----END $kind-----\n"

    /** Strip a PKCS#8 RSA wrapper to its PKCS#1 body (reverse of the loader's wrap).
     *  Reads the OCTET STRING length at DER offsets [24,25]; body starts at [26]. */
    private fun stripPkcs8ToPkcs1(pkcs8: ByteArray): ByteArray {
        val octetLen = ((pkcs8[24].toInt() and 0xff) shl 8) or (pkcs8[25].toInt() and 0xff)
        val bodyStart = 26
        return pkcs8.copyOfRange(bodyStart, bodyStart + octetLen)
    }

    private fun assertSignsAndVerifies(priv: java.security.PrivateKey, pub: java.security.PublicKey) {
        val data = "step-4 q2".toByteArray()
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(priv); update(data); sign()
        }
        val ok = Signature.getInstance("SHA256withRSA").run {
            initVerify(pub); update(data); verify(sig)
        }
        assertTrue(ok, "loaded key must produce a signature that verifies against its public key")
    }
}

class GithubAppConfigTest {
    @Test
    fun `disabled when any of the three creds is absent`() {
        assertFalse(GithubAppConfig.disabled().configured)
        assertFalse(GithubAppConfig(1L, null, "s").configured)
        assertFalse(GithubAppConfig(1L, "pem", null).configured)
        assertFalse(GithubAppConfig(null, "pem", "s").configured)
        assertTrue(GithubAppConfig(1L, "pem", "s").configured)
    }

    @Test
    fun `toString redacts the PEM and webhook secret`() {
        val s = GithubAppConfig(1L, "SUPERSECRETPEM", "SUPERSECRETWS").toString()
        assertNotEquals("SUPERSECRETPEM", s)
        assertFalse(s.contains("SUPERSECRETPEM"), s)
        assertFalse(s.contains("SUPERSECRETWS"), s)
        assertTrue(s.contains("***"))
    }
}

class ComparesTest {
    @Test
    fun `constantTimeEquals matches equality`() {
        assertTrue(constantTimeEquals("abc", "abc"))
        assertFalse(constantTimeEquals("abc", "abd"))
        assertFalse(constantTimeEquals("abc", "abcd"))
        assertFalse(constantTimeEquals("", "a"))
        assertTrue(constantTimeEquals("", ""))
    }

    @Test
    fun `bytearray overload matches equality`() {
        assertTrue(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)))
    }
}
