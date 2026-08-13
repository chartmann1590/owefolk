import {describe, expect, test} from "vitest";
import worker, {Env} from "./index";

const mockEnv: Env = {
  DB: {
    prepare: () => ({
      first: async () => ({ok: 1}),
      run: async () => {},
      bind: () => ({run: async () => {}}),
    }) as any,
  } as any,
  DELETION_ENCRYPTION_KEY: "test-key",
  ENVIRONMENT: "test",
  GH_API_TOKEN: "test-token",
  GH_REPO_OWNER: "test",
  GH_REPO_NAME: "test",
  GH_ASSETS_DIR: "test",
  FIREBASE_PROJECT_ID: "test",
  FIREBASE_PROJECT_NUMBER: "123",
  FIREBASE_ANDROID_APP_ID: "test",
};

describe("Worker fetch handler", () => {
  test("returns health check with ok true", async () => {
    const request = new Request("https://example.com/health");
    const response = await worker.fetch(request, mockEnv);
    expect(response.status).toBe(200);
    const body = await response.json<{ok: boolean}>();
    expect(body.ok).toBe(true);
  });

  test("returns 404 for unknown routes", async () => {
    const request = new Request("https://example.com/unknown");
    const response = await worker.fetch(request, mockEnv);
    expect(response.status).toBe(404);
    const body = await response.json<{error: string}>();
    expect(body.error).toBe("Not found");
  });

  test("handles OPTIONS preflight", async () => {
    const request = new Request("https://example.com/health", {method: "OPTIONS"});
    const response = await worker.fetch(request, mockEnv);
    expect(response.status).toBe(204);
    expect(response.headers.get("access-control-allow-methods")).toBe("GET,POST,OPTIONS");
  });
});
