# D1 migrations

`0001_baseline.sql` defines the fresh D1 schema equivalent to Kotlin schema v5. It intentionally does not contain `schema_meta`: Wrangler owns D1 migration history.

The approved fresh-database deltas are:

- `skills.source_dir` is required, because no legacy SQLite rows are imported.
- `submissions.revision` supports optimistic JSON-payload updates.
- `jobs.dedup_key`, `dispatched_at`, and `lease_until` support Queue outbox, idempotency, and leases.

`0002_seed_categories.sql` contains deterministic category IDs and the default platform settings. The SQL is checked against SQLite with foreign keys enabled by `tests/schema_test.py`; applying it to a Cloudflare D1 binding remains a provisioning-gated integration check.
