package dev.androidskills.ingest

/**
 * Validates a skill-file path stored in / written from `skill_files.path`.
 *
 * Paths are attacker-influenced once ingest accepts repo/upload trees, so every
 * path that becomes a [java.util.zip.ZipEntry] name or a [dev.androidskills.storage.FileStore]
 * key must be a normalised, relative, POSIX path that cannot escape the skill
 * root — otherwise a `../` entry enables Zip Slip (writing outside the consumer's
 * skills dir) or a key that escapes the store root.
 *
 * Returns the cleaned path on success, or `null` if the path is unsafe (the
 * caller decides whether to reject the whole file or skip it).
 */
object SkillPaths {
    private val SEGMENT = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*""")

    /** Maximum depth under a skill root (references/.../x.md etc.). */
    private const val MAX_DEPTH = 16

    fun safeRelativeOrNull(raw: String): String? {
        if (raw.isBlank()) return null
        if (raw.contains('\\') || raw.startsWith('/')) return null // posix only, not absolute
        if (raw.contains("\u0000")) return null
        val parts = raw.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > MAX_DEPTH) return null
        for (segment in parts) {
            if (segment == "." || segment == "..") return null
            if (!SEGMENT.matches(segment)) return null
            if (segment.length > 255) return null
        }
        return parts.joinToString("/")
    }

    /** Throws on an unsafe path — for write paths where silent skipping would hide bugs. */
    fun requireSafe(raw: String, field: String = "path"): String =
        safeRelativeOrNull(raw) ?: throw IllegalArgumentException("unsafe $field: '$raw'")
}
