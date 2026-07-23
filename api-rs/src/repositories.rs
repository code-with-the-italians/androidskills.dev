//! Typed, parameterized D1 access. Repository methods never interpolate caller-controlled SQL.
#![allow(dead_code)] // Route-specific repository methods are introduced incrementally.

use serde::de::DeserializeOwned;
use worker::{wasm_bindgen::JsValue, D1Database, Error, Result};

use crate::domain::{
    AuditEvent, Bundle, Category, Job, PlatformSetting, Report, Session, Skill, SkillFile, Star,
    Submission, User, Version,
};

pub(crate) struct Repositories<'db> {
    db: &'db D1Database,
}

impl<'db> Repositories<'db> {
    pub(crate) fn new(db: &'db D1Database) -> Self {
        Self { db }
    }

    pub(crate) fn users(&self) -> TableRepository<'_, User> {
        TableRepository::new(self.db)
    }
    pub(crate) fn categories(&self) -> TableRepository<'_, Category> {
        TableRepository::new(self.db)
    }
    pub(crate) fn bundles(&self) -> TableRepository<'_, Bundle> {
        TableRepository::new(self.db)
    }
    pub(crate) fn skills(&self) -> TableRepository<'_, Skill> {
        TableRepository::new(self.db)
    }
    pub(crate) fn skill_files(&self) -> TableRepository<'_, SkillFile> {
        TableRepository::new(self.db)
    }
    pub(crate) fn versions(&self) -> TableRepository<'_, Version> {
        TableRepository::new(self.db)
    }
    pub(crate) fn submissions(&self) -> TableRepository<'_, Submission> {
        TableRepository::new(self.db)
    }
    pub(crate) fn stars(&self) -> TableRepository<'_, Star> {
        TableRepository::new(self.db)
    }
    pub(crate) fn sessions(&self) -> TableRepository<'_, Session> {
        TableRepository::new(self.db)
    }
    pub(crate) fn jobs(&self) -> TableRepository<'_, Job> {
        TableRepository::new(self.db)
    }
    pub(crate) fn audit_log(&self) -> TableRepository<'_, AuditEvent> {
        TableRepository::new(self.db)
    }
    pub(crate) fn platform_settings(&self) -> TableRepository<'_, PlatformSetting> {
        TableRepository::new(self.db)
    }
    pub(crate) fn reports(&self) -> TableRepository<'_, Report> {
        TableRepository::new(self.db)
    }

    /// Executes already-validated, cross-table mutations atomically in D1.
    pub(crate) async fn execute_batch(&self, mutations: Vec<Mutation>) -> Result<Vec<WriteResult>> {
        let statements = mutations
            .iter()
            .map(|mutation| self.db.prepare(mutation.sql).bind(&mutation.params))
            .collect::<Result<Vec<_>>>()?;
        self.db
            .batch(statements)
            .await?
            .into_iter()
            .map(write_result)
            .collect()
    }
}

/// A D1 row type whose table name is compile-time controlled by this crate.
pub(crate) trait D1Row: DeserializeOwned {
    const TABLE: &'static str;
}

macro_rules! rows {
    ($($type:ty => $table:literal),+ $(,)?) => { $(impl D1Row for $type { const TABLE: &'static str = $table; })+ };
}
rows!(
    User => "users", Category => "categories", Bundle => "bundles", Skill => "skills",
    SkillFile => "skill_files", Version => "versions", Submission => "submissions", Star => "stars",
    Session => "sessions", Job => "jobs", AuditEvent => "audit_log", PlatformSetting => "platform_settings", Report => "reports",
);

pub(crate) struct TableRepository<'db, T> {
    db: &'db D1Database,
    marker: core::marker::PhantomData<T>,
}

/// Physical storage metadata from a successful D1 mutation.
///
/// `rows_written` includes index writes and must not be used as a logical affected-row count.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct WriteResult {
    pub rows_written: usize,
}

/// A parameterized mutation that can be submitted as part of an atomic D1 batch.
pub(crate) struct Mutation {
    sql: &'static str,
    params: Vec<JsValue>,
}

impl Mutation {
    /// Creates a mutation only when its static SQL targets the declared row's table.
    pub(crate) fn for_row<T: D1Row>(sql: &'static str, params: Vec<JsValue>) -> Result<Self> {
        if !is_table_mutation::<T>(sql) {
            return Err(Error::RustError(
                "repository statement table mismatch".into(),
            ));
        }
        Ok(Self { sql, params })
    }
}

impl<'db, T: D1Row> TableRepository<'db, T> {
    fn new(db: &'db D1Database) -> Self {
        Self {
            db,
            marker: core::marker::PhantomData,
        }
    }

    /// Private substrate used by typed repository methods in this module.
    async fn first_where(&self, predicate: &'static str, params: &[JsValue]) -> Result<Option<T>> {
        let sql = format!("SELECT * FROM {} WHERE {} LIMIT 1", T::TABLE, predicate);
        self.db.prepare(sql).bind(params)?.first(None).await
    }

    async fn list_where(&self, predicate: &'static str, params: &[JsValue]) -> Result<Vec<T>> {
        let sql = format!("SELECT * FROM {} WHERE {}", T::TABLE, predicate);
        self.db.prepare(sql).bind(params)?.all().await?.results()
    }

    /// Runs a static mutation for this repository's table and retains D1's changed-row count.
    pub(crate) async fn execute(
        &self,
        sql: &'static str,
        params: &[JsValue],
    ) -> Result<WriteResult> {
        if !is_table_mutation::<T>(sql) {
            return Err(Error::RustError(
                "repository statement table mismatch".into(),
            ));
        }
        let result = self.db.prepare(sql).bind(params)?.run().await?;
        write_result(result)
    }

    /// Runs a mutation that must affect exactly one row, rejecting stale or broad writes.
    /// The statement must include `RETURNING` so the logical row count is unambiguous.
    pub(crate) async fn execute_exactly_one(
        &self,
        sql: &'static str,
        params: &[JsValue],
    ) -> Result<()> {
        if !is_table_mutation::<T>(sql) || !sql.to_ascii_uppercase().contains(" RETURNING ") {
            return Err(Error::RustError(
                "exactly-one mutation must target this table and include RETURNING".into(),
            ));
        }
        let result = self.db.prepare(sql).bind(params)?.all().await?;
        let returned: Vec<serde::de::IgnoredAny> = result.results()?;
        ensure_exactly_one_returned(returned.len())
    }
}

fn write_result(result: worker::D1Result) -> Result<WriteResult> {
    let rows_written = result
        .meta()?
        .and_then(|meta| meta.rows_written)
        .ok_or_else(|| Error::RustError("D1 mutation did not return rows_written".into()))?;
    Ok(WriteResult { rows_written })
}

fn ensure_exactly_one_returned(returned_rows: usize) -> Result<()> {
    if returned_rows == 1 {
        Ok(())
    } else {
        Err(Error::RustError(format!(
            "expected exactly one returned row, got {returned_rows}"
        )))
    }
}

fn is_table_mutation<T: D1Row>(sql: &str) -> bool {
    mutation_table(sql).is_some_and(|table| table == T::TABLE)
}

fn mutation_table(sql: &str) -> Option<&str> {
    let mut tokens = sql.split_ascii_whitespace();
    match tokens.next()?.to_ascii_uppercase().as_str() {
        "INSERT" => {
            let next = tokens.next()?.to_ascii_uppercase();
            if next == "OR" {
                tokens.next()?; // SQLite conflict action, e.g. IGNORE or REPLACE.
                if !tokens.next()?.eq_ignore_ascii_case("INTO") {
                    return None;
                }
            } else if next != "INTO" {
                return None;
            }
            tokens.next()
        }
        "UPDATE" => tokens.next(),
        "DELETE" if tokens.next()?.eq_ignore_ascii_case("FROM") => tokens.next(),
        _ => None,
    }
    .map(|table| table.split_once('(').map_or(table, |(name, _)| name))
}

/// Local-Worker-only integration exercise for the real D1 binding. It is intentionally absent
/// from release builds, where application routes use the same repository methods.
#[cfg(debug_assertions)]
pub(crate) async fn local_worker_smoke(
    db: &D1Database,
) -> Result<(usize, bool, bool, bool, String)> {
    let repositories = Repositories::new(db);
    let settings = repositories.platform_settings();
    let key_a = "__ci_repository_a";
    let key_b = "__ci_repository_b";
    settings
        .execute(
            "DELETE FROM platform_settings WHERE key LIKE ?",
            &[JsValue::from_str("__ci_repository_%")],
        )
        .await?;
    let batch = repositories
        .execute_batch(vec![
            Mutation::for_row::<PlatformSetting>(
                "INSERT INTO platform_settings (key, value) VALUES (?, ?)",
                vec![JsValue::from_str(key_a), JsValue::from_str("one")],
            )?,
            Mutation::for_row::<PlatformSetting>(
                "INSERT INTO platform_settings (key, value) VALUES (?, ?)",
                vec![JsValue::from_str(key_b), JsValue::from_str("two")],
            )?,
        ])
        .await?;
    let first = settings
        .first_where("key = ?", &[JsValue::from_str(key_a)])
        .await?
        .ok_or_else(|| Error::RustError("missing bound platform setting".into()))?;
    let listed = settings
        .list_where(
            "key LIKE ? ORDER BY key",
            &[JsValue::from_str("__ci_repository_%")],
        )
        .await?;
    let exactly_one = settings
        .execute_exactly_one(
            "UPDATE platform_settings SET value = ? WHERE key = ? RETURNING key",
            &[JsValue::from_str("updated"), JsValue::from_str(key_a)],
        )
        .await
        .is_ok();
    let zero_returning = settings
        .execute_exactly_one(
            "UPDATE platform_settings SET value = ? WHERE key = ? RETURNING key",
            &[
                JsValue::from_str("missing"),
                JsValue::from_str("__ci_repository_missing"),
            ],
        )
        .await
        .is_err();
    let multiple_returning = settings
        .execute_exactly_one(
            "UPDATE platform_settings SET value = ? WHERE key LIKE ? RETURNING key",
            &[
                JsValue::from_str("multiple"),
                JsValue::from_str("__ci_repository_%"),
            ],
        )
        .await
        .is_err();
    settings
        .execute(
            "DELETE FROM platform_settings WHERE key LIKE ?",
            &[JsValue::from_str("__ci_repository_%")],
        )
        .await?;
    Ok((
        batch.len() + listed.len(),
        exactly_one,
        zero_returning,
        multiple_returning,
        first.value,
    ))
}

#[cfg(test)]
mod tests {
    use super::{ensure_exactly_one_returned, is_table_mutation, Mutation, Skill, User};

    #[test]
    fn allows_only_mutations_of_the_repository_table() {
        assert!(is_table_mutation::<User>(
            "INSERT INTO users (id) VALUES (?)"
        ));
        assert!(is_table_mutation::<User>(
            "INSERT INTO users(id) VALUES (?)"
        ));
        assert!(is_table_mutation::<User>(
            "INSERT OR IGNORE INTO users (id) VALUES (?)"
        ));
        assert!(is_table_mutation::<User>(
            "UPDATE users SET name = ? WHERE id = ?"
        ));
        assert!(is_table_mutation::<User>("DELETE FROM users WHERE id = ?"));
        assert!(!is_table_mutation::<User>("UPDATE skills SET title = ?"));
        assert!(!is_table_mutation::<User>("SELECT * FROM users"));
    }

    #[test]
    fn mutation_retains_static_sql_and_bound_values_for_a_batch() {
        let mutation =
            Mutation::for_row::<User>("UPDATE users SET status = ? WHERE id = ?", vec![])
                .expect("the user mutation should be accepted");
        assert!(is_table_mutation::<User>(mutation.sql));
        assert!(mutation.params.is_empty());
        assert!(Mutation::for_row::<Skill>("UPDATE users SET status = ?", vec![]).is_err());
    }

    #[test]
    fn exactly_one_guard_rejects_zero_and_multiple_returning_rows() {
        assert!(ensure_exactly_one_returned(0).is_err());
        assert!(ensure_exactly_one_returned(1).is_ok());
        assert!(ensure_exactly_one_returned(2).is_err());
    }
}
