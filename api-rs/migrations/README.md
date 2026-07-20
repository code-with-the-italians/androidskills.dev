# D1 migrations

`0001_baseline.sql` defines the fresh D1 schema equivalent to Kotlin schema v5. It intentionally does not contain `schema_meta`: Wrangler owns D1 migration history.

The approved fresh-database deltas are:

- `skills.source_dir` is required, because no legacy SQLite rows are imported.
- `submissions.revision` supports optimistic JSON-payload updates.
- `jobs.dedup_key`, `dispatched_at`, and `lease_until` support Queue outbox, idempotency, and leases.

`0002_seed_categories.sql` contains deterministic category IDs and the default platform settings.
`tests/d1_catalogue_test.mjs` imports the checked-in real catalogue JSON into the local D1 emulator
with the same persisted values and token heuristic as Kotlin's `RealSeed`; this intentionally stays
out of the baseline migration so production catalogue population remains an explicit operation.
CI checks migrations against SQLite with foreign keys enabled and applies them to Wrangler's local
D1 emulator. The all-zero database ID in `wrangler.d1.local.toml` is local-emulator-only; applying
to a provisioned Cloudflare D1 binding remains a permissions-gated integration check.
