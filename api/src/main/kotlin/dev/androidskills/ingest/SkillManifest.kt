package dev.androidskills.ingest

/**
 * Result of splitting a `SKILL.md` into YAML frontmatter + Markdown body.
 *
 * The manifest is the **source of truth** for name/description/tags/license/slug
 * (spec §3.2): these fields are parsed here and never edited via the API. The
 * slug itself is the skill's directory name (handled at ingest time), not a
 * frontmatter field.
 *
 * Parsing is a deliberately small YAML subset (scalars, block & flow lists, one
 * level of nesting for `metadata:`, and block scalars `|` / `>`) — enough for
 * the defined frontmatter shape without pulling in a YAML dependency. It never
 * *silently corrupts* an unsupported form: anything it can't structure is left
 * as a raw scalar and surfaces as a validation error (spec §10), since a
 * half-parsed source-of-truth file is worse than a rejected one. Unknown keys
 * are ignored for forward compatibility.
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
    /**
     * Structured-parse problems found while reading the frontmatter (e.g. a
     * known field in an unsupported form). Surfaced by [ManifestValidator.validate]
     * so a malformed source-of-truth manifest is rejected, never silently coerced.
     */
    val parseErrors: List<FieldError> = emptyList(),
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
        if (idx < lines.size && isDelim(lines[idx])) {
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
        // §10: tags must be well-formed if present. A scalar/malformed value
        // (e.g. `tags: android`, or an unterminated `[a, b`) parses to a non-List
        // node and stringList() returns empty — without this check the bad field
        // would be silently dropped. Flag it so the validator rejects the manifest.
        val parseErrors = buildList {
            val rawTags = map["tags"]
            if (rawTags != null && rawTags !is List<*>) {
                add(FieldError("tags", "must be a list (block sequence or [a, b] flow list)"))
            }
            // §10: metadata.version must be well-formed if present. A `metadata:`
            // value that isn't a mapping (e.g. a flow map `metadata: { version: 1.2.3 }`,
            // which this minimal parser reads as a scalar string) would silently
            // drop the version. Flag it instead, so the manifest is rejected.
            val rawMetadata = map["metadata"]
            if (rawMetadata != null && rawMetadata !is Map<*, *>) {
                add(FieldError("metadata", "must be a block mapping; inline flow maps are unsupported"))
            }
        }
        val version = (map["metadata"] as? Map<*, *>)?.string("version")?.trim()?.takeIf { it.isNotEmpty() }

        return ParsedManifest(name, description, tags, license, version, body, hadFrontmatter, parseErrors)
    }

    private fun isDelim(line: String): Boolean {
        // A YAML document separator sits at column 0. An indented `---` (e.g. a
        // line inside a `description: |` block scalar) is *content*, not a
        // delimiter — matching it here would truncate the frontmatter and drop
        // every field after it (license, metadata.version, …). Require the line
        // to start with `---` (3+ dashes) and have only trailing whitespace.
        if (!line.startsWith("---")) return false
        val afterDashes = line.dropWhile { it == '-' }
        return afterDashes.isBlank()
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
            if (!raw.startsWith(" ")) { // top-level keys only
                val colon = raw.indexOf(':')
                if (colon >= 0) {
                    val key = raw.substring(0, colon).trim()
                    val value = raw.substring(colon + 1).trim()
                    when {
                        isQuoted(value) -> { root[key] = unquote(value); i++ }
                        isBlockScalarIndicator(value) -> {
                            val (scalar, next) = parseBlockScalar(lines, i + 1, value)
                            root[key] = scalar
                            i = next
                        }
                        value.isEmpty() -> {
                            val (node, next) = parseBlock(lines, i + 1)
                            if (node != null) root[key] = node
                            i = next
                        }
                        value.startsWith("[") && value.endsWith("]") -> {
                            root[key] = splitFlowList(value.substring(1, value.length - 1)); i++
                        }
                        else -> { root[key] = unquote(value); i++ }
                    }
                    continue
                }
            }
            i++
        }
        return root
    }

    private fun isQuoted(s: String): Boolean =
        s.length >= 2 && (s.first() == '"' || s.first() == '\'') && s.last() == s.first()

    /** `|`, `|-`, `|+`, `>`, `>-`, `>+`, `|2`, … — a block scalar indicator. */
    private fun isBlockScalarIndicator(s: String): Boolean =
        s.isNotEmpty() && (s.first() == '|' || s.first() == '>') &&
            s.drop(1).all { it in "+-0123456789" }

    /**
     * Reads the indented block following a `key: |` / `key: >` indicator.
     * Returns the scalar text and the index of the first line back at the
     * parent indent. With no indented content, returns "" (so a required field
     * like `description: |` with nothing under it fails validation rather than
     * being stored as the literal string "|").
     */
    private fun parseBlockScalar(lines: List<String>, start: Int, indicator: String): Pair<String, Int> {
        val literal = indicator.first() == '|'
        val chomp = when {
            indicator.contains('-') -> '-' // strip
            indicator.contains('+') -> '+' // keep
            else -> 'c' // clip (default)
        }
        val content = mutableListOf<String>()
        var i = start
        var blockIndent = -1
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) { content.add(""); i++; continue } // tentative trailing/blank line
            val indent = line.takeWhile { it == ' ' }.length
            if (indent == 0) break // parent level again
            if (blockIndent < 0) blockIndent = indent
            if (indent < blockIndent) break
            content.add(line.substring(blockIndent)) // dedent, keep extra indent as text
            i++
        }
        // Drop trailing blank lines collected past the block (unless `+` keeps them).
        while (content.isNotEmpty() && content.last().isEmpty() && chomp != '+') {
            content.removeAt(content.size - 1)
        }
        val body = if (literal) {
            content.joinToString("\n")
        } else {
            // Folded: join consecutive non-empty lines with a space; blank line → newline.
            val sb = StringBuilder()
            for (l in content) {
                if (l.isEmpty()) sb.append('\n')
                else {
                    if (sb.isNotEmpty() && !sb.endsWith('\n')) sb.append(' ')
                    sb.append(l)
                }
            }
            sb.toString()
        }
        val trailing = when (chomp) {
            '-' -> ""
            '+' -> "\n"
            else -> if (body.isNotEmpty()) "\n" else ""
        }
        return (body + trailing) to i
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
    // Strict SemVer per semver.org: prerelease identifiers are dot-separated, each
    // either numeric with no leading zero (`0|[1-9]\d*`) or alphanumeric containing
    // a non-digit. This rejects invalid forms the loose regex let through, e.g.
    // `1.2.3-alpha..1` (empty identifier) and `1.2.3-01` (leading-zero numeric).
    // Build identifiers are `[0-9A-Za-z-]+` (leading zeros allowed by the spec).
    val SEMVER = Regex(
        """^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""" +
            """(?:-(?:0|[1-9]\d*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9]\d*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*)?""" +
            """(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$""",
    )

    /** Required: name, description, license. Optional-but-validated: tags, metadata.version. */
    fun validate(m: ParsedManifest): List<FieldError> {
        val errors = mutableListOf<FieldError>()
        // Parser-level problems first (e.g. a known field in an unsupported form),
        // so a malformed source-of-truth manifest is rejected wholesale (§10).
        errors += m.parseErrors
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
