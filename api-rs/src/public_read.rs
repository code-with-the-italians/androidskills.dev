//! Public D1-backed read models and queries.
//!
//! These endpoints intentionally use aggregate SQL rather than loading skills into the Worker:
//! D1 remains responsible for filtering public rows and computing counts.

use std::collections::{BTreeMap, HashMap};

use serde::{Deserialize, Serialize};
use worker::{wasm_bindgen::JsValue, D1Database, Error, Result};

use crate::{domain::PageRequest, error::ApiError};

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
    readme_md: Option<String>,
    security: Option<String>,
    file_count: i64,
    total_size: i64,
}

const CARD_SELECT: &str = "SELECT s.id, s.slug, s.name, s.description, s.license, s.tags, s.version, s.version_source, s.token_upfront, s.token_ondemand, s.token_band, s.verified, s.status, s.featured, s.installs, s.created_at, s.updated_at, c.slug AS category_slug, c.name AS category_name, b.id AS bundle_id, b.kind AS bundle_kind, b.provenance AS bundle_provenance, u.handle AS author_handle, u.name AS author_name, u.avatar_url AS author_avatar_url, s.readme_md, s.security, COUNT(f.id) AS file_count, COALESCE(SUM(f.size), 0) AS total_size FROM skills s INNER JOIN bundles b ON b.id=s.bundle_id INNER JOIN users u ON u.id=b.owner_user_id LEFT JOIN categories c ON c.id=s.category_id LEFT JOIN skill_files f ON f.skill_id=s.id";

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
            "{CARD_SELECT} WHERE {where_sql} GROUP BY s.id ORDER BY {order} LIMIT ? OFFSET ?"
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
    let rows: Vec<CardRow> = db
        .prepare(format!(
            "{CARD_SELECT} WHERE s.slug = ? AND s.status = 'published' GROUP BY s.id"
        ))
        .bind(&[JsValue::from_str(slug)])?
        .all()
        .await?
        .results()?;
    Ok(rows.into_iter().next().map(|row| SkillDetail {
        readme_md: row.readme_md.clone(),
        file_count: row.file_count,
        total_size: row.total_size,
        security_notes: row
            .security
            .as_deref()
            .and_then(|raw| serde_json::from_str(raw).ok())
            .unwrap_or_default(),
        card: card(row),
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

#[cfg(test)]
mod tests {
    use super::{
        AuthorRef, BundleRef, CategoryWithCount, SearchParams, SkillCard, SkillDetail,
        StatsResponse,
    };

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
}
