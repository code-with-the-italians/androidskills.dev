package dev.androidskills.api

import dev.androidskills.auth.Principal
import dev.androidskills.db.Categories
import dev.androidskills.db.Skills
import dev.androidskills.db.SkillStatus
import dev.androidskills.db.Stars
import dev.androidskills.util.nowIso
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Account-synced starred library (spec §9 Contributor).
 */
object StarsQueries {

    @Serializable
    data class StarredSkill(
        val slug: String,
        val name: String,
        val category: String?,
        val createdAt: String,
    )

    fun listStars(principal: Principal): List<StarredSkill> = transaction {
        val stars = Stars.selectAll()
            .where { Stars.userId eq principal.userId }
            .orderBy(Stars.createdAt to SortOrder.DESC)
            .map { it[Stars.skillId] to it[Stars.createdAt] }
        val skillIds = stars.map { it.first }
        val skillsById = if (skillIds.isEmpty()) emptyMap() else {
            Skills.selectAll()
                .where { (Skills.id inList skillIds) and (Skills.status eq SkillStatus.published.name) }
                .associateBy({ it[Skills.id] }, { it })
        }
        val categoryIds = skillsById.values.mapNotNull { it[Skills.categoryId] }.distinct()
        val categoryNames = if (categoryIds.isEmpty()) emptyMap() else {
            Categories.selectAll()
                .where { Categories.id inList categoryIds }
                .associate { it[Categories.id] to it[Categories.name] }
        }
        stars.mapNotNull { (skillId, createdAt) ->
            val skill = skillsById[skillId] ?: return@mapNotNull null
            StarredSkill(
                slug = skill[Skills.slug],
                name = skill[Skills.name],
                category = skill[Skills.categoryId]?.let { categoryNames[it] },
                createdAt = createdAt,
            )
        }
    }

    fun addStar(principal: Principal, slug: String) {
        transaction {
            val skill = Skills.selectAll()
                .where { (Skills.slug eq slug) and (Skills.status eq SkillStatus.published.name) }
                .singleOrNull()
            if (skill == null) throw ApiNotFoundException("Skill not found")
            Stars.insertIgnore {
                it[Stars.userId] = principal.userId
                it[Stars.skillId] = skill[Skills.id]
                it[Stars.createdAt] = nowIso()
            }
        }
    }

    fun removeStar(principal: Principal, slug: String) {
        transaction {
            val skillId = Skills.selectAll().where { Skills.slug eq slug }.singleOrNull()?.get(Skills.id)
                ?: throw ApiNotFoundException("Skill not found")
            val op = org.jetbrains.exposed.sql.SqlExpressionBuilder.run {
                (Stars.userId eq principal.userId) and (Stars.skillId eq skillId)
            }
            Stars.deleteWhere { op }
        }
    }
}
