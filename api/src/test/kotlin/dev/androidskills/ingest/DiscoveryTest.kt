package dev.androidskills.ingest

import dev.androidskills.api.ApiValidationException
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The read-only discovery core (§5, §3.1, §10) + the security guards + gotcha #1. */
class DiscoveryTest {

  @Test
  fun `repo zipball - wrapper peeled and skills under skills are detected`() {
    // GitHub wraps the repo in {owner}-{repo}-{sha}/ — gotcha #1: without peeling,
    // every entry is under the wrapper and a literal top-level skills/ lookup fails.
    val zip =
      zip(
        "alice-repo-abc123/.github/SKILL.md" to ignored, // ignored (dot-directory)
        "alice-repo-abc123/.claude/SKILL.md" to ignored,
        "alice-repo-abc123/.agents/SKILL.md" to ignored,
        "alice-repo-abc123/SKILL.md" to ignored, // ignored (repo root)
        "alice-repo-abc123/skills/mvi/SKILL.md" to
          skillMd("MVI Scaffold", "Predictable MVI baseline."),
        "alice-repo-abc123/skills/mvi/references/intent.md" to "Intents are reduced by the VM.\n",
        "alice-repo-abc123/skills/coroutines/SKILL.md" to
          skillMd("Coroutines", "Testing suspend fns."),
      )
    val res =
      Discovery.discover(
        ArchiveSource.RepoZipball(zip, "owner", "repo", commitSha = "abc1234567890abcd")
      )
    val found = assertIs<ScanResult.Found>(res)
    assertEquals(2, found.skills.size)
    val mvi = found.skills.first { it.slug == "mvi" }
    assertEquals("MVI Scaffold", mvi.name)
    assertEquals("git_head", mvi.versionSource)
    assertEquals("abc123456789", mvi.version) // commitSha.take(12)
    assertTrue(mvi.tokenUpfront > 0)
    assertTrue(mvi.tokenOndemand > 0, "on-demand body+references counted")
    assertTrue(mvi.fileCount >= 2)
    val co = found.skills.first { it.slug == "coroutines" }
    assertEquals("Coroutines", co.name)
  }

  @Test
  fun `uploaded zip rooted at skills is discovered without peeling`() {
    val zip = zip("skills/x/SKILL.md" to skillMd("X", "d"))
    val res = Discovery.discover(ArchiveSource.UploadedZip(zip, uploadHash = "deadbeefcafebabe"))
    val found = assertIs<ScanResult.Found>(res)
    assertEquals(1, found.skills.size)
    assertEquals("x", found.skills[0].slug)
    assertEquals("upload", found.skills[0].versionSource)
  }

  @Test
  fun `uploaded zip under a wrapper dir is discovered by peeling one segment`() {
    val zip = zip("bundle-1/skills/y/SKILL.md" to skillMd("Y", "d"))
    val res = Discovery.discover(ArchiveSource.UploadedZip(zip, uploadHash = "h"))
    val found = assertIs<ScanResult.Found>(res)
    assertEquals("y", found.skills[0].slug)
  }

  @Test
  fun `all four ignored locations are not treated as skills`() {
    // Dot-directories (.github/.claude/.agents) and a bare root SKILL.md are never skills.
    val zip =
      zip(
        "o-r-s/.github/SKILL.md" to ignored,
        "o-r-s/.claude/SKILL.md" to ignored,
        "o-r-s/.agents/SKILL.md" to ignored,
        "o-r-s/SKILL.md" to ignored,
        "o-r-s/skills/real/SKILL.md" to skillMd("Real", "d"),
      )
    val found =
      assertIs<ScanResult.Found>(
        Discovery.discover(ArchiveSource.RepoZipball(zip, "owner", "repo", "sha"))
      )
    assertEquals(listOf("real"), found.skills.map { it.slug })
  }

  @Test
  fun `no skills dir returns NoSkillsDir`() {
    // §10: submit blocked. Even with a stray SKILL.md at root, no skills/ → NoSkillsDir.
    val zip = zip("o-r-s/README.md" to "readme", "o-r-s/SKILL.md" to ignored)
    assertIs<ScanResult.NoSkillsDir>(
      Discovery.discover(ArchiveSource.RepoZipball(zip, "owner", "repo", "sha"))
    )
  }

  @Test
  fun `malformed manifest surfaces parseErrors on the detected skill`() {
    val zip =
      zip(
        "o-r-s/skills/bad/SKILL.md" to
          """
                ---
                name: Bad
                description: d
                license: MIT
                tags: notalist
                ---
                body
            """
            .trimIndent()
      )
    val found =
      assertIs<ScanResult.Found>(
        Discovery.discover(ArchiveSource.RepoZipball(zip, "owner", "repo", "sha"))
      )
    val bad = found.skills[0]
    assertTrue(
      bad.parseErrors.any { it.field == "tags" },
      "expected tags parseError; got ${bad.parseErrors}",
    )
  }

  @Test
  fun `zip slip entry is skipped not extracted`() {
    // A ../ path is unsafe; Discovery must skip it, not crash or write it.
    val zip = zip("o-r-s/skills/x/SKILL.md" to skillMd("X", "d"), "o-r-s/../escape.md" to "evil")
    val found =
      assertIs<ScanResult.Found>(
        Discovery.discover(ArchiveSource.RepoZipball(zip, "owner", "repo", "sha"))
      )
    assertEquals(1, found.skills.size)
  }

  @Test
  fun `too many entries trips the cap`() {
    val entries =
      (1..Discovery.MAX_ENTRIES + 1).map { i -> "o-r-s/skills/s$i/SKILL.md" to skillMd("s$i", "d") }
    val zip = zip(*entries.toTypedArray())
    assertFailsArchiveTooLarge {
      Discovery.discover(ArchiveSource.RepoZipball(zip, "owner", "repo", "sha"))
    }
  }

  @Test
  fun `inflated size cap rejects a zip bomb`() {
    // One entry whose inflated content blows past the cap. Build a ~60MB in-memory
    // stream of compressible data (repeated bytes compress tiny, inflate huge).
    val bomb = ByteArray((Discovery.MAX_INFLATED_BYTES + 1024).toInt()) // ~50MB+1KB
    val zip =
      zip("o-r-s/skills/x/big.txt" to String(bomb)) // String is inefficient but fine for one test
    assertFailsArchiveTooLarge {
      Discovery.discover(ArchiveSource.RepoZipball(zip, "owner", "repo", "sha"))
    }
  }

  @Test
  fun `bad slug directory is not a skill`() {
    // §10 slug is lowercase kebab; an Upper/dir with spaces yields no skill.
    val zip = zip("o-r-s/skills/Bad_Slug/SKILL.md" to skillMd("Bad", "d"))
    val found =
      assertIs<ScanResult.Found>(
        Discovery.discover(ArchiveSource.RepoZipball(zip, "owner", "repo", "sha"))
      )
    assertTrue(found.skills.isEmpty(), "Bad_Slug is not a valid slug; got ${found.skills}")
  }

  @Test
  fun `skills are discovered at any depth, not only under a skills dir`() {
    // Real repos vary: android/skills groups by category dir; skydoves puts skill dirs at the root.
    val zip =
      zip(
        "o-r-s/jetpack-compose/adaptive/SKILL.md" to skillMd("adaptive", "Adaptive UI."),
        "o-r-s/jetpack-compose/adaptive/references/grid.md" to "Grid API notes.\n",
        "o-r-s/recomposition/debugging-recompositions/SKILL.md" to
          skillMd("debug", "Find recompositions."),
        "o-r-s/testing/deep/SKILL.md" to skillMd("deep", "Deeply nested."),
      )
    val found =
      assertIs<ScanResult.Found>(
        Discovery.discover(ArchiveSource.RepoZipball(zip, "owner", "repo", "sha1234567890ab"))
      )
    // Slug is the leaf name (unique here) — short and stable.
    assertEquals(
      setOf("adaptive", "debugging-recompositions", "deep"),
      found.skills.map { it.slug }.toSet(),
    )
    val adaptive = found.skills.first { it.slug == "adaptive" }
    assertEquals("jetpack-compose/adaptive", adaptive.sourceDir)
    assertTrue(adaptive.fileCount >= 2, "SKILL.md + references counted")
  }

  @Test
  fun `dot-directories at any depth are excluded`() {
    val zip =
      zip(
        "o-r-s/.claude-plugin/foo/SKILL.md" to ignored, // dot-dir ancestor
        "o-r-s/.opencode/SKILL.md" to ignored,
        "o-r-s/tools/.hidden/SKILL.md" to ignored, // dot segment mid-path
        "o-r-s/lists/optimizing-layouts/SKILL.md" to skillMd("opt", "Real one."),
      )
    val found =
      assertIs<ScanResult.Found>(
        Discovery.discover(ArchiveSource.RepoZipball(zip, "owner", "repo", "sha"))
      )
    assertEquals(listOf("optimizing-layouts"), found.skills.map { it.slug })
  }

  @Test
  fun `duplicate leaf names keep the leaf slug but are flagged`() {
    // Same leaf in different dirs is NOT renumbered or merged: the slug stays the natural leaf and
    // every colliding skill is flagged with a `slug` parse error (surfaced by the scan; the ingest
    // pipeline skips them). Unique slugs are not flagged.
    val zip =
      zip(
        "o-r-s/foo-bar/baz/SKILL.md" to skillMd("a", "A."),
        "o-r-s/foo/bar/baz/SKILL.md" to skillMd("b", "B."), // same leaf 'baz'
        "o-r-s/material/styles/SKILL.md" to skillMd("c", "C."), // unique
      )
    val found =
      assertIs<ScanResult.Found>(
        Discovery.discover(ArchiveSource.RepoZipball(zip, "owner", "repo", "sha"))
      )
    val bazes = found.skills.filter { it.slug == "baz" }
    assertEquals(2, bazes.size)
    assertTrue(bazes.all { s -> s.parseErrors.any { it.field == "slug" } }, "both 'baz' flagged")
    val styles = found.skills.first { it.slug == "styles" }
    assertTrue(styles.parseErrors.none { it.field == "slug" }, "unique slug not flagged")
  }

  @Test
  fun `a skill nested inside another assigns files to the deepest skill dir`() {
    val zip =
      zip(
        "o-r-s/outer/SKILL.md" to skillMd("outer", "Outer."),
        "o-r-s/outer/notes.md" to "outer notes\n",
        "o-r-s/outer/inner/SKILL.md" to skillMd("inner", "Inner."),
        "o-r-s/outer/inner/refs.md" to "inner refs\n",
      )
    val found =
      assertIs<ScanResult.Found>(
        Discovery.discover(ArchiveSource.RepoZipball(zip, "owner", "repo", "sha"))
      )
    assertEquals(setOf("outer", "inner"), found.skills.map { it.slug }.toSet())
    // outer keeps SKILL.md + notes.md; inner's two files are NOT double-counted under outer.
    assertEquals(2, found.skills.first { it.slug == "outer" }.fileCount)
    assertEquals(2, found.skills.first { it.slug == "inner" }.fileCount)
  }

  @Test
  fun `an invalid-slug nested dir is not a skill and its files stay with the parent`() {
    // Fix: bad-slug dirs are excluded from BOTH detection and file assignment, so Discovery's
    // fileCount matches what IngestPipeline mirrors (which assigns from the same detected set).
    val zip =
      zip(
        "o-r-s/tools/valid-skill/SKILL.md" to skillMd("valid", "Valid."),
        "o-r-s/tools/valid-skill/Bad_Slug/SKILL.md" to ignored, // invalid leaf → not a skill
        "o-r-s/tools/valid-skill/Bad_Slug/extra.md" to "extra\n",
      )
    val found =
      assertIs<ScanResult.Found>(
        Discovery.discover(ArchiveSource.RepoZipball(zip, "owner", "repo", "sha"))
      )
    assertEquals(listOf("valid-skill"), found.skills.map { it.slug })
    assertEquals(3, found.skills[0].fileCount) // all 3 files under the one valid skill
  }

  // ---- helpers ----

  private val ignored = "name: Ignored\n" + skillMdBody("Ignored", "should not be picked up")

  private fun skillMd(name: String, description: String) =
    "---\n${skillMdBody(name, description)}\n---\n# $name\n"

  private fun skillMdBody(name: String, description: String) =
    "name: $name\ndescription: $description\nlicense: Apache-2.0\ntags: [android]\n"

  private fun assertFailsArchiveTooLarge(block: () -> Unit) {
    try {
      block()
      error("expected archive_too_large")
    } catch (e: ApiValidationException) {
      // The validation message carries the archive_too_large code.
      assertTrue(e.message?.contains("archive") == true || e.fields.containsKey("archive"))
    }
  }

  /** Build an in-memory zip from (entryName -> content) pairs. */
  private fun zip(vararg entries: Pair<String, String>): ByteArray {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zos ->
      entries.forEach { (name, content) ->
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
      }
    }
    return baos.toByteArray()
  }
}
