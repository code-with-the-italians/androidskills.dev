package dev.androidskills.ingest

import dev.androidskills.api.ApiValidationException
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Read-only skill discovery from a fetched archive (spec §5 core; no DB / FileStore writes here).
 *
 * A **skill** is any directory that directly contains a `SKILL.md`, at ANY depth — real-world skill
 * repos vary in layout (`skills/mvi/`, `jetpack-compose/adaptive/`, `recomposition/debugging/`), so
 * discovery is layout-agnostic. Two exclusions keep tooling and stray files out of the scan:
 * - **dot-directories** — any path segment starting with `.` (`.github`, `.claude`, `.agents`,
 *   `.codex-plugin`, …) is never a skill;
 * - **the archive root** — a bare top-level `SKILL.md` (no containing directory) is ignored.
 *
 * Shared by repo scans and uploaded-zip scans. Extraction is one JDK-native `java.util.zip` path
 * for both sources (deviation A: GitHub `/zipball`, not `/tarball`); **root-normalization differs
 * by source** (gotcha #1): a GitHub zipball wraps the repo in a single `{owner}-{repo}-{sha}/` dir,
 * which is peeled; `UploadedZip` peels one leading segment only if `skills/` isn't already at root.
 *
 * Security guards: the compressed body is size-bounded by the caller; here we bound the
 * **inflated** stream (count bytes as read, never trust entry sizes), cap entry count, and reject
 * Zip Slip via [SkillPaths.safeRelativeOrNull].
 */
sealed interface ArchiveSource {
  val bytes: ByteArray

  data class RepoZipball(
    override val bytes: ByteArray,
    val owner: String,
    val repo: String,
    val commitSha: String,
  ) : ArchiveSource {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false
      other as RepoZipball
      return bytes.contentEquals(other.bytes) &&
        owner == other.owner &&
        repo == other.repo &&
        commitSha == other.commitSha
    }

    override fun hashCode(): Int =
      bytes.contentHashCode() * 31 +
        owner.hashCode() * 31 +
        repo.hashCode() * 31 +
        commitSha.hashCode()
  }

  data class UploadedZip(override val bytes: ByteArray, val uploadHash: String) : ArchiveSource {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false
      other as UploadedZip
      return bytes.contentEquals(other.bytes) && uploadHash == other.uploadHash
    }

    override fun hashCode(): Int = bytes.contentHashCode() * 31 + uploadHash.hashCode()
  }
}

/** The read-only metadata for one detected skill (§9: "Detected metadata (read-only)"). */
data class DetectedSkill(
  val slug: String,
  /**
   * Archive-relative directory the skill lives in, e.g. "skills/mvi" or "jetpack-compose/adaptive".
   */
  val sourceDir: String,
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
    // Candidate skill dirs: every dir that directly holds a SKILL.md, at any depth, minus
    // dot-directories and the archive root (a bare "SKILL.md" has no '/', so the endsWith drops
    // it).
    val candidateDirs =
      entries
        .asSequence()
        .filter { it.path.endsWith("/SKILL.md") }
        .map { it.path.substringBeforeLast('/') }
        .filter { dir -> dir.isNotEmpty() && dir.split('/').none { seg -> seg.startsWith(".") } }
        .toSet()
    if (candidateDirs.isEmpty()) return ScanResult.NoSkillsDir
    // A candidate is a *skill* only if its leaf is a valid slug. Bad-slug dirs are excluded from
    // BOTH detection AND file assignment, so their files fall to a valid ancestor consistently with
    // IngestPipeline (which assigns from the same detected set) — no fileCount/tree mismatch.
    val skillDirs = candidateDirs.filterTo(HashSet()) { SLUG.matches(it.substringAfterLast('/')) }
    // Unique, valid-kebab slug per skill dir: the validated leaf, disambiguated with `-N` on a
    // same-leaf collision within the archive (deterministic via sorted dir order).
    val slugByDir = assignSlugs(skillDirs)
    // Assign each file to its DEEPEST containing skill dir (a skill can nest inside another).
    val byDir = skillDirs.associateWith { mutableListOf<Extracted>() }
    for (e in entries) ownerDir(e.path, skillDirs)?.let { byDir.getValue(it).add(e) }
    val detected =
      byDir.entries.mapNotNull { (dir, files) ->
        buildDetected(dir, slugByDir.getValue(dir), files, source)
      }
    return ScanResult.Found(detected)
  }

  /**
   * Assigns a unique, kebab-valid slug to each skill dir. The base is the dir's leaf (already
   * validated against [SLUG] by [discover]); a same-leaf collision within the archive gets a `-2`,
   * `-3`, … suffix. Deterministic: dirs are processed in sorted order, so a given archive always
   * yields the same slugs. Suffixing the validated leaf keeps every slug kebab-valid (unlike
   * flattening the full path, which could both collide and produce non-kebab segments).
   */
  private fun assignSlugs(skillDirs: Set<String>): Map<String, String> {
    val used = HashSet<String>()
    val out = HashMap<String, String>()
    for (dir in skillDirs.sorted()) {
      val base = dir.substringAfterLast('/')
      var slug = base
      var n = 1
      while (!used.add(slug)) {
        n++
        slug = "$base-$n"
      }
      out[dir] = slug
    }
    return out
  }

  /** The deepest skill dir in [skillDirs] that contains [path] (or IS its SKILL.md), else null. */
  internal fun ownerDir(path: String, skillDirs: Collection<String>): String? =
    skillDirs.filter { path == "$it/SKILL.md" || path.startsWith("$it/") }.maxByOrNull { it.length }

  /** Extracts + applies the source-specific root-normalization (gotcha #1). */
  internal fun extract(source: ArchiveSource): List<Extracted> {
    val raw = ArrayList<Extracted>(64)
    var inflated = 0L
    ZipInputStream(ByteArrayInputStream(source.bytes)).use { zis ->
      while (true) {
        val e = zis.nextEntry ?: break
        try {
          if (e.isDirectory) continue
          if (raw.size >= MAX_ENTRIES) {
            throw ApiValidationException(
              mapOf("archive" to "too many entries (max $MAX_ENTRIES)"),
              code = "archive_too_large",
            )
          }
          val safe =
            SkillPaths.safeRelativeOrNull(normalize(e.name, source))
              ?: continue // Zip Slip / unsafe path — skip, never write
          // Bounded read: count inflated bytes as we go, never trust entry getSize().
          val out = java.io.ByteArrayOutputStream()
          val buf = ByteArray(8 * 1024)
          var n = zis.read(buf)
          while (n >= 0) {
            inflated += n
            if (inflated > MAX_INFLATED_BYTES) {
              throw ApiValidationException(
                mapOf(
                  "archive" to "inflated size exceeds ${MAX_INFLATED_BYTES / (1024 * 1024)} MB"
                ),
                code = "archive_too_large",
              )
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
   * - RepoZipball: GitHub wraps the repo in `{owner}-{repo}-{sha}/`. Peel exactly one leading
   *   segment unconditionally → the real tree lands at the root.
   * - UploadedZip: may be rooted at `skills/...` (no peel) OR under a single wrapper dir (peel
   *   one). Peel only if `skills/` is NOT already at the root.
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
   * [dir] is the skill's archive-relative directory (any depth, already validated by [discover]);
   * [slug] is its resolved unique slug (see [assignSlugs]); [files] are the entries assigned to it.
   * Returns null only if the directory somehow has no SKILL.md.
   */
  private fun buildDetected(
    dir: String,
    slug: String,
    files: List<Extracted>,
    source: ArchiveSource,
  ): DetectedSkill? {
    val skillMd = files.firstOrNull { it.path == "$dir/SKILL.md" } ?: return null
    val manifest = SkillManifestParser.parse(String(skillMd.bytes, Charsets.UTF_8))
    val onDemandFiles = files.filter { isOnDemand(it.path, dir) && !it.binary }
    val onDemandText = buildString {
      append(manifest.body)
      onDemandFiles.forEach { f ->
        if (length + f.bytes.size <= MAX_FILE_BYTES.toInt() * 4) {
          append('\n')
          append(String(f.bytes, Charsets.UTF_8))
        }
      }
    }
    val upfront = Tokens.estimate("${manifest.name} ${manifest.description}")
    val onDemand = Tokens.estimate(onDemandText)
    val total = upfront + onDemand
    val version =
      manifest.version
        ?: when (source) {
          is ArchiveSource.RepoZipball -> source.commitSha.take(12)
          is ArchiveSource.UploadedZip -> source.uploadHash.take(12)
        }
    val versionSource =
      if (manifest.version != null) "manifest"
      else
        when (source) {
          is ArchiveSource.RepoZipball -> "git_head"
          is ArchiveSource.UploadedZip -> "upload"
        }
    return DetectedSkill(
      slug = slug,
      sourceDir = dir,
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

  private fun isProbablyBinary(bytes: ByteArray): Boolean {
    // Simple heuristic: a NUL byte in the first 2KB → binary.
    val n = minOf(bytes.size, 2048)
    for (i in 0 until n) if (bytes[i] == 0.toByte()) return true
    return false
  }

  internal data class Extracted(val path: String, val bytes: ByteArray, val binary: Boolean) {
    override fun equals(other: Any?) = other is Extracted && path == other.path

    override fun hashCode() = path.hashCode()
  }
}
