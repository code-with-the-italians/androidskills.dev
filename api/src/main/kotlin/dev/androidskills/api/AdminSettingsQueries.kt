package dev.androidskills.api

import dev.androidskills.auth.Principal
import dev.androidskills.db.PlatformSettings as PlatformSettingsTable
import dev.androidskills.util.appJson
import dev.androidskills.util.nowIso
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Platform settings (spec §9). Stored as a single JSON row under key `settings`
 * in the [PlatformSettings] table.
 */
object AdminSettingsQueries {

    const val SETTINGS_KEY = "settings"

    @Serializable
    data class PlatformSettings(
        val reviewPolicy: String = "manual",
        val tokenSoftCap: Int = 1000,
        val llmEnabled: Boolean = false,
    )

    fun get(): PlatformSettings = transaction {
        val row = PlatformSettingsTable.selectAll().where { PlatformSettingsTable.key eq SETTINGS_KEY }.singleOrNull()
        row?.get(PlatformSettingsTable.value)?.let { v ->
            runCatching { appJson.decodeFromString(PlatformSettings.serializer(), v) }.getOrNull()
        } ?: PlatformSettings()
    }

    fun put(principal: Principal, settings: PlatformSettings): PlatformSettings {
        val json = appJson.encodeToString(PlatformSettings.serializer(), settings)
        transaction {
            val existing = PlatformSettingsTable.selectAll().where { PlatformSettingsTable.key eq SETTINGS_KEY }.singleOrNull()
            if (existing != null) {
                PlatformSettingsTable.update({ PlatformSettingsTable.key eq SETTINGS_KEY }) {
                    it[PlatformSettingsTable.value] = json
                }
            } else {
                PlatformSettingsTable.insert {
                    it[PlatformSettingsTable.key] = SETTINGS_KEY
                    it[PlatformSettingsTable.value] = json
                }
            }
            AuditLogQueries.write(
                actorId = principal.userId,
                action = "settings.put",
                target = "platform:$SETTINGS_KEY",
                meta = AuditLogQueries.AuditMeta(after = mapOf("value" to json)),
            )
        }
        return settings
    }
}
