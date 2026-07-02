package dev.androidskills.api

import dev.androidskills.db.AuditLog
import dev.androidskills.db.Users
import dev.androidskills.util.appJson
import dev.androidskills.util.newId
import dev.androidskills.util.nowIso
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Append-only audit log (spec §11). Every admin mutation writes one row in the
 * same transaction as the mutation.
 */
object AuditLogQueries {

    const val PAGE_SIZE = 50

    @Serializable
    data class AuditMeta(
        val note: String? = null,
        val before: Map<String, String?>? = null,
        val after: Map<String, String?>? = null,
    )

    @Serializable
    data class AuditLogEntry(
        val id: String,
        val actorHandle: String,
        val action: String,
        val target: String,
        val meta: String?,
        val createdAt: String,
    )

    fun write(
        actorId: String,
        action: String,
        target: String,
        meta: AuditMeta? = null,
    ) {
        transaction {
            AuditLog.insert {
                it[AuditLog.id] = newId()
                it[AuditLog.actorId] = actorId
                it[AuditLog.action] = action
                it[AuditLog.target] = target
                it[AuditLog.meta] = meta?.let { appJson.encodeToString(AuditMeta.serializer(), it) }
                it[AuditLog.createdAt] = nowIso()
            }
        }
    }

    /**
     * List recent audit log entries (spec §11). Used by GET /api/admin/audit.
     */
    fun list(action: String? = null, target: String? = null, page: Int? = 1): List<AuditLogEntry> = transaction {
        val query = AuditLog
            .join(Users, JoinType.LEFT, AuditLog.actorId, Users.id)
            .selectAll()
            .apply {
                if (!action.isNullOrBlank()) andWhere { AuditLog.action like "$action%" }
                if (!target.isNullOrBlank()) andWhere { AuditLog.target like "%$target%" }
            }
            .orderBy(AuditLog.createdAt to SortOrder.DESC)
            .limit(PAGE_SIZE)
            .offset(((page ?: 1).coerceAtLeast(1) - 1L) * PAGE_SIZE)

        query.map { row ->
            AuditLogEntry(
                id = row[AuditLog.id],
                actorHandle = row[Users.handle] ?: "system",
                action = row[AuditLog.action],
                target = row[AuditLog.target],
                meta = row[AuditLog.meta],
                createdAt = row[AuditLog.createdAt],
            )
        }
    }
}
