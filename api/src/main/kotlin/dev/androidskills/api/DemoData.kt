package dev.androidskills.api

import dev.androidskills.db.Bundles
import dev.androidskills.db.BundleKind
import dev.androidskills.db.Categories
import dev.androidskills.db.Role
import dev.androidskills.db.SkillFiles
import dev.androidskills.db.SkillStatus
import dev.androidskills.db.Skills
import dev.androidskills.db.TokenBand
import dev.androidskills.db.UserStatus
import dev.androidskills.db.Users
import dev.androidskills.db.VersionSource
import dev.androidskills.db.Versions
import dev.androidskills.ingest.Tokens
import dev.androidskills.storage.FileStore
import dev.androidskills.util.appJson
import dev.androidskills.util.newId
import dev.androidskills.util.nowIso
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A small, deterministic dataset that exercises the whole public read surface
 * (search facets, detail, files, versions, download, bundles, authors, timeline)
 * so the frontend and tests have something to render. Seeded only when the DB is
 * empty and `SEED_DEMO=1` (opt-in) — prod stays clean (spec §13).
 *
 * Manifest fields (name/description/tags/license) are written as if parsed from
 * a real `SKILL.md`; the token estimate is computed with the real [Tokens]
 * heuristic so seed data is consistent with ingest (§5).
 */
object DemoData {

    private data class SeedFile(val path: String, val content: String, val isBinary: Boolean = false)

    private data class SeedSkill(
        val slug: String,
        val name: String,
        val description: String,
        val license: String,
        val tags: List<String>,
        val category: String,
        val version: String,
        val verified: Boolean,
        val featured: Boolean = false,
        val body: String,
        val files: List<SeedFile>,
        val daysAgo: Int,
        val installs: Int,
    )

    private val skills = listOf(
        SeedSkill(
            slug = "jetpack-compose-mvi",
            name = "Jetpack Compose MVI Scaffold",
            description = "A predictable Model-View-Intent baseline for Compose apps with unidirectional data flow and side-effect handling.",
            license = "Apache-2.0",
            tags = listOf("android", "compose", "mvi", "architecture"),
            category = "jetpack-compose",
            version = "1.4.2",
            verified = true,
            featured = true,
            body = """
                # Jetpack Compose MVI Scaffold

                Renders state through a single `UiState` and routes user intents through one
                `ViewModel`. Keeps Compose code side-effect free.

                ## Usage
                Drop the folder into your `skills/` directory and point your feature at the
                `MviScreen` composable.

                ## State
                All UI state is a sealed hierarchy; events are intents reduced in the VM.
            """.trimIndent(),
            files = listOf(
                SeedFile("references/intent.md", "Intents are sealed classes reduced by the ViewModel.\n"),
                SeedFile("examples/CounterScreen.kt", "package example\n\n@Composable\nfun CounterScreen(vm: CounterViewModel) { /* ... */ }\n"),
            ),
            daysAgo = 2,
            installs = 1280,
        ),
        SeedSkill(
            slug = "coroutine-test-patterns",
            name = "Kotlin Coroutine Test Patterns",
            description = "Idiomatic patterns for testing suspend functions, Flows, and Dispatchers with Turbine and runTest.",
            license = "MIT",
            tags = listOf("kotlin", "coroutines", "testing"),
            category = "kotlin-language",
            version = "0.9.0",
            verified = true,
            body = """
                # Kotlin Coroutine Test Patterns

                Covers `runTest`, virtual time, `Turbine` collectors, and replacing Dispatchers
                with a TestDispatcher.
            """.trimIndent(),
            files = listOf(SeedFile("references/runtest.md", "Use runTest to control virtual time.\n")),
            daysAgo = 6,
            installs = 640,
        ),
        SeedSkill(
            slug = "room-migration-helper",
            name = "Room Migration Helper",
            description = "Safe incremental Room schema migrations with validated fallbacks and export checks.",
            license = "Apache-2.0",
            tags = listOf("android", "room", "database", "persistence"),
            category = "data-persistence",
            version = "2.1.0",
            verified = true,
            body = "# Room Migration Helper\n\nGenerate and validate fallback migrations.\n",
            files = listOf(SeedFile("references/migrations.md", "Always export schemas to /schemas.\n")),
            daysAgo = 11,
            installs = 410,
        ),
        SeedSkill(
            slug = "retrofit-okhttp-config",
            name = "Retrofit + OkHttp Baseline",
            description = "A hardened networking baseline: timeouts, retry, auth interceptor, and structured logging.",
            license = "Apache-2.0",
            tags = listOf("android", "networking", "retrofit", "okhttp"),
            category = "networking",
            version = "1.0.3",
            verified = false,
            body = "# Retrofit + OkHttp Baseline\n\nSensible defaults for HTTP clients.\n",
            files = emptyList(),
            daysAgo = 14,
            installs = 95,
        ),
        SeedSkill(
            slug = "baseline-profile-gradle",
            name = "Baseline Profile Gradle Setup",
            description = "Generate and ship Android Baseline Profiles from your Gradle build for faster cold start.",
            license = "Apache-2.0",
            tags = listOf("android", "performance", "gradle", "macrobench"),
            category = "performance",
            version = "0.3.1",
            verified = true,
            body = "# Baseline Profile Gradle Setup\n\nWires the baselineprofile plugin and CI.\n",
            files = listOf(SeedFile("references/ci.md", "Run Macrobenchmark on CI to refresh profiles.\n")),
            daysAgo = 20,
            installs = 210,
        ),
        SeedSkill(
            slug = "compose-accessibility-audit",
            name = "Compose Accessibility Audit",
            description = "Audit Compose UIs for content descriptions, touch targets, and contrast; surface findings as TODOs.",
            license = "Apache-2.0",
            tags = listOf("android", "compose", "accessibility", "a11y"),
            category = "accessibility",
            version = "0.5.0",
            verified = true,
            body = "# Compose Accessibility Audit\n\nChecks semantics, target sizes, and contrast.\n",
            files = emptyList(),
            daysAgo = 27,
            installs = 150,
        ),
    )

    fun seed(store: FileStore) = transaction {
        if (Skills.selectAll().count() > 0) return@transaction

        val base = Instant.now()
        fun isoDaysAgo(days: Int, hour: Int) = base.minus(days.toLong(), ChronoUnit.DAYS)
            .truncatedTo(ChronoUnit.DAYS).plus(hour.toLong(), ChronoUnit.HOURS).toString()

        val aliceId = insertUser("alice", githubId = 10_001, name = "Alice Tan", role = Role.contributor)
        val bobId = insertUser("bob", githubId = 10_002, name = "Bob Lee", role = Role.member)

        val repoBundle = insertBundle(
            kind = BundleKind.repo, provenance = "alice/jetpack-toolkit", ownerUserId = aliceId,
            sourceRef = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2", createdAt = isoDaysAgo(30, 9),
        )
        val zipBundle = insertBundle(
            kind = BundleKind.zip, provenance = "upload-bob-1", ownerUserId = bobId,
            sourceRef = "sha-256:upload-bob-1", createdAt = isoDaysAgo(18, 11),
        )

        // Bundle assignment by author for realism.
        skills.forEachIndexed { idx, s ->
            val bundleId = if (s.category == "networking" || s.category == "performance") zipBundle else repoBundle
            val authorId = if (bundleId == zipBundle) bobId else aliceId
            insertSkill(s, bundleId, authorId, store, createdOffsetHours = idx)
        }
    }

    private fun insertUser(handle: String, githubId: Long, name: String, role: Role): String {
        val id = newId()
        val now = nowIso()
        Users.insert {
            it[Users.id] = id
            it[Users.githubId] = githubId
            it[Users.handle] = handle
            it[Users.name] = name
            it[Users.avatarUrl] = "https://avatars.example.com/$handle.png"
            it[Users.role] = role.name // validated enum (fix: typed invariants)
            it[Users.status] = UserStatus.active.name
            it[Users.createdAt] = now
            it[Users.updatedAt] = now
        }
        return id
    }

    private fun insertBundle(
        kind: BundleKind, provenance: String, ownerUserId: String, sourceRef: String?, createdAt: String,
    ): String {
        val id = newId()
        Bundles.insert {
            it[Bundles.id] = id
            it[Bundles.kind] = kind.name
            it[Bundles.provenance] = provenance
            it[Bundles.ownerUserId] = ownerUserId
            it[Bundles.sourceRef] = sourceRef
            it[Bundles.createdAt] = createdAt
            it[Bundles.syncedAt] = createdAt
        }
        return id
    }

    private fun insertSkill(
        s: SeedSkill, bundleId: String, authorId: String, store: FileStore, createdOffsetHours: Int,
    ) {
        val skillId = newId()
        val categoryId = transaction {
            Categories.selectAll().where { Categories.slug eq s.category }.singleOrNull()?.get(Categories.id)
        }
        val created = nowIso() // stable enough; ordering tested via timeline aggregation
        val tagsJson = appJson.encodeToString(s.tags)
        val onDemandText = buildString {
            append(s.body)
            s.files.forEach { append("\n").append(it.content) }
        }
        val tokenUpfront = Tokens.estimate("${s.name} ${s.description}")
        val tokenOndemand = Tokens.estimate(onDemandText)
        val band = Tokens.band(tokenUpfront + tokenOndemand)

        Skills.insert {
            it[Skills.id] = skillId
            it[Skills.bundleId] = bundleId
            it[Skills.slug] = s.slug
            it[Skills.name] = s.name
            it[Skills.description] = s.description
            it[Skills.license] = s.license
            it[Skills.tags] = tagsJson
            it[Skills.categoryId] = categoryId
            it[Skills.version] = s.version
            it[Skills.versionSource] = VersionSource.manifest.name
            it[Skills.tokenUpfront] = tokenUpfront
            it[Skills.tokenOndemand] = tokenOndemand
            it[Skills.tokenBand] = TokenBand.parse(band).name // validate band is a real bucket
            it[Skills.verified] = s.verified
            it[Skills.status] = SkillStatus.published.name
            it[Skills.featured] = s.featured
            it[Skills.installs] = s.installs
            it[Skills.readmeMd] = s.body
            it[Skills.createdAt] = created
            it[Skills.updatedAt] = created
        }

        s.files.forEach { f ->
            val bytes = f.content.toByteArray(Charsets.UTF_8)
            val key = "skills/$skillId/files/${f.path}"
            store.put(key, bytes)
            SkillFiles.insert {
                it[SkillFiles.id] = newId()
                it[SkillFiles.skillId] = skillId
                it[SkillFiles.path] = f.path
                it[SkillFiles.size] = bytes.size
                it[SkillFiles.isBinary] = f.isBinary
                it[SkillFiles.r2Key] = key
            }
        }

        val zipKey = "skills/$skillId/versions/${s.version}.zip"
        store.put(zipKey, zipBytes(s))
        Versions.insert {
            it[Versions.id] = newId()
            it[Versions.skillId] = skillId
            it[Versions.version] = s.version
            it[Versions.sourceRef] = "manifest:${s.version}"
            it[Versions.r2ZipKey] = zipKey
            it[Versions.createdAt] = created
        }
    }

    private fun zipBytes(s: SeedSkill): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("SKILL.md"))
            zos.write(s.body.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            s.files.forEach { f ->
                zos.putNextEntry(ZipEntry(f.path))
                zos.write(f.content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
