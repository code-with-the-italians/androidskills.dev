//! Public D1-backed read models and queries.
//!
//! These endpoints intentionally use aggregate SQL rather than loading skills into the Worker:
//! D1 remains responsible for filtering public rows and computing counts.

use std::collections::{BTreeMap, HashMap};

use serde::{Deserialize, Serialize};
use worker::{wasm_bindgen::JsValue, D1Database, Error, Result};

use crate::{
    domain::{PageError, PageRequest},
    error::ApiError,
};

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct StatsResponse {
    pub indexed: i64,
    pub contributors: i64,
    pub last_updated: Option<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
pub struct CategoryWithCount {
    pub id: String,
    pub slug: String,
    pub name: String,
    pub count: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchParams {
    pub q: Option<String>,
    pub categories: Vec<String>,
    pub tags: Vec<String>,
    pub size: Option<SizeFilter>,
    pub verified: bool,
    pub sort: Sort,
    pub page: u32,
    pub page_size: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SizeFilter {
    Small,
    Medium,
    Large,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Sort {
    Relevance,
    Installs,
    Updated,
    Tokens,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SkillSearchPage {
    pub items: Vec<SkillCard>,
    pub page: u32,
    pub page_size: u32,
    pub total: i64,
    pub total_pages: i64,
    pub facets: Facets,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct SkillCard {
    pub id: String,
    pub slug: String,
    pub name: String,
    pub description: String,
    pub license: Option<String>,
    pub tags: Vec<String>,
    pub category: Option<CategoryRef>,
    pub version: String,
    #[serde(rename = "versionSource")]
    pub version_source: String,
    #[serde(rename = "tokenUpfront")]
    pub token_upfront: i64,
    #[serde(rename = "tokenOndemand")]
    pub token_ondemand: i64,
    #[serde(rename = "tokenBand")]
    pub token_band: String,
    pub verified: bool,
    pub status: String,
    pub featured: bool,
    pub installs: i64,
    pub author: AuthorRef,
    pub bundle: BundleRef,
    #[serde(rename = "createdAt")]
    pub created_at: String,
    #[serde(rename = "updatedAt")]
    pub updated_at: String,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct CategoryRef {
    pub slug: String,
    pub name: String,
}
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct AuthorRef {
    pub handle: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(rename = "avatarUrl", skip_serializing_if = "Option::is_none")]
    pub avatar_url: Option<String>,
}
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct BundleRef {
    pub id: String,
    pub kind: String,
    pub provenance: String,
}
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct FacetCount {
    pub key: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub label: Option<String>,
    pub count: i64,
}
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct Facets {
    pub categories: Vec<FacetCount>,
    pub tags: Vec<FacetCount>,
    pub sizes: Vec<FacetCount>,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SkillDetail {
    #[serde(flatten)]
    pub card: SkillCard,
    pub readme_md: Option<String>,
    pub file_count: i64,
    pub total_size: i64,
    pub security_notes: Vec<String>,
}

#[derive(Debug, Deserialize)]
struct StatsRow {
    indexed: i64,
    contributors: i64,
    last_updated: Option<String>,
}

/// Matches Ktor's public `stats()` query: only published skills influence every aggregate.
pub async fn stats(db: &D1Database) -> Result<StatsResponse> {
    let row: Option<StatsRow> = db
        .prepare(
            "SELECT COUNT(*) AS indexed, \
                    COUNT(DISTINCT bundles.owner_user_id) AS contributors, \
                    MAX(skills.updated_at) AS last_updated \
             FROM skills \
             INNER JOIN bundles ON bundles.id = skills.bundle_id \
             WHERE skills.status = 'published'",
        )
        .first(None)
        .await?;
    let row = row.ok_or_else(|| Error::RustError("stats aggregate returned no row".into()))?;
    Ok(StatsResponse {
        indexed: row.indexed,
        contributors: row.contributors,
        last_updated: row.last_updated,
    })
}

/// Returns every seeded category, including categories which currently have no public skills.
pub async fn categories(db: &D1Database) -> Result<Vec<CategoryWithCount>> {
    db.prepare(
        "SELECT categories.id, categories.slug, categories.name, COUNT(skills.id) AS count \
         FROM categories \
         LEFT JOIN skills ON skills.category_id = categories.id AND skills.status = 'published' \
         GROUP BY categories.id, categories.slug, categories.name \
         ORDER BY categories.name",
    )
    .all()
    .await?
    .results()
}

impl SearchParams {
    pub fn from_url(url: &worker::Url) -> std::result::Result<Self, ApiError> {
        let pairs = url.query_pairs().into_owned().collect::<Vec<_>>();
        let values = |key: &str| {
            let direct = pairs
                .iter()
                .filter(|(name, _)| name == key)
                .map(|(_, value)| value.clone())
                .collect::<Vec<_>>();
            let source = if direct.is_empty() {
                pairs
                    .iter()
                    .filter(|(name, _)| name == &format!("{key}[]"))
                    .map(|(_, value)| value.clone())
                    .collect::<Vec<_>>()
            } else {
                direct
            };
            source
                .into_iter()
                .filter(|value| !value.trim().is_empty())
                .collect()
        };
        let one = |key: &str| {
            pairs
                .iter()
                .find(|(name, _)| name == key)
                .map(|(_, value)| value.clone())
        };
        let invalid = |field: &str, message: &str| {
            ApiError::validation(
                "validation_failed",
                format!("Invalid '{field}'"),
                BTreeMap::from([(field.into(), message.into())]),
            )
        };
        let verified = match one("verified")
            .as_deref()
            .map(str::to_ascii_lowercase)
            .as_deref()
        {
            None | Some("true" | "1" | "yes") => true,
            Some("false" | "0" | "no") => false,
            _ => return Err(invalid("verified", "must be one of true|false")),
        };
        let size = match one("size").as_deref() {
            None => None,
            Some("<2k") => Some(SizeFilter::Small),
            Some("2-5k") => Some(SizeFilter::Medium),
            Some("5k+") => Some(SizeFilter::Large),
            _ => return Err(invalid("size", "must be one of <2k|2-5k|5k+")),
        };
        let sort = match one("sort").as_deref() {
            None | Some("relevance") => Sort::Relevance,
            Some("installs") => Sort::Installs,
            Some("updated") => Sort::Updated,
            Some("tokens") => Sort::Tokens,
            _ => {
                return Err(invalid(
                    "sort",
                    "must be one of relevance|installs|updated|tokens",
                ))
            }
        };
        let positive = |key: &str, default: u32, max: u32| -> std::result::Result<u32, ApiError> {
            match one(key) {
                None => Ok(default),
                Some(value) => value
                    .parse::<u32>()
                    .ok()
                    .filter(|value| *value > 0 && *value <= max)
                    .ok_or_else(|| invalid(key, &format!("must be 1..{max}"))),
            }
        };
        let page = positive("page", 1, PageRequest::MAX_PAGE)?;
        let page_size = positive(
            "pageSize",
            PageRequest::DEFAULT_PAGE_SIZE,
            PageRequest::MAX_PAGE_SIZE,
        )?;
        Ok(Self {
            q: one("q")
                .map(|value| value.trim().to_owned())
                .filter(|value| !value.is_empty()),
            categories: values("cat"),
            tags: values("tag"),
            size,
            verified,
            sort,
            page,
            page_size,
        })
    }
}

#[derive(Debug, Deserialize)]
struct CardRow {
    id: String,
    slug: String,
    name: String,
    description: String,
    license: Option<String>,
    tags: String,
    version: String,
    version_source: String,
    token_upfront: i64,
    token_ondemand: i64,
    token_band: String,
    verified: i64,
    status: String,
    featured: i64,
    installs: i64,
    created_at: String,
    updated_at: String,
    category_slug: Option<String>,
    category_name: Option<String>,
    bundle_id: String,
    bundle_kind: String,
    bundle_provenance: String,
    author_handle: String,
    author_name: Option<String>,
    author_avatar_url: Option<String>,
}

#[derive(Debug, Deserialize)]
struct DetailRow {
    #[serde(flatten)]
    card: CardRow,
    readme_md: Option<String>,
    security: Option<String>,
    file_count: i64,
    total_size: i64,
}

const CARD_SELECT: &str = "SELECT s.id, s.slug, s.name, s.description, s.license, s.tags, s.version, s.version_source, s.token_upfront, s.token_ondemand, s.token_band, s.verified, s.status, s.featured, s.installs, s.created_at, s.updated_at, c.slug AS category_slug, c.name AS category_name, b.id AS bundle_id, b.kind AS bundle_kind, b.provenance AS bundle_provenance, u.handle AS author_handle, u.name AS author_name, u.avatar_url AS author_avatar_url FROM skills s INNER JOIN bundles b ON b.id=s.bundle_id INNER JOIN users u ON u.id=b.owner_user_id LEFT JOIN categories c ON c.id=s.category_id";
const DETAIL_SELECT: &str = "SELECT s.id, s.slug, s.name, s.description, s.license, s.tags, s.version, s.version_source, s.token_upfront, s.token_ondemand, s.token_band, s.verified, s.status, s.featured, s.installs, s.created_at, s.updated_at, c.slug AS category_slug, c.name AS category_name, b.id AS bundle_id, b.kind AS bundle_kind, b.provenance AS bundle_provenance, u.handle AS author_handle, u.name AS author_name, u.avatar_url AS author_avatar_url, s.readme_md, s.security, COUNT(f.id) AS file_count, COALESCE(SUM(f.size), 0) AS total_size FROM skills s INNER JOIN bundles b ON b.id=s.bundle_id INNER JOIN users u ON u.id=b.owner_user_id LEFT JOIN categories c ON c.id=s.category_id LEFT JOIN skill_files f ON f.skill_id=s.id";

pub async fn search(db: &D1Database, params: &SearchParams) -> Result<SkillSearchPage> {
    let (where_sql, bindings) = filters(params, false, false, false);
    let total: Option<CountRow> = db
        .prepare(format!(
            "SELECT COUNT(*) AS count FROM skills s WHERE {where_sql}"
        ))
        .bind(&bindings)?
        .first(None)
        .await?;
    let total = total.map_or(0, |row| row.count);
    let order = order_by(params);
    let mut bindings = bindings;
    if params.sort == Sort::Relevance && params.q.is_some() {
        bindings.push(JsValue::from_str(&format!(
            "%{}%",
            params.q.as_ref().unwrap().replace(['%', '_'], "")
        )));
    }
    bindings.push(JsValue::from_f64(params.page_size.into()));
    bindings.push(JsValue::from_f64(
        ((params.page - 1) * params.page_size).into(),
    ));
    let rows: Vec<CardRow> = db
        .prepare(format!(
            "{CARD_SELECT} WHERE {where_sql} ORDER BY {order} LIMIT ? OFFSET ?"
        ))
        .bind(&bindings)?
        .all()
        .await?
        .results()?;
    let (categories, tags, sizes) = facets(db, params).await?;
    Ok(SkillSearchPage {
        items: rows.into_iter().map(card).collect(),
        page: params.page,
        page_size: params.page_size,
        total,
        total_pages: (total + i64::from(params.page_size) - 1) / i64::from(params.page_size),
        facets: Facets {
            categories,
            tags,
            sizes,
        },
    })
}

pub async fn skill_detail(db: &D1Database, slug: &str) -> Result<Option<SkillDetail>> {
    let rows: Vec<DetailRow> = db
        .prepare(format!(
            "{DETAIL_SELECT} WHERE s.slug = ? AND s.status = 'published' GROUP BY s.id"
        ))
        .bind(&[JsValue::from_str(slug)])?
        .all()
        .await?
        .results()?;
    Ok(rows.into_iter().next().map(|row| SkillDetail {
        readme_md: row.readme_md,
        file_count: row.file_count,
        total_size: row.total_size,
        security_notes: row
            .security
            .as_deref()
            .and_then(|raw| serde_json::from_str(raw).ok())
            .unwrap_or_default(),
        card: card(row.card),
    }))
}

#[derive(Deserialize)]
struct CountRow {
    count: i64,
}

fn card(row: CardRow) -> SkillCard {
    SkillCard {
        id: row.id,
        slug: row.slug,
        name: row.name,
        description: row.description,
        license: row.license,
        tags: serde_json::from_str(&row.tags).unwrap_or_default(),
        category: row
            .category_slug
            .zip(row.category_name)
            .map(|(slug, name)| CategoryRef { slug, name }),
        version: row.version,
        version_source: row.version_source,
        token_upfront: row.token_upfront,
        token_ondemand: row.token_ondemand,
        token_band: row.token_band,
        verified: row.verified == 1,
        status: row.status,
        featured: row.featured == 1,
        installs: row.installs,
        author: AuthorRef {
            handle: row.author_handle,
            name: row.author_name,
            avatar_url: row.author_avatar_url,
        },
        bundle: BundleRef {
            id: row.bundle_id,
            kind: row.bundle_kind,
            provenance: row.bundle_provenance,
        },
        created_at: row.created_at,
        updated_at: row.updated_at,
    }
}

fn filters(
    params: &SearchParams,
    exclude_categories: bool,
    exclude_tags: bool,
    exclude_size: bool,
) -> (String, Vec<JsValue>) {
    let mut clauses = vec!["s.status = 'published'".to_owned()];
    let mut values = Vec::new();
    if params.verified {
        clauses.push("s.verified = 1".into());
    }
    if let Some(query) = &params.q {
        let pattern = format!("%{}%", query.replace(['%', '_'], ""));
        clauses.push(
            "(s.tags LIKE ? OR LOWER(s.name) LIKE LOWER(?) OR LOWER(s.description) LIKE LOWER(?))"
                .into(),
        );
        for _ in 0..3 {
            values.push(JsValue::from_str(&pattern));
        }
    }
    if !exclude_categories && !params.categories.is_empty() {
        clauses.push(format!(
            "s.category_id IN (SELECT id FROM categories WHERE slug IN ({}))",
            placeholders(params.categories.len())
        ));
        values.extend(
            params
                .categories
                .iter()
                .map(|value| JsValue::from_str(value)),
        );
    }
    if !exclude_tags && !params.tags.is_empty() {
        clauses.push(format!(
            "({})",
            params
                .tags
                .iter()
                .map(|_| "s.tags LIKE ?")
                .collect::<Vec<_>>()
                .join(" OR ")
        ));
        values.extend(
            params
                .tags
                .iter()
                .map(|tag| JsValue::from_str(&format!("%\"{}\"%", tag.replace(['%', '_'], "")))),
        );
    }
    if !exclude_size {
        if let Some(size) = params.size {
            clauses.push(
                match size {
                    SizeFilter::Small => "s.token_upfront + s.token_ondemand < 2000",
                    SizeFilter::Medium => {
                        "s.token_upfront + s.token_ondemand BETWEEN 2000 AND 5000"
                    }
                    SizeFilter::Large => "s.token_upfront + s.token_ondemand > 5000",
                }
                .into(),
            );
        }
    }
    (clauses.join(" AND "), values)
}
fn placeholders(count: usize) -> String {
    std::iter::repeat_n("?", count)
        .collect::<Vec<_>>()
        .join(",")
}
fn order_by(params: &SearchParams) -> &'static str {
    match params.sort {
        Sort::Installs => "s.installs DESC, s.updated_at DESC",
        Sort::Updated => "s.updated_at DESC",
        Sort::Tokens => "s.token_upfront + s.token_ondemand ASC, s.name ASC",
        Sort::Relevance if params.q.is_some() => {
            "(LOWER(s.name) LIKE LOWER(?)) DESC, s.installs DESC, s.updated_at DESC"
        }
        Sort::Relevance => "s.featured DESC, s.installs DESC, s.updated_at DESC",
    }
}

async fn facets(
    db: &D1Database,
    params: &SearchParams,
) -> Result<(Vec<FacetCount>, Vec<FacetCount>, Vec<FacetCount>)> {
    let (category_where, category_bindings) = filters(params, true, false, false);
    let category_rows: Vec<FacetRow> = db.prepare(format!("SELECT COALESCE(c.slug, 'uncategorized') AS key, COALESCE(c.name, 'Uncategorized') AS label, COUNT(*) AS count FROM skills s LEFT JOIN categories c ON c.id=s.category_id WHERE {category_where} GROUP BY s.category_id ORDER BY count DESC")).bind(&category_bindings)?.all().await?.results()?;
    let (tag_where, tag_bindings) = filters(params, false, true, false);
    let tag_rows: Vec<TagRow> = db
        .prepare(format!("SELECT s.tags FROM skills s WHERE {tag_where}"))
        .bind(&tag_bindings)?
        .all()
        .await?
        .results()?;
    let mut tag_counts = HashMap::<String, i64>::new();
    for row in tag_rows {
        for tag in serde_json::from_str::<Vec<String>>(&row.tags).unwrap_or_default() {
            *tag_counts.entry(tag).or_default() += 1;
        }
    }
    let mut tags = tag_counts
        .into_iter()
        .map(|(key, count)| FacetCount {
            key,
            label: None,
            count,
        })
        .collect::<Vec<_>>();
    tags.sort_by(|left, right| {
        right
            .count
            .cmp(&left.count)
            .then_with(|| left.key.cmp(&right.key))
    });
    tags.truncate(20);
    let (size_where, size_bindings) = filters(params, false, false, true);
    let size_rows: Vec<TokenRow> = db
        .prepare(format!(
            "SELECT s.token_upfront, s.token_ondemand FROM skills s WHERE {size_where}"
        ))
        .bind(&size_bindings)?
        .all()
        .await?
        .results()?;
    let mut size_counts = [0_i64; 3];
    for row in size_rows {
        let total = row.token_upfront + row.token_ondemand;
        size_counts[if total < 2000 {
            0
        } else if total <= 5000 {
            1
        } else {
            2
        }] += 1;
    }
    Ok((
        category_rows
            .into_iter()
            .map(|row| FacetCount {
                key: row.key,
                label: Some(row.label),
                count: row.count,
            })
            .collect(),
        tags,
        ["<2k", "2-5k", "5k+"]
            .into_iter()
            .zip(size_counts)
            .map(|(key, count)| FacetCount {
                key: key.into(),
                label: None,
                count,
            })
            .collect(),
    ))
}
#[derive(Deserialize)]
struct FacetRow {
    key: String,
    label: String,
    count: i64,
}
#[derive(Deserialize)]
struct TagRow {
    tags: String,
}
#[derive(Deserialize)]
struct TokenRow {
    token_upfront: i64,
    token_ondemand: i64,
}

pub(crate) const PREVIEW_LIMIT: i64 = 256 * 1024;
const MAX_TAG_FACETS: usize = 20;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SkillPath<'a> {
    Detail(&'a str),
    Files(&'a str),
    File { slug: &'a str, path: &'a str },
    Versions(&'a str),
    Download(&'a str),
}

impl<'a> SkillPath<'a> {
    pub fn parse(path: &'a str) -> Option<Self> {
        let rest = path.strip_prefix("/api/skills/")?;
        if rest.is_empty() {
            return None;
        }
        let parsed = match rest.split_once('/') {
            None => Self::Detail(rest),
            Some((slug, "files")) => Self::Files(slug),
            Some((slug, rest)) if rest.starts_with("files/") => Self::File {
                slug,
                path: &rest["files/".len()..],
            },
            Some((slug, "versions")) => Self::Versions(slug),
            Some((slug, "download")) => Self::Download(slug),
            _ => return None,
        };
        let slug = match parsed {
            Self::Detail(slug)
            | Self::Files(slug)
            | Self::File { slug, .. }
            | Self::Versions(slug)
            | Self::Download(slug) => slug,
        };
        (!slug.is_empty()).then_some(parsed)
    }
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct FileEntry {
    pub path: String,
    pub name: String,
    pub dir: String,
    pub size: i64,
    pub is_binary: bool,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TreeNode {
    pub name: String,
    pub path: String,
    #[serde(rename = "type")]
    pub node_type: String,
    pub size: i64,
    pub is_binary: bool,
    pub children: Option<Vec<TreeNode>>,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct FileTreeResponse {
    pub slug: String,
    pub files: Vec<FileEntry>,
    pub tree: Vec<TreeNode>,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct FileContentResponse {
    pub slug: String,
    pub path: String,
    pub size: i64,
    pub is_binary: bool,
    pub content: Option<String>,
    pub download_only: bool,
    pub download_url: Option<String>,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct VersionEntry {
    pub version: String,
    pub source_ref: String,
    pub created_at: String,
    pub current: bool,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct VersionsResponse {
    pub slug: String,
    pub current: String,
    pub versions: Vec<VersionEntry>,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct TrendsResponse {
    pub categories: Vec<FacetCount>,
    pub tags: Vec<FacetCount>,
    pub token_mix: Vec<FacetCount>,
    pub security_pass_rate: Option<f64>,
    pub submission_funnel: BTreeMap<String, i64>,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TimelineEvent {
    #[serde(rename = "type")]
    pub event_type: String,
    pub at: String,
    pub slug: String,
    pub name: String,
    pub version: Option<String>,
    pub author_handle: Option<String>,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TimelinePage {
    pub items: Vec<TimelineEvent>,
    pub page: u32,
    pub page_size: u32,
    pub total: i64,
    pub total_pages: i64,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct BundleSummary {
    pub id: String,
    pub kind: String,
    pub provenance: String,
    pub owner: AuthorRef,
    pub source_ref: Option<String>,
    pub skill_count: i64,
    pub synced_at: Option<String>,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct BundlePage {
    pub items: Vec<BundleSummary>,
    pub page: u32,
    pub page_size: u32,
    pub total: i64,
    pub total_pages: i64,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct BundleDetail {
    pub id: String,
    pub kind: String,
    pub provenance: String,
    pub owner: AuthorRef,
    pub source_ref: Option<String>,
    pub synced_at: Option<String>,
    pub created_at: String,
    pub skills: Vec<SkillCard>,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AuthorProfile {
    pub handle: String,
    pub name: Option<String>,
    pub avatar_url: Option<String>,
    pub skill_count: i64,
    pub total_installs: i64,
    pub skills: Vec<SkillCard>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileContentMeta {
    pub slug: String,
    pub path: String,
    pub size: i64,
    pub is_binary: bool,
    pub r2_key: String,
    pub download_only: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DownloadTarget {
    pub skill_id: String,
    pub slug: String,
    pub version: String,
    pub r2_zip_key: Option<String>,
    pub is_current: bool,
}

#[derive(Deserialize)]
struct PublishedSkillRow {
    id: String,
    slug: String,
    version: String,
}

#[derive(Deserialize)]
struct FileMetaRow {
    path: String,
    size: i64,
    is_binary: i64,
    r2_key: String,
}

#[derive(Deserialize)]
struct VersionRow {
    version: String,
    source_ref: String,
    created_at: String,
}

#[derive(Deserialize)]
struct DownloadVersionRow {
    version: String,
    r2_zip_key: Option<String>,
}

#[derive(Deserialize)]
struct FunnelRow {
    state: String,
    count: i64,
}

#[derive(Deserialize)]
struct TimelineRow {
    event_type: String,
    at: String,
    slug: String,
    name: String,
    version: Option<String>,
    author_handle: Option<String>,
}

#[derive(Deserialize)]
struct BundleListRow {
    id: String,
    kind: String,
    provenance: String,
    source_ref: Option<String>,
    synced_at: Option<String>,
    created_at: String,
    skill_count: i64,
    author_handle: String,
    author_name: Option<String>,
    author_avatar_url: Option<String>,
}

#[derive(Deserialize)]
struct BundleHeadRow {
    id: String,
    kind: String,
    provenance: String,
    source_ref: Option<String>,
    synced_at: Option<String>,
    created_at: String,
    author_handle: String,
    author_name: Option<String>,
    author_avatar_url: Option<String>,
}

#[derive(Deserialize)]
struct AuthorHeadRow {
    handle: String,
    name: Option<String>,
    avatar_url: Option<String>,
}

pub fn page_from_url(url: &worker::Url) -> std::result::Result<(u32, u32), ApiError> {
    let pairs = url.query_pairs().into_owned().collect::<Vec<_>>();
    let one = |key: &str| {
        pairs
            .iter()
            .find(|(name, _)| name == key)
            .map(|(_, value)| value.as_str())
    };
    Ok((
        parse_page_strict(one("page"))?,
        parse_page_size_strict(one("pageSize"))?,
    ))
}

pub fn query_value(url: &worker::Url, key: &str) -> Option<String> {
    url.query_pairs()
        .find(|(name, _)| name == key)
        .map(|(_, value)| value.into_owned())
}

pub fn is_raw_preview(url: &worker::Url) -> bool {
    query_value(url, "raw").as_deref() == Some("1")
}

fn parse_page_strict(raw: Option<&str>) -> std::result::Result<u32, ApiError> {
    match raw {
        None => Ok(1),
        Some(value) => {
            let parsed = value
                .parse::<u32>()
                .map_err(|_| page_error("page", "must be a positive integer", PageError::Page))?;
            PageRequest {
                page: parsed,
                page_size: PageRequest::DEFAULT_PAGE_SIZE,
            }
            .validate()
            .map_err(|error| match error {
                PageError::Page if parsed == 0 => page_error("page", "must be >= 1", error),
                PageError::Page => page_error(
                    "page",
                    &format!(
                        "must be <= {} (deep pagination is not supported)",
                        PageRequest::MAX_PAGE
                    ),
                    error,
                ),
                PageError::PageSize => page_error(
                    "pageSize",
                    &format!("must be 1..{}", PageRequest::MAX_PAGE_SIZE),
                    error,
                ),
            })?;
            Ok(parsed)
        }
    }
}

fn parse_page_size_strict(raw: Option<&str>) -> std::result::Result<u32, ApiError> {
    match raw {
        None => Ok(PageRequest::DEFAULT_PAGE_SIZE),
        Some(value) => {
            let parsed = value
                .parse::<u32>()
                .map_err(|_| page_error("pageSize", "must be an integer", PageError::PageSize))?;
            PageRequest {
                page: 1,
                page_size: parsed,
            }
            .validate()
            .map_err(|_| {
                page_error(
                    "pageSize",
                    &format!("must be 1..{}", PageRequest::MAX_PAGE_SIZE),
                    PageError::PageSize,
                )
            })?;
            Ok(parsed)
        }
    }
}

fn page_error(field: &str, message: &str, _kind: PageError) -> ApiError {
    ApiError::validation(
        "validation_failed",
        format!("Invalid '{field}'"),
        BTreeMap::from([(field.into(), message.into())]),
    )
}

fn total_pages(total: i64, page_size: u32) -> i64 {
    if page_size == 0 {
        0
    } else {
        (total + i64::from(page_size) - 1) / i64::from(page_size)
    }
}

async fn published_skill(db: &D1Database, slug: &str) -> Result<Option<PublishedSkillRow>> {
    db.prepare("SELECT id, slug, version FROM skills WHERE slug = ? AND status = 'published'")
        .bind(&[JsValue::from_str(slug)])?
        .first(None)
        .await
}

pub async fn file_tree(db: &D1Database, slug: &str) -> Result<Option<FileTreeResponse>> {
    let Some(skill) = published_skill(db, slug).await? else {
        return Ok(None);
    };
    let rows: Vec<FileMetaRow> = db
        .prepare(
            "SELECT path, size, is_binary, r2_key FROM skill_files WHERE skill_id = ? ORDER BY path",
        )
        .bind(&[JsValue::from_str(&skill.id)])?
        .all()
        .await?
        .results()?;
    let files = rows
        .into_iter()
        .map(|row| {
            let (dir, name) = row
                .path
                .rsplit_once('/')
                .map(|(dir, name)| (dir.to_owned(), name.to_owned()))
                .unwrap_or_else(|| (String::new(), row.path.clone()));
            FileEntry {
                path: row.path,
                name,
                dir,
                size: row.size,
                is_binary: row.is_binary == 1,
            }
        })
        .collect::<Vec<_>>();
    let tree = build_tree(&files);
    Ok(Some(FileTreeResponse {
        slug: skill.slug,
        files,
        tree,
    }))
}

pub async fn file_content_meta(
    db: &D1Database,
    slug: &str,
    path: &str,
) -> Result<Option<FileContentMeta>> {
    let Some(skill) = published_skill(db, slug).await? else {
        return Ok(None);
    };
    let row: Option<FileMetaRow> = db
        .prepare(
            "SELECT path, size, is_binary, r2_key FROM skill_files WHERE skill_id = ? AND path = ?",
        )
        .bind(&[JsValue::from_str(&skill.id), JsValue::from_str(path)])?
        .first(None)
        .await?;
    Ok(row.map(|row| {
        let is_binary = row.is_binary == 1;
        FileContentMeta {
            slug: skill.slug,
            path: row.path,
            size: row.size,
            is_binary,
            r2_key: row.r2_key,
            download_only: is_binary || row.size > PREVIEW_LIMIT,
        }
    }))
}

pub fn file_content_json(meta: FileContentMeta, content: Option<String>) -> FileContentResponse {
    FileContentResponse {
        slug: meta.slug,
        path: meta.path,
        size: meta.size,
        is_binary: meta.is_binary,
        content,
        download_only: meta.download_only,
        download_url: None,
    }
}

pub async fn versions(db: &D1Database, slug: &str) -> Result<Option<VersionsResponse>> {
    let Some(skill) = published_skill(db, slug).await? else {
        return Ok(None);
    };
    let rows: Vec<VersionRow> = db
        .prepare(
            "SELECT version, source_ref, created_at FROM versions WHERE skill_id = ? ORDER BY created_at DESC",
        )
        .bind(&[JsValue::from_str(&skill.id)])?
        .all()
        .await?
        .results()?;
    Ok(Some(VersionsResponse {
        slug: skill.slug,
        current: skill.version.clone(),
        versions: rows
            .into_iter()
            .map(|row| VersionEntry {
                current: row.version == skill.version,
                version: row.version,
                source_ref: row.source_ref,
                created_at: row.created_at,
            })
            .collect(),
    }))
}

pub async fn download_target(
    db: &D1Database,
    slug: &str,
    version: Option<&str>,
) -> Result<Option<DownloadTarget>> {
    let Some(skill) = published_skill(db, slug).await? else {
        return Ok(None);
    };
    let requested = version.unwrap_or(&skill.version);
    let row: Option<DownloadVersionRow> = db
        .prepare("SELECT version, r2_zip_key FROM versions WHERE skill_id = ? AND version = ?")
        .bind(&[JsValue::from_str(&skill.id), JsValue::from_str(requested)])?
        .first(None)
        .await?;
    Ok(row.map(|row| DownloadTarget {
        skill_id: skill.id,
        slug: skill.slug,
        is_current: row.version == skill.version,
        version: row.version,
        r2_zip_key: row.r2_zip_key,
    }))
}

pub fn download_filename(slug: &str, version: &str) -> Option<String> {
    let filename = format!("{slug}-{version}.zip");
    filename
        .bytes()
        .all(|byte| byte >= 0x20 && byte != b'"' && byte != b'\\')
        .then_some(filename)
}

pub async fn increment_installs(db: &D1Database, skill_id: &str) -> Result<()> {
    db.prepare("UPDATE skills SET installs = installs + 1 WHERE id = ?")
        .bind(&[JsValue::from_str(skill_id)])?
        .run()
        .await?;
    Ok(())
}

pub async fn trends(db: &D1Database) -> Result<TrendsResponse> {
    let category_rows: Vec<FacetRow> = db
        .prepare(
            "SELECT COALESCE(c.slug, 'uncategorized') AS key, COALESCE(c.name, 'Uncategorized') AS label, COUNT(*) AS count \
             FROM skills s LEFT JOIN categories c ON c.id = s.category_id \
             WHERE s.status = 'published' GROUP BY s.category_id ORDER BY count DESC, key ASC",
        )
        .all()
        .await?
        .results()?;
    let tag_rows: Vec<TagRow> = db
        .prepare("SELECT tags FROM skills WHERE status = 'published'")
        .all()
        .await?
        .results()?;
    let mut tag_counts = HashMap::<String, i64>::new();
    for row in tag_rows {
        for tag in serde_json::from_str::<Vec<String>>(&row.tags).unwrap_or_default() {
            *tag_counts.entry(tag).or_default() += 1;
        }
    }
    let mut tags = tag_counts
        .into_iter()
        .map(|(key, count)| FacetCount {
            key,
            label: None,
            count,
        })
        .collect::<Vec<_>>();
    tags.sort_by(|left, right| {
        right
            .count
            .cmp(&left.count)
            .then_with(|| left.key.cmp(&right.key))
    });
    tags.truncate(MAX_TAG_FACETS);
    let band_rows: Vec<FacetRow> = db
        .prepare(
            "SELECT token_band AS key, token_band AS label, COUNT(*) AS count \
             FROM skills WHERE status = 'published' GROUP BY token_band",
        )
        .all()
        .await?
        .results()?;
    let band_counts = band_rows
        .into_iter()
        .map(|row| (row.key, row.count))
        .collect::<HashMap<_, _>>();
    let funnel_rows: Vec<FunnelRow> = db
        .prepare("SELECT state, COUNT(*) AS count FROM submissions GROUP BY state")
        .all()
        .await?
        .results()?;
    Ok(TrendsResponse {
        categories: category_rows
            .into_iter()
            .map(|row| FacetCount {
                key: row.key,
                label: Some(row.label),
                count: row.count,
            })
            .collect(),
        tags,
        token_mix: ["100s", "1k", "10k", "100k"]
            .into_iter()
            .map(|key| FacetCount {
                key: key.into(),
                label: None,
                count: band_counts.get(key).copied().unwrap_or(0),
            })
            .collect(),
        security_pass_rate: None,
        submission_funnel: funnel_rows
            .into_iter()
            .map(|row| (row.state, row.count))
            .collect(),
    })
}

pub async fn timeline(db: &D1Database, page: u32, page_size: u32) -> Result<TimelinePage> {
    let total: Option<CountRow> = db
        .prepare(
            "SELECT (SELECT COUNT(*) FROM skills WHERE status = 'published') \
                    + (SELECT COUNT(*) FROM versions v INNER JOIN skills s ON s.id = v.skill_id WHERE s.status = 'published') \
                    AS count",
        )
        .first(None)
        .await?;
    let total = total.map_or(0, |row| row.count);
    let rows: Vec<TimelineRow> = db
        .prepare(
            "SELECT event_type, at, slug, name, version, author_handle FROM ( \
                SELECT 'publish' AS event_type, s.created_at AS at, s.slug, s.name, s.version, u.handle AS author_handle \
                FROM skills s INNER JOIN bundles b ON b.id = s.bundle_id INNER JOIN users u ON u.id = b.owner_user_id \
                WHERE s.status = 'published' \
                UNION ALL \
                SELECT 'version' AS event_type, v.created_at AS at, s.slug, s.name, v.version, u.handle AS author_handle \
                FROM versions v INNER JOIN skills s ON s.id = v.skill_id \
                INNER JOIN bundles b ON b.id = s.bundle_id INNER JOIN users u ON u.id = b.owner_user_id \
                WHERE s.status = 'published' \
             ) AS events ORDER BY at DESC, event_type ASC, slug ASC LIMIT ? OFFSET ?",
        )
        .bind(&[
            JsValue::from_f64(page_size.into()),
            JsValue::from_f64(((page - 1) * page_size).into()),
        ])?
        .all()
        .await?
        .results()?;
    Ok(TimelinePage {
        items: rows
            .into_iter()
            .map(|row| TimelineEvent {
                event_type: row.event_type,
                at: row.at,
                slug: row.slug,
                name: row.name,
                version: row.version,
                author_handle: row.author_handle,
            })
            .collect(),
        page,
        page_size,
        total,
        total_pages: total_pages(total, page_size),
    })
}

pub async fn bundles(db: &D1Database, page: u32, page_size: u32) -> Result<BundlePage> {
    let total: Option<CountRow> = db
        .prepare("SELECT COUNT(DISTINCT bundle_id) AS count FROM skills WHERE status = 'published'")
        .first(None)
        .await?;
    let total = total.map_or(0, |row| row.count);
    let rows: Vec<BundleListRow> = db
        .prepare(
            "SELECT b.id, b.kind, b.provenance, b.source_ref, b.synced_at, b.created_at, COUNT(s.id) AS skill_count, \
                    u.handle AS author_handle, u.name AS author_name, u.avatar_url AS author_avatar_url \
             FROM bundles b INNER JOIN users u ON u.id = b.owner_user_id \
             INNER JOIN skills s ON s.bundle_id = b.id AND s.status = 'published' \
             GROUP BY b.id ORDER BY b.created_at DESC, b.id DESC LIMIT ? OFFSET ?",
        )
        .bind(&[
            JsValue::from_f64(page_size.into()),
            JsValue::from_f64(((page - 1) * page_size).into()),
        ])?
        .all()
        .await?
        .results()?;
    Ok(BundlePage {
        items: rows
            .into_iter()
            .map(|row| BundleSummary {
                id: row.id,
                kind: row.kind,
                provenance: row.provenance,
                owner: AuthorRef {
                    handle: row.author_handle,
                    name: row.author_name,
                    avatar_url: row.author_avatar_url,
                },
                source_ref: row.source_ref,
                skill_count: row.skill_count,
                synced_at: row.synced_at,
                created_at: row.created_at,
            })
            .collect(),
        page,
        page_size,
        total,
        total_pages: total_pages(total, page_size),
    })
}

pub async fn bundle_detail(db: &D1Database, id: &str) -> Result<Option<BundleDetail>> {
    let head: Option<BundleHeadRow> = db
        .prepare(
            "SELECT b.id, b.kind, b.provenance, b.source_ref, b.synced_at, b.created_at, \
                    u.handle AS author_handle, u.name AS author_name, u.avatar_url AS author_avatar_url \
             FROM bundles b INNER JOIN users u ON u.id = b.owner_user_id WHERE b.id = ?",
        )
        .bind(&[JsValue::from_str(id)])?
        .first(None)
        .await?;
    let Some(head) = head else {
        return Ok(None);
    };
    let rows: Vec<CardRow> = db
        .prepare(format!(
            "{CARD_SELECT} WHERE s.bundle_id = ? AND s.status = 'published' ORDER BY s.updated_at DESC"
        ))
        .bind(&[JsValue::from_str(id)])?
        .all()
        .await?
        .results()?;
    if rows.is_empty() {
        return Ok(None);
    }
    Ok(Some(BundleDetail {
        id: head.id,
        kind: head.kind,
        provenance: head.provenance,
        owner: AuthorRef {
            handle: head.author_handle,
            name: head.author_name,
            avatar_url: head.author_avatar_url,
        },
        source_ref: head.source_ref,
        synced_at: head.synced_at,
        created_at: head.created_at,
        skills: rows.into_iter().map(card).collect(),
    }))
}

pub async fn author(db: &D1Database, handle: &str) -> Result<Option<AuthorProfile>> {
    let head: Option<AuthorHeadRow> = db
        .prepare("SELECT handle, name, avatar_url FROM users WHERE handle = ?")
        .bind(&[JsValue::from_str(handle)])?
        .first(None)
        .await?;
    let Some(head) = head else {
        return Ok(None);
    };
    let rows: Vec<CardRow> = db
        .prepare(format!(
            "{CARD_SELECT} WHERE u.handle = ? AND s.status = 'published' ORDER BY s.installs DESC"
        ))
        .bind(&[JsValue::from_str(handle)])?
        .all()
        .await?
        .results()?;
    if rows.is_empty() {
        return Ok(None);
    }
    let total_installs = rows.iter().map(|row| row.installs).sum();
    Ok(Some(AuthorProfile {
        handle: head.handle,
        name: head.name,
        avatar_url: head.avatar_url,
        skill_count: rows.len() as i64,
        total_installs,
        skills: rows.into_iter().map(card).collect(),
    }))
}

fn build_tree(files: &[FileEntry]) -> Vec<TreeNode> {
    #[derive(Default)]
    struct MutableNode {
        name: String,
        path: String,
        node_type: String,
        size: i64,
        is_binary: bool,
        kids: Vec<MutableNode>,
    }
    fn insert(node: &mut MutableNode, parts: &[&str], file: &FileEntry) {
        let Some((part, rest)) = parts.split_first() else {
            return;
        };
        let is_leaf = rest.is_empty();
        let full_path = if node.path.is_empty() {
            (*part).to_owned()
        } else {
            format!("{}/{}", node.path, part)
        };
        if let Some(index) = node.kids.iter().position(|kid| kid.name == *part) {
            if is_leaf {
                node.kids[index].size = file.size;
                node.kids[index].is_binary = file.is_binary;
            } else {
                insert(&mut node.kids[index], rest, file);
            }
            return;
        }
        let mut child = MutableNode {
            name: (*part).to_owned(),
            path: full_path,
            node_type: if is_leaf { "file" } else { "dir" }.into(),
            size: if is_leaf { file.size } else { 0 },
            is_binary: is_leaf && file.is_binary,
            kids: Vec::new(),
        };
        if !is_leaf {
            insert(&mut child, rest, file);
        }
        node.kids.push(child);
    }
    let mut root = MutableNode {
        node_type: "dir".into(),
        ..MutableNode::default()
    };
    for file in files {
        let parts = file.path.split('/').collect::<Vec<_>>();
        insert(&mut root, &parts, file);
    }
    fn freeze(node: MutableNode) -> TreeNode {
        TreeNode {
            name: node.name,
            path: node.path,
            node_type: node.node_type,
            size: node.size,
            is_binary: node.is_binary,
            children: if node.kids.is_empty() {
                None
            } else {
                Some(node.kids.into_iter().map(freeze).collect())
            },
        }
    }
    root.kids.into_iter().map(freeze).collect()
}

#[cfg(test)]
mod tests {
    use super::{
        build_tree, download_filename, page_from_url, AuthorRef, BundleRef, CategoryWithCount,
        FileContentResponse, FileEntry, SearchParams, SkillCard, SkillDetail, SkillPath,
        StatsResponse, TreeNode, TrendsResponse,
    };
    use std::collections::BTreeMap;

    #[test]
    fn public_payloads_keep_the_openapi_camel_case_shape() {
        let stats = serde_json::to_value(StatsResponse {
            indexed: 6,
            contributors: 2,
            last_updated: Some("2026-01-01T00:00:00Z".into()),
        })
        .unwrap();
        assert_eq!(stats["lastUpdated"], "2026-01-01T00:00:00Z");
        assert!(stats.get("last_updated").is_none());

        let category = serde_json::to_value(CategoryWithCount {
            id: "category".into(),
            slug: "testing-qa".into(),
            name: "Testing & QA".into(),
            count: 1,
        })
        .unwrap();
        assert_eq!(category["count"], 1);
    }

    #[test]
    fn search_parser_preserves_the_kotlin_query_contract() {
        let url = worker::Url::parse("https://example.test/api/skills?cat=build-ci&cat[]=testing-qa&tag=Android&verified=no&size=2-5k&sort=tokens&page=2&pageSize=10&q=compose").unwrap();
        let params = SearchParams::from_url(&url).unwrap();
        assert_eq!(params.categories, ["build-ci"]);
        assert_eq!(params.tags, ["Android"]);
        assert!(!params.verified);
        assert_eq!(params.page, 2);
        assert_eq!(params.page_size, 10);
        assert_eq!(params.q.as_deref(), Some("compose"));
        let mixed = SearchParams::from_url(
            &worker::Url::parse("https://example.test/api/skills?cat=plain&cat[]=bracketed")
                .unwrap(),
        )
        .unwrap();
        assert_eq!(mixed.categories, ["plain"]);
        assert!(SearchParams::from_url(
            &worker::Url::parse("https://example.test/api/skills?verified=maybe").unwrap()
        )
        .is_err());
        assert!(SearchParams::from_url(
            &worker::Url::parse("https://example.test/api/skills?page=0").unwrap()
        )
        .is_err());
    }

    #[test]
    fn required_nullable_skill_fields_are_encoded_as_null() {
        let card = SkillCard {
            id: "id".into(),
            slug: "slug".into(),
            name: "name".into(),
            description: "description".into(),
            license: None,
            tags: vec![],
            category: None,
            version: "1".into(),
            version_source: "manifest".into(),
            token_upfront: 0,
            token_ondemand: 0,
            token_band: "100s".into(),
            verified: true,
            status: "published".into(),
            featured: false,
            installs: 0,
            author: AuthorRef {
                handle: "author".into(),
                name: None,
                avatar_url: None,
            },
            bundle: BundleRef {
                id: "bundle".into(),
                kind: "repo".into(),
                provenance: "repo/name".into(),
            },
            created_at: "2026-01-01T00:00:00Z".into(),
            updated_at: "2026-01-01T00:00:00Z".into(),
        };
        let encoded = serde_json::to_value(SkillDetail {
            card,
            readme_md: None,
            file_count: 0,
            total_size: 0,
            security_notes: vec![],
        })
        .unwrap();
        assert!(encoded.get("license").unwrap().is_null());
        assert!(encoded.get("category").unwrap().is_null());
        assert!(encoded.get("readmeMd").unwrap().is_null());
    }

    #[test]
    fn list_card_select_does_not_over_fetch_detail_columns() {
        use super::{CARD_SELECT, DETAIL_SELECT};
        assert!(
            !CARD_SELECT.contains("readme_md")
                && !CARD_SELECT.contains("security")
                && !CARD_SELECT.contains("skill_files")
                && !CARD_SELECT.contains("file_count")
        );
        assert!(
            DETAIL_SELECT.contains("readme_md")
                && DETAIL_SELECT.contains("security")
                && DETAIL_SELECT.contains("skill_files")
                && DETAIL_SELECT.contains("file_count")
        );
    }

    #[test]
    fn skill_subroutes_do_not_collapse_into_detail_slugs() {
        assert_eq!(
            SkillPath::parse("/api/skills/jetpack-compose-mvi"),
            Some(SkillPath::Detail("jetpack-compose-mvi"))
        );
        assert_eq!(
            SkillPath::parse("/api/skills/jetpack-compose-mvi/files"),
            Some(SkillPath::Files("jetpack-compose-mvi"))
        );
        assert_eq!(
            SkillPath::parse("/api/skills/jetpack-compose-mvi/files/references/intent.md"),
            Some(SkillPath::File {
                slug: "jetpack-compose-mvi",
                path: "references/intent.md",
            })
        );
        assert_eq!(
            SkillPath::parse("/api/skills/jetpack-compose-mvi/versions"),
            Some(SkillPath::Versions("jetpack-compose-mvi"))
        );
        assert_eq!(
            SkillPath::parse("/api/skills/jetpack-compose-mvi/download"),
            Some(SkillPath::Download("jetpack-compose-mvi"))
        );
        assert_eq!(
            SkillPath::parse("/api/skills/jetpack-compose-mvi/report"),
            None
        );
        assert_eq!(SkillPath::parse("/api/skills/"), None);
        assert_eq!(SkillPath::parse("/api/skills"), None);
    }

    #[test]
    fn page_parser_matches_ktor_strict_messages() {
        let ok = page_from_url(
            &worker::Url::parse("https://example.test/api/timeline?page=2&pageSize=20").unwrap(),
        )
        .unwrap();
        assert_eq!(ok, (2, 20));
        assert_eq!(
            page_from_url(&worker::Url::parse("https://example.test/api/timeline").unwrap())
                .unwrap(),
            (1, 24)
        );
        assert!(page_from_url(
            &worker::Url::parse("https://example.test/api/timeline?page=0").unwrap()
        )
        .is_err());
        assert!(page_from_url(
            &worker::Url::parse("https://example.test/api/timeline?page=10001").unwrap()
        )
        .is_err());
        assert!(page_from_url(
            &worker::Url::parse("https://example.test/api/timeline?pageSize=abc").unwrap()
        )
        .is_err());
    }

    #[test]
    fn file_tree_preserves_nested_paths_and_required_null_children() {
        let tree = build_tree(&[
            FileEntry {
                path: "references/intent.md".into(),
                name: "intent.md".into(),
                dir: "references".into(),
                size: 12,
                is_binary: false,
            },
            FileEntry {
                path: "SKILL.md".into(),
                name: "SKILL.md".into(),
                dir: String::new(),
                size: 4,
                is_binary: false,
            },
        ]);
        assert_eq!(tree[0].path, "references");
        assert_eq!(tree[0].node_type, "dir");
        assert_eq!(
            tree[0].children.as_ref().unwrap()[0].path,
            "references/intent.md"
        );
        assert!(tree[1].children.is_none());
        let encoded = serde_json::to_value(&tree[1]).unwrap();
        assert!(encoded.get("children").unwrap().is_null());
        assert_eq!(encoded["isBinary"], false);
    }

    #[test]
    fn file_content_and_trends_keep_required_nullable_fields() {
        let encoded = serde_json::to_value(FileContentResponse {
            slug: "slug".into(),
            path: "SKILL.md".into(),
            size: 1,
            is_binary: false,
            content: None,
            download_only: true,
            download_url: None,
        })
        .unwrap();
        assert!(encoded.get("content").unwrap().is_null());
        assert!(encoded.get("downloadUrl").unwrap().is_null());
        let trends = serde_json::to_value(TrendsResponse {
            categories: vec![],
            tags: vec![],
            token_mix: vec![],
            security_pass_rate: None,
            submission_funnel: BTreeMap::new(),
        })
        .unwrap();
        assert!(trends.get("securityPassRate").unwrap().is_null());
        assert_eq!(trends["submissionFunnel"], serde_json::json!({}));
    }

    #[test]
    fn download_filenames_reject_header_injection() {
        assert_eq!(
            download_filename("jetpack-compose-mvi", "1.4.2").as_deref(),
            Some("jetpack-compose-mvi-1.4.2.zip")
        );
        assert!(download_filename("bad\r\nslug", "1.0.0").is_none());
        assert!(download_filename("slug", "1\".zip").is_none());
    }

    #[test]
    fn tree_node_type_is_not_a_rust_keyword_field() {
        let encoded = serde_json::to_value(TreeNode {
            name: "SKILL.md".into(),
            path: "SKILL.md".into(),
            node_type: "file".into(),
            size: 1,
            is_binary: false,
            children: None,
        })
        .unwrap();
        assert_eq!(encoded["type"], "file");
        assert!(encoded.get("node_type").is_none());
    }
}
