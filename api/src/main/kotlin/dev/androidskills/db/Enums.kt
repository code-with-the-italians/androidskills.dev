package dev.androidskills.db

import dev.androidskills.api.ApiValidationException

/**
 * Typed enum values for the otherwise-free-form TEXT columns in [Tables] (spec §4). Centralising
 * these here means every insert/update goes through one validation path, so later auth/admin code
 * never has to reason about impossible states like `role='superuser'` or `status='frozen'`.
 *
 * These are **app-layer** invariants. SQLite can't add `CHECK` constraints to existing columns
 * (`ALTER TABLE … ADD CONSTRAINT` isn't supported), and a table-rebuild migration is
 * disproportionate pre-release, so enforcement lives here at the write boundary instead. If
 * hardening at the DB layer is later wanted, these enums map 1:1 to a `CHECK (col IN (...))`
 * clause.
 */

/** User role (spec §2, §7). `member` < `contributor` < `admin`. */
enum class Role {
  member,
  contributor,
  admin;

  companion object {
    fun parse(raw: String): Role = entries.firstOrNull { it.name == raw } ?: invalid("role", raw)
  }
}

enum class UserStatus {
  active,
  suspended;

  companion object {
    fun parse(raw: String): UserStatus =
      entries.firstOrNull { it.name == raw } ?: invalid("status", raw)
  }
}

/**
 * Skill visibility to anonymous users (spec §3.5, §4). Only [published] is publicly reachable.
 * [unlisted] (owner took it down) and [flagged] (admin moderation) are both non-public — surfaced
 * in the admin queue, not the index. The public "caution badge" is a separate concern driven by
 * `verified=false`, not by this status.
 */
enum class SkillStatus {
  published,
  unlisted,
  flagged;

  companion object {
    fun parse(raw: String): SkillStatus =
      entries.firstOrNull { it.name == raw } ?: invalid("status", raw)
  }
}

enum class BundleKind {
  repo,
  zip;

  companion object {
    fun parse(raw: String): BundleKind =
      entries.firstOrNull { it.name == raw } ?: invalid("kind", raw)
  }
}

enum class VersionSource {
  manifest,
  git_head,
  upload;

  companion object {
    fun parse(raw: String): VersionSource =
      entries.firstOrNull { it.name == raw } ?: invalid("version_source", raw)
  }
}

enum class TokenBand {
  `100s`,
  `1k`,
  `10k`,
  `100k`;

  companion object {
    fun parse(raw: String): TokenBand =
      entries.firstOrNull { it.name == raw } ?: invalid("token_band", raw)
  }
}

private fun invalid(field: String, raw: String): Nothing =
  throw ApiValidationException(
    mapOf(field to "must be one of the allowed values"),
    "Invalid value for '$field': '$raw'",
  )
