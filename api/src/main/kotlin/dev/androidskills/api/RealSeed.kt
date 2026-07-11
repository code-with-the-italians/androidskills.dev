package dev.androidskills.api

import dev.androidskills.db.BundleKind
import dev.androidskills.db.Bundles
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
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Seeds the public index with **real** Android agent-skills, catalogued from six public repos
 * (`android/skills`, `chrisbanes/skills`, `skydoves/{compose-performance,android-testing}-skills`,
 * `hamen/{material-3-skill,compose_skill}`). The data lives in `resources/seed/real-skills.json`,
 * generated offline from each repo's `SKILL.md` frontmatter (name/description/tags/license/body).
 *
 * Opt-in via `SEED_REAL=1`, and only when the DB is empty — prod stays clean (spec §13). This is a
 * sibling to [DemoData]: DemoData is the tiny deterministic fixture the read-path tests assert
 * against; RealSeed is the real, browsable catalogue used for staging.
 *
 * Rows are `published` + `verified` (curated, known authors) with **`installs = 0`** — we do not
 * fabricate popularity. Provenance (repo + commit sha) is real; token estimates use the real
 * [Tokens] heuristic so rows are consistent with ingest (§5). Only each skill's `SKILL.md` is
 * materialised (readme + a single-file version zip); mirroring support files is a follow-up.
 */
object RealSeed {

  @Serializable
  private data class SeedAuthor(val handle: String, val name: String, val githubId: Long)

  @Serializable
  private data class SeedBundle(
    val provenance: String,
    val author: String,
    val sourceRef: String,
    val license: String,
  )

  @Serializable
  private data class SeedSkill(
    val slug: String,
    val name: String,
    val description: String,
    val tags: List<String>,
    val license: String,
    val category: String,
    val bundle: String,
    val version: String,
    val featured: Boolean = false,
    val body: String,
  )

  @Serializable
  private data class SeedRoot(
    val authors: List<SeedAuthor>,
    val bundles: List<SeedBundle>,
    val skills: List<SeedSkill>,
  )

  private const val RESOURCE = "/seed/real-skills.json"

  private fun load(): SeedRoot {
    val text =
      (RealSeed::class.java.getResourceAsStream(RESOURCE)
          ?: error("seed resource $RESOURCE not found on classpath"))
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }
    return appJson.decodeFromString(SeedRoot.serializer(), text)
  }

  fun seed(store: FileStore) = transaction {
    if (Skills.selectAll().count() > 0) return@transaction

    val data = load()
    val base = Instant.now()

    // Authors → user ids.
    val userIds =
      data.authors.associate { it.handle to insertUser(it.handle, it.githubId, it.name) }

    // One repo bundle per source repo.
    val bundleIds =
      data.bundles.associate {
        it.provenance to
          insertBundle(it.provenance, userIds.getValue(it.author), it.sourceRef, base.toString())
      }

    // Category slug → id (categories are pre-seeded by Migrations/DEFAULT_CATEGORIES).
    val categoryIds = Categories.selectAll().associate { it[Categories.slug] to it[Categories.id] }

    data.skills.forEachIndexed { idx, s ->
      insertSkill(
        s = s,
        bundleId = bundleIds.getValue(s.bundle),
        categoryId = categoryIds[s.category],
        store = store,
        // Unique, descending timestamps within "just indexed" — real add-time, honest ordering.
        createdAt = base.minusSeconds(idx.toLong()).toString(),
      )
    }
  }

  private fun insertUser(handle: String, githubId: Long, name: String): String {
    val id = newId()
    val now = nowIso()
    Users.insert {
      it[Users.id] = id
      it[Users.githubId] = githubId
      it[Users.handle] = handle
      it[Users.name] = name
      it[Users.avatarUrl] = "https://github.com/$handle.png"
      it[Users.role] = Role.contributor.name
      it[Users.status] = UserStatus.active.name
      it[Users.createdAt] = now
      it[Users.updatedAt] = now
    }
    return id
  }

  private fun insertBundle(
    provenance: String,
    ownerUserId: String,
    sourceRef: String,
    createdAt: String,
  ): String {
    val id = newId()
    Bundles.insert {
      it[Bundles.id] = id
      it[Bundles.kind] = BundleKind.repo.name
      it[Bundles.provenance] = provenance
      it[Bundles.ownerUserId] = ownerUserId
      it[Bundles.sourceRef] = sourceRef
      it[Bundles.createdAt] = createdAt
      it[Bundles.syncedAt] = createdAt
    }
    return id
  }

  private fun insertSkill(
    s: SeedSkill,
    bundleId: String,
    categoryId: String?,
    store: FileStore,
    createdAt: String,
  ) {
    val skillId = newId()
    val tokenUpfront = Tokens.estimate("${s.name} ${s.description}")
    val tokenOndemand = Tokens.estimate(s.body)
    val band = Tokens.band(tokenUpfront + tokenOndemand)

    Skills.insert {
      it[Skills.id] = skillId
      it[Skills.bundleId] = bundleId
      it[Skills.slug] = s.slug
      it[Skills.sourceDir] = "skills/${s.slug}"
      it[Skills.name] = s.name
      it[Skills.description] = s.description
      it[Skills.license] = s.license
      it[Skills.tags] = appJson.encodeToString(s.tags)
      it[Skills.categoryId] = categoryId
      it[Skills.version] = s.version
      it[Skills.versionSource] = VersionSource.git_head.name
      it[Skills.tokenUpfront] = tokenUpfront
      it[Skills.tokenOndemand] = tokenOndemand
      it[Skills.tokenBand] = TokenBand.parse(band).name
      it[Skills.verified] = true
      it[Skills.status] = SkillStatus.published.name
      it[Skills.featured] = s.featured
      it[Skills.installs] = 0
      it[Skills.readmeMd] = s.body
      it[Skills.createdAt] = createdAt
      it[Skills.updatedAt] = createdAt
    }

    // Materialise SKILL.md as the single mirrored file (readme is stored on the skill row).
    val bytes = s.body.toByteArray(Charsets.UTF_8)
    val fileKey = "skills/$skillId/files/SKILL.md"
    store.put(fileKey, bytes)
    SkillFiles.insert {
      it[SkillFiles.id] = newId()
      it[SkillFiles.skillId] = skillId
      it[SkillFiles.path] = "SKILL.md"
      it[SkillFiles.size] = bytes.size
      it[SkillFiles.isBinary] = false
      it[SkillFiles.r2Key] = fileKey
    }

    val zipKey = "skills/$skillId/versions/${s.version}.zip"
    store.put(zipKey, zipBytes(s.body))
    Versions.insert {
      it[Versions.id] = newId()
      it[Versions.skillId] = skillId
      it[Versions.version] = s.version
      it[Versions.sourceRef] = "git_head:${s.version}"
      it[Versions.r2ZipKey] = zipKey
      it[Versions.createdAt] = createdAt
    }
  }

  private fun zipBytes(body: String): ByteArray {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zos ->
      zos.putNextEntry(ZipEntry("SKILL.md"))
      zos.write(body.toByteArray(Charsets.UTF_8))
      zos.closeEntry()
    }
    return baos.toByteArray()
  }
}
