package dev.androidskills.storage

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a downloadable skill bundle zip from file entries. Shared by the ingest write path
 * (building a new version zip) and the read-API download fallback.
 *
 * SKILL.md is prepended (if not already present in [files] and [readmeMd] is non-blank) so the
 * bundle is complete.
 */
object ZipBuilder {
  /** Builds a zip from (path → bytes). Prepends SKILL.md from [readmeMd] if absent. */
  fun build(files: List<Pair<String, ByteArray>>, readmeMd: String? = null): ByteArray {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zos ->
      val hasSkillMd = files.any { it.first == "SKILL.md" }
      if (!hasSkillMd && !readmeMd.isNullOrBlank()) {
        zos.putNextEntry(ZipEntry("SKILL.md"))
        zos.write(readmeMd.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
      }
      files.forEach { (path, bytes) ->
        zos.putNextEntry(ZipEntry(path))
        zos.write(bytes)
        zos.closeEntry()
      }
    }
    return baos.toByteArray()
  }
}
