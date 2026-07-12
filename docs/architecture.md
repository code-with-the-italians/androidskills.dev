# androidskills.dev — Architecture

> Status: agreed (initial). androidskills.dev is a searchable index of AI coding
> skills for Android/Kotlin development. This document records the stack and the
> reasoning behind it. Source design: the Claude Design handoff (`spec.html` +
> 7 chat transcripts).

## Stack at a glance

| Layer        | Choice                                                        |
|--------------|--------------------------------------------------------------|
| Frontend     | **Astro** — static public pages + SSR for `/admin` & auth    |
| API          | **Kotlin / Ktor**, single instance                           |
| Database     | **SQLite** (WAL) on a host volume; **Litestream → R2** backup |
| File storage | **Cloudflare R2** (mirrored skill files + uploaded zips)      |
| Edge         | **kamal-proxy** — TLS + path routing, one domain             |
| Deploy       | **Kamal** (`web` + `api` services) into Ivan's Hetzner VPS    |
| External     | **GitHub App**; **OpenAI-compatible LLM** (provider/key/model configurable) for skill review |

## Topology

```
            Cloudflare DNS ──► kamal-proxy  (TLS + routing, on the VPS)
                                   │   one domain: androidskills.dev
                     ┌─────────────┴──────────────┐
            path: / , /search, /skill, ...   path: /api/* , /gh/webhooks
                  ┌──▼───────┐                  ┌────▼───────┐
                  │  web      │  ── /api ──►     │  api        │
                  │  Astro    │  first-party     │  Ktor (JVM) │
                  │  Node SSR │  cookie fwd      │  + worker   │
                  └───────────┘                  └──┬──────┬───┘
            renderer / BFF;                SQLite ──┘      │ LLM (OpenAI-compat:
            /admin SSR asks Ktor /me       (WAL) on a      │   base-url/key/model
            → 404 if not admin             host volume     │   all configurable)
            (real gate is Ktor)            + Litestream     │ GitHub App
                                                 │          │ (list repos,
                                                 │          │  scan, webhooks)
                                                 │
                                                 ▼
                                          Cloudflare R2
                                    ├ files/      (mirrored skills + zips)
                                    └ db-backups/ (Litestream stream target)
```

## Decisions & rationale

- **Single domain, path-based routing** (`/api/*` + `/gh/webhooks` → Ktor, rest →
  Astro). Cookies are first-party; no CORS.
- **Ktor is the source of truth for auth + authz.** Astro SSR is a thin
  renderer/BFF: `/admin` asks Ktor `/me` and returns a real 404 for non-admins,
  but every Ktor `/admin` + `/api` endpoint enforces the role itself. The spec
  requires server-enforced gating, not client-only hiding.
- **DB-backed sessions** (`sessions` table), not stateless JWTs — admin "suspend"
  revokes access immediately.
- **LLM review = in-process Kotlin coroutine worker** polling a `jobs` table in
  SQLite. No Redis. GitHub webhook → enqueue → worker runs category assignment +
  security pass + tag validation.
- **LLM provider is config, not code.** The worker talks to an `LlmClient`
  interface; the single implementation speaks the **OpenAI-compatible
  chat-completions** wire format, so `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL`
  (Kamal secrets) select OpenAI, OpenRouter, Groq, Azure, a local Ollama/vLLM, etc.
  Structured review output uses a single JSON Schema, obtained via a fallback
  ladder: strict `json_schema` → tool calling → JSON-in-prompt + validate + retry.
  A native (Anthropic/Gemini) adapter can drop in behind `LlmClient` later — the
  provider choice is deliberately deferred and reversible.
- **Token estimate is a heuristic band** (~100s / ~1k / ~10k / ~100k via chars/4
  bucketing), not a model call — every model tokenises differently, so exactness
  is false precision (per spec §09).
- **GitHub App, not OAuth App** — needs repo listing, on-demand scan, and push
  webhooks for re-sync/re-review.
- **Contract-first**: an OpenAPI spec is the boundary between the two services;
  Astro's TS client is generated from it.
- **Monorepo**: `/web` (Astro), `/api` (Ktor/Gradle), `/deploy` (Kamal config +
  secrets). The current GitHub-Pages `index.html` retires into `/web`.

## Known constraints (SQLite under Kamal)

1. **`api` is single-instance, pinned to one host.** SQLite is a single-writer
   file on a Docker volume — no horizontal scaling of the writer. Acceptable at
   this scale; the DB lives or dies with that host, so Litestream → R2 is DR.
2. **Zero-downtime deploys vs single writer.** Kamal's rollout briefly runs two
   `api` containers (two writers + two Litestream replicators for a few seconds).
   **Decision: keep zero-downtime, rely on WAL + `busy_timeout`** to absorb the
   window, with a short Kamal drain on the old container. Restore-on-boot only
   fires when the volume is empty, so a normal rollout just re-opens the file.

## Domain model (first cut)

```
users        (id, github_id, handle, name, role[member|contributor|admin],
              status[active|suspended], created_at)
bundles      (id, kind[repo|zip], provenance, owner_user_id, source_ref, synced_at)
skills       (id, bundle_id, slug UNIQUE, name, description, license,
              category_id, version, version_source[manifest|git_head],
              token_band, verified, status[published|unlisted|flagged],
              installs, created_at, updated_at)
skill_files  (id, skill_id, path, size, r2_key, is_binary)
versions     (id, skill_id, version, source_ref, r2_zip_key, created_at)
submissions  (id, skill_id?, bundle_id, submitter_id, state, lint_score, note, created_at)
categories   (id, name, slug)
stars        (user_id, skill_id)
sessions     (id, user_id, expires_at)
jobs         (id, type[review|resync], payload, state, attempts, run_after)
audit_log    (id, actor_id, action, target, meta, created_at)
```

Provenance: a `bundle` is a set of skills sharing an origin (a GitHub repo or an
uploaded zip). A repo is owned first-come-first-served by one account.

## Build order

1. **Repo + Kamal skeleton** — both services live-but-empty behind kamal-proxy.
2. **Public read path, real** — Astro ports the designed pages; Ktor serves
   skills/search/detail from SQLite; files stream from R2. No auth. (Spec CUJ-1.)
3. **Auth + contribute** — GitHub App + OAuth, sessions, submit flow, review worker.
4. **Admin shell** — role-gated tables, audit log, moderation.

## Product constraints carried from the design (not architecture, but binding)

- **No npm / package managers** for install. Install = manual download of a `.zip`
  into the agent's skills dir (`~/.claude/skills/`, `~/.codex/skills/`); CLI is
  secondary and is a single static binary (Kotlin Native / Rust / Go), distributed
  via brew/winget/install script — never npm.
- **Skills discovered at any depth** in a repo — every directory containing a
  `SKILL.md` is a skill; dot-directories and a bare repo-root `SKILL.md` are ignored.
- **Manifest (`SKILL.md`) is the source of truth** — name/slug/description/tags are
  read-only in the UI; category is LLM-assigned.
- **Prototype is deliberately vanilla** (no React, CSS `light-dark()`); keep the
  design-system CSS as-is.
