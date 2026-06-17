package dev.androidskills.ingest

/**
 * Order-of-magnitude token estimate (spec §5). Intentionally a coarse heuristic
 * (`tokens ≈ ceil(utf8_bytes / 4)`) surfaced as a **band** — every model
 * tokenises differently, so a precise count would be false precision (§09).
 */
object Tokens {

    /** ceil(utf8Bytes / 4). */
    fun estimateUtf8Bytes(byteCount: Int): Int = (byteCount + 3) / 4

    /** Token estimate for a UTF-8 string. */
    fun estimate(text: String): Int = estimateUtf8Bytes(text.toByteArray(Charsets.UTF_8).size)

    /**
     * Band for a *total* token count (upfront + on-demand):
     * `<1_000 → "100s"`, `<10_000 → "1k"`, `<100_000 → "10k"`, else `"100k"`.
     */
    fun band(total: Int): String = when {
        total < 1_000 -> "100s"
        total < 10_000 -> "1k"
        total < 100_000 -> "10k"
        else -> "100k"
    }
}
