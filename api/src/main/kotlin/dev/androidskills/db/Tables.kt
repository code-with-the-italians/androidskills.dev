package dev.androidskills.db

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * Exposed table objects for the full domain model (spec §4).
 *
 * IDs are TEXT UUIDv4 (varchar(36)); timestamps are ISO-8601 UTC TEXT. Indexes
 * are declared on every foreign key and on the filter/sort columns referenced by
 * the API (spec §9). Defaults mirror the SQL in the spec so raw inserts behave.
 *
 * Foreign keys use `.references()` (not `reference()`/`optReference()`, which
 * require Exposed `IdTable`s — we keep plain `Table` so IDs stay plain `String`).
 *
 * Table objects are created in dependency order by [Migrations]; the order of the
 * `object` declarations here does not matter for DDL.
 *
 * Note on `index(...)`: Exposed exposes two ambiguous overloads; passing
 * `(name, isUnique=false, columns...)` unambiguously selects the named one.
 */

object Users : Table("users") {
    val id = varchar("id", 36)
    val githubId = long("github_id")
    val handle = text("handle")
    val name = text("name").nullable()
    val avatarUrl = text("avatar_url").nullable()
    val role = varchar("role", 24).default("member")        // member|contributor|admin
    val status = varchar("status", 24).default("active")    // active|suspended
    val settingsJson = text("settings_json").nullable()     // JSON UserSettings
    val deletedAt = text("deleted_at").nullable()           // soft-delete marker (step 6)
    val createdAt = text("created_at")
    val updatedAt = text("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_users_github_id", githubId)
        uniqueIndex("uq_users_handle", handle)
        index("ix_users_role_status", false, role, status)
    }
}

object Categories : Table("categories") {
    val id = varchar("id", 36)
    val slug = text("slug")
    val name = text("name")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_categories_slug", slug)
    }
}

object Bundles : Table("bundles") {
    val id = varchar("id", 36)
    val kind = varchar("kind", 16)                              // repo|zip
    val provenance = text("provenance")                         // repo full_name, or upload id
    val ownerUserId = varchar("owner_user_id", 36).references(Users.id, onDelete = ReferenceOption.CASCADE)
    val sourceRef = text("source_ref").nullable()               // commit sha / upload hash
    val installationId = long("installation_id").nullable()     // GitHub App installation id
    val syncedAt = text("synced_at").nullable()
    val createdAt = text("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        // First-come-first-served ownership: one bundle per (kind, provenance).
        uniqueIndex("uq_bundles_kind_provenance", kind, provenance)
        index("ix_bundles_owner", false, ownerUserId)
    }
}

object Skills : Table("skills") {
    val id = varchar("id", 36)
    val bundleId = varchar("bundle_id", 36).references(Bundles.id, onDelete = ReferenceOption.CASCADE)
    val slug = text("slug")
    val name = text("name")
    val description = text("description")
    val license = text("license").nullable()
    val tags = text("tags").default("[]")                       // JSON array string
    val categoryId = varchar("category_id", 36)
        .references(Categories.id, onDelete = ReferenceOption.SET_NULL)
        .nullable()
    val version = text("version")
    val versionSource = varchar("version_source", 16)           // manifest|git_head|upload
    val tokenUpfront = integer("token_upfront").default(0)
    val tokenOndemand = integer("token_ondemand").default(0)
    val tokenBand = varchar("token_band", 8).default("100s")    // 100s|1k|10k|100k
    val verified = bool("verified").default(false)
    val status = varchar("status", 16).default("published")     // published|unlisted|flagged
    val featured = bool("featured").default(false)
    val installs = integer("installs").default(0)
    val readmeMd = text("readme_md").nullable()
    val createdAt = text("created_at")
    val updatedAt = text("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_skills_slug", slug)
        index("ix_skills_bundle", false, bundleId)
        index("ix_skills_category", false, categoryId)
        index("ix_skills_status_verified", false, status, verified)
        index("ix_skills_featured", false, featured)
        index("ix_skills_installs", false, installs)
        index("ix_skills_updated", false, updatedAt)
        index("ix_skills_created", false, createdAt)
        index("ix_skills_name", false, name)
    }
}

object SkillFiles : Table("skill_files") {
    val id = varchar("id", 36)
    val skillId = varchar("skill_id", 36).references(Skills.id, onDelete = ReferenceOption.CASCADE)
    val path = text("path")
    val size = integer("size")
    val isBinary = bool("is_binary").default(false)
    val r2Key = text("r2_key")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_skill_files_skill_path", skillId, path)
        index("ix_skill_files_skill", false, skillId)
    }
}

object Versions : Table("versions") {
    val id = varchar("id", 36)
    val skillId = varchar("skill_id", 36).references(Skills.id, onDelete = ReferenceOption.CASCADE)
    val version = text("version")
    val sourceRef = text("source_ref")
    val r2ZipKey = text("r2_zip_key").nullable()
    val createdAt = text("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_versions_skill_version", skillId, version)
        index("ix_versions_skill", false, skillId)
        index("ix_versions_created", false, createdAt)
    }
}

object Submissions : Table("submissions") {
    val id = varchar("id", 36)
    val bundleId = varchar("bundle_id", 36)
        .references(Bundles.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val skillId = varchar("skill_id", 36)
        .references(Skills.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val submitterId = varchar("submitter_id", 36).references(Users.id, onDelete = ReferenceOption.CASCADE)
    val state = varchar("state", 24)                            // draft|in_review|changes_requested|published|rejected
    val lintScore = integer("lint_score").nullable()
    val note = text("note").nullable()
    val payload = text("payload").nullable()
    val createdAt = text("created_at")
    val updatedAt = text("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index("ix_submissions_submitter", false, submitterId)
        index("ix_submissions_state", false, state)
        index("ix_submissions_bundle", false, bundleId)
        index("ix_submissions_skill", false, skillId)
    }
}

object Stars : Table("stars") {
    val userId = varchar("user_id", 36).references(Users.id, onDelete = ReferenceOption.CASCADE)
    val skillId = varchar("skill_id", 36).references(Skills.id, onDelete = ReferenceOption.CASCADE)
    val createdAt = text("created_at")

    override val primaryKey = PrimaryKey(userId, skillId)
}

object Sessions : Table("sessions") {
    val id = varchar("id", 128)                                 // 256-bit token (cookie value)
    val userId = varchar("user_id", 36).references(Users.id, onDelete = ReferenceOption.CASCADE)
    val createdAt = text("created_at")
    val expiresAt = text("expires_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index("ix_sessions_user", false, userId)
        index("ix_sessions_expires", false, expiresAt)
    }
}

object Jobs : Table("jobs") {
    val id = varchar("id", 36)
    val type = varchar("type", 16)                              // review|resync
    val payload = text("payload")
    val state = varchar("state", 16).default("queued")         // queued|running|done|failed
    val attempts = integer("attempts").default(0)
    val runAfter = text("run_after")
    val lastError = text("last_error").nullable()
    val createdAt = text("created_at")
    val updatedAt = text("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        // The review worker polls `state='queued' AND run_after<=now`.
        index("ix_jobs_state_runafter", false, state, runAfter)
    }
}

object AuditLog : Table("audit_log") {
    val id = varchar("id", 36)
    val actorId = varchar("actor_id", 36).references(Users.id, onDelete = ReferenceOption.RESTRICT)
    val action = text("action")
    val target = text("target")
    val meta = text("meta").nullable()
    val createdAt = text("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index("ix_audit_created", false, createdAt)
        index("ix_audit_action", false, action)
    }
}

object Reports : Table("reports") {
    val id = varchar("id", 36)
    val skillId = varchar("skill_id", 36).references(Skills.id, onDelete = ReferenceOption.CASCADE)
    val reporterId = varchar("reporter_id", 36)
        .references(Users.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val reason = text("reason")
    val createdAt = text("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index("ix_reports_skill", false, skillId)
        index("ix_reports_created", false, createdAt)
    }
}
