# Step 10 Phase 4 Plan — Admin Surfaces

## Goal
Build the authenticated admin web surfaces on top of the existing `api/admin` routes (spec §9), mirroring the design-handoff admin shell, while preserving the security foundations established in Phases 1–3.

## Scope

| Route | What it does | API base |
|-------|--------------|----------|
| `/admin` | Dashboard with counts (queue, skills, users) + audit preview | `/api/admin` |
| `/admin/queue` | Review queue list + detail/decision | `/api/admin/queue` |
| `/admin/skills` | Skill roster, search/filter, bulk actions, row edit | `/api/admin/skills` |
| `/admin/users` | User roster, search/filter, role/status patch, CSV export | `/api/admin/users` |
| `/admin/categories` | Category list, create, rename, merge | `/api/admin/categories` |
| `/admin/settings` | Platform settings (review policy, token soft cap, LLM toggle) | `/api/admin/settings` |
| `/admin/audit` | Audit log with action/target filters | `/api/admin/audit` |

Shared components:
- `AdminLayout.astro` — sidebar nav, admin bar, mobile menu, admin-only 404 gate
- `AdminNav.astro` — sidebar navigation with active state
- `AdminShell.astro` — wraps `BaseLayout` + `AdminLayout` so theme and account menu still work
- `AdminStatCard.astro` / `AdminBulkBar.astro` / `AdminTable.astro` — design-system-aligned reusable pieces

## API Contract — separate `openapi.admin.yaml`

The public OpenAPI spec deliberately omits admin routes. Phase 4 introduces a second spec that lives beside it:

- `api/src/main/resources/openapi-admin.yaml` — documents all `/api/admin/*` paths and schemas
- Admin schemas: `AdminQueueItem`, `AdminQueueDetail`, `AdminSkillListItem`, `AdminUserListItem`, `AdminCategoryDto`, `AdminPlatformSettings`, `AuditLogEntry`, `DecisionRequest`, `AdminSkillPatch`, `AdminUserPatch`, `BulkActionRequest`, `BulkActionResponse`
- Web: generate `web/src/lib/api-admin-types.ts` with `openapi-typescript`
- Add a new `AdminApiClient` class in `web/src/lib/admin-api.ts` that mirrors the public `ApiClient` pattern but is typed from the admin schema

> **Why separate:** keeps the public spec a true public contract, avoids leaking admin paths, and makes the TypeScript boundary explicit.

## Security Requirements

### 1. Admin 404 gate — exactly like the API

The API returns `404` for non-admin and anonymous callers to hide the routes. The web SSR must do the same:

- Every admin Astro page calls `api.getMe()` first. If it throws 401/404, the page returns `Astro.redirect('/404')` or renders a 404 response — **not** `/signin` and **not** 403.
- If the user is signed in but `role !== 'admin'`, the API call itself returns 404 (because the admin route is gated server-side), so the page naturally falls into the 404 branch.
- The 404 branch uses the same `404.astro` layout/response so there is no visual or status difference from a missing public page.

This is the counterpart to the API's `requireAdmin()` behavior.

### 2. XSS — reuse the Phase 3 escape discipline

The admin review queue and skill tables render attacker-controlled submission/skill metadata (e.g., skill `name`, `slug`, `description`, `submitterHandle`, `note`, `payload` fields). Because the victim is an admin, XSS here is a privilege-escalation bug.

Rules:
- All Astro templates use `{...}` escaping (no raw HTML interpolation).
- Any client-side `innerHTML` uses the shared `esc()` helper from `submit-wizard.ts` (exported to `web/src/lib/escape.ts`).
- `set:html` is allowed **only** for `renderMarkdown`-sanitized output (skill notes/reviews) and never for user-controlled plain text.
- Add a new test file: `web/src/lib/escape.test.ts` covering the same vectors as `submit-wizard.test.ts` plus attribute-context cases (`data-repo="<value>"`).

> **Concrete Phase 4 landmine:** queue detail renders `SubmissionPayload`/`ReviewOutputPayload` JSON; if we ever show raw string fields from those payloads, they must be escaped. Any "copy JSON" or "expand payload" client feature must escape before `innerHTML` or `textContent`.

### 3. Mutations use `fetch`, not HTML forms

- Admin skill/user/category mutations, decisions, and bulk actions use JS `fetch`.
- Forms may collect input, but submission is handled by a script module (consistent with settings/delete-account/star logic).
- No `method="DELETE"` on forms.

## Design & UX Notes

- Use the existing design-system classes from `styles.css` and the handoff admin markup (`admin-shell`, `admin-side`, `admin-main`, `admin-bar`, `admin-nav`, `queue-list`, `queue-detail`, `tbl`, `stat`, etc.).
- Sidebar nav shows current page with `aria-current="page"`.
- Mobile: `data-admin-menu` toggles the sidebar (the handoff already has this hook; we'll wire it).
- Queue tabs reflect the real API states: **Pending** (`in_review`), **Changes requested** (`changes_requested`), **All** (`in_review,changes_requested`). The handoff shows "Approved/Rejected" tabs, but the API `listQueue` only returns active states; approved/rejected are terminal states. Either omit those tabs or add a callout explaining they are not yet filterable (no inert tabs).
- Skill table: statuses map to `published|unlisted|flagged`; bulk actions `feature|unlist|delete`.
- User table: roles map to `admin|contributor|member`; status map to `active|suspended`. Self-guard is enforced by the API; the UI may disable the admin's own row or rely on the API error message.
- Settings: fields are `reviewPolicy` (`manual`/`auto` enum), `tokenSoftCap`, `llmEnabled`. The handoff has more toggles than the API currently supports; only render the ones that actually exist in `AdminSettingsQueries.PlatformSettings`.
- CSV export is a direct link to `/api/admin/users/export` (cookie-forwarded via proxy).

## Implementation Steps

1. **API spec**
   - Add `api/src/main/resources/openapi-admin.yaml` covering all `/api/admin` routes and schemas.
   - Add a contract test in the API that validates the YAML parses and matches route definitions (similar to `OpenApiContractTest` if it exists).

2. **Web types & client**
   - Add `web/src/lib/api-admin-types.ts` generated from `openapi-admin.yaml`.
   - Add `web/src/lib/admin-api.ts` with `AdminApiClient` and `createAdminApiClient(request)`.
   - Add `web/src/lib/escape.ts` exporting `esc()`, and import it from `submit-wizard.ts` so there is one source of truth.
   - Add `web/src/lib/escape.test.ts`.
   - Update `package.json` test script to run escape tests.

3. **Shared admin components**
   - `web/src/layouts/AdminLayout.astro` — 404 gate, sidebar, admin bar.
   - `web/src/components/AdminNav.astro` — nav links.
   - `web/src/components/AdminShell.astro` — shell wrapper (combines `BaseLayout` + `AdminLayout`).
   - `web/src/components/AdminStatCard.astro`, `AdminBulkBar.astro`, `AdminTable.astro`.

4. **Admin pages**
   - `/admin/index.astro` — dashboard + counts (fetch from public stats, admin queue length, admin skill count, admin user count) + recent audit.
   - `/admin/queue.astro` — queue list + detail/decision (client-side selection + fetch decision).
   - `/admin/skills.astro` — skill table, search, filter, bulk actions.
   - `/admin/users.astro` — user table, search, filter, role/status patch, export CSV.
   - `/admin/categories.astro` — category list, create modal, inline rename, merge.
   - `/admin/settings.astro` — platform settings form.
   - `/admin/audit.astro` — audit log table with pagination.

5. **Wiring & polish**
   - Add admin link to the auth-aware account menu when `role === 'admin'`.
   - Add `web/src/scripts/admin-shell.ts` for mobile sidebar toggle and bulk-selection helpers.
   - Ensure all admin pages use cookie-forwarded `createAdminApiClient(Astro.request)`.

6. **CI**
   - Add `openapi-admin.yaml` to the list of files checked in lint/format.
   - Add type-check step that regenerates `api-admin-types.ts` and fails if it diverges.

## Testing Plan

### Unit / Static
- `npm run format:check`, `npm run lint`, `npx astro check`, `npx tsc --noEmit`.
- `npm test` (includes markdown, submit-wizard, escape tests).
- `./gradlew test`.
- OpenAPI admin contract test in the API.

### Live per-role smoke test (this is the critical Phase 4 gate)

1. Start the API with `SEED_DEMO=1`.
2. Build/start the web preview server.
3. Anonymous → `GET /admin/queue` returns **404** (no redirect to `/signin`).
4. Mint a `member` session (`role=member`) → `GET /admin/queue` returns **404**.
5. Mint a `contributor` session (`role=contributor`) → `GET /admin/queue` returns **404**.
6. Mint an `admin` session (`role=admin`) → `GET /admin/queue` returns **200** and renders the queue.
7. Verify the admin can see the sidebar nav and all `/admin/*` routes return 200.
8. Verify the admin account menu shows the "Admin" link.

### XSS smoke test
- Drive a malicious scan through the API or directly insert a submission with a malicious `SKILL.md` payload containing `<script>alert(1)</script>` in name/description/tags.
- Load `/admin/queue` as admin and confirm the payload is rendered as escaped text (no script execution).
- Run the same vectors through the skill table (`/admin/skills`) and user table (`/admin/users`) if the seeded data has those fields.

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Admin spec drifts from API | Add contract test that round-trips the spec and route definitions |
| XSS via queue payload rendering | Reuse `esc()`; add malicious-fixture test |
| 404 gate accidentally leaks admin route | Live per-role test for all three roles + anonymous |
| Handoff shows unsupported settings | Render only fields in `AdminSettingsQueries.PlatformSettings` |
| Category merge destructive | Confirm via modal; rely on API transaction |

## Open Questions

- Should we add a lightweight `GET /api/admin/stats` endpoint for the dashboard, or derive counts from existing endpoints? (The dashboard can call `/api/stats` + `/api/admin/queue` + `/api/admin/skills` + `/api/admin/users` for now.)
- Should the admin skill/user "row menu" be a dropdown with inline actions, or a detail page? Given time, keep it simple: inline row actions with `fetch`.
- Should we port the existing `api-types.ts` generation to a script in `package.json` so both public and admin specs are regenerated on demand? **Yes** — add `generate:types` and `generate:types:admin` scripts.

## Definition of Done

- [ ] All `/admin/*` routes exist and match the design-handoff shell.
- [ ] `openapi-admin.yaml` is complete and has a passing API contract test.
- [ ] `AdminApiClient` is typed and cookie-forwards auth.
- [ ] Every admin page returns 404 for non-admin and anonymous callers (live verified).
- [ ] No raw `innerHTML` with user data; `esc()` reused and tested.
- [ ] Malicious submission renders escaped in the admin queue.
- [ ] Admin settings form only edits real backend fields.
- [ ] CI green (`api`, `web`).
