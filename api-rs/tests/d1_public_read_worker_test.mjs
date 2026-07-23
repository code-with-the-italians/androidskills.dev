/** Public read routes exercised against the real local Worker and seeded D1 catalogue. */
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
const categorySnapshot = JSON.parse(
  readFileSync(
    resolve(root, "api/src/test/resources/schema-v5-compatibility.json"),
    "utf8",
  ),
);
const categoryCounts = new Map();
for (const skill of catalogue.skills)
  categoryCounts.set(
    skill.category,
    (categoryCounts.get(skill.category) ?? 0) + 1,
  );
const contributorCount = new Set(
  catalogue.bundles.map((bundle) => bundle.author),
).size;
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

const stats = await fetch(`${base}/api/stats`);
assert.equal(stats.status, 200);
assert.match(stats.headers.get("content-type") ?? "", /application\/json/);
assert.deepEqual(await stats.json(), {
  indexed: catalogue.skills.length,
  contributors: contributorCount,
  lastUpdated: "2026-01-01T00:00:00Z",
});

const categories = await fetch(`${base}/api/categories`);
assert.equal(categories.status, 200);
const body = await categories.json();
assert.equal(body.length, Object.keys(categorySnapshot.categories).length);
assert.deepEqual(
  body.map((category) => category.name),
  [...body.map((category) => category.name)].sort(),
);
assert.equal(
  body.reduce((sum, category) => sum + category.count, 0),
  catalogue.skills.length,
);
assert.deepEqual(
  body.find((category) => category.slug === "jetpack-compose"),
  {
    id: "3c9572b4-3e39-4e0a-a0cb-0a7078522e00",
    slug: "jetpack-compose",
    name: "Jetpack Compose",
    count: categoryCounts.get("jetpack-compose") ?? 0,
  },
);
assert.equal(
  body.find((category) => category.slug === "uncategorized")?.count,
  categoryCounts.get("uncategorized") ?? 0,
);

// The seed starts entirely published, so explicitly change one row to prove both aggregates do
// not leak an unlisted skill. The fixture is restored for subsequent local runs.
const hidden = catalogue.skills[0];
mutateD1("UPDATE skills SET status = 'unlisted' WHERE id = 'fixture-skill-0'");
try {
  const hiddenStats = await fetch(`${base}/api/stats`);
  assert.equal((await hiddenStats.json()).indexed, catalogue.skills.length - 1);
  const hiddenCategories = await fetch(`${base}/api/categories`);
  const hiddenBody = await hiddenCategories.json();
  assert.equal(
    hiddenBody.reduce((sum, category) => sum + category.count, 0),
    catalogue.skills.length - 1,
  );
  assert.equal(
    hiddenBody.find((category) => category.slug === hidden.category)?.count,
    (categoryCounts.get(hidden.category) ?? 0) - 1,
  );
} finally {
  mutateD1(
    "UPDATE skills SET status = 'published' WHERE id = 'fixture-skill-0'",
  );
}

console.log("D1 Worker public-read routes verified.");
