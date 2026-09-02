use serde::{Deserialize, Serialize};
pub mod domain;
pub mod error;
pub mod ids;
pub mod json_column;
pub mod public_read;
pub mod repositories;
pub mod time;
use worker::{
    event, Context, Env, MessageBatch, Method, Request, Response, Result, ScheduleContext,
    ScheduledEvent,
};

use crate::public_read::SkillPath;

const PUBLIC_OPENAPI: &str = include_str!("../../api/src/main/resources/openapi.yaml");
const ADMIN_OPENAPI: &str = include_str!("../../api/src/main/resources/openapi-admin.yaml");

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct JobMessage {
    pub job_id: String,
}

#[cfg(debug_assertions)]
#[derive(Serialize)]
struct RepositorySmokeResponse {
    operations: usize,
    exactly_one: bool,
    zero_returning_rejected: bool,
    multiple_returning_rejected: bool,
    decoded_value: String,
}

#[derive(Serialize)]
struct HealthResponse<'a> {
    ok: bool,
    version: &'a str,
    db: &'a str,
    #[serde(rename = "fileStore")]
    file_store: &'a str,
    llm: &'a str,
    auth: &'a str,
}

/// Phase 0 route surface. It intentionally stays free of account bindings so it can run in
/// Wrangler's local runtime and provide a stable target for the contract harness.
#[event(fetch, respond_with_errors)]
pub async fn fetch(request: Request, env: Env, _ctx: Context) -> Result<Response> {
    if request.method() != Method::Get {
        return Response::error("Method Not Allowed", 405);
    }

    #[cfg(debug_assertions)]
    if request.path() == "/__ci/repositories" {
        let db = env.d1("DB")?;
        let (
            operations,
            exactly_one,
            zero_returning_rejected,
            multiple_returning_rejected,
            decoded_value,
        ) = repositories::local_worker_smoke(&db).await?;
        return Response::from_json(&RepositorySmokeResponse {
            operations,
            exactly_one,
            zero_returning_rejected,
            multiple_returning_rejected,
            decoded_value,
        });
    }

    let path = request.path();
    let url = request.url()?;
    match path.as_str() {
        "/api/health" => Response::from_json(&HealthResponse {
            ok: true,
            version: env!("CARGO_PKG_VERSION"),
            db: "unconfigured",
            file_store: "unconfigured",
            llm: "disabled",
            auth: "disabled",
        }),
        "/api/openapi.yaml" => Response::ok(PUBLIC_OPENAPI)
            .map(|response| response.with_headers(content_type("application/yaml"))),
        "/api/openapi-admin.yaml" => Response::ok(ADMIN_OPENAPI)
            .map(|response| response.with_headers(content_type("application/yaml"))),
        "/api/stats" => match env.d1("DB") {
            Ok(db) => Response::from_json(&public_read::stats(&db).await?),
            Err(_) => error::ApiError::service_unavailable().response(),
        },
        "/api/categories" => match env.d1("DB") {
            Ok(db) => Response::from_json(&public_read::categories(&db).await?),
            Err(_) => error::ApiError::service_unavailable().response(),
        },
        "/api/skills" => match env.d1("DB") {
            Ok(db) => match public_read::SearchParams::from_url(&url) {
                Ok(params) => Response::from_json(&public_read::search(&db, &params).await?),
                Err(error) => error.response(),
            },
            Err(_) => error::ApiError::service_unavailable().response(),
        },
        "/api/trends" => match env.d1("DB") {
            Ok(db) => Response::from_json(&public_read::trends(&db).await?),
            Err(_) => error::ApiError::service_unavailable().response(),
        },
        "/api/timeline" => match env.d1("DB") {
            Ok(db) => match public_read::page_from_url(&url) {
                Ok((page, page_size)) => {
                    Response::from_json(&public_read::timeline(&db, page, page_size).await?)
                }
                Err(error) => error.response(),
            },
            Err(_) => error::ApiError::service_unavailable().response(),
        },
        "/api/bundles" => match env.d1("DB") {
            Ok(db) => match public_read::page_from_url(&url) {
                Ok((page, page_size)) => {
                    Response::from_json(&public_read::bundles(&db, page, page_size).await?)
                }
                Err(error) => error.response(),
            },
            Err(_) => error::ApiError::service_unavailable().response(),
        },
        path if path.starts_with("/api/skills/") => match SkillPath::parse(path) {
            Some(route) => skill_route(route, &url, &env).await,
            None => Response::error("Not Found", 404),
        },
        path if path.starts_with("/api/bundles/") => match env.d1("DB") {
            Ok(db) => match single_segment(path, "/api/bundles/") {
                Some(id) => match public_read::bundle_detail(&db, id).await? {
                    Some(bundle) => Response::from_json(&bundle),
                    None => error::ApiError::not_found().response(),
                },
                None => Response::error("Not Found", 404),
            },
            Err(_) => error::ApiError::service_unavailable().response(),
        },
        path if path.starts_with("/api/authors/") => match env.d1("DB") {
            Ok(db) => match single_segment(path, "/api/authors/") {
                Some(handle) => match public_read::author(&db, handle).await? {
                    Some(author) => Response::from_json(&author),
                    None => error::ApiError::not_found().response(),
                },
                None => Response::error("Not Found", 404),
            },
            Err(_) => error::ApiError::service_unavailable().response(),
        },
        _ => Response::error("Not Found", 404),
    }
}

async fn skill_route(route: SkillPath<'_>, url: &worker::Url, env: &Env) -> Result<Response> {
    let db = match env.d1("DB") {
        Ok(db) => db,
        Err(_) => return error::ApiError::service_unavailable().response(),
    };
    match route {
        SkillPath::Detail(slug) => match public_read::skill_detail(&db, slug).await? {
            Some(skill) => Response::from_json(&skill),
            None => error::ApiError::not_found().response(),
        },
        SkillPath::Files(slug) => match public_read::file_tree(&db, slug).await? {
            Some(tree) => Response::from_json(&tree),
            None => error::ApiError::not_found().response(),
        },
        SkillPath::File { slug, path } => file_content_route(&db, env, url, slug, path).await,
        SkillPath::Versions(slug) => match public_read::versions(&db, slug).await? {
            Some(versions) => Response::from_json(&versions),
            None => error::ApiError::not_found().response(),
        },
        SkillPath::Download(slug) => download_route(&db, env, url, slug).await,
    }
}

async fn file_content_route(
    db: &worker::D1Database,
    env: &Env,
    url: &worker::Url,
    slug: &str,
    path: &str,
) -> Result<Response> {
    let Some(meta) = public_read::file_content_meta(db, slug, path).await? else {
        return error::ApiError::not_found().response();
    };
    let raw = public_read::is_raw_preview(url);
    if meta.download_only {
        return Response::from_json(&public_read::file_content_json(meta, None));
    }
    let Some(bucket) = files_bucket(env) else {
        return error::ApiError::service_unavailable().response();
    };
    let missing = || {
        error::ApiError::storage(format!(
            "File bytes missing for '{}/{}' (key={})",
            meta.slug, meta.path, meta.r2_key
        ))
    };
    let Some(object) = bucket.get(&meta.r2_key).execute().await? else {
        return missing().response();
    };
    let Some(body) = object.body() else {
        return missing().response();
    };
    let bytes = body.bytes().await?;
    if bytes.len() as i64 > public_read::PREVIEW_LIMIT {
        return Response::from_json(&public_read::file_content_json(
            public_read::FileContentMeta {
                download_only: true,
                ..meta
            },
            None,
        ));
    }
    let content = match String::from_utf8(bytes) {
        Ok(content) => content,
        Err(_) => {
            return error::ApiError::storage(format!(
                "File bytes missing for '{}/{}' (key={})",
                meta.slug, meta.path, meta.r2_key
            ))
            .response()
        }
    };
    if raw {
        let headers = content_type("text/plain; charset=utf-8");
        headers
            .set("X-Content-Type-Options", "nosniff")
            .expect("valid nosniff header");
        return Response::ok(content).map(|response| response.with_headers(headers));
    }
    Response::from_json(&public_read::file_content_json(meta, Some(content)))
}

async fn download_route(
    db: &worker::D1Database,
    env: &Env,
    url: &worker::Url,
    slug: &str,
) -> Result<Response> {
    let version = public_read::query_value(url, "version");
    let Some(target) = public_read::download_target(
        db,
        slug,
        version.as_deref().filter(|value| !value.is_empty()),
    )
    .await?
    else {
        return error::ApiError::not_found().response();
    };
    let Some(filename) = public_read::download_filename(&target.slug, &target.version) else {
        return error::ApiError::internal().response();
    };
    let Some(bucket) = files_bucket(env) else {
        return error::ApiError::service_unavailable().response();
    };
    let Some(key) = target.r2_zip_key.as_deref() else {
        return missing_archive(&target).response();
    };
    let Some(object) = bucket.get(key).execute().await? else {
        return missing_archive(&target).response();
    };
    let Some(body) = object.body() else {
        return missing_archive(&target).response();
    };
    public_read::increment_installs(db, &target.skill_id).await?;
    let headers = content_type("application/zip");
    headers
        .set(
            "content-disposition",
            &format!("attachment; filename=\"{filename}\""),
        )
        .expect("valid content disposition");
    Ok(Response::from_body(body.response_body()?)?.with_headers(headers))
}

fn missing_archive(target: &public_read::DownloadTarget) -> error::ApiError {
    if target.is_current {
        error::ApiError::storage(format!(
            "File bytes missing for '{}' version '{}' (key={})",
            target.slug,
            target.version,
            target.r2_zip_key.as_deref().unwrap_or("none")
        ))
    } else {
        error::ApiError::not_found()
    }
}

fn files_bucket(env: &Env) -> Option<worker::Bucket> {
    env.bucket("FILES").ok()
}

fn single_segment<'a>(path: &'a str, prefix: &str) -> Option<&'a str> {
    path.strip_prefix(prefix)
        .filter(|rest| !rest.is_empty() && !rest.contains('/'))
}

/// Queue plumbing is present from the first Worker build. The job ledger and consumer logic are
/// added once D1 and the queue are provisioned; acknowledging no messages here would hide work,
/// so every message is explicitly retried until that implementation lands.
#[event(queue)]
pub async fn consume_jobs(batch: MessageBatch<JobMessage>, _env: Env, _ctx: Context) -> Result<()> {
    batch.retry_all();
    Ok(())
}

/// The scheduled entrypoint will dispatch D1 outbox rows and purge expired sessions in Phase 1.
#[event(scheduled)]
pub async fn scheduled(_event: ScheduledEvent, _env: Env, _ctx: ScheduleContext) {}

fn content_type(value: &str) -> worker::Headers {
    let headers = worker::Headers::new();
    headers
        .set("content-type", value)
        .expect("valid content type");
    headers
}
