# Deploy androidskills.dev

This directory contains [Kamal](https://kamal-deploy.org/) 2.x configuration for deploying both the Astro `web` service and the Ktor `api` service to the Hetzner VPS behind `kamal-proxy`.

## Default topology (deployable today)

`deploy/kamal.yml` uses the fallback topology: `kamal-proxy` sends all traffic to the Astro `web` service, and the Astro Node server proxies `/api/*` and `/gh/*` to the `api` service. The `api` service is not exposed by `kamal-proxy`; it is reachable from the `web` container via the host-published port `127.0.0.1:8080`.

This avoids depending on `kamal-proxy` support for path-prefix routing to two distinct services on one domain. Once that support is confirmed, `deploy/kamal.path-prefix.yml` can replace `deploy/kamal.yml` (or route `/api` directly and keep Astro proxying `/gh`).

## Prerequisites

- Kamal 2.x installed locally (`gem install kamal` or via brew).
- Docker (for building images) and SSH access to the VPS.
- A container registry (e.g. GitHub Container Registry) and credentials.
- DNS for `androidskills.dev` pointing to the VPS **before** Kamal can request TLS.
- R2 bucket + credentials for Litestream backups (optional for the scaffold, required for production).

## Setup

1. Copy the env template and fill in non-secret values:

   ```bash
   cp deploy/.env.example deploy/.env
   # edit deploy/.env
   ```

2. Configure Kamal secrets. The exact mechanism depends on your Kamal version and secret store; common options are:

   - `KAMAL_REGISTRY_PASSWORD` and app secrets from a password manager / env file.
   - `kamal secrets` with a 1Password/Bitwarden vault.

   The app secrets are the same env vars documented in `api/README.md` plus `TRUSTED_PROXY_COUNT` and `API_URL`. The default `deploy/kamal.yml` pins `API_URL=http://127.0.0.1:8080` for the web-to-api proxy.

3. (Optional) Set up the GitHub Actions workflows in `.github/workflows/`:

   - `ci.yml` runs `./gradlew test` and `npm ci && npm run build` on PRs and pushes to `develop`/`main`.
   - `deploy.yml` runs on pushes to `main` and via `workflow_dispatch`; it requires the same secrets listed above plus `KAMAL_SSH_PRIVATE_KEY` for the VPS.

4. Verify the Kamal config lints:

   ```bash
   kamal config
   ```

5. Verify the single-writer deploy option: the `api` service uses `deploy.rolling: false` so the old container stops before the new one starts. Only one `api` container may ever write the SQLite file. Confirm the exact no-overlap option exists in your Kamal version.

6. Deploy (or trigger the GitHub Actions workflow):

   ```bash
   kamal setup
   kamal deploy
   ```

## DNS cutover sequence

The domain currently serves GitHub Pages (`CNAME` at the repo root). Repointing DNS is externally visible:

1. Stand up the VPS and install Docker + Kamal bootstrapping.
2. Prove TLS issues against a temporary host or with DNS already moved.
3. Cut the DNS `A`/`CNAME` record for `androidskills.dev` to the VPS.
4. Keep the old `web/public/legacy.html` page until the new site is verified.

## Disaster recovery

The `api` container entrypoint (`api/entrypoint.sh`) restores the SQLite database from R2 if the file is missing, then starts Ktor under Litestream replication. On a routine redeploy the host volume still contains the DB, so no restore is performed. Restore is only for an empty volume / new host.

Configure `api/litestream.yml` for your object-storage backend (R2 by default). The required env vars are `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET`, `R2_ENDPOINT`, and optionally `R2_BACKUP_PATH`. The `DATA_DIR` env var must match the path in `litestream.yml` (`/data` in the default Kamal deploy).

## Graceful shutdown

With Litestream as PID 1, the old container must receive a clean SIGTERM so Litestream forwards it to Ktor, Ktor runs its `ApplicationStopped` hooks (cancel job worker, close HTTP clients), and Litestream writes a final WAL checkpoint before the new container takes over. Ensure Kamal's `stop_grace`/`stop_grace_period` for the `api` service is long enough for this drain (typically 10–30s). A hard SIGKILL mid-checkpoint is the one thing that can leave the WAL in a state the new container has to recover.

## Astro proxy routes

In the default fallback topology, the Astro Node server handles proxying in `web/src/pages/api/[...path].ts` and `web/src/pages/gh/[...path].ts`. These routes forward to the `API_URL` configured in the Kamal env (default `http://127.0.0.1:8080`). Because this adds a proxy hop, the `web` service uses `TRUSTED_PROXY_COUNT=2` while the `api` service continues to use `TRUSTED_PROXY_COUNT=1` (it sees the `X-Forwarded-For` header appended by `kamal-proxy`).

## SSH / host volume

The SQLite DB and mirrored files live on the host at `/var/lib/androidskills/data` and are mounted into the `api` container. The container runs as UID/GID 1000, so ensure that directory exists and is writable by `1000:1000` before the first deploy.

## Useful commands

| Command | Description |
|-------- | ----------- |
| `kamal config` | Inspect the generated Kamal/proxy config |
| `kamal setup` | Bootstrap the server and accessories |
| `kamal deploy` | Deploy both services |
| `kamal logs -f api` | Tail api logs |
| `kamal console` | SSH to the server |

## Open questions / known risks

- The default `deploy/kamal.yml` uses the fallback topology (Astro proxies `/api/*` and `/gh/*`). For direct path-prefix routing, use `deploy/kamal.path-prefix.yml` once you have confirmed your Kamal version supports it.
- The `/gh/webhooks` path must reach the `api` service for GitHub webhooks to work; it is proxied by Astro in the default topology.
- If you switch to `deploy/kamal.path-prefix.yml`, the `web` service drops to one proxy hop (`TRUSTED_PROXY_COUNT=1`) because the `api` service is reached directly by `kamal-proxy`.
