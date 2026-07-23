const base = process.env.CONTRACT_BASE_URL ?? "http://127.0.0.1:8788";
const response = await fetch(`${base}/__ci/repositories`);
if (!response.ok) throw new Error(`repository worker smoke failed: ${response.status} ${await response.text()}`);
const body = await response.json();
if (body.operations !== 4 || !body.exactly_one || !body.zero_returning_rejected || !body.multiple_returning_rejected || body.decoded_value !== "one") {
  throw new Error(`repository worker smoke contract failed: ${JSON.stringify(body)}`);
}
console.log("D1 Worker repository smoke verified.");
