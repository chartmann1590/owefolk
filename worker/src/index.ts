interface Env {
  DB: D1Database;
  DELETION_ENCRYPTION_KEY: string;
  ENVIRONMENT: string;
}

const allowedOrigins = new Set([
  "https://chartmann1590.github.io",
]);

function cors(origin: string | null): HeadersInit {
  const allowed = origin && allowedOrigins.has(origin) ? origin : "https://chartmann1590.github.io";
  return {
    "Access-Control-Allow-Origin": allowed,
    "Access-Control-Allow-Headers": "Content-Type",
    "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
    "Cache-Control": "no-store",
    "Content-Security-Policy": "default-src 'none'",
    "Referrer-Policy": "no-referrer",
    "X-Content-Type-Options": "nosniff",
  };
}

function json(body: unknown, status = 200, origin: string | null = null): Response {
  return Response.json(body, {status, headers: cors(origin)});
}

function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

async function protectPayload(secret: string, email: string, details: string) {
  const raw = Uint8Array.from(atob(secret), (character) => character.charCodeAt(0));
  if (raw.byteLength !== 32) throw new Error("Invalid encryption key configuration");
  const encryptionKey = await crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["encrypt"]);
  const signingKey = await crypto.subtle.importKey("raw", raw, {name: "HMAC", hash: "SHA-256"}, false, ["sign"]);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const payload = new TextEncoder().encode(JSON.stringify({email, details}));
  const encrypted = await crypto.subtle.encrypt({name: "AES-GCM", iv}, encryptionKey, payload);
  const emailHash = await crypto.subtle.sign("HMAC", signingKey, new TextEncoder().encode(email));
  return {
    encryptedPayload: bytesToBase64(new Uint8Array(encrypted)),
    iv: bytesToBase64(iv),
    emailHash: bytesToBase64(new Uint8Array(emailHash)),
  };
}

async function handleDeletion(request: Request, env: Env): Promise<Response> {
  const origin = request.headers.get("Origin");
  if (!origin || !allowedOrigins.has(origin)) return json({error: "Origin is not allowed"}, 403, origin);
  if (!(request.headers.get("Content-Type") || "").toLowerCase().startsWith("application/json")) {
    return json({error: "Content-Type must be application/json"}, 415, origin);
  }
  const length = Number(request.headers.get("Content-Length") || 0);
  if (length > 10_000) return json({error: "Request is too large"}, 413, origin);
  const body = await request.json<Record<string, unknown>>().catch(() => null);
  const email = typeof body?.email === "string" ? body.email.trim().toLowerCase() : "";
  const details = typeof body?.details === "string" ? body.details.trim() : "";
  const website = typeof body?.website === "string" ? body.website : "";
  if (website) return json({accepted: true}, 202, origin);
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 254 || details.length > 2000) {
    return json({error: "Enter a valid account email and keep details under 2,000 characters"}, 400, origin);
  }
  const protectedPayload = await protectPayload(env.DELETION_ENCRYPTION_KEY, email, details);
  const id = crypto.randomUUID();
  const createdAt = new Date().toISOString();
  await env.DB.prepare(
    "INSERT INTO deletion_requests (id, email_hash, encrypted_payload, iv, status, created_at) VALUES (?, ?, ?, ?, 'pending', ?)",
  ).bind(id, protectedPayload.emailHash, protectedPayload.encryptedPayload, protectedPayload.iv, createdAt).run();
  return json({accepted: true, requestId: id, createdAt}, 202, origin);
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const origin = request.headers.get("Origin");
    if (request.method === "OPTIONS") return new Response(null, {status: 204, headers: cors(origin)});
    if (request.method === "GET" && url.pathname === "/health") {
      const database = await env.DB.prepare("SELECT 1 AS ok").first<{ok: number}>();
      return json({ok: database?.ok === 1, service: "owefolk-api", environment: env.ENVIRONMENT}, 200, origin);
    }
    if (request.method === "POST" && url.pathname === "/v1/deletion-requests") return handleDeletion(request, env);
    return json({error: "Not found"}, 404, origin);
  },
} satisfies ExportedHandler<Env>;
