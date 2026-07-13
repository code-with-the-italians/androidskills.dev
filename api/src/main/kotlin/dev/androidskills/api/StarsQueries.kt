package dev.androidskills.api

import dev.androidskills.auth.Principal
import dev.androidskills.db.SkillStatus
import dev.androidskills.db.Skills
import dev.androidskills.db.Stars
import dev.androidskills.util.nowIso
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** Account-synced starred library (spec §9 Contributor). */
object StarsQueries {

  /**
   * The user's starred skills as full [SkillCard]s (spec: `GET /api/me/stars` returns
   * `SkillCard[]`), newest star first. Unpublished/removed skills are dropped so the library never
   * renders a broken card.
   */
  fun listStars(principal: Principal): List<SkillCard> {
    val skillIds = transaction {
      Stars.selectAll()
        .where { Stars.userId eq principal.userId }
        .orderBy(Stars.createdAt to SortOrder.DESC)
        .map { it[Stars.skillId] }
    }
    return PublicQueries.cardsByIds(skillIds)
  }

  fun addStar(principal: Principal, slug: String) {
    transaction {
      val skill =
        Skills.selectAll()
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
      val skillId =
        Skills.selectAll().where { Skills.slug eq slug }.singleOrNull()?.get(Skills.id)
          ?: throw ApiNotFoundException("Skill not found")
      val op =
        org.jetbrains.exposed.sql.SqlExpressionBuilder.run {
          (Stars.userId eq principal.userId) and (Stars.skillId eq skillId)
        }
      Stars.deleteWhere { op }
    }
  }
}
