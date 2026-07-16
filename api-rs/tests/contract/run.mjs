import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const directory = path.dirname(fileURLToPath(import.meta.url));
const baseUrl = process.env.CONTRACT_BASE_URL ?? process.argv[2];

if (!baseUrl) {
  throw new Error("Set CONTRACT_BASE_URL or pass the base URL as the first argument.");
}

const fixtureNames = (await readdir(directory)).filter((name) => name.endsWith(".json"));
let failures = 0;

for (const name of fixtureNames) {
  const fixture = JSON.parse(await readFile(path.join(directory, name), "utf8"));
  try {
    const response = await fetch(new URL(fixture.request.path, baseUrl), {
      method: fixture.request.method,
    });
    assert.equal(response.status, fixture.response.status);
    if (fixture.response.contentType) {
      assert.match(response.headers.get("content-type") ?? "", new RegExp(fixture.response.contentType));
    }

    const body = await response.text();
    for (const expected of fixture.response.bodyIncludes ?? []) {
      assert.ok(body.includes(expected), `${fixture.name}: expected body marker ${expected}`);
    }
    if (fixture.response.bodySha256) {
      const actual = createHash("sha256").update(body).digest("hex");
      assert.equal(actual, fixture.response.bodySha256, `${fixture.name}: response digest`);
    }
    if (fixture.response.json) {
      const json = JSON.parse(body);
      for (const [field, type] of Object.entries(fixture.response.json)) {
        assert.equal(typeof json[field], type, `${fixture.name}: ${field}`);
      }
      for (const [field, value] of Object.entries(fixture.response.jsonEquals ?? {})) {
        assert.deepEqual(json[field], value, `${fixture.name}: ${field}`);
      }
      for (const [field, values] of Object.entries(fixture.response.jsonAllowedValues ?? {})) {
        assert.ok(values.includes(json[field]), `${fixture.name}: ${field}`);
      }
      for (const [field, pattern] of Object.entries(fixture.response.jsonPatterns ?? {})) {
        assert.match(json[field], new RegExp(pattern), `${fixture.name}: ${field}`);
      }
    }
    console.log(`PASS ${fixture.name}`);
  } catch (error) {
    failures += 1;
    console.error(`FAIL ${fixture.name}: ${error.message}`);
  }
}

if (failures > 0) process.exitCode = 1;
