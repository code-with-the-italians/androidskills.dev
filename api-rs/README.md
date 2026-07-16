# Rust Cloudflare Worker

This is the incremental replacement for `api/`; the Ktor service remains authoritative until route groups pass the shared black-box contract fixtures.

## Local prerequisites

- Rust 1.88.0 and the `wasm32-unknown-unknown` target
- `worker-build` 0.8.3 (`cargo install worker-build --version 0.8.3 --locked`)
- Node 24.16.0 and Wrangler 4.111.0

Run `npm ci`, then start the Phase 0 Worker with `npx wrangler dev --local --port 8787` and execute `CONTRACT_BASE_URL=http://127.0.0.1:8787 node tests/contract/run.mjs`. The exact same fixtures can target Ktor at `http://127.0.0.1:8080`.

`wrangler.toml` deliberately contains no D1/R2/Queue IDs or cron configuration. Provision those resources and add their bindings only in the Cloudflare-authorized change. The fetch, Queue, and scheduled entrypoints compile in CI; only fetch routes are locally exercised until a D1/Queue-enabled integration slice is added.
