package dev.androidskills.ingest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SkillPathsTest {

  @Test
  fun `accepts normalised relative posix paths`() {
    assertEquals("references/guide.md", SkillPaths.safeRelativeOrNull("references/guide.md"))
    assertEquals("examples/a/b.kt", SkillPaths.safeRelativeOrNull("examples/a/b.kt"))
    assertEquals("SKILL.md", SkillPaths.safeRelativeOrNull("SKILL.md"))
  }

  @Test
  fun `rejects traversal absolute backslash and null`() {
    assertNull(SkillPaths.safeRelativeOrNull("../etc/passwd"))
    assertNull(SkillPaths.safeRelativeOrNull("a/../../b"))
    assertNull(SkillPaths.safeRelativeOrNull("/etc/passwd"))
    assertNull(SkillPaths.safeRelativeOrNull("a\\b"))
    assertNull(SkillPaths.safeRelativeOrNull("a\u0000b"))
    assertNull(SkillPaths.safeRelativeOrNull("./hidden"))
    assertNull(SkillPaths.safeRelativeOrNull(""))
    // Segment with only dots/symbols or spaces is rejected.
    assertNull(SkillPaths.safeRelativeOrNull("a/ b.md"))
  }

  @Test
  fun `requireSafe throws on unsafe path`() {
    assertFailsWith<IllegalArgumentException> { SkillPaths.requireSafe("../escape") }
  }
}
