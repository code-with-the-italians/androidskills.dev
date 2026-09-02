/** Remaining public-read routes against the real local Worker and catalogue D1 fixture. */
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const base = process.env.CONTRACT_BASE_URL ?? "http://127.0.0.1:8788";
const root = resolve(import.meta.dirname, "../..");
const catalogue = JSON.parse(
  readFileSync(
    resolve(root, "api/src/main/resources/seed/real-skills.json"),
    "utf8",
  ),
);
const wrangler = resolve(
  import.meta.dirname,
  "../node_modules/wrangler/bin/wrangler.js",
);
const mutateD1 = (command) =>
  execFileSync(
    process.execPath,
    [
      wrangler,
      "d1",
      "execute",
      "androidskills",
      "--local",
      "--config",
      "wrangler.d1.local.toml",
      "--command",
      command,
    ],
    { cwd: resolve(import.meta.dirname, ".."), stdio: "pipe" },
  );

const json = async (path, status = 200) => {
  const response = await fetch(`${base}${path}`);
  assert.equal(response.status, status, `${path} -> ${response.status}`);
  assert.match(response.headers.get("content-type") ?? "", /application\/json/);
  return response.json();
};

const known = catalogue.skills[0];
const skill = await json(`/api/skills/${known.slug}`);
const authorHandle = skill.author.handle;
const knownBundleId = skill.bundle.id;

const tree = await json(`/api/skills/${known.slug}/files`);
assert.equal(tree.slug, known.slug);
assert.ok(tree.files.some((file) => file.path === "SKILL.md" && file.isBinary === false));
assert.ok(tree.tree.some((node) => node.path === "SKILL.md" && node.type === "file" && node.children === null));

const versions = await json(`/api/skills/${known.slug}/versions`);
assert.equal(versions.slug, known.slug);
assert.equal(versions.current, known.version);
assert.ok(versions.versions.some((entry) => entry.version === known.version && entry.current));

const trends = await json("/api/trends");
assert.equal(trends.tokenMix.length, 4);
assert.equal(
  trends.tokenMix.reduce((sum, band) => sum + band.count, 0),
  catalogue.skills.length,
);
assert.equal(trends.securityPassRate, null);
assert.deepEqual(trends.submissionFunnel, {});
assert.ok(trends.categories.every((facet) => facet.count > 0));

const timeline = await json("/api/timeline?page=1&pageSize=20");
assert.equal(timeline.page, 1);
assert.equal(timeline.pageSize, 20);
assert.equal(timeline.total, catalogue.skills.length * 2);
assert.equal(timeline.items.length, 20);
assert.ok(timeline.items.some((item) => item.type === "publish"));
assert.ok(timeline.items.every((item) => item.slug && item.at && item.name));

const invalidPage = await json("/api/timeline?page=0", 422);
assert.equal(invalidPage.error.code, "validation_failed");

const bundles = await json("/api/bundles?page=1&pageSize=60");
assert.equal(bundles.total, catalogue.bundles.length);
assert.ok(bundles.items.every((bundle) => bundle.skillCount >= 1 && bundle.owner.handle));
const firstBundle = bundles.items[0];
const bundle = await json(`/api/bundles/${firstBundle.id}`);
assert.equal(bundle.id, firstBundle.id);
assert.ok(bundle.skills.length >= 1);
assert.ok(bundle.skills.every((skill) => skill.status === "published"));

const profile = await json(`/api/authors/${authorHandle}`);
assert.equal(profile.handle, authorHandle);
assert.ok(profile.skillCount >= 1);
assert.ok(profile.skills.every((card) => card.author.handle === authorHandle));

assert.equal((await json("/api/skills/does-not-exist/files", 404)).error.code, "not_found");
assert.equal((await json("/api/skills/does-not-exist/versions", 404)).error.code, "not_found");
assert.equal((await json(`/api/skills/${known.slug}/files/missing.md`, 404)).error.code, "not_found");
assert.equal((await json("/api/bundles/nope", 404)).error.code, "not_found");
assert.equal((await json("/api/authors/nobody", 404)).error.code, "not_found");
assert.equal((await json(`/api/skills/${known.slug}/download?version=not-a-version`, 404)).error.code, "not_found");
const missingBundleZip = await fetch(`${base}/api/bundles/${firstBundle.id}/download`);
assert.equal(missingBundleZip.status, 404);

const preview = await fetch(`${base}/api/skills/${known.slug}/files/SKILL.md`);
assert.equal(preview.status, 503);
assert.equal((await preview.json()).error.code, "service_unavailable");
const download = await fetch(`${base}/api/skills/${known.slug}/download`);
assert.equal(download.status, 503);
assert.equal((await download.json()).error.code, "service_unavailable");

mutateD1(
  "INSERT INTO skill_files (id, skill_id, path, size, is_binary, r2_key) VALUES ('fixture-bin-test', 'fixture-skill-0', 'scripts/tool.bin', 12, 1, 'skills/fixture-skill-0/files/scripts/tool.bin')",
);
try {
  const binary = await json(`/api/skills/${known.slug}/files/scripts/tool.bin`);
  assert.equal(binary.downloadOnly, true);
  assert.equal(binary.content, null);
  assert.equal(binary.downloadUrl, null);
  assert.equal(binary.isBinary, true);
  const updatedTree = await json(`/api/skills/${known.slug}/files`);
  assert.ok(updatedTree.files.some((file) => file.path === "scripts/tool.bin" && file.isBinary));
} finally {
  mutateD1("DELETE FROM skill_files WHERE id = 'fixture-bin-test'");
}

mutateD1("UPDATE skills SET status = 'unlisted' WHERE id = 'fixture-skill-0'");
try {
  assert.equal((await json(`/api/skills/${known.slug}`, 404)).error.code, "not_found");
  assert.equal((await json(`/api/skills/${known.slug}/files`, 404)).error.code, "not_found");
  assert.equal((await json(`/api/skills/${known.slug}/versions`, 404)).error.code, "not_found");
  assert.equal((await json(`/api/skills/${known.slug}/files/SKILL.md`, 404)).error.code, "not_found");
  assert.equal((await json(`/api/skills/${known.slug}/download`, 404)).error.code, "not_found");
  const hiddenTrends = await json("/api/trends");
  assert.equal(
    hiddenTrends.tokenMix.reduce((sum, band) => sum + band.count, 0),
    catalogue.skills.length - 1,
  );
  const hiddenTimeline = await json("/api/timeline?pageSize=60");
  assert.equal(hiddenTimeline.total, catalogue.skills.length * 2 - 2);
  const hiddenBundles = await json("/api/bundles?pageSize=60");
  const ownerBundle = hiddenBundles.items.find((item) => item.id === knownBundleId);
  if (ownerBundle) {
    const detail = await json(`/api/bundles/${ownerBundle.id}`);
    assert.ok(detail.skills.every((card) => card.slug !== known.slug));
  } else {
    assert.equal((await json(`/api/bundles/${knownBundleId}`, 404)).error.code, "not_found");
  }
} finally {
  mutateD1("UPDATE skills SET status = 'published' WHERE id = 'fixture-skill-0'");
}

console.log("D1 Worker remaining public-read routes verified.");
