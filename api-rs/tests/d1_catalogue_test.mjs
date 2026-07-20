/** Account-free D1 seed parity check for the checked-in real catalogue fixture. */
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

const root = resolve(import.meta.dirname, "../..");
const source = readFileSync(join(root, "api/src/main/resources/seed/real-skills.json"), "utf8");
const data = JSON.parse(source);
const snapshot = JSON.parse(readFileSync(join(root, "api/src/test/resources/schema-v5-compatibility.json"), "utf8"));
const categories = new Map(Object.entries(snapshot.categories));
if (createHash("sha256").update(source).digest("hex") !== snapshot.catalogue.sha256) {
  throw new Error("Real catalogue fixture changed; refresh the Kotlin-validated compatibility snapshot.");
}
const now = "2026-01-01T00:00:00Z";
const quote = (value) => `'${String(value).replaceAll("'", "''")}'`;
const inserts = (table, columns, rows) => rows.map((row) =>
  `INSERT INTO ${table}(${columns}) VALUES (${row.map(quote).join(",")});`
).join("\n");
const bytes = (text) => Buffer.byteLength(text, "utf8");
const tokens = (text) => Math.ceil(bytes(text) / 4);
const band = (total) => total < 1_000 ? "100s" : total < 10_000 ? "1k" : total < 100_000 ? "10k" : "100k";
const userId = new Map(data.authors.map((author, index) => [author.handle, `fixture-user-${index}`]));
const bundleId = new Map(data.bundles.map((bundle, index) => [bundle.provenance, `fixture-bundle-${index}`]));

const sql = [
  // Re-running the harness against a retained local emulator must produce the same fixture.
  "DELETE FROM versions WHERE id LIKE 'fixture-%';",
  "DELETE FROM skill_files WHERE id LIKE 'fixture-%';",
  "DELETE FROM skills WHERE id LIKE 'fixture-%';",
  "DELETE FROM bundles WHERE id LIKE 'fixture-%';",
  "DELETE FROM users WHERE id LIKE 'fixture-%';",
  inserts("users", "id,github_id,handle,name,avatar_url,role,status,created_at,updated_at", data.authors.map((author, index) => [
    `fixture-user-${index}`, author.githubId, author.handle, author.name, `https://github.com/${author.handle}.png`, "contributor", "active", now, now,
  ])),
  inserts("bundles", "id,kind,provenance,owner_user_id,source_ref,synced_at,created_at", data.bundles.map((bundle, index) => [
    `fixture-bundle-${index}`, "repo", bundle.provenance, userId.get(bundle.author), bundle.sourceRef, now, now,
  ])),
  inserts("skills", "id,bundle_id,slug,source_dir,name,description,license,tags,category_id,version,version_source,token_upfront,token_ondemand,token_band,verified,status,featured,installs,readme_md,created_at,updated_at", data.skills.map((skill, index) => {
    const upfront = tokens(`${skill.name} ${skill.description}`);
    const ondemand = tokens(skill.body);
    return [`fixture-skill-${index}`, bundleId.get(skill.bundle), skill.slug, `skills/${skill.slug}`, skill.name, skill.description, skill.license, JSON.stringify(skill.tags), categories.get(skill.category), skill.version, "git_head", upfront, ondemand, band(upfront + ondemand), 1, "published", skill.featured ? 1 : 0, 0, skill.body, now, now];
  })),
  inserts("skill_files", "id,skill_id,path,size,is_binary,r2_key", data.skills.map((skill, index) => [
    `fixture-file-${index}`, `fixture-skill-${index}`, "SKILL.md", bytes(skill.body), 0, `skills/fixture-skill-${index}/files/SKILL.md`,
  ])),
  inserts("versions", "id,skill_id,version,source_ref,r2_zip_key,created_at", data.skills.map((skill, index) => [
    `fixture-version-${index}`, `fixture-skill-${index}`, skill.version, `git_head:${skill.version}`, `skills/fixture-skill-${index}/versions/${skill.version}.zip`, now,
  ])),
].join("\n");

const directory = mkdtempSync(join(tmpdir(), "androidskills-d1-fixture-"));
const fixture = join(directory, "catalogue.sql");
const wrangler = join(resolve(import.meta.dirname, ".."), "node_modules/wrangler/bin/wrangler.js");
const runWrangler = (args, options = {}) => execFileSync(process.execPath, [wrangler, ...args], {
  cwd: resolve(import.meta.dirname, ".."),
  ...options,
});
const query = (command) => {
  const output = runWrangler(["d1", "execute", "androidskills", "--local", "--config", "wrangler.d1.local.toml", "--command", command], { encoding: "utf8" });
  const start = output.indexOf("[\n  {");
  const end = output.lastIndexOf("\n]") + 2;
  return JSON.parse(output.slice(start, end))[0].results;
};
try {
  writeFileSync(fixture, sql, "utf8");
  runWrangler(["d1", "execute", "androidskills", "--local", "--config", "wrangler.d1.local.toml", "--file", fixture], { stdio: "inherit" });
  const result = query("SELECT (SELECT COUNT(*) FROM users) AS users, (SELECT COUNT(*) FROM bundles) AS bundles, (SELECT COUNT(*) FROM skills) AS skills, (SELECT COUNT(*) FROM skill_files) AS files, (SELECT COUNT(*) FROM versions) AS versions;")[0];
  const expected = { users: data.authors.length, bundles: data.bundles.length, skills: data.skills.length, files: data.skills.length, versions: data.skills.length };
  if (JSON.stringify(result) !== JSON.stringify(expected)) throw new Error(`Catalogue parity failed: ${JSON.stringify(result)} != ${JSON.stringify(expected)}`);
  const d1Tables = query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'd1_%' AND name NOT LIKE '_cf_%' AND name != 'sqlite_sequence' ORDER BY name;").map((row) => row.name);
  const kotlinTables = Object.keys(snapshot.tables).sort();
  if (JSON.stringify(d1Tables) !== JSON.stringify(kotlinTables)) throw new Error("D1 table snapshot differs from Kotlin compatibility snapshot.");
  const d1Indexes = query("SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_autoindex%' ORDER BY name;").map((row) => row.name);
  for (const index of [...snapshot.indexes, "ix_jobs_dispatch_pending", "ix_jobs_lease_expired"]) {
    if (!d1Indexes.includes(index)) throw new Error(`D1 index missing: ${index}`);
  }
  const skillColumns = query("PRAGMA table_info(skills);").map((row) => row.name);
  if (!skillColumns.includes("source_dir") || !skillColumns.includes("security")) throw new Error("D1 skill column snapshot is incomplete.");
  const jobColumns = query("PRAGMA table_info(jobs);").map((row) => row.name);
  if (!["dedup_key", "dispatched_at", "lease_until"].every((column) => jobColumns.includes(column))) throw new Error("D1 job operational columns are missing.");
  const skillForeignKeys = query("PRAGMA foreign_key_list(skills);");
  if (skillForeignKeys.length !== 2) throw new Error("D1 skill foreign-key snapshot differs from Kotlin compatibility snapshot.");
  console.log(`D1 real catalogue fixture verified (${expected.skills} skills).`);
} finally {
  rmSync(directory, { recursive: true, force: true });
}
