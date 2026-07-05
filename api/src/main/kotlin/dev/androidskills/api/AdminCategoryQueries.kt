package dev.androidskills.api

import dev.androidskills.auth.Principal
import dev.androidskills.db.Categories
import dev.androidskills.db.Skills
import dev.androidskills.util.newId
import dev.androidskills.util.nowIso
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** Admin category management (spec §9). Create, rename, and merge categories. */
object AdminCategoryQueries {

  @Serializable data class CategoryDto(val id: String, val slug: String, val name: String)

  @Serializable data class RenameRequest(val name: String? = null, val slug: String? = null)

  fun list(): List<CategoryDto> = transaction {
    Categories.selectAll().orderBy(Categories.name to SortOrder.ASC).map { row ->
      CategoryDto(row[Categories.id], row[Categories.slug], row[Categories.name])
    }
  }

  fun create(principal: Principal, slug: String, name: String): CategoryDto {
    val trimmed = slug.trim().lowercase().replace(Regex("[^a-z0-9-]"), "-")
    if (trimmed.isEmpty() || trimmed.length < 2) {
      throw ApiValidationException(mapOf("slug" to "must be at least 2 alphanumeric chars"))
    }
    return transaction {
      val existing = Categories.selectAll().where { Categories.slug eq trimmed }.singleOrNull()
      if (existing != null)
        throw ApiConflictException("Category slug already exists", "duplicate_category")
      val id = newId()
      Categories.insert {
        it[Categories.id] = id
        it[Categories.slug] = trimmed
        it[Categories.name] = name
      }
      AuditLogQueries.write(
        actorId = principal.userId,
        action = "category.create",
        target = "category:$trimmed",
      )
      CategoryDto(id, trimmed, name)
    }
  }

  fun rename(principal: Principal, oldSlug: String, request: RenameRequest): CategoryDto {
    val newSlug = request.slug?.trim()?.lowercase()?.replace(Regex("[^a-z0-9-]"), "-")
    val newName = request.name?.trim()
    transaction {
      val category =
        Categories.selectAll().where { Categories.slug eq oldSlug }.singleOrNull()
          ?: throw ApiNotFoundException("Category not found")
      newSlug?.let { slug ->
        if (slug != oldSlug) {
          val conflict = Categories.selectAll().where { Categories.slug eq slug }.singleOrNull()
          if (conflict != null)
            throw ApiConflictException("Target slug already in use", "duplicate_category")
        }
      }
      val targetSlug = newSlug ?: oldSlug
      val targetName = newName ?: category[Categories.name]
      Categories.update({ Categories.id eq category[Categories.id] }) {
        it[Categories.slug] = targetSlug
        it[Categories.name] = targetName
      }
      AuditLogQueries.write(
        actorId = principal.userId,
        action = "category.rename",
        target = "category:$oldSlug",
        meta = AuditLogQueries.AuditMeta(after = mapOf("slug" to targetSlug, "name" to targetName)),
      )
    }
    val finalSlug = newSlug ?: oldSlug
    val row = transaction { Categories.selectAll().where { Categories.slug eq finalSlug }.single() }
    return CategoryDto(row[Categories.id], row[Categories.slug], row[Categories.name])
  }

  fun merge(principal: Principal, fromSlug: String, toSlug: String) {
    if (fromSlug == toSlug)
      throw ApiConflictException("Cannot merge a category into itself", "merge_self")
    transaction {
      val from =
        Categories.selectAll().where { Categories.slug eq fromSlug }.singleOrNull()
          ?: throw ApiNotFoundException("Source category not found")
      val to =
        Categories.selectAll().where { Categories.slug eq toSlug }.singleOrNull()
          ?: throw ApiNotFoundException("Target category not found")
      val fromId = from[Categories.id]
      val toId = to[Categories.id]

      Skills.update({ Skills.categoryId eq fromId }) {
        it[Skills.categoryId] = toId
        it[Skills.updatedAt] = nowIso()
      }
      val deleteOp = org.jetbrains.exposed.sql.SqlExpressionBuilder.run { Categories.id eq fromId }
      Categories.deleteWhere { deleteOp }
      AuditLogQueries.write(
        actorId = principal.userId,
        action = "category.merge",
        target = "category:$fromSlug",
        meta = AuditLogQueries.AuditMeta(after = mapOf("into" to toSlug)),
      )
    }
  }
}
