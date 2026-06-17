package dev.androidskills.ingest

/**
 * Result of splitting a `SKILL.md` into YAML frontmatter + Markdown body.
 *
 * The manifest is the **source of truth** for name/description/tags/license/slug
 * (spec §3.2): these fields are parsed here and never edited via the API. The
 * slug itself is the skill's directory name (handled at ingest time), not a
 * frontmatter field.
 *
 * Parsing is a deliberately small YAML subset (scalars, block/flow lists, one
 * level of nesting for `metadata:`) — enough for the defined frontmatter shape
 * without pulling in a YAML dependency. Unknown fields are ignored.
 */
data class ParsedManifest(
    val name: String,
    val description: String,
    val tags: List<String>,
    val license: String?,
    /** Raw `metadata.version` string, validated as SemVer by [ManifestValidator]. */
    val version: String?,
    val body: String,
    /** True when the input had no `---` frontmatter block at all. */
    val hadFrontmatter: Boolean,
)

/** A single per-field validation failure (spec §10). */
data class FieldError(val field: String, val reason: String)

object SkillManifestParser {

    private val DELIM = Regex("""-{3,}\s*""")

    /**
     * Splits `content` into frontmatter fields + the markdown body. Never throws
     * on a malformed/missing manifest — it returns empty fields and leaves
     * validation to [ManifestValidator].
     */
    fun parse(content: String): ParsedManifest {
        val src = content.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val lines = src.split('\n')

        var idx = 0
        while (idx < lines.size && lines[idx].isBlank()) idx++

        var hadFrontmatter = false
        val frontmatter: String
        if (idx < lines.size && DELIM.matches(lines[idx].trim()) && lines[idx].trim().all { it == '-' }) {
            hadFrontmatter = true
            idx++ // opening delimiter
            val start = idx
            while (idx < lines.size && !isDelim(lines[idx])) idx++
            frontmatter = lines.subList(start, idx).joinToString("\n")
            if (idx < lines.size) idx++ // closing delimiter
        } else {
            frontmatter = ""
        }
        val body = lines.subList(idx, lines.size).joinToString("\n").trim('\n')

        val map = parseSimpleYaml(frontmatter)
        val name = map.string("name")?.trim().orEmpty()
        val description = map.string("description")?.trim().orEmpty()
        val license = map.string("license")?.trim()?.takeIf { it.isNotEmpty() }
        val tags = map.stringList("tags")
        val version = (map["metadata"] as? Map<*, *>)?.string("version")?.trim()?.takeIf { it.isNotEmpty() }

        return ParsedManifest(name, description, tags, license, version, body, hadFrontmatter)
    }

    private fun isDelim(line: String): Boolean {
        val t = line.trim()
        return t.isNotEmpty() && t.all { it == '-' } && t.length >= 3
    }

    // ---- minimal YAML subset ------------------------------------------------

    private typealias YamlNode = MutableMap<String, Any?>

    private fun parseSimpleYaml(text: String): YamlNode {
        val root = LinkedHashMap<String, Any?>()
        val lines = text.split('\n')
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            if (raw.isBlank() || raw.trimStart().startsWith("#")) { i++; continue }
            if (!raw.startsWith(" ")) { // top-level only
                val colon = raw.indexOf(':')
                if (colon >= 0) {
                    val key = raw.substring(0, colon).trim()
                    val value = raw.substring(colon + 1).trim()
                    if (value.isEmpty()) {
                        val (node, next) = parseBlock(lines, i + 1)
                        if (node != null) root[key] = node
                        i = next
                        continue
                    } else if (value.startsWith("[") && value.endsWith("]")) {
                        root[key] = splitFlowList(value.substring(1, value.length - 1))
                    } else {
                        root[key] = unquote(value)
                    }
                }
            }
            i++
        }
        return root
    }

    /**
     * Reads consecutive indented lines after a `key:` with no inline value.
     * Returns either a List<String> (block sequence) or a Map (block mapping),
     * plus the index of the first line that belongs to the parent again.
     */
    private fun parseBlock(lines: List<String>, start: Int): Pair<Any?, Int> {
        val items = mutableListOf<String>()
        val map = LinkedHashMap<String, Any?>()
        var mode: Char? = null // '-' sequence, ':' mapping
        var i = start
        while (i < lines.size) {
            val raw = lines[i]
            if (raw.isBlank()) { i++; continue }
            if (!raw.startsWith(" ")) break // back to parent indent
            val trimmed = raw.trim()
            if (trimmed.startsWith("#")) { i++; continue }
            when {
                trimmed.startsWith("- ") || trimmed == "-" -> {
                    if (mode == null) mode = '-'
                    items += unquote(trimmed.removePrefix("-").trim())
                }
                trimmed.contains(':') -> {
                    if (mode == null) mode = ':'
                    val c = trimmed.indexOf(':')
                    map[trimmed.substring(0, c).trim()] = unquote(trimmed.substring(c + 1).trim())
                }
                else -> break
            }
            i++
        }
        val result: Any? = when (mode) {
            '-' -> items
            ':' -> map
            else -> null
        }
        return result to i
    }

    private fun splitFlowList(inner: String): List<String> =
        if (inner.isBlank()) emptyList()
        else inner.split(',').map { unquote(it.trim()) }.filter { it.isNotEmpty() }

    private fun unquote(s: String): String {
        if (s.length >= 2) {
            val q = s.first()
            if ((q == '"' || q == '\'') && s.last() == q) return s.substring(1, s.length - 1)
        }
        return s
    }

    private fun Map<*, *>.string(key: String): String? = (this[key] as? String)
    private fun Map<*, *>.stringList(key: String): List<String> =
        (this[key] as? List<*>)?.mapNotNull { it?.toString()?.trim()?.takeIf { v -> v.isNotEmpty() } } ?: emptyList()
}

object ManifestValidator {
    private val SLUG_OR_TAG = Regex("""^[a-z0-9][a-z0-9._+-]{0,39}$""")
    private val SEMVER = Regex(
        """^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$""",
    )

    /** Required: name, description, license. Optional-but-validated: tags, metadata.version. */
    fun validate(m: ParsedManifest): List<FieldError> {
        val errors = mutableListOf<FieldError>()
        if (m.name.isBlank()) errors += FieldError("name", "is required")
        if (m.description.isBlank()) errors += FieldError("description", "is required")
        if (m.license.isNullOrBlank()) errors += FieldError("license", "is required")
        m.tags.forEachIndexed { i, tag ->
            if (!SLUG_OR_TAG.matches(tag)) {
                errors += FieldError("tags[$i]", "must be lowercase alnum/._+- (max 40)")
            }
        }
        if (m.version != null && !SEMVER.matches(m.version)) {
            errors += FieldError("metadata.version", "must be valid SemVer")
        }
        return errors
    }
}
