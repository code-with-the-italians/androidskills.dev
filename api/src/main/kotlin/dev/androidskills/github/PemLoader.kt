package dev.androidskills.github

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Loads a GitHub App PEM private key into a Java [PrivateKey], accepting either
 * PEM flavour GitHub/App owners have lying around:
 *
 *  - **PKCS#8** `-----BEGIN PRIVATE KEY-----` — what `KeyFactory.generatePrivate`
 *    (via [PKCS8EncodedKeySpec]) ingests directly.
 *  - **PKCS#1** `-----BEGIN RSA PRIVATE KEY-----` — what the GitHub App settings
 *    page **exports**. Java has no native PKCS#1 reader without BouncyCastle.
 *
 * The fix is the well-known ~15-byte ASN.1 prefix wrapper: a PKCS#1 RSA key
 * becomes a valid PKCS#8 RSA key by prepending the fixed头
 * `30 82 … 02 01 00 30 0d 06 09 2a 86 48 86 f7 0d 01 01 01 05 00 04 82 …`
 * (the `RSAEncryption` algorithm identifier + OCTET STRING wrapper). No dep.
 *
 * This is the step-4 review "Q2 footgun": without it, the App key GitHub
 * exports silently fails to load at wiring time.
 */
internal object PemLoader {

    // PKCS#8 wrapper for an RSA private key: SEQUENCE { version, AlgorithmIdentifier,
    // OCTET STRING { <PKCS#1 bytes> } }. The length placeholders (at the outer
    // SEQUENCE and the OCTET STRING) are filled in for the actual PKCS#1 size.
    // Exact ASN.1 (mirrors a real RSA PKCS#8 DER prefix):
    //   30 82 LL LL       SEQUENCE (2-byte length)
    //   02 01 00          INTEGER version = 0
    //   30 0d             SEQUENCE AlgorithmIdentifier (length 13)
    //   06 09 2a 86 48 86 f7 0d 01 01 01   OID rsaEncryption
    //   05 00             NULL
    //   04 82 LL LL       OCTET STRING (2-byte length) — the PKCS#1 bytes follow
    private val PKCS8_RSA_PREFIX = byteArrayOf(
        0x30.toByte(), 0x82.toByte(), 0x00, 0x00,                            // outer SEQUENCE, length at [2,3]
        0x02, 0x01, 0x00,                                                   // version
        0x30.toByte(), 0x0d,                                               // AlgorithmIdentifier SEQUENCE
        0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, // OID 1.2.840.113549.1.1.1
        0x05, 0x00,                                                         // NULL
        0x04, 0x82.toByte(), 0x00, 0x00,                                    // OCTET STRING, length at [24,25]
    )

    fun loadPrivateKey(pem: String): PrivateKey {
        val (base64, kind) = parsePem(pem)
        val der = Base64.getDecoder().decode(base64)
        val pkcs8Der = when (kind) {
            PemKind.PKCS8 -> der
            PemKind.PKCS1 -> wrapPkcs1AsPkcs8(der)
        }
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8Der))
    }

    private fun wrapPkcs1AsPkcs8(pkcs1: ByteArray): ByteArray {
        if (pkcs1.size > 0xffff) throw GitHubAppException("RSA private key too large for PKCS#8 wrapper")
        val out = PKCS8_RSA_PREFIX.copyOf()
        // OCTET STRING length (at [24,25]) = pkcs1.size.
        out[24] = (pkcs1.size ushr 8).toByte()
        out[25] = pkcs1.size.toByte()
        // Outer SEQUENCE length (at [2,3]) = everything after [0..3] = (prefix minus 4) + pkcs1.size.
        val outerLen = out.size - 4 + pkcs1.size
        out[2] = (outerLen ushr 8).toByte()
        out[3] = outerLen.toByte()
        return out + pkcs1
    }

    private enum class PemKind { PKCS8, PKCS1 }

    private fun parsePem(pem: String): Pair<String, PemKind> {
        val marker = PEM_BEGIN.find(pem)
            ?: throw GitHubAppException("No PEM private key found (missing BEGIN marker)")
        val kindLabel = marker.groupValues[1].trim()
        // Take the text up to the END marker, keep ONLY base64 alphabet chars.
        // (Filtering by char-class is more robust than split+strip: the END marker's
        // letters include valid base64 chars that would silently corrupt the body.)
        val body = pem.substring(marker.range.last + 1)
        // Everything up to the END marker. Use a literal search (the END line is
        // fixed text); the previous regex anchored on `[A-Z ]+` failed to match
        // across the END line and left the marker letters in the base64 body.
        val endIdx = body.indexOf("-----END")
        val safeEnd = if (endIdx >= 0) endIdx else body.length
        val base64 = body.substring(0, safeEnd).filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
        val kind = when (kindLabel) {
            "PRIVATE KEY", "EC PRIVATE KEY" -> PemKind.PKCS8
            "RSA PRIVATE KEY" -> PemKind.PKCS1
            else -> throw GitHubAppException("Unsupported PEM type: -----BEGIN $kindLabel-----")
        }
        if (base64.isEmpty()) throw GitHubAppException("PEM private key has empty body")
        return base64 to kind
    }

    private val PEM_BEGIN = Regex("""-----BEGIN ([A-Z ]*PRIVATE KEY)-----""")
}
