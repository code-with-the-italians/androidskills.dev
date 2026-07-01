package dev.androidskills.api

import dev.androidskills.auth.Principal
import dev.androidskills.db.Categories
import dev.androidskills.db.Skills
import dev.androidskills.util.nowIso
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Admin skill management (spec §9). Admins may edit only non-manifest fields;
 * [name/description/license/tags] are read-only from SKILL.md.
 */
object AdminSkillQueries {

    const val PAGE_SIZE = 50

    @Serializable
    data class SkillListItem(
        val id: String,
        val slug: String,
        val name: String,
        val status: String,
        val verified: Boolean,
        val featured: Boolean,
        val categoryId: String?,
        val categorySlug: String?,
        val installs: Int,
        val updatedAt: String,
    )

    @Serializable
    data class AdminSkillPatch(
        val featured: Boolean? = null,
        val status: String? = null, // published|unlisted|flagged
        val categoryId: String? = null,
    )

    @Serializable
    data class BulkActionRequest(
        val ids: List<String>,
        val action: String, // feature|unlist|delete
    )

    @Serializable
    data class BulkActionResponse(
        val results: Map<String, Boolean>,
        val failed: Map<String, String>,
    )

    fun list(filter: String? = null, search: String? = null, sort: String? = "updated", page: Int? = 1): List<SkillListItem> = transaction {
        val validStatuses = setOf("published", "unlisted", "flagged")
        if (filter != null && filter !in validStatuses) {
            throw ApiValidationException(mapOf("filter" to "must be published, unlisted, or flagged"))
        }
        val query = (Skills leftJoin Categories)
            .selectAll()
            .apply {
                if (filter != null) andWhere { Skills.status eq filter }
                if (!search.isNullOrBlank()) {
                    val pat = "%${search.replace("%", "").replace("_", "")}%"
                    andWhere { (Skills.slug like pat) or (Skills.name like pat) }
                }
            }
            .orderBy(resolveSort(sort) to SortOrder.DESC)
            .limit(PAGE_SIZE)
            .offset(((page ?: 1).coerceAtLeast(1) - 1L) * PAGE_SIZE)

        query.map { row ->
            SkillListItem(
                id = row[Skills.id],
                slug = row[Skills.slug],
                name = row[Skills.name],
                status = row[Skills.status],
                verified = row[Skills.verified],
                featured = row[Skills.featured],
                categoryId = row[Skills.categoryId],
                categorySlug = row[Categories.slug],
                installs = row[Skills.installs],
                updatedAt = row[Skills.updatedAt],
            )
        }
    }

    private fun resolveSort(sort: String?): Column<*> = when (sort) {
        "updated" -> Skills.updatedAt
        "installs" -> Skills.installs
        "created" -> Skills.createdAt
        "name" -> Skills.name
        else -> Skills.updatedAt
    }

    fun patch(principal: Principal, id: String, patch: AdminSkillPatch) {
        transaction {
            val skill = Skills.selectAll().where { Skills.id eq id }.singleOrNull()
                ?: throw ApiNotFoundException("Skill not found")

            val status = patch.status?.let {
                when (it) {
                    "published", "unlisted", "flagged" -> it
                    else -> throw ApiValidationException(mapOf("status" to "must be published, unlisted, or flagged"))
                }
            }

            patch.categoryId?.let { catId ->
                if (Categories.selectAll().where { Categories.id eq catId }.count() == 0L) {
                    throw ApiNotFoundException("Category not found")
                }
            }

            val before = mapOf(
                "status" to skill[Skills.status],
                "featured" to skill[Skills.featured].toString(),
                "categoryId" to skill[Skills.categoryId],
            )
            val now = nowIso()
            Skills.update({ Skills.id eq id }) {
                patch.featured?.let { v -> it[Skills.featured] = v }
                status?.let { v -> it[Skills.status] = v }
                patch.categoryId?.let { v -> it[Skills.categoryId] = v }
                it[Skills.updatedAt] = now
            }
            val after = mapOf(
                "status" to (status ?: skill[Skills.status]),
                "featured" to (patch.featured?.toString() ?: skill[Skills.featured].toString()),
                "categoryId" to (patch.categoryId ?: skill[Skills.categoryId]),
            )

            AuditLogQueries.write(
                actorId = principal.userId,
                action = "skill.patch",
                target = "skill:$id",
                meta = AuditLogQueries.AuditMeta(before = before, after = after),
            )
        }
    }

    fun bulk(principal: Principal, request: BulkActionRequest): BulkActionResponse {
        val results = mutableMapOf<String, Boolean>()
        val failed = mutableMapOf<String, String>()

        for (id in request.ids) {
            try {
                transaction {
                    val skill = Skills.selectAll().where { Skills.id eq id }.singleOrNull()
                        ?: throw ApiNotFoundException("Skill not found")

                    when (request.action) {
                        "feature" -> {
                            Skills.update({ Skills.id eq id }) {
                                it[Skills.featured] = true
                                it[Skills.updatedAt] = nowIso()
                            }
                            AuditLogQueries.write(
                                actorId = principal.userId,
                                action = "skill.feature",
                                target = "skill:$id",
                                meta = AuditLogQueries.AuditMeta(after = mapOf("featured" to "true")),
                            )
                        }
                        "unlist" -> {
                            Skills.update({ Skills.id eq id }) {
                                it[Skills.status] = "unlisted"
                                it[Skills.updatedAt] = nowIso()
                            }
                            AuditLogQueries.write(
                                actorId = principal.userId,
                                action = "skill.unlist",
                                target = "skill:$id",
                                meta = AuditLogQueries.AuditMeta(after = mapOf("status" to "unlisted")),
                            )
                        }
                        "delete" -> {
                            throw ApiBadRequestException("Delete not implemented; use unlist")
                        }
                        else -> throw ApiValidationException(mapOf("action" to "must be feature, unlist, or delete"))
                    }
                }
                results[id] = true
            } catch (e: Throwable) {
                failed[id] = e.message ?: "failed"
            }
        }
        return BulkActionResponse(results, failed)
    }
}
