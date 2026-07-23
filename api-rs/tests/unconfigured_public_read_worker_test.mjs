/** Public D1 reads must fail safely until the production binding is provisioned. */
import assert from "node:assert/strict";

const base = process.env.CONTRACT_BASE_URL ?? "http://127.0.0.1:8787";

for (const path of ["/api/stats", "/api/categories"]) {
  const response = await fetch(`${base}${path}`);
  assert.equal(response.status, 503, path);
  assert.match(response.headers.get("content-type") ?? "", /application\/json/);
  assert.deepEqual(await response.json(), {
    error: {
      code: "service_unavailable",
      message: "Service temporarily unavailable",
    },
  });
}

console.log("Unconfigured public-read routes fail safely.");
