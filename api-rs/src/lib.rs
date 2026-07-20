use serde::{Deserialize, Serialize};
use worker::{
    event, Context, Env, MessageBatch, Method, Request, Response, Result, ScheduleContext,
    ScheduledEvent,
};

const PUBLIC_OPENAPI: &str = include_str!("../../api/src/main/resources/openapi.yaml");
const ADMIN_OPENAPI: &str = include_str!("../../api/src/main/resources/openapi-admin.yaml");

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct JobMessage {
    pub job_id: String,
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
pub async fn fetch(request: Request, _env: Env, _ctx: Context) -> Result<Response> {
    if request.method() != Method::Get {
        return Response::error("Method Not Allowed", 405);
    }

    match request.path().as_str() {
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
        _ => Response::error("Not Found", 404),
    }
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
