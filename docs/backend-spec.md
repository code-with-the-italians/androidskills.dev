# androidskills.dev — Backend Implementation Spec

> **Audience:** an engineer/agent implementing the Kotlin/Ktor backend (`api/`)
> **without** access to the visual mockups. Everything needed is in text here.
> Pair with [architecture.md](architecture.md) for stack/hosting rationale. The
> authoritative product/UX source is the design bundle's `spec.html`; the
> backend-relevant rules from it are distilled below so you don't need the mockups.

## 0. How to use this doc

Implement in the order of **§13 Build order**. Each API endpoint in §9 lists method,
path, auth, request and response. Domain rules (§3) and validation (§10) are binding —
they encode product decisions that are easy to get subtly wrong. When in doubt, the
**`SKILL.md` manifest is the source of truth** and **admin authorization is enforced
server-side on every request** are the two invariants to never break.

## 1. Stack (already scaffolded)

- Kotlin + Ktor 3.5.0, Netty + `EngineMain`, config in `application.conf`.
- SQLite via Exposed (WAL, set through `SQLiteConfig` at connect — see `Database.kt`).
- `FileStore` interface (local-fs impl now; Cloudflare R2/S3 in prod).
- `LlmClient` interface (stub now; OpenAI-compatible impl in prod).
- Package root `dev.androidskills`. Health endpoint `GET /api/health` already works.

The frontend (Astro) is a thin BFF/renderer; **this API is the source of truth for
data and authorization.** Single domain, path-routed: `/api/*` and `/gh/*` reach this
service, everything else is Astro.

## 2. Glossary

| Term | Meaning |
|---|---|
| **Skill** | A folder containing a `SKILL.md` manifest (YAML frontmatter + Markdown body), optionally `references/`, `examples/`, `scripts/`. |
| **Bundle** | A set of skills sharing one **provenance**: a GitHub repo *or* an uploaded zip. |
| **Provenance** | Origin of a bundle's files: GitHub repo `@ commit`, or an uploaded zip id. |
| **Version** | Optional `metadata.version` (SemVer) from the manifest; if absent, the Git `HEAD` commit hash. |
| **Token band** | Order-of-magnitude estimate of a skill's token cost: `~100s` / `~1k` / `~10k` / `~100k`. Never a precise number. |
| **Verified** | Maintainer-reviewed **and** automated checks passed. Drives a badge + the default "Verified only" filter. |
| **Role** | `member` (default), `contributor`, `admin`. |

## 3. Domain rules (binding)

1. **Skill discovery:** skills are found **at any depth** in a repo/zip — every
   directory that directly contains a `SKILL.md` is a skill. A `SKILL.md` in a
   dot-directory (`.github/`, `.claude/`, `.agents/`) or a bare one at the repo root
   is **intentionally ignored**. A bundle may expose one or many skills.
2. **Manifest is source of truth:** `name`, `description`, `tags`, `license`, slug
   come from `SKILL.md` and are **never editable via the UI/API** — changing them
   means editing the file and re-syncing.
3. **Category is LLM-assigned**, not submitter-chosen (see §6).
4. **Repo ownership** is first-come-first-served: one account owns the skills
   published from a given repo. GitHub **org membership** counts as proof of
   ownership. No org/team accounts at launch.
5. **Verification** gates the public default view ("Verified only" on by default).
   Unverified skills are still reachable but carry a caution flag + are reportable.
6. **Versioning:** validate `metadata.version` as SemVer if present and use as-is;
   else derive from `HEAD` commit hash. New releases arrive via webhook (§8) and are
   **re-reviewed before publishing**. Each version is individually downloadable.
7. **Token estimate** (§5) is computed on ingest and surfaced as a band, split into
   **upfront cost** (name + description, always in agent context) vs **on-demand cost**
   (SKILL.md body + references/examples/scripts).
8. **Audit:** every admin mutation (approve/reject/edit/suspend/promote/delete/feature/
   unlist/category change/settings change) writes an immutable `audit_log` row.
9. **Admin self-protection:** an admin cannot suspend/demote/delete **their own**
   account; the **last remaining admin** cannot be removed; deleting a skill with
   active installs warns first.

## 4. Data model (SQLite)

IDs are `TEXT` UUIDv4 unless noted. Timestamps are ISO-8601 `TEXT` (UTC). Add indexes
on every foreign key and on the filter/sort columns named in §9.

```sql
CREATE TABLE users (
  id           TEXT PRIMARY KEY,
  github_id    INTEGER UNIQUE NOT NULL,
  handle       TEXT UNIQUE NOT NULL,          -- GitHub login
  name         TEXT,
  avatar_url   TEXT,
  role         TEXT NOT NULL DEFAULT 'member', -- member|contributor|admin
  status       TEXT NOT NULL DEFAULT 'active', -- active|suspended
  created_at   TEXT NOT NULL,
  updated_at   TEXT NOT NULL
);

CREATE TABLE bundles (
  id            TEXT PRIMARY KEY,
  kind          TEXT NOT NULL,                 -- repo|zip
  provenance    TEXT NOT NULL,                 -- repo full_name, or uploaded-zip id
  owner_user_id TEXT NOT NULL REFERENCES users(id),
  source_ref    TEXT,                          -- commit sha (repo) or upload hash (zip)
  installation_id INTEGER,                     -- GitHub App installation, for repos
  synced_at     TEXT,
  created_at    TEXT NOT NULL,
  UNIQUE(kind, provenance)                     -- enforces first-come ownership
);

CREATE TABLE categories (
  id    TEXT PRIMARY KEY,
  slug  TEXT UNIQUE NOT NULL,
  name  TEXT NOT NULL
);

CREATE TABLE skills (
  id              TEXT PRIMARY KEY,
  bundle_id       TEXT NOT NULL REFERENCES bundles(id),
  slug            TEXT UNIQUE NOT NULL,        -- skill folder name, validated (§10)
  name            TEXT NOT NULL,               -- from manifest
  description     TEXT NOT NULL,               -- from manifest
  license         TEXT,                        -- from manifest
  tags            TEXT NOT NULL DEFAULT '[]',  -- JSON array, validated by review
  category_id     TEXT REFERENCES categories(id), -- assigned by LLM review
  version         TEXT NOT NULL,
  version_source  TEXT NOT NULL,               -- manifest|git_head
  token_upfront   INTEGER NOT NULL DEFAULT 0,  -- estimated tokens
  token_ondemand  INTEGER NOT NULL DEFAULT 0,
  token_band      TEXT NOT NULL,               -- 100s|1k|10k|100k (of upfront+ondemand)
  verified        INTEGER NOT NULL DEFAULT 0,
  status          TEXT NOT NULL DEFAULT 'published', -- published|unlisted|flagged
  featured        INTEGER NOT NULL DEFAULT 0,
  installs        INTEGER NOT NULL DEFAULT 0,
  readme_md       TEXT,                        -- rendered-on-read; store raw markdown
  created_at      TEXT NOT NULL,
  updated_at      TEXT NOT NULL
);

CREATE TABLE skill_files (
  id        TEXT PRIMARY KEY,
  skill_id  TEXT NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
  path      TEXT NOT NULL,                     -- relative to skill root, e.g. references/x.md
  size      INTEGER NOT NULL,
  is_binary INTEGER NOT NULL DEFAULT 0,
  r2_key    TEXT NOT NULL,                     -- FileStore key (§11)
  UNIQUE(skill_id, path)
);

CREATE TABLE versions (
  id          TEXT PRIMARY KEY,
  skill_id    TEXT NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
  version     TEXT NOT NULL,
  source_ref  TEXT NOT NULL,                   -- commit sha / upload hash
  r2_zip_key  TEXT,                            -- prebuilt downloadable zip (§11)
  created_at  TEXT NOT NULL,
  UNIQUE(skill_id, version)
);

CREATE TABLE submissions (
  id           TEXT PRIMARY KEY,
  bundle_id    TEXT REFERENCES bundles(id),
  skill_id     TEXT REFERENCES skills(id),     -- set for update-an-existing-skill
  submitter_id TEXT NOT NULL REFERENCES users(id),
  state        TEXT NOT NULL,                  -- draft|in_review|changes_requested|published|rejected
  lint_score   INTEGER,                        -- from automated review
  note         TEXT,                           -- reviewer note (changes/reject reason)
  payload      TEXT,                           -- JSON: selected detected skills, scan result
  created_at   TEXT NOT NULL,
  updated_at   TEXT NOT NULL
);

CREATE TABLE stars (
  user_id   TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  skill_id  TEXT NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
  created_at TEXT NOT NULL,
  PRIMARY KEY (user_id, skill_id)
);

CREATE TABLE sessions (
  id         TEXT PRIMARY KEY,                 -- random 256-bit token (the cookie value)
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL
);

CREATE TABLE jobs (
  id          TEXT PRIMARY KEY,
  type        TEXT NOT NULL,                   -- review|resync
  payload     TEXT NOT NULL,                   -- JSON
  state       TEXT NOT NULL DEFAULT 'queued',  -- queued|running|done|failed
  attempts    INTEGER NOT NULL DEFAULT 0,
  run_after   TEXT NOT NULL,
  last_error  TEXT,
  created_at  TEXT NOT NULL,
  updated_at  TEXT NOT NULL
);

CREATE TABLE audit_log (
  id         TEXT PRIMARY KEY,
  actor_id   TEXT NOT NULL REFERENCES users(id),
  action     TEXT NOT NULL,                    -- e.g. skill.unlist, user.suspend
  target     TEXT NOT NULL,                    -- "skill:<id>", "user:<id>", ...
  meta       TEXT,                             -- JSON details (before/after, note)
  created_at TEXT NOT NULL
);

CREATE TABLE reports (                         -- user reports of unverified/bad skills
  id          TEXT PRIMARY KEY,
  skill_id    TEXT NOT NULL REFERENCES skills(id),
  reporter_id TEXT REFERENCES users(id),       -- nullable (anonymous allowed)
  reason      TEXT NOT NULL,
  created_at  TEXT NOT NULL
);
```

Use a `schema_meta(key,value)` row for the migration version (already created in
`Database.kt`). Write forward-only migrations.

## 5. Skill ingest pipeline

Triggered by (a) a submission being approved, or (b) a `resync` job from a webhook.

1. **Fetch source.** Repo: download the tarball at the target commit via the GitHub
   App installation token. Zip: read the uploaded archive from `FileStore`.
2. **Discover skills.** Walk the archive; each directory that directly contains a
   `SKILL.md` (at any depth) is one skill. Ignore dot-directories and a bare
   repo-root `SKILL.md`.
3. **Parse `SKILL.md`.** Split YAML frontmatter from the Markdown body. Frontmatter
   fields: `name` (req), `description` (req), `tags` (optional list), `license`
   (req per §10), `metadata.version` (optional SemVer). The **slug** is derived from
   the skill's leaf directory name (uniquified with a `-N` suffix on collision).
4. **Mirror files** into `FileStore` under the key layout in §11; record each in
   `skill_files` (path, size, binary flag, key). Store the raw body in `skills.readme_md`.
5. **Compute token estimate** (§ below) → `token_upfront`, `token_ondemand`, `token_band`.
6. **Resolve version** per rule §3.6; insert/update `versions` and build a downloadable
   zip (`r2_zip_key`).
7. **Enqueue a `review` job** (§6). Publishing/verification happens only after review.

**Token estimate.** Heuristic, not a tokenizer: `tokens ≈ ceil(utf8_bytes / 4)`.
- `token_upfront` = estimate over `name` + `description` only.
- `token_ondemand` = estimate over the SKILL.md body + all `references/`, `examples/`,
  `scripts/` files (skip binaries).
- `token_band` from the **total** (`upfront + ondemand`):
  `< 1_000 → "100s"`, `< 10_000 → "1k"`, `< 100_000 → "10k"`, else `"100k"`.

## 6. LLM review pipeline

A coroutine worker started with the app polls `jobs WHERE state='queued' AND run_after<=now`
(single worker; SQLite single-writer). For a `review` job:

1. Build the review input from the parsed manifest + body.
2. Call `LlmClient.review(...)` which returns a structured `ReviewResult`:
   ```
   ReviewResult {
     category: String,          // one of the existing categories' slugs
     tagsProposed: List<String>,        // tags the reviewer proposes for discovery
     security: {
       passed: Boolean,
       findings: List<{ severity: "flag" | "fyi", message: String }>,
     },
     lintScore: Int             // 0..100
   }
   ```
   Findings judge each risk **relative to the skill's declared purpose**: risks inherent
   to what the skill openly does are `fyi` (advisory); unexpected/avoidable/hidden ones
   are `flag`. Pass/fail is derived **fail-closed** — a review passes only when every
   finding is `fyi`. If the skill is already published, its current tags are passed in as
   `existingTags` so the reviewer validates against them and flags any change as an `fyi`.
   The impl speaks the **OpenAI-compatible** chat-completions API. Obtain the JSON via
   the fallback ladder: strict `response_format:json_schema` → tool calling →
   JSON-in-prompt + validate + one retry. Config: `LLM_BASE_URL`, `LLM_API_KEY`,
   `LLM_MODEL` (absent locally → the stub returns a benign result).
3. Apply results: set `skills.category_id`, `skills.tags` (proposed), record
   `lint_score` on the submission. On approve, the admin's final (editable) tags override
   these. A `flag` finding surfaces to the admin queue as blocking; `fyi` findings are
   advisory. The review never auto-verifies.
4. **Verification** = automated checks passed **and** a maintainer approved (§9 admin
   queue). The review never auto-publishes; it prepares the submission for a human.

## 7. Auth & authorization

- **Provider:** GitHub only (OAuth via the GitHub App's user flow). No Google.
- **Sessions:** on callback, create a `sessions` row and set an **httpOnly, Secure,
  SameSite=Lax** cookie holding the session id. DB-backed so suspend/logout revoke
  immediately. Reject requests whose session's user is `suspended`.
- **Roles:** `member` < `contributor` < `admin`. Browsing is anonymous; contributing
  needs a session; admin needs `role=admin`.
- **Admin gating (critical):** every `/api/admin/*` route checks `role=admin`
  **server-side**. Non-admins get **404** (not 403 — do not reveal existence). There
  is no client-only gating; the Astro `/admin` page must also 404 for non-admins, but
  that is cosmetic — this API is the real gate.

## 8. GitHub App integration

Use a **GitHub App** (not an OAuth App). Needs: read repo metadata + contents, and
push webhooks. Store the App id, private key (PEM), and webhook secret as env/secrets.

- **List repos** (`GET /api/me/repos`): use the user's installation(s) to list
  accessible repos. The user may need to install/expand the App's repo access — if a
  repo isn't accessible, return a "re-scope" hint.
- **On-demand scan** (`POST /api/me/repos/{owner}/{repo}/scan`): with an installation
  token, fetch the tree at the default branch's HEAD, apply §3.1 discovery, parse each
  `SKILL.md`, return detected skills as **read-only** metadata + token estimate. If no
  `SKILL.md` anywhere → return a "No SKILL.md found" error; submit blocked.
- **Webhooks** (`POST /gh/webhooks`): verify the HMAC signature against the webhook
  secret. On `push` to a tracked repo's default branch → enqueue a `resync` job (which
  re-ingests and re-reviews before re-publishing). Handle `installation` /
  `installation_repositories` to track access changes.

## 9. API reference

All under `/api`. Auth column: `—` none, `S` session required, `A` admin required.
Responses are JSON. Errors use `{ "error": { "code": "...", "message": "..." } }` with
appropriate HTTP status (`401` no session, `404` admin-or-missing, `409` conflict,
`422` validation).

### Public (`—`)
| Method | Path | Notes |
|---|---|---|
| GET | `/api/health` | liveness (exists) |
| GET | `/api/stats` | home stat line: indexed count, contributors, last-updated |
| GET | `/api/skills` | search/list. Query: `q, cat[], tag[], size(<2k\|2-5k\|5k+), verified(bool, default true), sort(relevance\|installs\|updated\|tokens), page`. Returns paged cards + facet counts. |
| GET | `/api/skills/{slug}` | detail: manifest fields, author, version, license, token bands (upfront/ondemand/band), installs, dates, verified, status. |
| GET | `/api/skills/{slug}/files` | file tree (grouped, sizes, binary flags). |
| GET | `/api/skills/{slug}/files/{path}` | one file's content (or `?raw=1`). Binary/oversized → metadata + "download only". |
| GET | `/api/skills/{slug}/versions` | version list with source provenance. |
| GET | `/api/skills/{slug}/download` | zip of current version (stream from `FileStore` / redirect to presigned URL). `?version=` for a specific one. Increments `installs`. |
| GET | `/api/categories` | taxonomy + per-category skill counts. |
| GET | `/api/trends` | dashboard aggregates (themes, security pass rate, token mix, submission funnel). |
| GET | `/api/timeline` | activity feed (publishes, version bumps, re-syncs), newest first, paged. |
| GET | `/api/bundles` / `/api/bundles/{id}` | bundle index / a bundle + its skills. |
| GET | `/api/authors/{handle}` | author profile + their published skills. |
| POST | `/api/skills/{slug}/report` | file a report (anonymous allowed). |

### Auth
| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/api/auth/github/start` | — | redirect to GitHub OAuth (state cookie). |
| GET | `/api/auth/github/callback` | — | exchange code, upsert user, create session, set cookie. |
| POST | `/api/auth/logout` | S | delete session row + clear cookie. |
| GET | `/api/me` | S | current user (role, status) — `401` if anon. |

### Contributor (`S`)
| Method | Path | Notes |
|---|---|---|
| GET | `/api/me/repos` | list installation-accessible repos. |
| POST | `/api/me/repos/{owner}/{repo}/scan` | on-demand scan (§8). |
| GET | `/api/me/submissions` | my submissions grouped by state. |
| POST | `/api/me/submissions` | create draft / submit; body = selected detected skills. |
| GET | `/api/me/submissions/{id}` | one submission. |
| POST | `/api/me/submissions/{id}/submit` | move `draft → in_review`; enqueue review. |
| POST | `/api/me/submissions/{id}/withdraw` | withdraw an in-review item. |
| DELETE | `/api/me/submissions/{id}` | delete a draft. |
| POST | `/api/skills/{slug}/unpublish` | owner only → `status=unlisted`. |
| POST | `/api/skills/{slug}/resync` | owner only → enqueue `resync`. |
| GET | `/api/me/stars` · POST/DELETE `/api/me/stars/{slug}` | account-synced starred library. |
| GET/PUT | `/api/me/settings` | GitHub access, prefs. |
| DELETE | `/api/me` | delete account. |

### Admin (`A`, non-admins → 404)
| Method | Path | Notes |
|---|---|---|
| GET | `/api/admin/queue` | review queue; filter `status`, search `q`. |
| GET | `/api/admin/queue/{id}` | review detail: metadata, lint score, full file list, provenance (repo @ commit), dupe flag. |
| POST | `/api/admin/queue/{id}/decision` | body `{decision: approve\|request_changes\|reject\|skip, note?}`. Approve → publish + verify + ingest; writes audit; notifies author. |
| GET | `/api/admin/skills` | dense list; filter `category,status`, search, sort any column, server-side `page`. |
| PATCH | `/api/admin/skills/{id}` | edit metadata-not-from-manifest (feature/unlist/flag). |
| POST | `/api/admin/skills/bulk` | body `{ids[], action: feature\|unlist\|delete}`. Partial-failure reports per-row results. |
| GET | `/api/admin/users` | roster; filter `role,status`, search; summary stats. |
| PATCH | `/api/admin/users/{id}` | promote/demote/suspend/reinstate (guards §3.9). |
| GET | `/api/admin/users/export` | CSV of the filtered roster. |
| GET/POST/PATCH | `/api/admin/categories` | list/create/rename/**merge** (reassigns skill counts). |
| GET/PUT | `/api/admin/settings` | platform config: review policy, token soft-cap, integrations. |
| GET | `/api/admin/audit` | audit log, paged. |

### Webhooks
| Method | Path | Notes |
|---|---|---|
| POST | `/gh/webhooks` | verify HMAC; `push` → `resync` job; installation events → access tracking. |

## 10. Validation & edge cases

- **Slug:** lowercase kebab `^[a-z0-9]+(-[a-z0-9]+)*$`, unique across all skills.
  Duplicate on submit → `409` with a "propose an update" path tied to the existing skill.
- **Required manifest fields:** `name`, `description`, `license` present; `tags`
  well-formed if present; `metadata.version` valid SemVer if present. Missing → `422`
  with per-field reasons; submission blocked until the manifest is valid.
- **No `skills/` dir** on scan → inline error, submit blocked (§8).
- **Token soft-cap:** warn (not block) when a band reaches the configured soft-cap.
- **Admin guards (§3.9):** self-suspend/-demote/-delete blocked; last-admin removal
  blocked; deleting a skill with installs warns about downstream impact.
- **Bulk partial failure:** return which ids succeeded/failed; leave failures for retry.
- **Unknown slug** → `404`. **Binary/oversized file** preview → metadata + download-only.

## 11. File storage layout (`FileStore`)

Keys are deterministic so they're rebuildable. Suggested layout:

```
skills/{skillId}/files/{path}          # mirrored source files
skills/{skillId}/versions/{version}.zip # prebuilt download
uploads/{uploadHash}.zip                # raw uploaded bundles
```

Local dev writes under `./data/files/<key>`; prod writes to R2 with the same keys and
serves downloads via presigned URLs. The interface (`put/get/exists`) already exists;
add `presignedUrl(key)` for prod and a streaming `get` for large files.

## 12. Audit log

Write an `audit_log` row for every admin mutation **in the same transaction** as the
change, capturing `actor_id`, `action`, `target`, and a `meta` JSON with before/after
+ any note. Never update or delete audit rows.

## 13. Config / env vars

| Var | Purpose | Local default |
|---|---|---|
| `PORT` | HTTP port | 8080 |
| `DATA_DIR` | sqlite + local file store root | `../data` |
| `APP_VERSION` | reported in `/api/health` | `0.0.1-local` |
| `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL` | OpenAI-compatible review LLM | unset → stub |
| `GITHUB_APP_ID` / `GITHUB_APP_PRIVATE_KEY` / `GITHUB_WEBHOOK_SECRET` | GitHub App | unset → scan/webhooks disabled |
| `GITHUB_OAUTH_CLIENT_ID` / `GITHUB_OAUTH_CLIENT_SECRET` | login | unset → auth disabled |
| `R2_*` (endpoint, bucket, key, secret) | prod file storage | unset → local-fs |
| `SESSION_COOKIE_DOMAIN` / `PUBLIC_BASE_URL` | cookie + OAuth callback | localhost |

Keep cloud creds out of the repo; in prod they come from Kamal secrets.

## 14. Backend build order

1. **Migrations + domain model** (§4) — tables, Exposed mappings, forward migrations.
2. **Public read API** (§9 Public) over seed data — search/list, skill detail, files,
   download from `FileStore`. No auth. This unblocks the whole public frontend.
3. **Auth** (§7) — GitHub OAuth, sessions, `/api/me`, role middleware (admin → 404).
4. **GitHub App + ingest** (§5, §8) — repo list, scan, `SKILL.md` parse, file mirror,
   token estimate, version resolution.
5. **Review worker** (§6) — `jobs` table, coroutine worker, `LlmClient` OpenAI-compat impl.
6. **Contributor flows** (§9 Contributor) — submissions, drafts, stars, settings.
7. **Admin** (§9 Admin) — queue + decisions, skills/users tables with bulk actions,
   categories, settings, audit log.

Stub external dependencies (LLM, GitHub) behind their interfaces so steps 1–2 and 6
run with zero cloud setup.
