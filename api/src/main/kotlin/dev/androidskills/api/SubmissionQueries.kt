package dev.androidskills.api

import dev.androidskills.auth.Principal
import dev.androidskills.db.BundleKind
import dev.androidskills.db.Bundles
import dev.androidskills.db.SkillStatus
import dev.androidskills.db.Skills
import dev.androidskills.db.Submissions
import dev.androidskills.db.VersionSource
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import dev.androidskills.github.GitHubAppClient
import dev.androidskills.github.GitHubAppException
import dev.androidskills.ingest.StagedPayload
import dev.androidskills.ingest.SubmissionPayload
import dev.androidskills.util.appJson
import dev.androidskills.util.newId
import dev.androidskills.util.nowIso
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Contributor submission lifecycle (spec §9 Contributor, step 6).
 *
 * Drafts are created from scan results and are where bundles are first
 * materialised (first-come-first-served ownership, §3.4). Each selected skill
 * becomes an unlisted skill shell + a draft submission. Submit moves the
 * submission to `in_review` and enqueues a metadata-only review via the step-5
 * worker.
 */
object SubmissionQueries {

    /** Lowercase kebab-case, matching the spec §10 slug rule. */
    private val SLUG_RE = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

    @Serializable
    data class CreateDraftsRequest(
        val repoOwner: String,
        val repoName: String,
        val ref: String,
        val skills: List<SelectedSkill>,
    )

    @Serializable
    data class SelectedSkill(
        val slug: String,
        val name: String,
        val description: String,
        val license: String,
        val tags: List<String> = emptyList(),
        val version: String? = null,
    )

    @Serializable
    data class CreateDraftsResponse(val submissionIds: List<String>)

    @Serializable
    data class SubmissionSummary(
        val id: String,
        val slug: String,
        val state: String,
        val lintScore: Int?,
        val createdAt: String,
        val updatedAt: String,
    )

    @Serializable
    data class GroupedSubmissions(
        val state: String,
        val items: List<SubmissionSummary>,
    )

    @Serializable
    data class SubmissionDetail(
        val id: String,
        val slug: String,
        val state: String,
        val lintScore: Int?,
        val note: String?,
        val payload: SubmissionPayload?,
        val createdAt: String,
        val updatedAt: String,
    )

    /**
     * Creates a bundle (first-come) and one draft submission per selected skill.
     * Each skill becomes an unlisted shell so the step-5 review worker has a row
     * to read. Validates slugs up front to avoid claiming bad slugs.
     */
    suspend fun createDrafts(
        principal: Principal,
        request: CreateDraftsRequest,
        gh: GitHubAppClient,
    ): CreateDraftsResponse {
        if (request.repoOwner.isBlank() || request.repoName.isBlank() || request.ref.isBlank()) {
            throw ApiValidationException(mapOf("repo" to "owner, repo and ref are required"))
        }
        if (request.skills.isEmpty()) {
            throw ApiValidationException(mapOf("skills" to "at least one skill is required"))
        }

        val provenance = "${request.repoOwner}/${request.repoName}"

        // Resolve the GitHub App installation for this repo. The user must have
        // installed the App on the account that owns the repo.
        val installationId = try {
            gh.installations()
                .firstOrNull { it.accountLogin.equals(request.repoOwner, ignoreCase = true) }
                ?.id
        } catch (e: GitHubAppException) {
            throw ApiBadGatewayException("GitHub installations request failed: ${e.message}", "github_installations_failed")
        } ?: throw ApiValidationException(
            mapOf("repo" to "install the GitHub App on '$request.repoOwner' first"),
            code = "github_app_not_installed",
        )

        val now = nowIso()
        val submissionIds = transaction {
            // First-come-first-served bundle ownership (§3.4).
            val existingBundle = Bundles.selectAll()
                .where { (Bundles.kind eq BundleKind.repo.name) and (Bundles.provenance eq provenance) }
                .singleOrNull()
            val bundleId = if (existingBundle != null) {
                if (existingBundle[Bundles.ownerUserId] != principal.userId) {
                    throw ApiConflictException(
                        "Repository '$provenance' is already linked to another account",
                        code = "bundle_ownership_conflict",
                    )
                }
                existingBundle[Bundles.id]
            } else {
                val id = newId()
                Bundles.insert {
                    it[Bundles.id] = id
                    it[Bundles.kind] = BundleKind.repo.name
                    it[Bundles.provenance] = provenance
                    it[Bundles.ownerUserId] = principal.userId
                    it[Bundles.sourceRef] = request.ref
                    it[Bundles.installationId] = installationId
                    it[Bundles.createdAt] = now
                }
                id
            }

            request.skills.map { selection ->
                validateSlug(selection.slug)
                val version = selection.version ?: request.ref.take(12)
                val versionSource = if (selection.version != null) VersionSource.manifest.name else VersionSource.git_head.name

                // Slug must not already belong to a different bundle.
                val existingSkill = Skills.selectAll().where { Skills.slug eq selection.slug }.singleOrNull()
                if (existingSkill != null && existingSkill[Skills.bundleId] != bundleId) {
                    throw ApiConflictException(
                        "Skill slug '${selection.slug}' is already used by another repository",
                        code = "slug_conflict",
                    )
                }

                val skillId = if (existingSkill != null) {
                    // Same bundle: update the shell from the latest scan metadata.
                    val id = existingSkill[Skills.id]
                    Skills.update({ Skills.id eq id }) {
                        it[Skills.name] = selection.name
                        it[Skills.description] = selection.description
                        it[Skills.license] = selection.license
                        it[Skills.tags] = appJson.encodeToString(selection.tags)
                        it[Skills.version] = version
                        it[Skills.versionSource] = versionSource
                        it[Skills.updatedAt] = now
                    }
                    id
                } else {
                    val id = newId()
                    Skills.insert {
                        it[Skills.id] = id
                        it[Skills.bundleId] = bundleId
                        it[Skills.slug] = selection.slug
                        it[Skills.name] = selection.name
                        it[Skills.description] = selection.description
                        it[Skills.license] = selection.license
                        it[Skills.tags] = appJson.encodeToString(selection.tags)
                        it[Skills.version] = version
                        it[Skills.versionSource] = versionSource
                        it[Skills.status] = SkillStatus.unlisted.name
                        it[Skills.verified] = false
                        it[Skills.createdAt] = now
                        it[Skills.updatedAt] = now
                    }
                    id
                }

                // Reuse an existing draft submission for (bundle, skill) if present.
                val existingSub = Submissions.selectAll()
                    .where {
                        (Submissions.bundleId eq bundleId) and
                            (Submissions.skillId eq skillId) and
                            (Submissions.state eq "draft")
                    }
                    .singleOrNull()

                val subId = if (existingSub != null) {
                    val id = existingSub[Submissions.id]
                    val staged = buildStaged(selection, version, versionSource, request)
                    val merged = SubmissionPayload(staged = staged)
                    Submissions.update({ Submissions.id eq id }) {
                        it[Submissions.payload] = appJson.encodeToString(SubmissionPayload.serializer(), merged)
                        it[Submissions.updatedAt] = now
                    }
                    id
                } else {
                    val id = newId()
                    val staged = buildStaged(selection, version, versionSource, request)
                    Submissions.insert {
                        it[Submissions.id] = id
                        it[Submissions.bundleId] = bundleId
                        it[Submissions.skillId] = skillId
                        it[Submissions.submitterId] = principal.userId
                        it[Submissions.state] = "draft"
                        it[Submissions.payload] = appJson.encodeToString(SubmissionPayload.serializer(), SubmissionPayload(staged = staged))
                        it[Submissions.createdAt] = now
                        it[Submissions.updatedAt] = now
                    }
                    id
                }
                subId
            }
        }
        return CreateDraftsResponse(submissionIds)
    }

    fun mySubmissions(principal: Principal): List<GroupedSubmissions> = transaction {
        val rows = Submissions.selectAll()
            .where { Submissions.submitterId eq principal.userId }
            .orderBy(Submissions.updatedAt to org.jetbrains.exposed.sql.SortOrder.DESC)
            .map {
                SubmissionSummary(
                    id = it[Submissions.id],
                    slug = it[Submissions.skillId]?.let { sid ->
                        Skills.selectAll().where { Skills.id eq sid }.singleOrNull()?.get(Skills.slug)
                    } ?: "",
                    state = it[Submissions.state],
                    lintScore = it[Submissions.lintScore],
                    createdAt = it[Submissions.createdAt],
                    updatedAt = it[Submissions.updatedAt],
                )
            }
        rows.groupBy { it.state }.map { (state, items) -> GroupedSubmissions(state, items) }
    }

    fun getSubmission(principal: Principal, submissionId: String): SubmissionDetail? = transaction {
        val row = Submissions.selectAll()
            .where { (Submissions.id eq submissionId) and (Submissions.submitterId eq principal.userId) }
            .singleOrNull() ?: return@transaction null
        val skillId = row[Submissions.skillId]
        val slug = skillId?.let { sid ->
            Skills.selectAll().where { Skills.id eq sid }.singleOrNull()?.get(Skills.slug)
        } ?: ""
        val payload = row[Submissions.payload]?.let {
            runCatching { appJson.decodeFromString(SubmissionPayload.serializer(), it) }.getOrNull()
        }
        SubmissionDetail(
            id = row[Submissions.id],
            slug = slug,
            state = row[Submissions.state],
            lintScore = row[Submissions.lintScore],
            note = row[Submissions.note],
            payload = payload,
            createdAt = row[Submissions.createdAt],
            updatedAt = row[Submissions.updatedAt],
        )
    }

    private fun buildStaged(
        selection: SelectedSkill,
        version: String,
        versionSource: String,
        request: CreateDraftsRequest,
    ) = StagedPayload(
        version = version,
        versionSource = versionSource,
        name = selection.name,
        description = selection.description,
        license = selection.license,
        tags = selection.tags,
        sourceRef = StagedPayload.SourceRef(
            repoOwner = request.repoOwner,
            repoName = request.repoName,
            ref = request.ref,
        ),
    )

    private fun validateSlug(slug: String) {
        if (!SLUG_RE.matches(slug)) {
            throw ApiValidationException(mapOf("slug" to "must be lowercase kebab-case"))
        }
    }
}
