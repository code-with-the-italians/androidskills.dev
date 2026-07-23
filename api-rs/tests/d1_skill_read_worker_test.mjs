/** Search and detail routes exercised against the real local Worker and catalogue D1 fixture. */
import assert from "node:assert/strict";
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

const list = await fetch(
  `${base}/api/skills?verified=false&pageSize=2&sort=tokens`,
);
assert.equal(list.status, 200);
const page = await list.json();
assert.equal(page.page, 1);
assert.equal(page.pageSize, 2);
assert.equal(page.total, catalogue.skills.length);
assert.equal(page.items.length, 2);
assert.ok(
  page.items[0].tokenUpfront + page.items[0].tokenOndemand <=
    page.items[1].tokenUpfront + page.items[1].tokenOndemand,
);
assert.ok(page.facets.categories.length > 0 && page.facets.tags.length > 0);

const known = catalogue.skills[0];
const detail = await fetch(`${base}/api/skills/${known.slug}`);
assert.equal(detail.status, 200);
const skill = await detail.json();
assert.equal(skill.slug, known.slug);
assert.equal(skill.name, known.name);
assert.deepEqual(skill.tags, known.tags);
assert.equal(skill.status, "published");
assert.ok(skill.author.handle && skill.bundle.id && skill.fileCount >= 1);

const filtered = await fetch(
  `${base}/api/skills?verified=false&cat=${encodeURIComponent(known.category)}&pageSize=60`,
);
assert.equal(filtered.status, 200);
assert.ok(
  (await filtered.json()).items.every(
    (item) => item.category?.slug === known.category,
  ),
);

const matching = await fetch(
  `${base}/api/skills?q=${encodeURIComponent(known.name.split(" ")[0])}&verified=false`,
);
assert.equal(matching.status, 200);
assert.ok(
  (await matching.json()).items.some((item) => item.slug === known.slug),
);

const invalid = await fetch(`${base}/api/skills?sort=made-up`);
assert.equal(invalid.status, 422);
assert.equal((await invalid.json()).error.code, "validation_failed");

const missing = await fetch(`${base}/api/skills/does-not-exist`);
assert.equal(missing.status, 404);
assert.equal((await missing.json()).error.code, "not_found");

console.log("D1 Worker search and detail routes verified.");
