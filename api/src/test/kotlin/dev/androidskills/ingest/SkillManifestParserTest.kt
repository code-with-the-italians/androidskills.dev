package dev.androidskills.ingest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillManifestParserTest {

    @Test
    fun `parses scalars block list metadata and body`() {
        val md = """
            ---
            name: Jetpack MVI Scaffold
            description: A predictable MVI baseline for Compose apps.
            license: Apache-2.0
            tags:
              - android
              - kotlin
              - compose
            metadata:
              version: 1.2.3
            ---
            # Jetpack MVI Scaffold

            ## Usage
            Drop into `skills/` and wire your ViewModel.
        """.trimIndent()

        val m = SkillManifestParser.parse(md)
        assertEquals("Jetpack MVI Scaffold", m.name)
        assertEquals("A predictable MVI baseline for Compose apps.", m.description)
        assertEquals("Apache-2.0", m.license)
        assertEquals(listOf("android", "kotlin", "compose"), m.tags)
        assertEquals("1.2.3", m.version)
        assertTrue(m.hadFrontmatter)
        assertTrue(m.body.startsWith("# Jetpack MVI Scaffold"))
        assertTrue(m.body.contains("Drop into `skills/`"))
    }

    @Test
    fun `parses flow list tags`() {
        val md = """
            ---
            name: Flow Tags
            description: d
            license: MIT
            tags: [android, kotlin, "multi-line"]
            ---
            body
        """.trimIndent()
        val m = SkillManifestParser.parse(md)
        assertEquals(listOf("android", "kotlin", "multi-line"), m.tags)
    }

    @Test
    fun `handles CRLF and BOM`() {
        val bom = "\uFEFF"
        val md = bom + "---\r\nname: X\r\ndescription: d\r\nlicense: MIT\r\n---\r\n# body\r\n"
        val m = SkillManifestParser.parse(md)
        assertEquals("X", m.name)
        assertEquals("# body", m.body.trim())
    }

    @Test
    fun `no frontmatter leaves whole content as body`() {
        val m = SkillManifestParser.parse("# just markdown\nno yaml here")
        assertEquals("", m.name)
        assertEquals("", m.description)
        assertEquals(null, m.license)
        assertTrue(m.body.contains("just markdown"))
        assertEquals(false, m.hadFrontmatter)
    }

    @Test
    fun `validator flags missing required fields`() {
        val m = ParsedManifest(name = "", description = "  ", tags = listOf("OK"), license = null, version = null, body = "", hadFrontmatter = true)
        val fields = ManifestValidator.validate(m).map { it.field }.toSet()
        assertTrue("name" in fields)
        assertTrue("description" in fields)
        assertTrue("license" in fields)
    }

    @Test
    fun `validator flags bad tags and semver`() {
        val m = ParsedManifest(
            name = "N", description = "d", license = "MIT",
            tags = listOf("good", "Bad Tag", "x".repeat(50)),
            version = "not-semver",
            body = "", hadFrontmatter = true,
        )
        val errs = ManifestValidator.validate(m)
        assertTrue(errs.any { it.field.startsWith("tags[") })
        assertTrue(errs.any { it.field == "metadata.version" })
    }

    @Test
    fun `validator accepts a clean manifest`() {
        val m = ParsedManifest(
            name = "N", description = "d", license = "Apache-2.0",
            tags = listOf("android", "kotlin"), version = "0.1.0-rc.1", body = "", hadFrontmatter = true,
        )
        assertEquals(emptyList(), ManifestValidator.validate(m))
    }

    @Test
    fun `scalar tags are rejected not silently dropped`() {
        // §10: tags must be well-formed if present. `tags: android` is a scalar,
        // not a list — the parser must flag it rather than return an empty list
        // (which would silently accept the manifest while discarding its tags).
        val md = """
            ---
            name: Scalar Tags
            description: d
            license: MIT
            tags: android
            ---
            body
        """.trimIndent()
        val m = SkillManifestParser.parse(md)
        assertTrue(m.tags.isEmpty(), "scalar tags should not be coerced into a list")
        val errs = ManifestValidator.validate(m)
        val tagErr = errs.firstOrNull { it.field == "tags" }
        assertTrue(tagErr != null, "expected a tags validation error; got $errs")
        assertTrue(tagErr.reason.contains("list", ignoreCase = true))
    }

    @Test
    fun `malformed flow list tags are rejected`() {
        val md = """
            ---
            name: Bad Flow
            description: d
            license: MIT
            tags: [android, kotlin
            ---
            body
        """.trimIndent()
        val m = SkillManifestParser.parse(md)
        assertTrue(ManifestValidator.validate(m).any { it.field == "tags" })
    }

    @Test
    fun `empty flow list tags are accepted`() {
        // `tags: []` is a valid (empty) list — not flagged as malformed.
        val md = """
            ---
            name: Empty List
            description: d
            license: MIT
            tags: []
            ---
            body
        """.trimIndent()
        val m = SkillManifestParser.parse(md)
        assertEquals(emptyList(), ManifestValidator.validate(m).filter { it.field == "tags" })
    }

    @Test
    fun `parses literal block scalar description`() {
        val md = """
            ---
            name: Block Skill
            description: |
              First line of the description.
              Second line, indented under the indicator.
            license: MIT
            ---
            body
        """.trimIndent()
        val m = SkillManifestParser.parse(md)
        assertTrue(m.description.contains("First line of the description"), "got: ${m.description}")
        assertTrue(m.description.contains("Second line"))
        // Must NOT be the literal indicator string "|".
        assertTrue(m.description != "|")
        assertEquals(emptyList(), ManifestValidator.validate(m))
    }

    @Test
    fun `parses folded block scalar and chomping`() {
        val md = """
            ---
            name: Folded
            description: >-
              folded
              into one line
            license: MIT
            ---
            body
        """.trimIndent()
        val m = SkillManifestParser.parse(md)
        // Folded joins non-empty lines with a space; strip chomps the trailing newline.
        assertEquals("folded into one line", m.description.trim())
    }

    @Test
    fun `block scalar indicator with no content fails validation not silent pipe`() {
        val md = """
            ---
            name: Empty
            description: |
            license: MIT
            ---
            body
        """.trimIndent()
        val m = SkillManifestParser.parse(md)
        // No indented content under `|` → empty, which validation flags as missing
        // (never the literal string "|").
        assertTrue(m.description.isBlank())
        val errs = ManifestValidator.validate(m).map { it.field }
        assertTrue("description" in errs)
    }

    @Test
    fun `indented dashes inside a block scalar are content not a delimiter`() {
        // In YAML a document separator sits at column 0; an indented `---` under
        // `description: |` is scalar text. Treating it as the closing delimiter
        // would truncate the frontmatter and drop license + metadata.version.
        val md = """
            ---
            name: Dashes Skill
            description: |
              Line one.
              ---
              Line three after a dashed line.
            license: Apache-2.0
            metadata:
              version: 1.0.0
            ---
            # Body
        """.trimIndent()
        val m = SkillManifestParser.parse(md)
        assertTrue(m.hadFrontmatter)
        assertTrue(m.description.contains("Line one."), "desc=${m.description}")
        assertTrue(m.description.contains("---"), "the dashes belong to the description")
        assertTrue(m.description.contains("Line three"))
        assertEquals("Apache-2.0", m.license, "license was dropped: ${m.license}")
        assertEquals("1.0.0", m.version, "version was dropped: ${m.version}")
        assertEquals("# Body", m.body.trim())
    }
}

class TokensTest {
    @Test
    fun `estimate is ceil bytes over 4`() {
        // "abcd" = 4 bytes -> 1 token; "abcde" = 5 bytes -> 2 tokens
        assertEquals(1, Tokens.estimate("abcd"))
        assertEquals(2, Tokens.estimate("abcde"))
        assertEquals(0, Tokens.estimate(""))
    }

    @Test
    fun `estimate counts utf8 bytes`() {
        // "€" is 3 UTF-8 bytes -> 1 token
        assertEquals(1, Tokens.estimate("€"))
    }

    @Test
    fun `bands bucket at 1k 10k 100k`() {
        assertEquals("100s", Tokens.band(0))
        assertEquals("100s", Tokens.band(999))
        assertEquals("1k", Tokens.band(1_000))
        assertEquals("1k", Tokens.band(9_999))
        assertEquals("10k", Tokens.band(10_000))
        assertEquals("10k", Tokens.band(99_999))
        assertEquals("100k", Tokens.band(100_000))
        assertEquals("100k", Tokens.band(9_999_999))
    }
}
