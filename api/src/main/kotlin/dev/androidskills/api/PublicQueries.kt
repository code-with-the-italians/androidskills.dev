package dev.androidskills.api

import dev.androidskills.db.Bundles
import dev.androidskills.db.Categories
import dev.androidskills.db.Reports
import dev.androidskills.db.SkillFiles
import dev.androidskills.db.Skills
import dev.androidskills.db.Submissions
import dev.androidskills.db.Users
import dev.androidskills.db.Versions
import dev.androidskills.ingest.SkillPaths
import dev.androidskills.storage.FileStore
import dev.androidskills.util.appJson
import dev.androidskills.util.newId
import dev.androidskills.util.nowIso
import kotlinx.serialization.decodeFromString
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.LowerCase
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Parsed `GET /api/skills` query (spec §9). Defaults match the public browse
 * experience: `verified` only on, relevance sort, page 1.
 *
 * `verified == true` (default) restricts to verified skills; `verified == false`
 * lifts that restriction to show everything (the "Verified only" toggle off),
 * matching spec §3.5 ("Unverified skills are still reachable").
 */
data class SearchParams(
    val q: String?,
    val cats: List<String>,
    val tags: List<String>,
    val size: String?, // "<2k" | "2-5k" | "5k+"
    val verified: Boolean,
    val sort: String, // relevance|installs|updated|tokens
    val page: Int,
    val pageSize: Int,
)

/**
 * Read-side queries for the public API (spec §9 Public). Each top-level function
 * runs in its own transaction against the default Exposed database (set by
 * [dev.androidskills.Database.init]) and returns plain DTOs, throwing the typed
 * [ApiNotFoundException] / [ApiValidationException] for missing/invalid input.
 *
 * Public visibility = `status = 'published'`; unlisted/flagged skills are not
 * reachable anonymously (spec §3.5, §10). The `verified` boolean drives the
 * "caution" filter independently.
 *
 * NOTE on the query DSL: Exposed 0.61 added `ColumnSet.select(columns)` members
 * that shadow the classic `select { where }` trailing lambda, so predicates are
 * applied via `selectAll().where { … }` / `selectAll().where(op)` instead.
 */
object PublicQueries {

    /** Soft cap for in-browser file preview; larger/binary files are download-only. */
    const val PREVIEW_LIMIT = 256 * 1024
    private const val DEFAULT_PAGE_SIZE = 24
    private const val MAX_PAGE_SIZE = 60
    private const val MAX_TAG_FACETS = 20

    // ---- stats / taxonomy --------------------------------------------------

    fun stats(): StatsResponse = transaction {
        val indexed = Skills.selectAll().where { Skills.status eq "published" }.count().toInt()
        val contributors = Skills
            .join(Bundles, JoinType.INNER, Skills.bundleId, Bundles.id)
            .join(Users, JoinType.INNER, Bundles.ownerUserId, Users.id)
            .select(Users.id)
            .where { Skills.status eq "published" }
            .withDistinct()
            .count()
            .toInt()
        val lastUpdated = Skills.select(Skills.updatedAt)
            .orderBy(Skills.updatedAt, SortOrder.DESC).firstOrNull()?.get(Skills.updatedAt)
        StatsResponse(indexed, contributors, lastUpdated)
    }

    fun categories(): List<CategoryWithCount> = transaction {
        val byCat = Skills.select(Skills.categoryId).where { Skills.status eq "published" }
            .toList().groupingBy { it[Skills.categoryId] }.eachCount()
        Categories.selectAll().orderBy(Categories.name).map { cr ->
            CategoryWithCount(
                id = cr[Categories.id],
                slug = cr[Categories.slug],
                name = cr[Categories.name],
                count = byCat[cr[Categories.id]] ?: 0,
            )
        }
    }

    // ---- search / list -----------------------------------------------------

    fun search(p: SearchParams): SkillSearchPage = transaction {
        val where = p.buildCondition()
        val total = Skills.selectAll().where(where).count().toInt()
        val rows = Skills.selectAll().where(where)
            .orderBy(*p.orderColumns().toTypedArray())
            .limit(p.pageSize)
            .offset(((p.page - 1) * p.pageSize).toLong())
            .toList()
        SkillSearchPage(
            items = buildCards(rows),
            page = p.page,
            pageSize = p.pageSize,
            total = total,
            totalPages = pagesOf(total, p.pageSize),
            facets = computeFacets(p),
        )
    }

    // ---- detail ------------------------------------------------------------

    fun skillDetail(slug: String): SkillDetail = transaction {
        val row = Skills.selectAll().where { (Skills.slug eq slug) and (Skills.status eq "published") }
            .singleOrNull() ?: throw ApiNotFoundException("Skill '$slug' not found")
        val card = buildCards(listOf(row)).first()
        var count = 0
        var totalSize = 0L
        SkillFiles.select(SkillFiles.size).where { SkillFiles.skillId eq row[Skills.id] }
            .forEach { count++; totalSize += it[SkillFiles.size] }
        SkillDetail(
            id = card.id, slug = card.slug, name = card.name, description = card.description,
            license = card.license, tags = card.tags, category = card.category, version = card.version,
            versionSource = card.versionSource, tokenUpfront = card.tokenUpfront, tokenOndemand = card.tokenOndemand,
            tokenBand = card.tokenBand, verified = card.verified, status = card.status, featured = card.featured,
            installs = card.installs, author = card.author, bundle = card.bundle,
            readmeMd = row[Skills.readmeMd], fileCount = count, totalSize = totalSize,
            createdAt = card.createdAt, updatedAt = card.updatedAt,
        )
    }

    // ---- files -------------------------------------------------------------

    fun fileTree(slug: String): FileTreeResponse = transaction {
        val skill = skillRowPublic(slug)
        val files = SkillFiles.selectAll().where { SkillFiles.skillId eq skill[Skills.id] }
            .orderBy(SkillFiles.path).map { row ->
                val path = row[SkillFiles.path]
                FileEntry(
                    path = path,
                    name = path.substringAfterLast('/'),
                    dir = path.substringBeforeLast('/').takeIf { it != path } ?: "",
                    size = row[SkillFiles.size],
                    isBinary = row[SkillFiles.isBinary],
                )
            }
        FileTreeResponse(slug, files, buildTree(files))
    }

    fun fileContent(slug: String, path: String, store: FileStore): FileContentResult = transaction {
        val skill = skillRowPublic(slug)
        val row = SkillFiles.selectAll().where {
            (SkillFiles.skillId eq skill[Skills.id]) and (SkillFiles.path eq path)
        }.singleOrNull() ?: throw ApiNotFoundException("File '$path' not found for '$slug'")
        val isBinary = row[SkillFiles.isBinary]
        val size = row[SkillFiles.size]
        val key = row[SkillFiles.r2Key]
        val downloadOnly = isBinary || size > PREVIEW_LIMIT
        val content = if (!downloadOnly) {
            store.get(key)?.toString(Charsets.UTF_8)
                ?: throw ApiStorageException("File bytes missing for '$slug'/'$path' (key=$key)")
        } else null
        FileContentResult(slug, path, size, isBinary, content, downloadOnly, key)
    }

    // ---- versions ----------------------------------------------------------

    fun versions(slug: String): VersionsResponse = transaction {
        val skill = skillRowPublic(slug)
        val current = skill[Skills.version]
        val list = Versions.selectAll().where { Versions.skillId eq skill[Skills.id] }
            .orderBy(Versions.createdAt, SortOrder.DESC)
            .map { VersionEntry(it[Versions.version], it[Versions.sourceRef], it[Versions.createdAt], it[Versions.version] == current) }
        VersionsResponse(slug, current, list)
    }

    // ---- download ----------------------------------------------------------

    fun download(slug: String, version: String?, store: FileStore): DownloadResult = transaction {
        val skill = skillRowPublic(slug)
        val skillId = skill[Skills.id]
        val verRow = (if (version != null) {
            Versions.selectAll().where { (Versions.skillId eq skillId) and (Versions.version eq version) }.singleOrNull()
        } else {
            // No version requested → the public contract is "the current version"
            // (skills.version), NOT "whichever versions row was inserted last".
            // Selecting by newest created_at would let a later backfilled/re-reviewed
            // row shadow the real current release (or 404 against the wrong row).
            val currentVersion = skill[Skills.version]
            Versions.selectAll().where {
                (Versions.skillId eq skillId) and (Versions.version eq currentVersion)
            }.singleOrNull()
        }) ?: throw ApiNotFoundException("No version available for '$slug'")

        val currentVersion = skill[Skills.version]
        val isCurrent = verRow[Versions.version] == currentVersion
        val label = version ?: verRow[Versions.version]
        val zipKey = verRow[Versions.r2ZipKey]
        // A version's archive must come from its own r2_zip_key. We only ever
        // fall back to building from current skill_files for the *current*
        // version (where current files == that version); a historical version
        // with a missing/absent archive is a 404, never silently substituted
        // with today's files named as the old version (spec §5.6).
        val storedBytes = if (zipKey != null && store.exists(zipKey)) store.get(zipKey) else null
        val bytes = storedBytes ?: if (isCurrent) {
            buildZip(skillId, store)
        } else {
            throw ApiNotFoundException("No downloadable archive for '$slug' version '$label'")
        }
        // Best-effort install counter (single writer; spec §3 keeps this simple).
        // Runs after the archive is resolved and inside the same transaction, so a
        // thrown build/lookup rolls the bump back — a failed download never counts.
        Skills.update({ Skills.id eq skillId }) { it[Skills.installs] = skill[Skills.installs] + 1 }
        Skills.update({ Skills.id eq skillId }) { it[Skills.installs] = skill[Skills.installs] + 1 }
        DownloadResult(bytes, "${slug}-${verRow[Versions.version]}.zip", "application/zip")
    }

    // ---- trends / timeline -------------------------------------------------

    fun trends(): TrendsResponse = transaction {
        val allPublished = Skills.selectAll().where { Skills.status eq "published" }.toList()
        val catCounts = allPublished.groupingBy { it[Skills.categoryId] }.eachCount()
        val catMap = if (catCounts.isNotEmpty()) {
            Categories.selectAll().where { Categories.id inList catCounts.keys.filterNotNull() }
                .associateBy { it[Categories.id] }
        } else emptyMap()
        val catFacet = catCounts.entries.sortedByDescending { it.value }
            .map { (id, n) ->
                val cr = id?.let { catMap[it] }
                FacetCount(cr?.get(Categories.slug) ?: "uncategorized", cr?.get(Categories.name) ?: "Uncategorized", n)
            }
        val tagCounts = mutableMapOf<String, Int>()
        allPublished.forEach { decodeTags(it[Skills.tags]).forEach { t -> tagCounts.merge(t, 1) { a, b -> a + b } } }
        val tagFacet = tagCounts.entries.sortedByDescending { it.value }.take(MAX_TAG_FACETS)
            .map { FacetCount(it.key, null, it.value) }
        val bandCounts = allPublished.groupingBy { it[Skills.tokenBand] }.eachCount()
        val tokenMix = listOf("100s", "1k", "10k", "100k").map { FacetCount(it, null, bandCounts[it] ?: 0) }
        val funnel = Submissions.select(Submissions.state).toList()
            .groupingBy { it[Submissions.state] }.eachCount()
        TrendsResponse(catFacet, tagFacet, tokenMix, securityPassRate = null, submissionFunnel = funnel)
    }

    fun timeline(page: Int, pageSize: Int): TimelinePage = transaction {
        val skillRows = Skills.selectAll().where { Skills.status eq "published" }.toList()
        val ownerByBundle = ownerByBundleMap(skillRows.map { it[Skills.bundleId] }.distinct())
        val events = mutableListOf<TimelineEvent>()
        skillRows.forEach { s ->
            events += TimelineEvent(
                type = "publish",
                at = s[Skills.createdAt],
                slug = s[Skills.slug],
                name = s[Skills.name],
                version = s[Skills.version],
                authorHandle = ownerByBundle[s[Skills.bundleId]],
            )
        }
        val skillById = skillRows.associateBy { it[Skills.id] }
        val skillIds = skillRows.map { it[Skills.id] }
        if (skillIds.isNotEmpty()) {
            Versions.selectAll().where { Versions.skillId inList skillIds }.orderBy(Versions.createdAt, SortOrder.DESC).forEach { v ->
                val s = skillById[v[Versions.skillId]] ?: return@forEach
                events += TimelineEvent(
                    type = "version",
                    at = v[Versions.createdAt],
                    slug = s[Skills.slug],
                    name = s[Skills.name],
                    version = v[Versions.version],
                    authorHandle = ownerByBundle[s[Skills.bundleId]],
                )
            }
        }
        events.sortByDescending { it.at } // ISO-8601 UTC sorts lexicographically
        val total = events.size
        val items = events.drop((page - 1) * pageSize).take(pageSize)
        TimelinePage(items, page, pageSize, total, pagesOf(total, pageSize))
    }

    // ---- bundles / authors -------------------------------------------------

    fun bundles(page: Int, pageSize: Int): Page<BundleSummary> = transaction {
        // Public bundle index: only bundles exposing >=1 published skill (spec
        // §3.5 — non-public skills are not reachable anonymously, so a bundle
        // that has only unlisted/flagged skills is hidden from the public list).
        val publicBundleIds = Skills.select(Skills.bundleId)
            .where { Skills.status eq "published" }.map { it[Skills.bundleId] }.toSet()
        val total = publicBundleIds.size
        val rows = if (publicBundleIds.isEmpty()) emptyList()
        else Bundles.selectAll().where { Bundles.id inList publicBundleIds.toList() }
            .orderBy(Bundles.createdAt, SortOrder.DESC)
            .limit(pageSize).offset(((page - 1) * pageSize).toLong()).toList()
        val owners = usersByIds(rows.map { it[Bundles.ownerUserId] }.distinct())
        val counts = Skills.select(Skills.bundleId)
            .where { (Skills.bundleId inList rows.map { it[Bundles.id] }) and (Skills.status eq "published") }
            .toList().groupingBy { it[Skills.bundleId] }.eachCount()
        val items = rows.map { row ->
            BundleSummary(
                id = row[Bundles.id], kind = row[Bundles.kind], provenance = row[Bundles.provenance],
                owner = authorRef(owners[row[Bundles.ownerUserId]]), sourceRef = row[Bundles.sourceRef],
                skillCount = counts[row[Bundles.id]] ?: 0, syncedAt = row[Bundles.syncedAt], createdAt = row[Bundles.createdAt],
            )
        }
        Page(items, page, pageSize, total, pagesOf(total, pageSize))
    }

    fun bundle(id: String): BundleDetail = transaction {
        val row = Bundles.selectAll().where { Bundles.id eq id }.singleOrNull()
            ?: throw ApiNotFoundException("Bundle '$id' not found")
        val skillRows = Skills.selectAll()
            .where { (Skills.bundleId eq id) and (Skills.status eq "published") }
            .orderBy(Skills.updatedAt, SortOrder.DESC).toList()
        if (skillRows.isEmpty()) throw ApiNotFoundException("Bundle '$id' not found")
        val owner = usersByIds(listOf(row[Bundles.ownerUserId])).values.first()
        BundleDetail(
            id = row[Bundles.id], kind = row[Bundles.kind], provenance = row[Bundles.provenance],
            owner = authorRef(owner), sourceRef = row[Bundles.sourceRef], syncedAt = row[Bundles.syncedAt],
            createdAt = row[Bundles.createdAt], skills = buildCards(skillRows),
        )
    }

    fun author(handle: String): AuthorProfile = transaction {
        val user = Users.selectAll().where { Users.handle eq handle }.singleOrNull()
            ?: throw ApiNotFoundException("Author '$handle' not found")
        val userId = user[Users.id]
        val bundleIds = Bundles.select(Bundles.id).where { Bundles.ownerUserId eq userId }.map { it[Bundles.id] }
        val skillRows = if (bundleIds.isNotEmpty()) {
            Skills.selectAll().where { (Skills.bundleId inList bundleIds) and (Skills.status eq "published") }
                .orderBy(Skills.installs, SortOrder.DESC).toList()
        } else emptyList()
        val cards = buildCards(skillRows)
        AuthorProfile(
            handle = handle, name = user[Users.name], avatarUrl = user[Users.avatarUrl],
            skillCount = cards.size, totalInstalls = skillRows.sumOf { it[Skills.installs] }, skills = cards,
        )
    }

    // ---- report ------------------------------------------------------------

    fun createReport(slug: String, reason: String?, reporterId: String?): ReportResponse = transaction {
        val skill = skillRowPublic(slug)
        val trimmed = reason?.trim().orEmpty()
        if (trimmed.isEmpty()) throw ApiValidationException(mapOf("reason" to "is required"))
        val id = newId()
        Reports.insert {
            it[Reports.id] = id
            it[Reports.skillId] = skill[Skills.id]
            it[Reports.reporterId] = reporterId
            it[Reports.reason] = trimmed.take(2000)
            it[Reports.createdAt] = nowIso()
        }
        ReportResponse(id, accepted = true)
    }

    // ---- internals ---------------------------------------------------------

    /** Resolves a published skill row or throws 404. */
    private fun skillRowPublic(slug: String): ResultRow = transaction {
        Skills.selectAll().where { (Skills.slug eq slug) and (Skills.status eq "published") }.singleOrNull()
    } ?: throw ApiNotFoundException("Skill '$slug' not found")

    private fun SearchParams.buildCondition(
        excludeCats: Boolean = false,
        excludeTags: Boolean = false,
        excludeSize: Boolean = false,
    ): Op<Boolean> = with(SqlExpressionBuilder) {
        // like / inList / inSubQuery / arithmetic are members of SqlExpressionBuilder,
        // so the predicate is built inside its scope (and propagates into nested lambdas).
        val conds = mutableListOf<Op<Boolean>>(Skills.status eq "published")
        if (verified) conds += Skills.verified eq true
        val qq = q?.trim()
        if (!qq.isNullOrBlank()) {
            val pat = "%${qq.sanitizeLike()}%"
            val lpat = "%${qq.sanitizeLike().lowercase()}%"
            conds += (Skills.tags like pat) or
                (LowerCase(Skills.name) like lpat) or
                (LowerCase(Skills.description) like lpat)
        }
        if (!excludeCats && cats.isNotEmpty()) {
            val catIds = Categories.select(Categories.id).where { Categories.slug inList cats }
            conds += Skills.categoryId inSubQuery catIds
        }
        if (!excludeTags && tags.isNotEmpty()) {
            val tagOps: List<Op<Boolean>> = tags.map { Skills.tags like "%\"${it.sanitizeLike()}\"%" }
            conds += if (tagOps.size == 1) tagOps[0] else tagOps.reduce { acc, op -> acc or op }
        }
        if (!excludeSize && size != null) {
            val total = Skills.tokenUpfront + Skills.tokenOndemand
            conds += when (size) {
                "<2k" -> total less 2000
                "2-5k" -> (total greaterEq 2000) and (total lessEq 5000)
                "5k+" -> total greater 5000
                else -> total greaterEq 0
            }
        }
        conds.reduce { acc, op -> acc and op }
    }

    private fun SearchParams.orderColumns(): List<Pair<Expression<*>, SortOrder>> = with(SqlExpressionBuilder) {
        val qq = q?.trim()
        when (sort) {
            "installs" -> listOf(Skills.installs to SortOrder.DESC, Skills.updatedAt to SortOrder.DESC)
            "updated" -> listOf(Skills.updatedAt to SortOrder.DESC)
            "tokens" -> listOf(
                (Skills.tokenUpfront + Skills.tokenOndemand) to SortOrder.ASC,
                Skills.name to SortOrder.ASC,
            )
            else -> if (!qq.isNullOrBlank()) {
                val lpat = "%${qq.sanitizeLike().lowercase()}%"
                listOf(
                    (LowerCase(Skills.name) like lpat) to SortOrder.DESC,
                    Skills.installs to SortOrder.DESC,
                    Skills.updatedAt to SortOrder.DESC,
                )
            } else {
                listOf(Skills.featured to SortOrder.DESC, Skills.installs to SortOrder.DESC, Skills.updatedAt to SortOrder.DESC)
            }
        }
    }

    private fun computeFacets(p: SearchParams): Facets {
        val catRows = Skills.select(Skills.categoryId).where(p.buildCondition(excludeCats = true)).toList()
        val catCounts = catRows.groupingBy { it[Skills.categoryId] }.eachCount()
        val catMap = if (catCounts.isNotEmpty()) {
            Categories.selectAll().where { Categories.id inList catCounts.keys.filterNotNull() }.associateBy { it[Categories.id] }
        } else emptyMap()
        val categoryFacet = catCounts.entries.sortedByDescending { it.value }.map { (id, n) ->
            val cr = id?.let { catMap[it] }
            FacetCount(cr?.get(Categories.slug) ?: "uncategorized", cr?.get(Categories.name) ?: "Uncategorized", n)
        }

        val tagCounts = mutableMapOf<String, Int>()
        Skills.select(Skills.tags).where(p.buildCondition(excludeTags = true)).forEach { row ->
            decodeTags(row[Skills.tags]).forEach { tagCounts.merge(it, 1) { a, b -> a + b } }
        }
        val tagFacet = tagCounts.entries.sortedByDescending { it.value }.take(MAX_TAG_FACETS)
            .map { FacetCount(it.key, null, it.value) }

        val sizeBuckets = linkedMapOf("<2k" to 0, "2-5k" to 0, "5k+" to 0)
        Skills.select(Skills.tokenUpfront, Skills.tokenOndemand).where(p.buildCondition(excludeSize = true)).forEach { row ->
            val total = row[Skills.tokenUpfront] + row[Skills.tokenOndemand]
            val key = when { total < 2000 -> "<2k"; total <= 5000 -> "2-5k"; else -> "5k+" }
            sizeBuckets[key] = sizeBuckets[key]!! + 1
        }
        val sizeFacet = sizeBuckets.map { FacetCount(it.key, null, it.value) }

        return Facets(categoryFacet, tagFacet, sizeFacet)
    }

    private fun buildCards(rows: List<ResultRow>): List<SkillCard> {
        if (rows.isEmpty()) return emptyList()
        val bundles = Bundles.selectAll().where { Bundles.id inList rows.map { it[Skills.bundleId] }.distinct() }
            .associateBy { it[Bundles.id] }
        val owners = usersByIds(bundles.values.map { it[Bundles.ownerUserId] }.distinct())
        val catIds = rows.mapNotNull { it[Skills.categoryId] }.distinct()
        val cats = if (catIds.isNotEmpty()) {
            Categories.selectAll().where { Categories.id inList catIds }.associateBy { it[Categories.id] }
        } else emptyMap()
        return rows.map { row ->
            val bundle = bundles[row[Skills.bundleId]] ?: error("missing bundle for skill")
            val owner = owners[bundle[Bundles.ownerUserId]]
            val category = row[Skills.categoryId]?.let { cats[it] }
            SkillCard(
                id = row[Skills.id], slug = row[Skills.slug], name = row[Skills.name], description = row[Skills.description],
                license = row[Skills.license], tags = decodeTags(row[Skills.tags]),
                category = category?.let { CategoryRef(it[Categories.slug], it[Categories.name]) },
                version = row[Skills.version], versionSource = row[Skills.versionSource],
                tokenUpfront = row[Skills.tokenUpfront], tokenOndemand = row[Skills.tokenOndemand], tokenBand = row[Skills.tokenBand],
                verified = row[Skills.verified], status = row[Skills.status], featured = row[Skills.featured], installs = row[Skills.installs],
                author = authorRef(owner), bundle = BundleRef(bundle[Bundles.id], bundle[Bundles.kind], bundle[Bundles.provenance]),
                createdAt = row[Skills.createdAt], updatedAt = row[Skills.updatedAt],
            )
        }
    }

    private fun usersByIds(ids: List<String>): Map<String, ResultRow> =
        if (ids.isEmpty()) emptyMap() else Users.selectAll().where { Users.id inList ids }.associateBy { it[Users.id] }

    private fun ownerByBundleMap(bundleIds: List<String>): Map<String, String> {
        if (bundleIds.isEmpty()) return emptyMap()
        val bundles = Bundles.selectAll().where { Bundles.id inList bundleIds }.associateBy { it[Bundles.id] }
        val owners = usersByIds(bundles.values.map { it[Bundles.ownerUserId] }.distinct())
        return bundles.mapNotNull { (bid, b) -> owners[b[Bundles.ownerUserId]]?.get(Users.handle)?.let { bid to it } }.toMap()
    }

    private fun authorRef(owner: ResultRow?): AuthorRef = if (owner == null) AuthorRef("unknown") else
        AuthorRef(owner[Users.handle], owner[Users.name], owner[Users.avatarUrl])

    private fun buildZip(skillId: String, store: FileStore): ByteArray {
        val files = SkillFiles.selectAll().where { SkillFiles.skillId eq skillId }.orderBy(SkillFiles.path).toList()
        if (files.isEmpty()) {
            throw ApiStorageException("Cannot build archive for skill '$skillId': no files recorded")
        }
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            files.forEach { f ->
                // Zip Slip defence: never write an entry that escapes the skill root.
                // Unlike a quiet skip, an unsafe stored path or missing bytes is a
                // data-integrity failure — failing loudly (ApiStorageException) means
                // download() never returns a 200 with a truncated archive and a
                // bumped install count. (§11; matches the file-preview behaviour.)
                val safePath = SkillPaths.safeRelativeOrNull(f[SkillFiles.path])
                    ?: throw ApiStorageException("Unsafe file path stored for skill '$skillId': '${f[SkillFiles.path]}'")
                val bytes = store.get(f[SkillFiles.r2Key])
                    ?: throw ApiStorageException("File bytes missing for skill '$skillId': '${f[SkillFiles.path]}' (key=${f[SkillFiles.r2Key]})")
                zos.putNextEntry(ZipEntry(safePath))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun buildTree(files: List<FileEntry>): List<TreeNode> {
        // Mutable intermediate, then frozen into immutable TreeNode for the response.
        data class MNode(val name: String, val path: String, var type: String, var size: Int = 0, var binary: Boolean = false, val kids: MutableMap<String, MNode> = LinkedHashMap())
        val root = MNode("", "", "dir")
        for (f in files) {
            val parts = f.path.split('/')
            var cur = root
            parts.forEachIndexed { i, part ->
                val fullPath = if (cur.path.isEmpty()) part else "${cur.path}/$part"
                val isLeaf = i == parts.lastIndex
                cur = cur.kids.getOrPut(part) { MNode(part, fullPath, if (isLeaf) "file" else "dir") }
                if (isLeaf) { cur.size = f.size; cur.binary = f.isBinary }
            }
        }
        fun toNode(m: MNode): TreeNode = TreeNode(
            name = m.name, path = m.path, type = m.type, size = m.size, isBinary = m.binary,
            children = if (m.kids.isEmpty()) null else m.kids.values.map(::toNode),
        )
        return root.kids.values.map(::toNode)
    }

    private fun decodeTags(json: String): List<String> = try {
        appJson.decodeFromString<List<String>>(json)
    } catch (_: Throwable) {
        emptyList()
    }

    private fun pagesOf(total: Int, size: Int): Int = if (size <= 0) 0 else (total + size - 1) / size

    private fun String.sanitizeLike(): String = replace("%", "").replace("_", "")

    fun parsePageSize(raw: String?): Int = (raw?.toIntOrNull() ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)
    fun parsePage(raw: String?): Int = (raw?.toIntOrNull() ?: 1).coerceAtLeast(1)

    /** Strict paging for HTTP query params — malformed values 422 instead of coercing. */
    fun parsePageStrict(raw: String?): Int {
        if (raw == null) return 1
        val n = raw.toIntOrNull() ?: throw ApiValidationException(mapOf("page" to "must be a positive integer"), "Invalid 'page'")
        if (n < 1) throw ApiValidationException(mapOf("page" to "must be >= 1"), "Invalid 'page'")
        return n
    }

    fun parsePageSizeStrict(raw: String?): Int {
        if (raw == null) return DEFAULT_PAGE_SIZE
        val n = raw.toIntOrNull() ?: throw ApiValidationException(mapOf("pageSize" to "must be an integer"), "Invalid 'pageSize'")
        if (n !in 1..MAX_PAGE_SIZE) throw ApiValidationException(mapOf("pageSize" to "must be 1..$MAX_PAGE_SIZE"), "Invalid 'pageSize'")
        return n
    }
}

data class FileContentResult(
    val slug: String,
    val path: String,
    val size: Int,
    val isBinary: Boolean,
    val content: String?,
    val downloadOnly: Boolean,
    val r2Key: String,
)

data class DownloadResult(val bytes: ByteArray, val filename: String, val contentType: String)
