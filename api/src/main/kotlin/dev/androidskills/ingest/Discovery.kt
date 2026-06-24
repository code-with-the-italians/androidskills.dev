package dev.androidskills.ingest

import dev.androidskills.api.ApiValidationException
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Read-only skill discovery from a fetched archive (spec §5 core; the *write*
 * half — file mirroring, skill/version upsert, review enqueue — is deferred to
 * step 5 per the plan's issue B, so this writes nothing: no DB, no FileStore).
 *
 * Shared by repo scans and uploaded-zip scans. Extraction is one JDK-native
 * `java.util.zip` path for both sources (deviation A: GitHub `/zipball`, not
 * `/tarball`); **root-normalization differs by source** (gotcha #1): a GitHub
 * zipball wraps the repo in a single `{owner}-{repo}-{sha}/` dir, so a literal
 * top-level `skills/` lookup matches nothing. `RepoZipball` peels exactly one
 * leading segment; `UploadedZip` peels one only if `skills/` isn't already at
 * the root.
 *
 * Security guards: the compressed body is size-bounded by the caller; here we
 * bound the **inflated** stream (count bytes as read, never trust entry sizes),
 * cap entry count, and reject Zip Slip via [SkillPaths.safeRelativeOrNull].
 */
sealed interface ArchiveSource {
    val bytes: ByteArray
    data class RepoZipball(override val bytes: ByteArray, val commitSha: String) : ArchiveSource
    data class UploadedZip(override val bytes: ByteArray, val uploadHash: String) : ArchiveSource
}

/** The read-only metadata for one detected skill (§9: "Detected metadata (read-only)"). */
data class DetectedSkill(
    val slug: String,
    val name: String,
    val description: String,
    val license: String?,
    val tags: List<String>,
    val version: String,
    val versionSource: String, // manifest|git_head|upload
    val tokenUpfront: Int,
    val tokenOndemand: Int,
    val tokenBand: String,
    val parseErrors: List<FieldError>,
    val fileCount: Int,
)

sealed interface ScanResult {
    data class Found(val skills: List<DetectedSkill>) : ScanResult
    data object NoSkillsDir : ScanResult // §10: submit blocked
}

object Discovery {

    /** Inflated bytes / entry-count caps (Q4). The compressed cap is the caller's. */
    const val MAX_INFLATED_BYTES = 50L * 1024 * 1024
    const val MAX_ENTRIES = 1000
    /** Per-file cap for the body fed to the token estimator. */
    const val MAX_FILE_BYTES = 5L * 1024 * 1024

    /** §10 slug: lowercase kebab-case. Stricter than SkillManifest's tag regex. */
    private val SLUG = Regex("""^[a-z0-9]+(-[a-z0-9]+)*$""")

    fun discover(source: ArchiveSource): ScanResult {
        val entries = extract(source) // guarded + root-normalized per-source
        if (entries.none { it.path.startsWith("skills/") }) return ScanResult.NoSkillsDir
        val grouped = entries
            .filter { it.path.startsWith("skills/") }
            .groupBy { topDir(it.path) } // "skills/<slug>" -> all files under it
        val detected = grouped.mapNotNull { (dir, files) -> buildDetected(dir, files, source) }
        return ScanResult.Found(detected)
    }

    /** Extracts + applies the source-specific root-normalization (gotcha #1). */
    private fun extract(source: ArchiveSource): List<Extracted> {
        val raw = ArrayList<Extracted>(64)
        var inflated = 0L
        ZipInputStream(ByteArrayInputStream(source.bytes)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                try {
                    if (e.isDirectory) continue
                    if (raw.size >= MAX_ENTRIES) {
                        throw ApiValidationException(mapOf("archive" to "too many entries (max $MAX_ENTRIES)"), "archive_too_large")
                    }
                    val safe = SkillPaths.safeRelativeOrNull(normalize(e.name, source))
                        ?: continue // Zip Slip / unsafe path — skip, never write
                    // Bounded read: count inflated bytes as we go, never trust entry getSize().
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8 * 1024)
                    var n = zis.read(buf)
                    while (n >= 0) {
                        inflated += n
                        if (inflated > MAX_INFLATED_BYTES) {
                            throw ApiValidationException(mapOf("archive" to "inflated size exceeds ${MAX_INFLATED_BYTES / (1024 * 1024)} MB"), "archive_too_large")
                        }
                        out.write(buf, 0, n)
                        n = zis.read(buf)
                    }
                    val bytes = out.toByteArray()
                    raw.add(Extracted(safe, bytes, isProbablyBinary(bytes)))
                } finally {
                    zis.closeEntry()
                }
            }
        }
        return raw
    }

    /**
     * Root-normalize (gotcha #1):
     *  - RepoZipball: GitHub wraps the repo in `{owner}-{repo}-{sha}/`. Peel exactly
     *    one leading segment unconditionally → `skills/...` lands at the root.
     *  - UploadedZip: may be rooted at `skills/...` (no peel) OR under a single
     *    wrapper dir (peel one). Peel only if `skills/` is NOT already at the root.
     */
    private fun normalize(rawName: String, source: ArchiveSource): String {
        val slash = rawName.indexOf('/')
        if (slash < 0) return rawName // no segments to peel
        val rest = rawName.substring(slash + 1)
        return when (source) {
            is ArchiveSource.RepoZipball -> rest // always peel the wrapper
            is ArchiveSource.UploadedZip -> if (rawName.startsWith("skills/")) rawName else rest
        }
    }

    /**
     * `dir` is "skills/<slug>". Files are the entries under it. Returns null if the
     * directory has no SKILL.md (not a skill) or the slug is malformed.
     */
    private fun buildDetected(dir: String, files: List<Extracted>, source: ArchiveSource): DetectedSkill? {
        val slug = dir.removePrefix("skills/")
        if (slug.isEmpty() || !SLUG.matches(slug)) return null
        val skillMd = files.firstOrNull { it.path == "$dir/SKILL.md" } ?: return null
        val manifest = SkillManifestParser.parse(String(skillMd.bytes, Charsets.UTF_8))
        val onDemandFiles = files.filter { isOnDemand(it.path, dir) && !it.binary }
        val onDemandText = buildString {
            append(manifest.body)
            onDemandFiles.forEach { f ->
                if (length + f.bytes.size <= MAX_FILE_BYTES.toInt() * 4) {
                    append('\n'); append(String(f.bytes, Charsets.UTF_8))
                }
            }
        }
        val upfront = Tokens.estimate("${manifest.name} ${manifest.description}")
        val onDemand = Tokens.estimate(onDemandText)
        val total = upfront + onDemand
        val version = manifest.version ?: when (source) {
            is ArchiveSource.RepoZipball -> source.commitSha.take(12)
            is ArchiveSource.UploadedZip -> source.uploadHash.take(12)
        }
        val versionSource = if (manifest.version != null) "manifest" else when (source) {
            is ArchiveSource.RepoZipball -> "git_head"
            is ArchiveSource.UploadedZip -> "upload"
        }
        return DetectedSkill(
            slug = slug,
            name = manifest.name,
            description = manifest.description,
            license = manifest.license,
            tags = manifest.tags,
            version = version,
            versionSource = versionSource,
            tokenUpfront = upfront,
            tokenOndemand = onDemand,
            tokenBand = Tokens.band(total),
            parseErrors = manifest.parseErrors + ManifestValidator.validate(manifest),
            fileCount = files.size,
        )
    }

    /** references/, examples/, scripts/ under the skill dir (spec §5 on-demand cost). */
    private fun isOnDemand(path: String, dir: String): Boolean {
        val rel = path.removePrefix("$dir/").substringBefore('/', "")
        return rel in setOf("references", "examples", "scripts")
    }

    private fun topDir(path: String): String {
        // path is normalized like "skills/foo/bar.md"; top dir under skills/ is "skills/foo".
        val after = path.removePrefix("skills/")
        val next = after.indexOf('/')
        return if (next >= 0) "skills/${after.substring(0, next)}" else "skills/$after"
    }

    private fun isProbablyBinary(bytes: ByteArray): Boolean {
        // Simple heuristic: a NUL byte in the first 2KB → binary.
        val n = minOf(bytes.size, 2048)
        for (i in 0 until n) if (bytes[i] == 0.toByte()) return true
        return false
    }

    private data class Extracted(val path: String, val bytes: ByteArray, val binary: Boolean) {
        override fun equals(other: Any?) = other is Extracted && path == other.path
        override fun hashCode() = path.hashCode()
    }
}
