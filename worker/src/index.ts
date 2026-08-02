import {createRemoteJWKSet, jwtVerify} from "jose";

interface Env {
  DB: D1Database;
  DELETION_ENCRYPTION_KEY: string;
  ENVIRONMENT: string;
  GH_API_TOKEN: string;
  GH_REPO_OWNER: string;
  GH_REPO_NAME: string;
  GH_ASSETS_DIR: string;
  FIREBASE_PROJECT_ID: string;
  FIREBASE_PROJECT_NUMBER: string;
  FIREBASE_ANDROID_APP_ID: string;
}

const allowedOrigins = new Set(["https://chartmann1590.github.io"]);
const appCheckKeys = createRemoteJWKSet(new URL("https://firebaseappcheck.googleapis.com/v1/jwks"));
const firebaseAuthKeys = createRemoteJWKSet(
  new URL("https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"),
);

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

function bearerToken(request: Request): string | null {
  const value = request.headers.get("Authorization") || "";
  return value.startsWith("Bearer ") ? value.slice(7).trim() : null;
}

async function verifyFeedbackCaller(request: Request, env: Env): Promise<string> {
  const authToken = bearerToken(request);
  const appCheckToken = request.headers.get("X-Firebase-AppCheck")?.trim();
  if (!authToken || !appCheckToken) throw new Error("missing");

  const auth = await jwtVerify(authToken, firebaseAuthKeys, {
    algorithms: ["RS256"],
    audience: env.FIREBASE_PROJECT_ID,
    issuer: `https://securetoken.google.com/${env.FIREBASE_PROJECT_ID}`,
  });
  if (!auth.payload.sub || auth.payload.sub.length > 128) throw new Error("invalid-auth-subject");

  const checked = await jwtVerify(appCheckToken, appCheckKeys, {
    algorithms: ["RS256"],
    audience: `projects/${env.FIREBASE_PROJECT_NUMBER}`,
    issuer: `https://firebaseappcheck.googleapis.com/${env.FIREBASE_PROJECT_NUMBER}`,
  });
  if (checked.protectedHeader.typ !== "JWT" || checked.payload.sub !== env.FIREBASE_ANDROID_APP_ID) {
    throw new Error("invalid-app");
  }
  return auth.payload.sub;
}

async function requesterHash(uid: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(uid));
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function enforceRateLimit(env: Env, uid: string, action: string, limit: number, windowSeconds: number): Promise<boolean> {
  const hash = await requesterHash(uid);
  const now = Math.floor(Date.now() / 1000);
  const cutoff = now - windowSeconds;
  await env.DB.prepare("DELETE FROM feedback_rate_limits WHERE created_at < ?").bind(now - 86_400).run();
  const count = await env.DB.prepare(
    "SELECT COUNT(*) AS count FROM feedback_rate_limits WHERE requester_hash = ? AND action = ? AND created_at >= ?",
  ).bind(hash, action, cutoff).first<{count: number}>();
  if ((count?.count || 0) >= limit) return false;
  await env.DB.prepare(
    "INSERT INTO feedback_rate_limits (requester_hash, action, created_at) VALUES (?, ?, ?)",
  ).bind(hash, action, now).run();
  return true;
}

function isJson(request: Request): boolean {
  return (request.headers.get("Content-Type") || "").toLowerCase().startsWith("application/json");
}

async function readJson(request: Request, maxBytes: number): Promise<Record<string, unknown> | null> {
  if (!isJson(request)) return null;
  const declared = Number(request.headers.get("Content-Length") || 0);
  if (declared > maxBytes) return null;
  const text = await request.text();
  if (new TextEncoder().encode(text).byteLength > maxBytes) return null;
  const parsed = JSON.parse(text) as unknown;
  return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed as Record<string, unknown> : null;
}

async function github(env: Env, path: string, method: "GET" | "POST" | "PUT", body?: unknown): Promise<Response> {
  const upstream = await fetch(`https://api.github.com/repos/${encodeURIComponent(env.GH_REPO_OWNER)}/${encodeURIComponent(env.GH_REPO_NAME)}/${path}`, {
    method,
    headers: {
      "Accept": "application/vnd.github+json",
      "Authorization": `Bearer ${env.GH_API_TOKEN}`,
      "Content-Type": "application/json",
      "User-Agent": "Owefolk-Android-Feedback-Worker/1.0",
      "X-GitHub-Api-Version": "2022-11-28",
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const responseText = await upstream.text();
  return new Response(responseText, {
    status: upstream.status,
    headers: {"Cache-Control": "no-store", "Content-Type": "application/json", "X-Content-Type-Options": "nosniff"},
  });
}

function issueNumber(value: string | undefined): number | null {
  if (!value || !/^[1-9]\d{0,8}$/.test(value)) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) ? parsed : null;
}

async function handleFeedback(request: Request, env: Env, url: URL): Promise<Response> {
  if (!env.GH_API_TOKEN || !env.GH_REPO_OWNER || !env.GH_REPO_NAME || !env.GH_ASSETS_DIR) {
    return json({error: "Feedback service is not configured"}, 503);
  }
  let uid: string;
  try {
    uid = await verifyFeedbackCaller(request, env);
  } catch {
    return json({error: "Sign in to Owefolk on a verified device to use feedback"}, 401);
  }

  const parts = url.pathname.slice("/v1/feedback/".length).split("/").filter(Boolean);
  if (parts[0] === "issues" && parts.length === 1 && request.method === "POST") {
    if (!(await enforceRateLimit(env, uid, "issue", 5, 3_600))) return json({error: "Too many reports. Try again later."}, 429);
    const body = await readJson(request, 25_000);
    const title = typeof body?.title === "string" ? body.title.trim() : "";
    const description = typeof body?.body === "string" ? body.body.trim() : "";
    if (!title.startsWith("[Feedback] ") || title.length > 140 || description.length < 1 || description.length > 20_000) {
      return json({error: "Invalid feedback title or description"}, 400);
    }
    return github(env, "issues", "POST", {title, body: description});
  }

  const number = issueNumber(parts[1]);
  if (parts[0] === "issues" && number && parts.length === 2 && request.method === "GET") {
    return github(env, `issues/${number}`, "GET");
  }
  if (parts[0] === "issues" && number && parts[2] === "comments" && parts.length === 3) {
    if (request.method === "GET") return github(env, `issues/${number}/comments?per_page=100`, "GET");
    if (request.method === "POST") {
      if (!(await enforceRateLimit(env, uid, "comment", 30, 3_600))) return json({error: "Too many replies. Try again later."}, 429);
      const body = await readJson(request, 12_000);
      const comment = typeof body?.body === "string" ? body.body.trim() : "";
      if (!comment.startsWith("## Reply") || comment.length > 10_000) return json({error: "Invalid reply"}, 400);
      return github(env, `issues/${number}/comments`, "POST", {body: comment});
    }
  }

  if (parts[0] === "assets" && parts.length === 2 && request.method === "PUT") {
    if (!(await enforceRateLimit(env, uid, "asset", 15, 3_600))) return json({error: "Too many attachments. Try again later."}, 429);
    const filename = parts[1];
    if (!/^(issue|comment)-\d{8}-\d{6}-[a-f0-9]{4,16}\.(png|jpe?g|webp)$/.test(filename)) {
      return json({error: "Invalid attachment filename"}, 400);
    }
    const body = await readJson(request, 7_500_000);
    const message = typeof body?.message === "string" ? body.message.trim() : "";
    const content = typeof body?.content === "string" ? body.content : "";
    const decodedBytes = Math.floor(content.length * 0.75);
    if (!message.startsWith("Add feedback attachment ") || message.length > 180 || !/^[A-Za-z0-9+/]+={0,2}$/.test(content) || decodedBytes > 5_000_000) {
      return json({error: "Invalid attachment"}, 400);
    }
    const assetPath = `${encodeURIComponent(env.GH_ASSETS_DIR)}/${encodeURIComponent(filename)}`;
    return github(env, `contents/${assetPath}`, "PUT", {message, content});
  }

  return json({error: "Feedback operation is not supported"}, 404);
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
    if (url.pathname.startsWith("/v1/feedback/")) return handleFeedback(request, env, url);
    return json({error: "Not found"}, 404, origin);
  },
} satisfies ExportedHandler<Env>;
