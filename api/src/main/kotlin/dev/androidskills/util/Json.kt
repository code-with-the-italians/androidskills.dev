package dev.androidskills.util

import kotlinx.serialization.json.Json

/**
 * Shared JSON configuration. Used both for Ktor ContentNegotiation and for
 * serialising JSON-valued TEXT columns (e.g. `skills.tags`, `jobs.payload`).
 */
val appJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
