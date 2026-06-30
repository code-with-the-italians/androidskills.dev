package dev.androidskills.api

import dev.androidskills.auth.Principal
import dev.androidskills.db.BundleKind
import dev.androidskills.db.Bundles
import dev.androidskills.db.Jobs
import dev.androidskills.db.SkillStatus
import dev.androidskills.db.Skills
import dev.androidskills.db.Submissions
import dev.androidskills.db.VersionSource
import dev.androidskills.db.Versions
import dev.androidskills.github.GitHubAppClient
import dev.androidskills.github.GitHubAppException
import dev.androidskills.ingest.ManifestValidator
import dev.androidskills.ingest.StagedPayload
import dev.androidskills.ingest.SubmissionPayload
import dev.androidskills.util.appJson
import dev.androidskills.util.newId
import dev.androidskills.util.nowIso
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
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
    private val TAG_RE = Regex("^[a-z0-9][a-z0-9._+-]{0,39}$")

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
                .firstOrNull {
                    // SEC1: must be an installation controlled by the principal. The App-level
                    // /app/installations list includes every account that installed the App; we
                    // reject any installation whose account is not the caller's (org installs
                    // require membership proof and are deferred).
                    it.accountId == principal.githubId &&
                        it.accountLogin.equals(request.repoOwner, ignoreCase = true)
                }
                ?.id
        } catch (e: GitHubAppException) {
            throw ApiBadGatewayException("GitHub installations request failed: ${e.message}", "github_installations_failed")
        } ?: throw ApiValidationException(
            mapOf("repo" to "install the GitHub App on '${request.repoOwner}' first"),
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

                // Slug must not already belong to a different bundle. If it belongs to
                // the same bundle, we only allow re-drafting an unlisted shell; a published
                // skill or an active submission must be handled via the admin queue (Bugbot
                // high + medium findings).
                val existingSkill = Skills.selectAll().where { Skills.slug eq selection.slug }.singleOrNull()
                if (existingSkill != null && existingSkill[Skills.bundleId] != bundleId) {
                    throw ApiConflictException(
                        "Skill slug '${selection.slug}' is already used by another repository",
                        code = "slug_conflict",
                    )
                }
                if (existingSkill != null && existingSkill[Skills.status] == SkillStatus.published.name) {
                    throw ApiConflictException(
                        "Skill '${selection.slug}' is already published; updates go through the admin queue",
                        code = "skill_already_published",
                    )
                }
                if (existingSkill != null) {
                    val activeSub = Submissions.selectAll()
                        .where {
                            (Submissions.bundleId eq bundleId) and
                                (Submissions.skillId eq existingSkill[Skills.id]) and
                                (Submissions.state inList listOf("in_review", "changes_requested", "published", "rejected"))
                        }
                        .singleOrNull()
                    if (activeSub != null) {
                        throw ApiConflictException(
                            "An active submission already exists for '${selection.slug}'",
                            code = "submission_already_active",
                        )
                    }
                }

                val skillId = if (existingSkill != null) {
                    // Same bundle, unlisted shell: update the shell from the latest scan metadata.
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

    /**
     * Moves a draft submission to `in_review` and enqueues a metadata-only review
     * job (guarded against duplicates). Validates the manifest per §10.
     */
    fun submit(principal: Principal, submissionId: String) {
        transaction {
            val row = Submissions.selectAll()
                .where { (Submissions.id eq submissionId) and (Submissions.submitterId eq principal.userId) }
                .singleOrNull() ?: throw ApiNotFoundException("Submission not found")
            if (row[Submissions.state] != "draft") {
                throw ApiConflictException("Submission is not a draft", code = "invalid_state_transition")
            }
            val skillId = row[Submissions.skillId]
                ?: throw IllegalStateException("Draft submission $submissionId has no skill")
            val payload = row[Submissions.payload]?.let {
                runCatching { appJson.decodeFromString(SubmissionPayload.serializer(), it) }.getOrNull()
            }
            val staged = payload?.staged
                ?: throw IllegalStateException("Draft submission $submissionId has no staged payload")
            val selection = SelectedSkill(
                slug = Skills.selectAll().where { Skills.id eq skillId }.singleOrNull()?.get(Skills.slug) ?: "",
                name = staged.name,
                description = staged.description,
                license = staged.license ?: "",
                tags = staged.tags,
                version = staged.version,
            )
            val expectSemVer = staged.versionSource == VersionSource.manifest.name
            val errors = validateForSubmit(selection, expectSemVer)
            if (errors.isNotEmpty()) throw ApiValidationException(errors)

            val now = nowIso()
            Submissions.update({ Submissions.id eq submissionId }) {
                it[Submissions.state] = "in_review"
                it[Submissions.updatedAt] = now
            }
            enqueueReview(skillId, submissionId, now)
        }
    }

    /**
     * Withdraws an `in_review` submission back to `draft`. Clears any queued
     * review job; a running job is left alone (its output will be merged but
     * ignored until the next submit).
     */
    fun withdraw(principal: Principal, submissionId: String) {
        transaction {
            val row = Submissions.selectAll()
                .where { (Submissions.id eq submissionId) and (Submissions.submitterId eq principal.userId) }
                .singleOrNull() ?: throw ApiNotFoundException("Submission not found")
            if (row[Submissions.state] != "in_review") {
                throw ApiConflictException("Submission is not in review", code = "invalid_state_transition")
            }
            Submissions.update({ Submissions.id eq submissionId }) {
                it[Submissions.state] = "draft"
                it[Submissions.updatedAt] = nowIso()
            }
            // Remove queued review jobs for this submission.
            val reviewPayload = appJson.encodeToString(
                ReviewPayload(skillId = row[Submissions.skillId] ?: "", submissionId = submissionId),
            )
            val deleteOp = org.jetbrains.exposed.sql.SqlExpressionBuilder.run {
                (Jobs.type eq "review") and (Jobs.payload eq reviewPayload) and (Jobs.state eq "queued")
            }
            Jobs.deleteWhere { deleteOp }
        }
    }

    /**
     * Deletes a draft submission and its unlisted skill shell, but only if the
     * skill has no published versions (prevents accidental data loss).
     */
    fun deleteDraft(principal: Principal, submissionId: String) {
        transaction {
            val row = Submissions.selectAll()
                .where { (Submissions.id eq submissionId) and (Submissions.submitterId eq principal.userId) }
                .singleOrNull() ?: throw ApiNotFoundException("Submission not found")
            if (row[Submissions.state] != "draft") {
                throw ApiConflictException("Only drafts can be deleted", code = "invalid_state_transition")
            }
            val skillId = row[Submissions.skillId]
            val hasVersions = skillId?.let { sid -> Versions.selectAll().where { Versions.skillId eq sid }.any() } ?: false
            val subOp = org.jetbrains.exposed.sql.SqlExpressionBuilder.run { Submissions.id eq submissionId }
            Submissions.deleteWhere { subOp }
            if (skillId != null && !hasVersions) {
                val skillOp = org.jetbrains.exposed.sql.SqlExpressionBuilder.run { Skills.id eq skillId }
                Skills.deleteWhere { skillOp }
            }
        }
    }

    private fun enqueueReview(skillId: String, submissionId: String, now: String) {
        val payload = appJson.encodeToString(
            ReviewPayload(skillId = skillId, submissionId = submissionId),
        )
        val alreadyQueued = Jobs.selectAll()
            .where { (Jobs.type eq "review") and (Jobs.payload eq payload) and (Jobs.state inList listOf("queued", "running")) }
            .any()
        if (!alreadyQueued) {
            Jobs.insert {
                it[Jobs.id] = newId()
                it[Jobs.type] = "review"
                it[Jobs.payload] = payload
                it[Jobs.state] = "queued"
                it[Jobs.attempts] = 0
                it[Jobs.runAfter] = now
                it[Jobs.createdAt] = now
                it[Jobs.updatedAt] = now
            }
        }
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

    /**
     * Validates the manifest fields required before a submission can move to
     * `in_review` (spec §10). Returns a map of field → reason, empty if valid.
     * [expectSemVer] is true when the version came from the manifest (not a
     * derived git HEAD ref).
     */
    fun validateForSubmit(selection: SelectedSkill, expectSemVer: Boolean): Map<String, String> {
        val errors = mutableMapOf<String, String>()
        if (!SLUG_RE.matches(selection.slug)) errors["slug"] = "must be lowercase kebab-case"
        if (selection.name.isBlank()) errors["name"] = "is required"
        if (selection.description.isBlank()) errors["description"] = "is required"
        if (selection.license.isBlank()) errors["license"] = "is required"
        selection.tags.forEachIndexed { i, tag ->
            if (!TAG_RE.matches(tag)) errors["tags[$i]"] = "must be lowercase alnum/._+- (max 40)"
        }
        if (expectSemVer && selection.version != null && !ManifestValidator.SEMVER.matches(selection.version)) {
            errors["version"] = "must be valid SemVer"
        }
        return errors
    }

    private fun validateSlug(slug: String) {
        if (!SLUG_RE.matches(slug)) {
            throw ApiValidationException(mapOf("slug" to "must be lowercase kebab-case"))
        }
    }

    @Serializable
    private data class ReviewPayload(val skillId: String, val submissionId: String)
}