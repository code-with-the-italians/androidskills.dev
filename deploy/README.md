# Deploy androidskills.dev

This directory contains [Kamal](https://kamal-deploy.org/) 2.x configuration for deploying both the Astro `web` service and the Ktor `api` service to the Hetzner VPS behind `kamal-proxy`.

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

   The app secrets are the same 15 env vars documented in `api/README.md` plus `TRUSTED_PROXY_COUNT`.

3. Verify the Kamal config lints:

   ```bash
   kamal config
   ```

4. Verify the critical topology assumptions before deploying:

   - **Path routing:** `kamal-proxy` must route `/api/*` and `/gh/*` to the `api` service and everything else to `web`. Run `kamal config` and inspect the generated proxy config. If your Kamal version does **not** support path_prefix routing to distinct roles on one domain, use `deploy/kamal.fallback.yml` (route all to `web`; Astro proxies `/api` and `/gh` to `api`).
   - **Single-writer deploy:** The `api` service uses `deploy.rolling: false` so the old container stops before the new one starts. Only one `api` container may ever write the SQLite file. Confirm the exact no-overlap option exists in your Kamal version.

5. Deploy:

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

Configure `api/litestream.yml` for your object-storage backend (R2 by default). The required env vars are `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET`, `R2_ENDPOINT`, and optionally `R2_BACKUP_PATH`.

## Graceful shutdown

With Litestream as PID 1, the old container must receive a clean SIGTERM so Litestream forwards it to Ktor, Ktor runs its `ApplicationStopped` hooks (cancel job worker, close HTTP clients), and Litestream writes a final WAL checkpoint before the new container takes over. Ensure Kamal's `stop_grace`/`stop_grace_period` for the `api` service is long enough for this drain (typically 10–30s). A hard SIGKILL mid-checkpoint is the one thing that can leave the WAL in a state the new container has to recover.

## SSH / host volume

The SQLite DB and mirrored files live on the host at `/var/lib/androidskills/data` and are mounted into the `api` container. Ensure that directory exists and is writable before the first deploy.

## Useful commands

| Command | Description |
|-------- | ----------- |
| `kamal config` | Inspect the generated Kamal/proxy config |
| `kamal setup` | Bootstrap the server and accessories |
| `kamal deploy` | Deploy both services |
| `kamal logs -f api` | Tail api logs |
| `kamal console` | SSH to the server |

## Open questions / known risks

- Path-prefix routing to two services on one domain is the intended architecture, but its support depends on the exact Kamal version. Use `deploy/kamal.fallback.yml` if needed.
- The `/gh/webhooks` path must reach the `api` service for GitHub webhooks to work.
- The fallback model (Astro proxies `/api` and `/gh`) requires a stable way for the `web` container to reach the `api` container; the fallback config publishes `api` on `127.0.0.1:8080` for that purpose.
