# Staging — staging.androidskills.dev

A private staging environment: the same CI-built images, run with Docker Compose on
a Proxmox LXC, exposed **only** through a Cloudflare Tunnel + Cloudflare Access
limited to two emails. No inbound ports are opened on the host.

Full rationale and the reviewed design decisions live in `.plans/STAGING-PLAN.md`.

## Topology

```
Browser ──TLS──► Cloudflare edge ──► Cloudflare Tunnel ──► cloudflared ──► web (Astro :4321) ──► api (Ktor :8080)
 (2 emails)          │  Access                (outbound-only)      proxies /api/* and /gh/*      SQLite volume
                     ▼                                                                            (Litestream OFF)
              Access gate: allow the 2 emails; bypass /gh/webhooks (HMAC-verified)
```

- `api` is **never** published to the host — internal compose network only (`api:8080`).
- Litestream/R2 are **off** in staging; data is ephemeral + `SEED_REAL=1` (real skills catalogue).

## Files

| File | Purpose |
|------|---------|
| `docker-compose.staging.yml` | The three services (`init-data`, `api`, `web`, `cloudflared` behind a `tunnel` profile). |
| `.env.staging.example` | Copy to `.env.staging` (git-ignored) and fill in. |

## 1. Local test (no tunnel, no Access)

Proves the two-service wiring before anything is exposed.

```bash
cd deploy/staging
cp .env.staging.example .env.staging
# edit .env.staging: PUBLIC_BASE_URL=http://localhost:4321 (leave CF_TUNNEL_TOKEN blank)
docker compose --env-file .env.staging -f docker-compose.staging.yml up
```

Then verify at `http://localhost:4321`:

- Site loads; `/api/*` and `/gh/*` reach Ktor through the Astro proxy.
- `curl http://localhost:4321/api/health` → healthy.
- `curl http://localhost:4321/api/health/deep` → all green. **If `githubApp` is false**, the
  multi-line `GITHUB_APP_PRIVATE_KEY` is mis-quoted (see `.env.staging.example`).

> `${...}` in the compose file interpolate from `--env-file` / the shell, **not** from the
> service `env_file:` — always pass `--env-file .env.staging`. A missing `PUBLIC_BASE_URL`
> fails loudly at compose time (`:?` guard) instead of silently defaulting to localhost.
> `CF_TUNNEL_TOKEN` may be left blank for a local run; it is only validated by cloudflared
> when the `tunnel` profile is active.

## 2. Cloudflare setup (one-time)

**Tunnel** — Zero Trust → Networks → Tunnels → create `staging`. Add a public hostname:
`staging.androidskills.dev` → service `http://web:4321`. This auto-creates the proxied DNS
record. Copy the tunnel token into `CF_TUNNEL_TOKEN` in `.env.staging`.

**Access — two applications** (path scoping lives on the *application*, most-specific wins):

1. App `staging.androidskills.dev` → policy **Allow** emails `poggos@gmail.com`,
   `imorgillo@gmail.com` (Google login or one-time PIN). Gates everything.
2. App `staging.androidskills.dev/gh/webhooks` → policy **Bypass (Everyone)**. Required, or
   GitHub's webhook POSTs (no Access cookie) get 302'd to SSO. Safe: Ktor verifies the
   `X-Hub-Signature-256` HMAC. Scope this exact path — it is the app's only `/gh` route.

**Staging GitHub App** — register ONE GitHub App (not a separate OAuth App) with callback +
webhook at `https://staging.androidskills.dev/...`. Put all five creds in `.env.staging`
(`GITHUB_OAUTH_CLIENT_ID/SECRET`, `GITHUB_APP_ID`, `GITHUB_APP_PRIVATE_KEY`,
`GITHUB_WEBHOOK_SECRET`).

## 3. Deploy on the Proxmox LXC

1. **Container** — unprivileged LXC, enable `nesting=1` and `keyctl=1` (needed for Docker).
   If overlay2 misbehaves under the unprivileged container, use `fuse-overlayfs` or run a VM.
   Open **no** inbound ports — the tunnel is outbound-only.
2. **Docker** — install Docker Engine + the compose plugin.
3. **GHCR auth** — packages are private by default:
   ```bash
   echo "$GHCR_PAT" | docker login ghcr.io -u <user> --password-stdin   # read:packages PAT
   ```
4. **Deploy** — copy this directory + your filled `.env.staging`, then:
   ```bash
   cd deploy/staging
   docker compose --env-file .env.staging -f docker-compose.staging.yml --profile tunnel pull
   docker compose --env-file .env.staging -f docker-compose.staging.yml --profile tunnel up -d
   ```

## 4. Verify deployed

- Visiting `https://staging.androidskills.dev` with a non-listed email is refused; the two
  allowed emails get in.
- GitHub OAuth round-trips (edge case: an Access session expiring while you idle on GitHub's
  authorize page forces re-auth on the callback).
- A signed test webhook (`X-Hub-Signature-256`) reaches Ktor via the bypass app.
- `/api/health/deep` all green; admin routes reachable (via `BOOTSTRAP_ADMIN_GITHUB_ID`).

## Updating

CI pushes `:develop` (and `:<sha>`) on every push to `develop`. To roll staging forward:

```bash
docker compose --env-file .env.staging -f docker-compose.staging.yml --profile tunnel pull
docker compose --env-file .env.staging -f docker-compose.staging.yml --profile tunnel up -d
```

`watchtower` can automate this, but it will re-flash staging mid-QA on every develop push —
manual pull is usually preferable. `up -d` recreates stop-then-start, preserving the SQLite
single-writer invariant.
