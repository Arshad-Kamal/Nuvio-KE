// Spec 116 D5: write-only report endpoint. POST /v1/reports stores one report in the private Workers
// KV namespace (A10: on Workers Free a spent quota fails, never bills) and answers its code. Nothing
// else is served, nothing is read back, and no request data (IP, country, headers) is stored or
// logged; the one exception is the daily cap's counter (A11: 5 reports per address per 24 h), kept
// in the same namespace under a SHA-256 of the address and deleted by KV at the end of its 24 h
// window. Two reports at once may both read the same count (read, then write): the per-minute limit
// bounds that overshoot. The scrub happens on the TV (D6); this only checks shape.

export const MAX_BYTES = 262144;
export const FIRST_LINE = "Nuvio KE report v1";
export const MEDIA_TYPE = "application/x-nuvio-report";
const ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"; // Crockford base32: no I L O U
const PUT_ATTEMPTS = 4; // one put plus up to 3 retries on a taken code
export const REPORT_TTL_SECONDS = 30 * 24 * 60 * 60; // D9: KV deletes each report after 30 days
export const DAILY_LIMIT = 5;
export const DAY_SECONDS = 24 * 60 * 60;
// C0 and C1 control code points other than \t \n \r (the TV escapes them, D6 step 6).
const CONTROL = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F]/;

const status = (code) => new Response(null, { status: code });
const tooMany = (seconds) => new Response(null, { status: 429, headers: { "retry-after": String(seconds) } });

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method !== "POST" || url.pathname !== "/v1/reports") return status(404);
    const type = (request.headers.get("content-type") || "").split(";")[0].trim().toLowerCase();
    if (type !== MEDIA_TYPE) return status(415);
    const declared = request.headers.get("content-length");
    if (declared === null || !/^\d+$/.test(declared) || Number(declared) > MAX_BYTES) return status(413);

    const address = rateKey(request.headers.get("cf-connecting-ip"));
    const { success } = await env.REPORT_LIMITER.limit({ key: address });
    if (!success) return tooMany(60);

    const body = await readCapped(request, Math.min(Number(declared), MAX_BYTES));
    if (body === null) return status(413);
    if (!isReport(body)) return status(400);

    // Only a well-formed report reaches KV, so junk never spends the Free plan's daily KV quota.
    // A KV failure (an outage or that quota spent) refuses the report: never an unlimited store.
    const now = Math.floor(Date.now() / 1000);
    let day;
    try {
      day = await readDay(env, await dayKey(address), now);
    } catch {
      return status(503);
    }
    if (day.count >= DAILY_LIMIT) return tooMany(day.until - now);
    // Counted before the store, and a failed count refuses: once KV's write quota is spent the cap
    // could not hold, so nothing more is stored that day. A store that then fails costs one count.
    try {
      await countReport(env, day, now);
    } catch {
      return status(503);
    }

    // KV has no create-if-absent: a taken code is found by a read before the write (not atomic, not
    // load-bearing at 1.07e9 codes, D5).
    for (let attempt = 0; attempt < PUT_ATTEMPTS; attempt++) {
      const code = newCode();
      const key = `reports/${code}.txt`;
      try {
        if ((await env.REPORTS.get(key, "arrayBuffer")) !== null) continue;
        await env.REPORTS.put(key, body.buffer, { expirationTtl: REPORT_TTL_SECONDS });
      } catch {
        return status(503);
      }
      return new Response(JSON.stringify({ code }), {
        status: 201,
        headers: { "content-type": "application/json" },
      });
    }
    return status(503);
  },
};

/**
 * The daily counter's KV key: a SHA-256 of [address] (never the address itself). Unsalted, over a
 * public prefix: someone with read access to the namespace (only Arshad's login) could hash every
 * IPv4 address in minutes and match a key during the 24 h it lives.
 */
export async function dayKey(address) {
  const hash = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`nuvio-reports/day/${address}`));
  return "day/" + Array.from(new Uint8Array(hash), (b) => b.toString(16).padStart(2, "0")).join("");
}

/** This address's window: reports stored so far and when the window ends (epoch s); a new window when none is live. */
async function readDay(env, key, now) {
  const stored = await env.REPORTS.get(key, "json");
  if (stored && Number.isInteger(stored.count) && Number.isInteger(stored.until) && stored.until > now) {
    return { key, count: stored.count, until: stored.until };
  }
  return { key, count: 0, until: now + DAY_SECONDS };
}

/** One more report in [day]'s window; KV deletes the key when the window ends (expiration is at least 60 s ahead). */
async function countReport(env, day, now) {
  await env.REPORTS.put(day.key, JSON.stringify({ count: day.count + 1, until: day.until }), {
    expiration: Math.max(day.until, now + 60),
  });
}

/** IPv4 as is; IPv6 by its /64 (one household). */
export function rateKey(ip) {
  if (!ip) return "none";
  if (!ip.includes(":")) return ip;
  const groups = expandIpv6(ip);
  return groups ? `${groups.slice(0, 4).join(":")}::/64` : ip;
}

function expandIpv6(ip) {
  const halves = ip.toLowerCase().split("::");
  if (halves.length > 2) return null;
  const head = halves[0] ? halves[0].split(":") : [];
  const tail = halves.length === 2 && halves[1] ? halves[1].split(":") : [];
  const missing = 8 - head.length - tail.length;
  if (halves.length === 2 ? missing < 1 : missing !== 0) return null;
  const groups = [...head, ...Array(missing).fill("0"), ...tail];
  if (!groups.every((g) => /^[0-9a-f]{1,4}$/.test(g))) return null;
  return groups.map((g) => g.replace(/^0+(?=.)/, ""));
}

/** The body's bytes, or null when it runs past [limit] (a short or lying Content-Length). */
async function readCapped(request, limit) {
  if (!request.body) return new Uint8Array(0);
  const reader = request.body.getReader();
  const chunks = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > limit) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
  const out = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return out;
}

function isReport(bytes) {
  let text;
  try {
    // ignoreBOM keeps a BOM in the text, so a BOM-prefixed body fails the first-line check.
    text = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(bytes);
  } catch {
    return false;
  }
  const end = text.indexOf("\n");
  const first = end === -1 ? text : text.slice(0, end);
  return first === FIRST_LINE && !CONTROL.test(text);
}

export function newCode() {
  const bytes = crypto.getRandomValues(new Uint8Array(6));
  // 256 is a multiple of 32, so the low 5 bits are uniform.
  return "KE-" + Array.from(bytes, (b) => ALPHABET[b & 31]).join("");
}
