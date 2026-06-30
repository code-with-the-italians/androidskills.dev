package dev.androidskills.api

import dev.androidskills.auth.GitHubUser
import dev.androidskills.auth.Principal
import dev.androidskills.auth.UsersRepo
import dev.androidskills.db.Role
import dev.androidskills.db.Sessions
import dev.androidskills.db.Users
import dev.androidskills.util.appJson
import dev.androidskills.util.nowIso
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Contributor settings and account deletion (spec §9 Contributor, step 6).
 * Account deletion is soft: the user row stays so published skills/bundles and
 * audit logs remain valid. Re-login reactivates the account.
 */
object UserQueries {

    @Serializable
    data class UserSettings(
        val emailNotifications: Boolean = true,
        val publicProfile: Boolean = true,
    )

    fun getSettings(principal: Principal): UserSettings = transaction {
        val row = Users.selectAll().where { Users.id eq principal.userId }.singleOrNull()
            ?: throw ApiNotFoundException("User not found")
        row[Users.settingsJson]?.let {
            runCatching { appJson.decodeFromString(UserSettings.serializer(), it) }.getOrNull()
        } ?: UserSettings()
    }

    fun updateSettings(principal: Principal, settings: UserSettings) {
        transaction {
            Users.update({ Users.id eq principal.userId }) {
                it[Users.settingsJson] = appJson.encodeToString(UserSettings.serializer(), settings)
                it[Users.updatedAt] = nowIso()
            }
        }
    }

    /**
     * Soft-deletes the authenticated account. Admins cannot self-delete (§3.9);
     * they must be demoted by another admin first.
     */
    fun deleteAccount(principal: Principal) {
        transaction {
            val row = Users.selectAll().where { Users.id eq principal.userId }.singleOrNull()
                ?: throw ApiNotFoundException("User not found")
            if (Role.parse(row[Users.role]) == Role.admin) {
                throw ApiConflictException("Admins cannot delete their own account", code = "admin_self_delete")
            }
            Users.update({ Users.id eq principal.userId }) {
                it[Users.deletedAt] = nowIso()
                it[Users.updatedAt] = nowIso()
            }
            // LOW2: revoke all existing sessions so a deleted user cannot remain logged in,
            // and so old cookies do not become valid again if the account is later reactivated.
            val op = org.jetbrains.exposed.sql.SqlExpressionBuilder.run { Sessions.userId eq principal.userId }
            Sessions.deleteWhere { op }
        }
    }
}
