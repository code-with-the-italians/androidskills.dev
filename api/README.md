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
- `PUBLIC_BASE_URL` — canonical URL used for OAuth redirects and absolute links (default: `http://localhost:8080`).

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

## Operations

- Health (LB probe): `GET /api/health`
- Deep health (DB/files/GitHub-App/LLM checks): `GET /api/health/deep`
- Public endpoints are rate-limited by source IP; health and webhook endpoints are exempt.

The app exits non-zero at boot if env validation fails or database migrations fail.

## Useful tasks

| Task | Description |
|------|-------------|
| `./gradlew test`    | Run the tests     |
| `./gradlew build`   | Build the project |
| `./gradlew run`     | Run the server    |
