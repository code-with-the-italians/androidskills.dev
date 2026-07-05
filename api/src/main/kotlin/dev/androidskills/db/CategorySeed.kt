package dev.androidskills.db

/**
 * The default category taxonomy seeded on first migration.
 *
 * Source of truth for the names/slugs: the design's `admin-categories.html` (CATS table) plus an
 * `uncategorized` bucket the LLM review falls back to ("Skills the review can't confidently place
 * land in Uncategorized…"). `Build & CI` is included but carries the design's "unlisted" intent —
 * it is seeded normally; visibility of individual categories is a future admin setting.
 */
val DEFAULT_CATEGORIES: List<Pair<String, String>> =
  listOf(
    "jetpack-compose" to "Jetpack Compose",
    "kotlin-language" to "Kotlin Language",
    "architecture" to "Architecture",
    "testing-qa" to "Testing & QA",
    "material-design" to "Material Design",
    "data-persistence" to "Data & Persistence",
    "networking" to "Networking",
    "performance" to "Performance",
    "accessibility" to "Accessibility",
    "build-ci" to "Build & CI",
    "uncategorized" to "Uncategorized",
  )
