/** Account-free D1 checks for repository query, constraint, pagination, and write contracts. */
import { execFileSync } from "node:child_process";
import { resolve } from "node:path";

const apiRoot = resolve(import.meta.dirname, "..");
const wrangler = resolve(apiRoot, "node_modules/wrangler/bin/wrangler.js");
const run = (command) => execFileSync(process.execPath, [wrangler, "d1", "execute", "androidskills", "--local", "--config", "wrangler.d1.local.toml", "--command", command], {
  cwd: apiRoot,
  encoding: "utf8",
});
const query = (command) => {
  const output = run(command);
  const start = output.indexOf("[\n  {");
  const end = output.lastIndexOf("\n]") + 2;
  return JSON.parse(output.slice(start, end))[0].results;
};
const id = "repository-contract-user";
const now = "2026-01-01T00:00:00Z";
const p = "repository-contract";
const expectRejected = (command, message) => {
  try { run(command); } catch { return; }
  throw new Error(message);
};
const one = (command) => query(command)[0];

try {
  run(`DELETE FROM users WHERE id = '${id}'`);
  run(`INSERT INTO users (id, github_id, handle, role, status, created_at, updated_at) VALUES ('${id}', 760076, 'repository-contract', 'member', 'active', '${now}', '${now}')`);

  const firstPage = query("SELECT id FROM users WHERE id = 'repository-contract-user' ORDER BY id LIMIT 1 OFFSET 0");
  if (firstPage.length !== 1 || firstPage[0].id !== id) throw new Error("D1 first-page repository contract failed.");
  const emptyPage = query("SELECT id FROM users WHERE id = 'repository-contract-user' ORDER BY id LIMIT 1 OFFSET 1");
  if (emptyPage.length !== 0) throw new Error("D1 pagination boundary contract failed.");

  run(`UPDATE users SET status = 'suspended' WHERE id = '${id}' AND status = 'active'`);
  const changed = query(`SELECT status FROM users WHERE id = '${id}'`)[0];
  if (changed.status !== "suspended") throw new Error("Expected the conditional update to affect one matching row.");
  run(`UPDATE users SET status = 'active' WHERE id = '${id}' AND status = 'active'`);
  const unchanged = query(`SELECT status FROM users WHERE id = '${id}'`)[0];
  if (unchanged.status !== "suspended") throw new Error("Expected stale conditional update to affect zero rows.");

  let duplicateRejected = false;
  try {
    run(`INSERT INTO users (id, github_id, handle, role, status, created_at, updated_at) VALUES ('${id}-duplicate', 760077, 'repository-contract', 'member', 'active', '${now}', '${now}')`);
  } catch {
    duplicateRejected = true;
  }
  if (!duplicateRejected) throw new Error("D1 uniqueness constraint did not reject a duplicate handle.");
  const count = query("SELECT COUNT(*) AS count FROM users WHERE handle = 'repository-contract'")[0];
  if (count.count !== 1) throw new Error("D1 constraint failure changed persisted repository state.");

  // Exercise every named UNIQUE constraint and the complete FK action matrix from 0001.
  run(`DELETE FROM reports WHERE id LIKE '${p}%'; DELETE FROM audit_log WHERE id LIKE '${p}%'; DELETE FROM stars WHERE user_id LIKE '${p}%' OR skill_id LIKE '${p}%'; DELETE FROM sessions WHERE id LIKE '${p}%'; DELETE FROM submissions WHERE id LIKE '${p}%'; DELETE FROM versions WHERE id LIKE '${p}%'; DELETE FROM skill_files WHERE id LIKE '${p}%'; DELETE FROM skills WHERE id LIKE '${p}%'; DELETE FROM bundles WHERE id LIKE '${p}%'; DELETE FROM categories WHERE id LIKE '${p}%'; DELETE FROM users WHERE id LIKE '${p}%'; DELETE FROM jobs WHERE id LIKE '${p}%'; DELETE FROM platform_settings WHERE key LIKE '${p}%'`);
  run(`INSERT INTO users (id, github_id, handle, role, status, created_at, updated_at) VALUES ('${p}-owner', 760100, '${p}-owner', 'member', 'active', '${now}', '${now}'), ('${p}-reporter', 760101, '${p}-reporter', 'member', 'active', '${now}', '${now}')`);
  expectRejected(`INSERT INTO users (id, github_id, handle, role, status, created_at, updated_at) VALUES ('${p}-u-github', 760100, '${p}-other', 'member', 'active', '${now}', '${now}')`, "users.github_id UNIQUE was not enforced");
  expectRejected(`INSERT INTO users (id, github_id, handle, role, status, created_at, updated_at) VALUES ('${p}-u-handle', 760102, '${p}-owner', 'member', 'active', '${now}', '${now}')`, "users.handle UNIQUE was not enforced");
  run(`INSERT INTO categories (id, slug, name) VALUES ('${p}-cat', '${p}-cat', 'Contract')`);
  expectRejected(`INSERT INTO categories (id, slug, name) VALUES ('${p}-cat-2', '${p}-cat', 'Duplicate')`, "categories.slug UNIQUE was not enforced");
  run(`INSERT INTO bundles (id, kind, provenance, owner_user_id, created_at) VALUES ('${p}-bundle', 'repo', '${p}-bundle', '${p}-owner', '${now}')`);
  expectRejected(`INSERT INTO bundles (id, kind, provenance, owner_user_id, created_at) VALUES ('${p}-bundle-2', 'repo', '${p}-bundle', '${p}-owner', '${now}')`, "bundles(kind, provenance) UNIQUE was not enforced");
  run(`INSERT INTO skills (id, bundle_id, slug, source_dir, name, description, tags, category_id, version, version_source, created_at, updated_at) VALUES ('${p}-skill', '${p}-bundle', '${p}-skill', 'skill', 'Contract', 'Contract', '[]', '${p}-cat', '1.0.0', 'git', '${now}', '${now}')`);
  expectRejected(`INSERT INTO skills (id, bundle_id, slug, source_dir, name, description, tags, version, version_source, created_at, updated_at) VALUES ('${p}-skill-null', '${p}-bundle', '${p}-skill-null', NULL, 'Contract', 'Contract', '[]', '1.0.0', 'git', '${now}', '${now}')`, "skills.source_dir NOT NULL was not enforced");
  expectRejected(`INSERT INTO skills (id, bundle_id, slug, source_dir, name, description, tags, version, version_source, created_at, updated_at) VALUES ('${p}-skill-slug', '${p}-bundle', '${p}-skill', 'other', 'Contract', 'Contract', '[]', '1.0.0', 'git', '${now}', '${now}')`, "skills.slug UNIQUE was not enforced");
  expectRejected(`INSERT INTO skills (id, bundle_id, slug, source_dir, name, description, tags, version, version_source, created_at, updated_at) VALUES ('${p}-skill-dir', '${p}-bundle', '${p}-skill-dir', 'skill', 'Contract', 'Contract', '[]', '1.0.0', 'git', '${now}', '${now}')`, "skills(bundle_id, source_dir) UNIQUE was not enforced");
  run(`INSERT INTO skill_files (id, skill_id, path, size, is_binary, r2_key) VALUES ('${p}-file', '${p}-skill', 'SKILL.md', 1, 0, 'k'); INSERT INTO versions (id, skill_id, version, source_ref, created_at) VALUES ('${p}-version', '${p}-skill', '1.0.0', 'git', '${now}'); INSERT INTO stars (user_id, skill_id, created_at) VALUES ('${p}-owner', '${p}-skill', '${now}'); INSERT INTO jobs (id, type, payload, run_after, dedup_key, created_at, updated_at) VALUES ('${p}-job', 'sync', '{}', '${now}', 'dedup', '${now}', '${now}')`);
  expectRejected(`INSERT INTO skill_files (id, skill_id, path, size, is_binary, r2_key) VALUES ('${p}-file-2', '${p}-skill', 'SKILL.md', 1, 0, 'k')`, "skill_files(skill_id, path) UNIQUE was not enforced");
  expectRejected(`INSERT INTO versions (id, skill_id, version, source_ref, created_at) VALUES ('${p}-version-2', '${p}-skill', '1.0.0', 'git', '${now}')`, "versions(skill_id, version) UNIQUE was not enforced");
  expectRejected(`INSERT INTO stars (user_id, skill_id, created_at) VALUES ('${p}-owner', '${p}-skill', '${now}')`, "stars primary key was not enforced");
  expectRejected(`INSERT INTO jobs (id, type, payload, run_after, dedup_key, created_at, updated_at) VALUES ('${p}-job-2', 'sync', '{}', '${now}', 'dedup', '${now}', '${now}')`, "jobs(type, dedup_key) UNIQUE was not enforced");

  run(`INSERT INTO submissions (id, bundle_id, skill_id, submitter_id, state, created_at, updated_at) VALUES ('${p}-submission', '${p}-bundle', '${p}-skill', '${p}-owner', 'pending', '${now}', '${now}'); INSERT INTO sessions (id, user_id, created_at, expires_at) VALUES ('${p}-session', '${p}-owner', '${now}', '${now}'); INSERT INTO reports (id, skill_id, reporter_id, reason, created_at) VALUES ('${p}-report', '${p}-skill', '${p}-reporter', 'reason', '${now}')`);
  run(`DELETE FROM categories WHERE id = '${p}-cat'`);
  if (one(`SELECT category_id FROM skills WHERE id = '${p}-skill'`).category_id !== null) throw new Error("skills.category_id SET NULL was not applied");
  run(`DELETE FROM users WHERE id = '${p}-reporter'`);
  if (one(`SELECT reporter_id FROM reports WHERE id = '${p}-report'`).reporter_id !== null) throw new Error("reports.reporter_id SET NULL was not applied");
  run(`DELETE FROM bundles WHERE id = '${p}-bundle'`);
  if (one(`SELECT bundle_id, skill_id FROM submissions WHERE id = '${p}-submission'`).bundle_id !== null) throw new Error("submissions.bundle_id SET NULL was not applied");
  if (one(`SELECT COUNT(*) AS count FROM skills WHERE id = '${p}-skill'`).count !== 0) throw new Error("bundles -> skills CASCADE was not applied");
  if (one(`SELECT skill_id FROM submissions WHERE id = '${p}-submission'`).skill_id !== null) throw new Error("submissions.skill_id SET NULL was not applied");

  // A separate hierarchy proves every remaining CASCADE action without relying on a transitive
  // deletion from the earlier bundle test.
  run(`INSERT INTO users (id, github_id, handle, role, status, created_at, updated_at) VALUES ('${p}-cascade-owner', 760103, '${p}-cascade-owner', 'member', 'active', '${now}', '${now}'), ('${p}-cascade-reporter', 760104, '${p}-cascade-reporter', 'member', 'active', '${now}', '${now}'); INSERT INTO bundles (id, kind, provenance, owner_user_id, created_at) VALUES ('${p}-cascade-bundle', 'repo', '${p}-cascade-bundle', '${p}-cascade-owner', '${now}'); INSERT INTO skills (id, bundle_id, slug, source_dir, name, description, tags, version, version_source, created_at, updated_at) VALUES ('${p}-cascade-skill', '${p}-cascade-bundle', '${p}-cascade-skill', 'skill', 'Contract', 'Contract', '[]', '1.0.0', 'git', '${now}', '${now}'); INSERT INTO skill_files (id, skill_id, path, size, is_binary, r2_key) VALUES ('${p}-cascade-file', '${p}-cascade-skill', 'SKILL.md', 1, 0, 'k'); INSERT INTO versions (id, skill_id, version, source_ref, created_at) VALUES ('${p}-cascade-version', '${p}-cascade-skill', '1.0.0', 'git', '${now}'); INSERT INTO stars (user_id, skill_id, created_at) VALUES ('${p}-cascade-owner', '${p}-cascade-skill', '${now}'); INSERT INTO reports (id, skill_id, reporter_id, reason, created_at) VALUES ('${p}-cascade-report', '${p}-cascade-skill', '${p}-cascade-reporter', 'reason', '${now}'); INSERT INTO submissions (id, submitter_id, state, created_at, updated_at) VALUES ('${p}-cascade-submission', '${p}-cascade-owner', 'pending', '${now}', '${now}')`);
  run(`DELETE FROM users WHERE id = '${p}-cascade-owner'`);
  const cascaded = one(`SELECT (SELECT COUNT(*) FROM bundles WHERE id = '${p}-cascade-bundle') AS bundles, (SELECT COUNT(*) FROM skills WHERE id = '${p}-cascade-skill') AS skills, (SELECT COUNT(*) FROM skill_files WHERE id = '${p}-cascade-file') AS files, (SELECT COUNT(*) FROM versions WHERE id = '${p}-cascade-version') AS versions, (SELECT COUNT(*) FROM stars WHERE skill_id = '${p}-cascade-skill') AS stars, (SELECT COUNT(*) FROM reports WHERE id = '${p}-cascade-report') AS reports, (SELECT COUNT(*) FROM submissions WHERE id = '${p}-cascade-submission') AS submissions`);
  if (Object.values(cascaded).some((count) => count !== 0)) throw new Error(`CASCADE contract failed: ${JSON.stringify(cascaded)}`);
  run(`INSERT INTO audit_log (id, actor_id, action, target, created_at) VALUES ('${p}-audit', '${p}-owner', 'test', 'test', '${now}')`);
  expectRejected(`DELETE FROM users WHERE id = '${p}-owner'`, "audit_log.actor_id RESTRICT was not enforced");
  run(`DELETE FROM audit_log WHERE id = '${p}-audit'; DELETE FROM users WHERE id = '${p}-owner'`);
  if (one(`SELECT COUNT(*) AS count FROM sessions WHERE id = '${p}-session'`).count !== 0) throw new Error("users -> sessions CASCADE was not applied");
  console.log("D1 repository contracts verified.");
} finally {
  run(`DELETE FROM reports WHERE id LIKE '${p}%'; DELETE FROM audit_log WHERE id LIKE '${p}%'; DELETE FROM stars WHERE user_id LIKE '${p}%' OR skill_id LIKE '${p}%'; DELETE FROM sessions WHERE id LIKE '${p}%'; DELETE FROM submissions WHERE id LIKE '${p}%'; DELETE FROM versions WHERE id LIKE '${p}%'; DELETE FROM skill_files WHERE id LIKE '${p}%'; DELETE FROM skills WHERE id LIKE '${p}%'; DELETE FROM bundles WHERE id LIKE '${p}%'; DELETE FROM categories WHERE id LIKE '${p}%'; DELETE FROM users WHERE id LIKE '${p}%' OR id IN ('${id}', '${id}-duplicate'); DELETE FROM jobs WHERE id LIKE '${p}%'; DELETE FROM platform_settings WHERE key LIKE '${p}%'`);
}
