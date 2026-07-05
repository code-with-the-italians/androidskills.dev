package dev.androidskills.api

import dev.androidskills.db.Skills
import dev.androidskills.db.Submissions
import dev.androidskills.db.UserStatus
import dev.androidskills.db.Users
import dev.androidskills.util.nowIso
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Admin dashboard stats (Phase 4). Counts are aggregated; list endpoints are paginated and do not return totals. */
object AdminStatsQueries {

  @Serializable
  data class AdminStats(
    val users: Int,
    val contributors: Int,
    val newUsers30d: Int,
    val suspended: Int,
    val skills: Int,
    val queue: Int,
  )

  fun get(): AdminStats = transaction {
    val cutoff30d = Instant.parse(nowIso()).minus(30, ChronoUnit.DAYS).toString()
    val totalUsers = Users.selectAll().count().toInt()
    val contributors = Users.selectAll().where { Users.role eq "contributor" }.count().toInt()
    val newUsers30d = Users.selectAll().where { Users.createdAt greaterEq cutoff30d }.count().toInt()
    val suspended = Users.selectAll().where { Users.status eq UserStatus.suspended.name }.count().toInt()
    val skills = Skills.selectAll().count().toInt()
    val queue = Submissions.selectAll().where { Submissions.state inList listOf("in_review", "changes_requested") }.count().toInt()

    AdminStats(
      users = totalUsers,
      contributors = contributors,
      newUsers30d = newUsers30d,
      suspended = suspended,
      skills = skills,
      queue = queue,
    )
  }
}
