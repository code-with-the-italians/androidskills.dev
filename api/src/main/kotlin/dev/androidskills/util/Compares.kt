package dev.androidskills.util

/**
 * Constant-time string compare — used for secrets where a timing leak would
 * matter (OAuth state check, webhook HMAC). A length mismatch returns `false`
 * immediately (the length isn't secret in either call site), but the byte
 * comparison always runs across the full common length regardless of where the
 * first difference is, so timing doesn't reveal a shared prefix.
 *
 * Extracted here so both `auth` (OAuth state) and `github` (webhook HMAC) share
 * one implementation rather than copy-pasting it.
 */
fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
    return diff == 0
}

/** Constant-time compare over [ByteArray] (HMAC digests are bytes). */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}
