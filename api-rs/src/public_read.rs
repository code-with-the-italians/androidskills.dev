//! Public D1-backed read models and queries.
//!
//! These endpoints intentionally use aggregate SQL rather than loading skills into the Worker:
//! D1 remains responsible for filtering public rows and computing counts.

use serde::{Deserialize, Serialize};
use worker::{D1Database, Error, Result};

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

#[cfg(test)]
mod tests {
    use super::{CategoryWithCount, StatsResponse};

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
}
