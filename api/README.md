# androidskills-api

Kotlin/Ktor backend for [androidskills.dev](https://androidskills.dev). Serves `/api/*` and `/gh/webhooks`; everything else is handled by the Astro frontend.

## Quick start

```bash
./gradlew test
./gradlew run
```

The server listens on the port configured in `application.conf` (8080 by default).

## Configuration

Copy `api/.env.example` to `.env` and fill in the values for your environment. The app reads env vars at boot; there are no secrets in the repo.

Required for any boot:

- `DATA_DIR` — directory for SQLite and mirrored files. Must be writable (default: `../data`).
- `PUBLIC_BASE_URL` — canonical URL used for OAuth redirects and absolute links (default: `http://localhost:8080`). Must be a bare scheme://host[:port] URL with no trailing path or credentials.

Feature sets are **all-or-nothing**. If any env var in a feature is present but the others are missing, the app fails to start with a clear error. If all are absent, the feature is disabled cleanly.

- **GitHub OAuth login:** `GITHUB_OAUTH_CLIENT_ID`, `GITHUB_OAUTH_CLIENT_SECRET`
- **GitHub App (repo scan, install tokens, webhooks):** `GITHUB_APP_ID`, `GITHUB_APP_PRIVATE_KEY`, `GITHUB_WEBHOOK_SECRET`
- **LLM review worker:** `LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL`

Optional:

- `APP_VERSION` — reported by `/api/health` (default: `0.0.1-local`).
- `SEED_DEMO` — set to `1` to seed demo data on first boot (local/dev only).
- `BOOTSTRAP_ADMIN_GITHUB_ID` — numeric GitHub user id promoted to admin on first login.
- `SESSION_COOKIE_DOMAIN` — optional `Domain` attribute; leave blank for host-only.
- `SESSION_COOKIE_SECURE` — `1`/`true` to require `Secure` cookies. Inferred from `PUBLIC_BASE_URL` host by default (false for localhost).
- `TRUSTED_PROXY_COUNT` — number of trusted reverse-proxy hops in front of the app; used for rate-limit source IP keys. `0` uses the direct connection, `1` (default) uses the last `X-Forwarded-For` entry.

## Operations

- Health (LB probe): `GET /api/health`
- Deep health (DB/files/GitHub-App/LLM checks): `GET /api/health/deep`
- Public endpoints are rate-limited by source IP; health and webhook endpoints are exempt.

The app exits non-zero at boot if env validation fails or database migrations fail.

## Running in Docker

Build a production image from the repo root:

```bash
docker build -t androidskills-api ./api
```

Run locally, mounting a host directory for SQLite/files:

```bash
docker run -p 8080:8080 \
  -v "$(pwd)/data:/data" \
  -e DATA_DIR=/data \
  -e PUBLIC_BASE_URL=http://localhost:8080 \
  androidskills-api
```

The production image includes [Litestream](https://litestream.io/) and uses `api/entrypoint.sh`: it restores the SQLite DB from R2 if the file is missing, then starts Ktor under Litestream replication. Health is checked by the Kamal proxy hitting `/api/health`; no Docker `HEALTHCHECK` is configured.

To run locally without Litestream, override the entrypoint:

```bash
docker run ... --entrypoint java androidskills-api -jar androidskills-api.jar
```

## Useful tasks

| Task | Description |
|------|-------------|
| `./gradlew test`    | Run the tests     |
| `./gradlew build`   | Build the project |
| `./gradlew run`     | Run the server    |
