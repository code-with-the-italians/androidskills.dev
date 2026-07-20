# Cloudflare migration — Phase 0 foundation slice

This slice starts the incremental migration of the Ktor API to a Rust/Wasm Cloudflare Worker. It is the local/CI foundation for Phase 0, not completion of the account-bound Phase 0 deployment gate. It does not change production routing, data, authentication, or storage: Ktor remains authoritative.

## Delivered in this slice

- `api-rs/` is a Rust `workers-rs` Worker with fetch, Queue, and scheduled entrypoints.
- The compatibility surface begins with `/api/health` and the two OpenAPI documents.
- JSON contract fixtures are independent of either implementation and can target Ktor or the Rust Worker through `CONTRACT_BASE_URL`.
- CI builds the Worker for `wasm32-unknown-unknown`, launches Wrangler locally, and runs the shared fixtures. CI also launches Ktor and runs the same fixtures against it.

## Acceptance criteria

1. The Worker compiles for the Workers Wasm target and starts under local Wrangler.
2. Every Phase 0 fixture passes against both current Ktor and the Worker.
3. The Worker has no secrets or account-specific D1/R2 IDs in version control.
4. A production or preview deploy is **not** claimed by this slice. It requires Cloudflare credentials plus provisioned D1, R2, Queue, rate-limit, and service-binding resources.

The health fixture asserts `ok: true`, non-empty versioning, and the explicit transition values for each unprovisioned/provisioned dependency rather than accepting arbitrary strings.

The OpenAPI fixtures verify SHA-256 digests of the current public and admin documents. `include_str!` keeps the first Worker endpoints byte-for-byte aligned with Ktor; replace those includes with Rust-owned generated artifacts before retiring Ktor, while retaining the digest fixtures.

Queue and scheduled handlers are compile-only declarations in this slice. Their bindings, delivery/retry, and scheduled-dispatch acceptance tests are part of the Cloudflare-authorized integration slice.

## Deliberate next steps

Provisioning is intentionally deferred rather than represented with fake resource IDs. Once authorized, add bindings and a deploy-only verification that proves D1 batch access, R2 streaming, Queue delivery/retry, scheduled dispatch, secrets, and the Astro-to-API service binding in a real Worker runtime.

`Cargo.lock` is generated with Rust 1.88.0 and CI consumes it with `--locked`; do not relax the lockfile gate.
