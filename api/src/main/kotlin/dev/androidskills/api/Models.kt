package dev.androidskills.api

import kotlinx.serialization.Serializable

@Serializable data class CategoryRef(val slug: String, val name: String)

@Serializable
data class AuthorRef(val handle: String, val name: String? = null, val avatarUrl: String? = null)

@Serializable data class BundleRef(val id: String, val kind: String, val provenance: String)

/** A skill card shown in search results and lists (spec §9 GET /api/skills). */
@Serializable
data class SkillCard(
  val id: String,
  val slug: String,
  val name: String,
  val description: String,
  val license: String? = null,
  val tags: List<String> = emptyList(),
  val category: CategoryRef? = null,
  val version: String,
  val versionSource: String,
  val tokenUpfront: Int,
  val tokenOndemand: Int,
  val tokenBand: String,
  val verified: Boolean,
  val status: String,
  val featured: Boolean,
  val installs: Int,
  val author: AuthorRef,
  val bundle: BundleRef,
  val createdAt: String,
  val updatedAt: String,
)

@Serializable data class FacetCount(val key: String, val label: String? = null, val count: Int)

@Serializable
data class Facets(
  val categories: List<FacetCount>,
  val tags: List<FacetCount>,
  val sizes: List<FacetCount>,
)

@Serializable
data class SkillSearchPage(
  val items: List<SkillCard>,
  val page: Int,
  val pageSize: Int,
  val total: Int,
  val totalPages: Int,
  val facets: Facets,
)

/** Full skill detail (spec §9 GET /api/skills/{slug}). */
@Serializable
data class SkillDetail(
  val id: String,
  val slug: String,
  val name: String,
  val description: String,
  val license: String? = null,
  val tags: List<String> = emptyList(),
  val category: CategoryRef? = null,
  val version: String,
  val versionSource: String,
  val tokenUpfront: Int,
  val tokenOndemand: Int,
  val tokenBand: String,
  val verified: Boolean,
  val status: String,
  val featured: Boolean,
  val installs: Int,
  val author: AuthorRef,
  val bundle: BundleRef,
  val readmeMd: String? = null,
  val fileCount: Int,
  val totalSize: Long,
  val createdAt: String,
  val updatedAt: String,
)

@Serializable
data class FileEntry(
  val path: String,
  val name: String,
  val dir: String,
  val size: Int,
  val isBinary: Boolean,
)

@Serializable
data class TreeNode(
  val name: String,
  val path: String,
  val type: String, // "file" | "dir"
  val size: Int = 0,
  val isBinary: Boolean = false,
  val children: List<TreeNode>? = null,
)

@Serializable
data class FileTreeResponse(val slug: String, val files: List<FileEntry>, val tree: List<TreeNode>)

@Serializable
data class FileContentResponse(
  val slug: String,
  val path: String,
  val size: Int,
  val isBinary: Boolean,
  val content: String? = null,
  val downloadOnly: Boolean,
  val downloadUrl: String? = null,
)

@Serializable
data class VersionEntry(
  val version: String,
  val sourceRef: String,
  val createdAt: String,
  val current: Boolean,
)

@Serializable
data class VersionsResponse(
  val slug: String,
  val current: String,
  val versions: List<VersionEntry>,
)

@Serializable
data class CategoryWithCount(val id: String, val slug: String, val name: String, val count: Int)

@Serializable
data class StatsResponse(val indexed: Int, val contributors: Int, val lastUpdated: String? = null)

@Serializable
data class TrendsResponse(
  val categories: List<FacetCount>,
  val tags: List<FacetCount>,
  val tokenMix: List<FacetCount>, // band -> count
  val securityPassRate: Double? = null,
  val submissionFunnel: Map<String, Int>,
)

@Serializable
data class TimelineEvent(
  val type: String, // "publish" | "version"
  val at: String,
  val slug: String,
  val name: String,
  val version: String? = null,
  val authorHandle: String? = null,
)

@Serializable
data class TimelinePage(
  val items: List<TimelineEvent>,
  val page: Int,
  val pageSize: Int,
  val total: Int,
  val totalPages: Int,
)

@Serializable
data class BundleSummary(
  val id: String,
  val kind: String,
  val provenance: String,
  val owner: AuthorRef,
  val sourceRef: String? = null,
  val skillCount: Int,
  val syncedAt: String? = null,
  val createdAt: String,
)

@Serializable
data class BundleDetail(
  val id: String,
  val kind: String,
  val provenance: String,
  val owner: AuthorRef,
  val sourceRef: String? = null,
  val syncedAt: String? = null,
  val createdAt: String,
  val skills: List<SkillCard>,
)

@Serializable
data class AuthorProfile(
  val handle: String,
  val name: String? = null,
  val avatarUrl: String? = null,
  val skillCount: Int,
  val totalInstalls: Int,
  val skills: List<SkillCard>,
)

@Serializable
data class Page<T>(
  val items: List<T>,
  val page: Int,
  val pageSize: Int,
  val total: Int,
  val totalPages: Int,
)

@Serializable data class ReportRequest(val reason: String? = null)

@Serializable data class ReportResponse(val id: String, val accepted: Boolean)
