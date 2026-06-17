package dev.androidskills.util

import java.time.Instant

/**
 * Returns the current instant as an ISO-8601 UTC string, e.g. `2026-06-17T20:00:00Z`.
 * Used for every `created_at` / `updated_at` / timestamp TEXT column (spec §4).
 */
fun nowIso(): String = Instant.now().toString()
