import { test } from "node:test";
import assert from "node:assert/strict";
import worker, { DAILY_LIMIT, DAY_SECONDS, MAX_BYTES, REPORT_TTL_SECONDS, dayKey, newCode, rateKey } from "../src/index.js";

const CODE = /^KE-[0-9A-HJKMNP-TV-Z]{6}$/;

// One in-memory KV namespace (reports and daily counters, as in wrangler.toml) and the limiter.
// `taken` codes are reported as existing by the read before a report's write.
function fakeEnv({ allow = () => true, taken = 0, reportPutThrows = false, kvGetThrows = false, kvPutThrows = false } = {}) {
  const kv = new Map();
  const limited = [];
  let collisions = taken;
  return {
    kv,
    limited,
    get objects() {
      return new Map([...kv].filter(([key]) => key.startsWith("reports/")));
    },
    REPORTS: {
      async get(key, type) {
        if (kvGetThrows) throw new Error("kv quota");
        if (key.startsWith("reports/")) {
          assert.equal(type, "arrayBuffer");
          if (collisions > 0) {
            collisions--;
            return new ArrayBuffer(1);
          }
        } else {
          assert.equal(type, "json");
        }
        const entry = kv.get(key);
        if (!entry) return null;
        return type === "json" ? JSON.parse(entry.value) : entry.value;
      },
      async put(key, value, options) {
        if (kvPutThrows || (reportPutThrows && key.startsWith("reports/"))) throw new Error("kv down");
        kv.set(key, { value, options });
      },
    },
    REPORT_LIMITER: {
      async limit({ key }) {
        limited.push(key);
        return { success: allow(key) };
      },
    },
  };
}

function post(body, { path = "/v1/reports", method = "POST", type = "application/x-nuvio-report", length, ip = "203.0.113.7", headers = {} } = {}) {
  const bytes = typeof body === "string" ? new TextEncoder().encode(body) : body;
  const h = new Headers(headers);
  if (type !== null) h.set("content-type", type);
  if (length !== null) h.set("content-length", String(length ?? bytes?.byteLength ?? 0));
  if (ip) h.set("cf-connecting-ip", ip);
  return new Request(`https://nuvio-reports.example.workers.dev${path}`, {
    method,
    headers: h,
    body: method === "GET" || method === "HEAD" ? undefined : bytes,
  });
}

// Every stored key and value as text (counters are JSON strings, reports ArrayBuffers).
const storedText = (env) => [...env.kv.entries()].map(([k, v]) => k + " " + (typeof v.value === "string" ? v.value : new TextDecoder().decode(v.value))).join("\n");

const REPORT = "Nuvio KE report v1\n# This TV\nmodel = SHIELD Android TV\n";

test("a valid report is stored under its code and the code is answered", async () => {
  const env = fakeEnv();
  const res = await worker.fetch(post(REPORT, { headers: { "x-forwarded-for": "198.51.100.1", "user-agent": "Nuvio/1.3.0" } }), env);
  assert.equal(res.status, 201);
  assert.equal(res.headers.get("access-control-allow-origin"), null);
  const { code } = await res.json();
  assert.match(code, CODE);
  assert.deepEqual([...env.objects.keys()], [`reports/${code}.txt`]);
  const stored = env.objects.get(`reports/${code}.txt`);
  // KV takes an ArrayBuffer (not a typed array): exactly the body's bytes.
  assert.ok(stored.value instanceof ArrayBuffer);
  assert.equal(new TextDecoder().decode(stored.value), REPORT);
  // No request metadata stored: the put carries only the 30-day expiry.
  assert.deepEqual(stored.options, { expirationTtl: REPORT_TTL_SECONDS });
  assert.equal(REPORT_TTL_SECONDS, 30 * 24 * 60 * 60);
  assert.ok(!storedText(env).includes("203.0.113.7"));
});

test("a non-ASCII title and a charset parameter are accepted", async () => {
  const env = fakeEnv();
  const body = REPORT + "Playing Amélie (2001) · 東京物語.mkv\tok\r\n";
  const res = await worker.fetch(post(body, { type: "Application/X-Nuvio-Report; charset=utf-8" }), env);
  assert.equal(res.status, 201);
});

test("wrong path or method is 404, never an OPTIONS answer", async () => {
  const env = fakeEnv();
  for (const req of [
    post(REPORT, { path: "/" }),
    post(REPORT, { path: "/v1/reports/KE-ABCDEF" }),
    post(REPORT, { path: "/v1/reports/" }),
    post(null, { method: "GET", length: null }),
    post(null, { method: "OPTIONS", length: null, headers: { "access-control-request-method": "POST", origin: "https://evil.example" } }),
    post(REPORT, { method: "PUT" }),
    post(REPORT, { method: "DELETE" }),
  ]) {
    const res = await worker.fetch(req, env);
    assert.equal(res.status, 404, `${req.method} ${new URL(req.url).pathname}`);
    assert.equal(res.headers.get("access-control-allow-origin"), null);
    assert.equal(res.headers.get("access-control-allow-methods"), null);
  }
  assert.equal(env.objects.size, 0);
  assert.equal(env.limited.length, 0);
});

test("a wrong or missing media type is 415", async () => {
  const env = fakeEnv();
  for (const type of ["text/plain", "application/x-www-form-urlencoded", "multipart/form-data", "application/x-nuvio-reports", null]) {
    assert.equal((await worker.fetch(post(REPORT, { type }), env)).status, 415, String(type));
  }
  assert.equal(env.objects.size, 0);
});

test("Content-Length missing, malformed or over the cap is 413 before the limiter", async () => {
  const env = fakeEnv();
  assert.equal((await worker.fetch(post(REPORT, { length: null }), env)).status, 413);
  assert.equal((await worker.fetch(post(REPORT, { length: "12abc" }), env)).status, 413);
  assert.equal((await worker.fetch(post(REPORT, { length: MAX_BYTES + 1 }), env)).status, 413);
  assert.equal(env.limited.length, 0);
});

test("a body longer than its Content-Length or the cap is 413", async () => {
  const env = fakeEnv();
  assert.equal((await worker.fetch(post(REPORT, { length: 10 }), env)).status, 413);
  const big = new Uint8Array(MAX_BYTES + 1).fill(0x61);
  big.set(new TextEncoder().encode("Nuvio KE report v1\n"));
  assert.equal((await worker.fetch(post(big, { length: MAX_BYTES }), env)).status, 413);
  assert.equal(env.objects.size, 0);
});

test("a body of exactly the cap is accepted", async () => {
  const env = fakeEnv();
  const body = new Uint8Array(MAX_BYTES).fill(0x61);
  body.set(new TextEncoder().encode("Nuvio KE report v1\n"));
  assert.equal((await worker.fetch(post(body), env)).status, 201);
});

test("not a report is 400: first line, invalid UTF-8, BOM, control characters", async () => {
  const env = fakeEnv();
  const bad = [
    "hello\n",
    "Nuvio KE report v2\n",
    " Nuvio KE report v1\n",
    "Nuvio KE report v1 \n",
    "",
    "\uFEFF" + REPORT,
    REPORT + "esc \u001b[31m red\n",
    REPORT + "nul \u0000\n",
    REPORT + "c1 \u0085 \u009b\n",
    REPORT + "del \u007f\n",
  ];
  for (const body of bad) {
    assert.equal((await worker.fetch(post(body), env)).status, 400, JSON.stringify(body.slice(0, 30)));
  }
  // Bad sequence, overlong NUL and "/", encoded surrogate, above U+10FFFF, truncated tail.
  for (const tail of [[0xc3, 0x28], [0xc0, 0x80], [0xe0, 0x80, 0xaf], [0xed, 0xa0, 0x80], [0xf4, 0x90, 0x80, 0x80], [0xe2, 0x82]]) {
    const invalid = new Uint8Array([...new TextEncoder().encode(REPORT), ...tail]);
    assert.equal((await worker.fetch(post(invalid), env)).status, 400, tail.join(","));
  }
  assert.equal(env.objects.size, 0);
});

test("the limiter is keyed by IPv4 address or IPv6 /64 and answers 429", async () => {
  const env = fakeEnv({ allow: (key) => key !== "2a02:c7f:1:2::/64" });
  assert.equal((await worker.fetch(post(REPORT, { ip: "2a02:c7f:1:2:aaaa::5" }), env)).status, 429);
  assert.equal((await worker.fetch(post(REPORT, { ip: "2a02:0c7f:0001:0002:ffff:1:2:3" }), env)).status, 429);
  assert.equal((await worker.fetch(post(REPORT, { ip: "2a02:c7f:1:3::5" }), env)).status, 201);
  assert.equal((await worker.fetch(post(REPORT, { ip: "203.0.113.7" }), env)).status, 201);
  assert.deepEqual(env.limited, ["2a02:c7f:1:2::/64", "2a02:c7f:1:2::/64", "2a02:c7f:1:3::/64", "203.0.113.7"]);
  assert.equal(env.objects.size, 2);
});

test("an address gets 5 reports per 24 h, then 429 with Retry-After until its window ends", async () => {
  const env = fakeEnv();
  const start = Math.floor(Date.now() / 1000);
  for (let i = 0; i < DAILY_LIMIT; i++) {
    assert.equal((await worker.fetch(post(REPORT, { ip: "2a02:c7f:1:2::7" }), env)).status, 201, `report ${i + 1}`);
  }
  const refused = await worker.fetch(post(REPORT, { ip: "2a02:c7f:1:2:ffff::1" }), env); // same /64
  assert.equal(refused.status, 429);
  const wait = Number(refused.headers.get("retry-after"));
  assert.ok(wait > DAY_SECONDS - 60 && wait <= DAY_SECONDS, String(wait));
  assert.equal(env.objects.size, DAILY_LIMIT);
  // Another address is not affected.
  assert.equal((await worker.fetch(post(REPORT, { ip: "198.51.100.9" }), env)).status, 201);
  // The counter is keyed by a hash, never the address, and KV deletes it when the window ends.
  const key = await dayKey("2a02:c7f:1:2::/64");
  const entry = env.kv.get(key);
  assert.match(key, /^day\/[0-9a-f]{64}$/);
  assert.deepEqual(JSON.parse(entry.value).count, DAILY_LIMIT);
  assert.ok(entry.options.expiration >= start + DAY_SECONDS && entry.options.expiration <= start + DAY_SECONDS + 5);
  assert.ok(!/2a02|198\.51/.test(storedText(env)));
});

test("an expired window starts again, and only stored reports count", async () => {
  const env = fakeEnv();
  const key = await dayKey("203.0.113.7");
  env.kv.set(key, { value: JSON.stringify({ count: DAILY_LIMIT, until: Math.floor(Date.now() / 1000) - 1 }) });
  assert.equal((await worker.fetch(post(REPORT), env)).status, 201);
  assert.equal(JSON.parse(env.kv.get(key).value).count, 1);
  // A refused body spends nothing; a failed store costs its count (counted before the store).
  assert.equal((await worker.fetch(post("hello\n"), env)).status, 400);
  assert.equal(JSON.parse(env.kv.get(key).value).count, 1);
  const failing = fakeEnv({ reportPutThrows: true });
  assert.equal((await worker.fetch(post(REPORT), failing)).status, 503);
  assert.equal(JSON.parse(failing.kv.get(key).value).count, 1);
  assert.equal(failing.objects.size, 0);
});

test("a KV read or counter write failure refuses the report and stores nothing", async () => {
  for (const env of [fakeEnv({ kvGetThrows: true }), fakeEnv({ kvPutThrows: true })]) {
    assert.equal((await worker.fetch(post(REPORT), env)).status, 503);
    assert.equal(env.objects.size, 0);
  }
});

test("the per-minute limit answers Retry-After 60", async () => {
  const res = await worker.fetch(post(REPORT), fakeEnv({ allow: () => false }));
  assert.equal(res.status, 429);
  assert.equal(res.headers.get("retry-after"), "60");
});

test("rateKey handles compression, case and odd input", () => {
  assert.equal(rateKey("2A02:C7F::5"), "2a02:c7f:0:0::/64");
  assert.equal(rateKey("::1"), "0:0:0:0::/64");
  assert.equal(rateKey("2a02:c7f:1:2:3:4:5:6"), "2a02:c7f:1:2::/64");
  assert.equal(rateKey(null), "none");
  assert.equal(rateKey("::ffff:203.0.113.7"), "::ffff:203.0.113.7"); // mapped IPv4 keyed whole, not as one /64
  assert.equal(rateKey("1.2.3.4"), "1.2.3.4");
  assert.equal(rateKey("not:an::ip::x"), "not:an::ip::x");
});

test("a request with no CF-Connecting-IP is limited under one shared key", async () => {
  const env = fakeEnv();
  assert.equal((await worker.fetch(post(REPORT, { ip: null }), env)).status, 201);
  assert.deepEqual(env.limited, ["none"]);
});

test("a taken code is retried, up to 3 times, then 503", async () => {
  const retried = fakeEnv({ taken: 3 });
  assert.equal((await worker.fetch(post(REPORT), retried)).status, 201);
  assert.equal(retried.objects.size, 1);
  const full = fakeEnv({ taken: 4 });
  assert.equal((await worker.fetch(post(REPORT), full)).status, 503);
  assert.equal(full.objects.size, 0);
});

test("a report write failure is 503", async () => {
  assert.equal((await worker.fetch(post(REPORT), fakeEnv({ reportPutThrows: true }))).status, 503);
});

test("codes use the Crockford alphabet only", () => {
  const seen = new Set();
  for (let i = 0; i < 5000; i++) {
    const code = newCode();
    assert.match(code, CODE);
    for (const ch of code.slice(3)) seen.add(ch);
  }
  assert.equal(seen.size, 32);
});

test("read-report.sh --filter strips control and format characters, keeps tabs, newlines and text", async () => {
  const { execFileSync } = await import("node:child_process");
  const script = new URL("../../scripts/read-report.sh", import.meta.url).pathname;
  const input = "a\u001b[31mb\u202ec\u200bd\te\r\nf\u0085g\u{E0001}h é 東\n";
  const out = execFileSync("bash", [script, "--filter"], { input }).toString("utf-8");
  assert.equal(out, "a[31mbcd\te\nfgh é 東\n");
});
