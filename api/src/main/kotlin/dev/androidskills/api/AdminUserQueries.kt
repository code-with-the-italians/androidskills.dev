package dev.androidskills.api

import dev.androidskills.auth.Principal
import dev.androidskills.db.Bundles
import dev.androidskills.db.Role
import dev.androidskills.db.Skills
import dev.androidskills.db.UserStatus
import dev.androidskills.db.Users
import dev.androidskills.util.nowIso
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Admin user roster management (spec §9). Includes §3.9 self-guards: an admin
 * cannot modify their own role/status, and the last remaining admin cannot be
 * demoted or suspended.
 */
object AdminUserQueries {

    const val PAGE_SIZE = 50

    @Serializable
    data class UserListItem(
        val id: String,
        val handle: String,
        val role: String,
        val status: String,
        val skillCount: Int,
        val createdAt: String,
    )

    @Serializable
    data class AdminUserPatch(
        val role: String? = null,
        val status: String? = null,
    )

    fun list(filter: String? = null, search: String? = null, page: Int? = 1): List<UserListItem> = transaction {
        val validStatuses = setOf("active", "suspended")
        if (filter != null && filter !in validStatuses) {
            throw ApiValidationException(mapOf("filter" to "must be active or suspended"))
        }

        val query = Users
            .selectAll()
            .apply {
                if (filter == "active") andWhere { Users.status eq UserStatus.active.name }
                if (filter == "suspended") andWhere { Users.status eq UserStatus.suspended.name }
                if (!search.isNullOrBlank()) {
                    val pat = "%${search.replace("%", "").replace("_", "")}%"
                    andWhere { (Users.handle like pat) or (Users.name like pat) }
                }
            }
            .orderBy(Users.createdAt to SortOrder.DESC)

        val paged = page?.let { p ->
            query.limit(PAGE_SIZE).offset(((p.coerceAtLeast(1) - 1L) * PAGE_SIZE))
        } ?: query

        val rows = paged.toList()
        val userIds = rows.map { it[Users.id] }
        val counts = if (userIds.isEmpty()) emptyMap() else {
            Skills.join(Bundles, JoinType.INNER, Skills.bundleId, Bundles.id)
                .select(Bundles.ownerUserId, Skills.id.count())
                .where { Bundles.ownerUserId inList userIds }
                .groupBy(Bundles.ownerUserId)
                .associate { it[Bundles.ownerUserId] to it[Skills.id.count()].toInt() }
        }

        rows.map { row ->
            UserListItem(
                id = row[Users.id],
                handle = row[Users.handle],
                role = row[Users.role],
                status = row[Users.status],
                skillCount = counts[row[Users.id]] ?: 0,
                createdAt = row[Users.createdAt],
            )
        }
    }

    fun patch(principal: Principal, id: String, patch: AdminUserPatch) {
        if (principal.userId == id) {
            throw ApiConflictException("Admins cannot modify their own role or status", "admin_self_guard")
        }
        transaction {
            val user = Users.selectAll().where { Users.id eq id }.singleOrNull()
                ?: throw ApiNotFoundException("User not found")

            val role = patch.role?.let { r ->
                when (r) {
                    "member", "contributor", "admin" -> r
                    else -> throw ApiValidationException(mapOf("role" to "must be member, contributor, or admin"))
                }
            }
            val status = patch.status?.let { s ->
                when (s) {
                    "active", "suspended" -> s
                    else -> throw ApiValidationException(mapOf("status" to "must be active or suspended"))
                }
            }

            // If demoting or suspending an admin, ensure they are not the last admin.
            val targetRole = user[Users.role]
            if (targetRole == Role.admin.name && ((role != null && role != Role.admin.name) || status == UserStatus.suspended.name)) {
                val adminCount = Users.selectAll().where { Users.role eq Role.admin.name }.count()
                if (adminCount <= 1) {
                    throw ApiConflictException("Cannot remove the last admin", "last_admin_guard")
                }
            }

            val before = mapOf("role" to user[Users.role], "status" to user[Users.status])
            Users.update({ Users.id eq id }) {
                role?.let { v -> it[Users.role] = v }
                status?.let { v -> it[Users.status] = v }
                it[Users.updatedAt] = nowIso()
            }
            val after = mapOf(
                "role" to (role ?: user[Users.role]),
                "status" to (status ?: user[Users.status]),
            )

            AuditLogQueries.write(
                actorId = principal.userId,
                action = "user.patch",
                target = "user:$id",
                meta = AuditLogQueries.AuditMeta(before = before, after = after),
            )
        }
    }

    fun exportCsv(filter: String? = null, search: String? = null): String = transaction {
        val rows = list(filter, search, page = null)
        val lines = mutableListOf("id,handle,role,status,skillCount,createdAt")
        rows.forEach { u ->
            lines += listOf(
                u.id,
                u.handle,
                u.role,
                u.status,
                u.skillCount.toString(),
                u.createdAt,
            ).joinToString(",") { cell -> escapeCsv(cell) }
        }
        lines.joinToString("\r\n")
    }

    private fun escapeCsv(value: String): String {
        // Formula injection guard: prefix leading =, +, -, @ with a single quote (spec D8).
        var v = value
        if (v.isNotEmpty() && v[0] in setOf('=', '+', '-', '@')) {
            v = "'$v"
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = v.replace("\"", "\"\"")
            v = "\"$v\""
        }
        return v
    }
}
