import {
  createServer,
  type IncomingMessage,
  type Server,
  type ServerResponse,
} from "node:http";
import { pathToFileURL } from "node:url";
import { fileURLToPath } from "node:url";
import { readFile } from "node:fs/promises";
import { extname, join } from "node:path";
import { SearchAdsRecsBuyerAgent } from "./buyer-agent.js";
import type { BuyerTurnRequest } from "./contracts.js";
import { DEMO_PAGE } from "./demo-page.js";
import { PythonDiscoveryAdapter } from "./python-adapter.js";
import { runtimeFromEnvironment } from "./runtime-from-env.js";
import { IdentityManager, assertSameOrigin } from "./v2-identity.js";
import {
  CommerceRepository,
  SqliteCartDraftStore,
  SqlitePendingDecisionStore,
} from "./v2-repository.js";
import { PersistentRunManager } from "./v2-runs.js";

const MAX_BODY_BYTES = 1024 * 1024;

class SlidingWindowRateLimiter {
  readonly #requests = new Map<string, number[]>();
  #operations = 0;

  constructor(
    private readonly limit = 30,
    private readonly windowMs = 60_000,
    private readonly now: () => number = Date.now,
  ) {}

  allow(key: string): boolean {
    const cutoff = this.now() - this.windowMs;
    this.#operations += 1;
    if (this.#operations % 256 === 0) {
      for (const [candidateKey, timestamps] of this.#requests) {
        const retained = timestamps.filter((timestamp) => timestamp > cutoff);
        if (retained.length === 0) this.#requests.delete(candidateKey);
        else this.#requests.set(candidateKey, retained);
      }
    }
    const active = (this.#requests.get(key) ?? []).filter((timestamp) => timestamp > cutoff);
    if (active.length >= this.limit) {
      this.#requests.set(key, active);
      return false;
    }
    active.push(this.now());
    this.#requests.set(key, active);
    return true;
  }
}

interface DataPlaneRuntimeStatus {
  mode: "memory" | "python";
  baseUrl: string | null;
  status: "up" | "down" | "embedded";
  latencyMs: number;
  error: string | null;
}

class RequestValidationError extends Error {
  constructor(
    readonly field: string,
    message: string,
  ) {
    super(message);
  }
}

function send(response: ServerResponse, status: number, payload: unknown): void {
  const body = Buffer.from(JSON.stringify(payload), "utf8");
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": body.length,
  });
  response.end(body);
}

function sendHtml(response: ServerResponse, body: string): void {
  const encoded = Buffer.from(body, "utf8");
  response.writeHead(200, {
    "content-type": "text/html; charset=utf-8",
    "content-length": encoded.length,
  });
  response.end(encoded);
}

// `tsx src/server.ts` and `node dist/src/server.js` have different module depths.
// Resolve the shared code root once so both supported startup modes serve the
// same console and committed quality report.
const CODE_ROOT = new URL(import.meta.url.includes("/dist/") ? "../../../" : "../../", import.meta.url);
const FRONTEND_DIST = fileURLToPath(new URL("apps/commerce-console/dist/", CODE_ROOT));
const QUALITY_REPORT = fileURLToPath(new URL("data/v2/retrieval_report.json", CODE_ROOT));

async function sendStatic(response: ServerResponse, root: string, path: string): Promise<boolean> {
  const relative = path === "/" || path === "/demo" ? "index.html" : path.slice(1);
  if (!/^(?:index\.html|assets\/[a-zA-Z0-9._-]+)$/.test(relative)) return false;
  try {
    const body = await readFile(join(root, relative));
    const contentType = {
      ".html": "text/html; charset=utf-8",
      ".js": "text/javascript; charset=utf-8",
      ".css": "text/css; charset=utf-8",
      ".svg": "image/svg+xml",
    }[extname(relative)] ?? "application/octet-stream";
    response.writeHead(200, {
      "content-type": contentType,
      "content-length": body.length,
      "cache-control": relative === "index.html" ? "no-cache" : "public, max-age=31536000, immutable",
    });
    response.end(body);
    return true;
  } catch {
    return false;
  }
}

async function jsonBody(request: IncomingMessage): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of request) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    size += buffer.length;
    if (size > MAX_BODY_BYTES) {
      throw new RequestValidationError("request", `body exceeds ${MAX_BODY_BYTES} bytes`);
    }
    chunks.push(buffer);
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
  } catch {
    throw new RequestValidationError("request", "body must be valid JSON");
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    throw new RequestValidationError("request", "body must be a JSON object");
  }
  return parsed as Record<string, unknown>;
}

function requiredString(
  payload: Record<string, unknown>,
  field: string,
  maxLength: number,
): string {
  const value = payload[field];
  if (typeof value !== "string" || !value.trim()) {
    throw new RequestValidationError(field, "must be a non-empty string");
  }
  if (value.length > maxLength) {
    throw new RequestValidationError(field, `must be at most ${maxLength} characters`);
  }
  return value.trim();
}

function buyerRequest(payload: Record<string, unknown>): BuyerTurnRequest {
  const allowed = new Set(["sessionId", "userId", "message", "confirmed"]);
  const unknown = Object.keys(payload).filter((field) => !allowed.has(field));
  if (unknown.length > 0) {
    throw new RequestValidationError("request", `unknown fields: ${unknown.sort().join(", ")}`);
  }
  if (payload.confirmed !== undefined && typeof payload.confirmed !== "boolean") {
    throw new RequestValidationError("confirmed", "must be a boolean");
  }
  return {
    sessionId: requiredString(payload, "sessionId", 128),
    userId: requiredString(payload, "userId", 128),
    message: requiredString(payload, "message", 2_000),
    ...(payload.confirmed === true ? { confirmed: true } : {}),
  };
}

function v2RunRequest(payload: Record<string, unknown>): { message: string; confirmed: boolean } {
  const allowed = new Set(["message", "confirmed"]);
  const unknown = Object.keys(payload).filter((field) => !allowed.has(field));
  if (unknown.length > 0) {
    throw new RequestValidationError("request", `unknown fields: ${unknown.sort().join(", ")}`);
  }
  if (payload.confirmed !== undefined && typeof payload.confirmed !== "boolean") {
    throw new RequestValidationError("confirmed", "must be a boolean");
  }
  return {
    message: requiredString(payload, "message", 2_000),
    confirmed: payload.confirmed === true,
  };
}

function pathRunId(pathname: string, suffix = ""): string | null {
  const pattern = suffix
    ? new RegExp(`^/api/v2/runs/([^/]+)/${suffix}$`)
    : /^\/api\/v2\/runs\/([^/]+)$/;
  const match = pattern.exec(pathname);
  return match?.[1] ? decodeURIComponent(match[1]) : null;
}

function eventCursor(request: IncomingMessage, url: URL): number {
  const raw = request.headers["last-event-id"] ?? url.searchParams.get("after") ?? "0";
  const cursor = Number(Array.isArray(raw) ? raw[0] : raw);
  if (!Number.isInteger(cursor) || cursor < 0) {
    throw new RequestValidationError("Last-Event-ID", "must be a non-negative integer");
  }
  return cursor;
}

export function buildControlPlaneServer(options: {
  agent?: SearchAdsRecsBuyerAgent;
  discoveryMode?: "memory" | "python";
  discoveryBaseUrl?: string;
  repository?: CommerceRepository;
  secureCookie?: boolean;
  legacyV1Enabled?: boolean;
  frontendDist?: string;
} = {}): Server {
  const ownsRepository = options.repository === undefined;
  const repository = options.repository ?? new CommerceRepository();
  const agent = options.agent ?? new SearchAdsRecsBuyerAgent({
    pending: new SqlitePendingDecisionStore(repository),
    drafts: new SqliteCartDraftStore(repository),
  });
  const identities = new IdentityManager(repository, {
    secureCookie: options.secureCookie ?? false,
  });
  const runs = new PersistentRunManager(repository, agent);
  const rateLimiter = new SlidingWindowRateLimiter();
  repository.performMaintenance();
  const maintenance = setInterval(() => repository.performMaintenance(), 60 * 60 * 1_000);
  maintenance.unref();
  runs.resumeIncomplete();
  const discoveryMode = options.discoveryMode ?? "memory";
  const discoveryBaseUrl = options.discoveryBaseUrl ?? "http://127.0.0.1:18083";
  const frontendDist = options.frontendDist ?? FRONTEND_DIST;
  const legacyV1Enabled = options.legacyV1Enabled ?? true;

  const dataPlaneProbe = async (): Promise<DataPlaneRuntimeStatus> => {
    if (discoveryMode === "memory") {
      return {
        mode: "memory",
        baseUrl: null,
        status: "embedded",
        latencyMs: 0,
        error: null,
      };
    }
    const startedAt = performance.now();
    try {
      const response = await fetch(`${discoveryBaseUrl}/health`, {
        signal: AbortSignal.timeout(2_000),
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      return {
        mode: "python",
        baseUrl: discoveryBaseUrl,
        status: "up",
        latencyMs: Math.round((performance.now() - startedAt) * 10) / 10,
        error: null,
      };
    } catch (error) {
      return {
        mode: "python",
        baseUrl: discoveryBaseUrl,
        status: "down",
        latencyMs: Math.round((performance.now() - startedAt) * 10) / 10,
        error: error instanceof Error ? error.message : "data plane probe failed",
      };
    }
  };

  const runtimePayload = async () => {
    const [model, dataPlane] = await Promise.all([
      agent.runtime.probe(),
      dataPlaneProbe(),
    ]);
    return {
      service: "moyuan-search-ads-recs-agent",
      version: "2.0.0",
      model,
      dataPlane,
      agentFramework: "@earendil-works/pi-agent-core",
      paymentEnabled: false,
    };
  };

  const server = createServer(async (request, response) => {
    try {
      const url = new URL(request.url ?? "/", "http://127.0.0.1");
      if (request.method === "GET" && url.pathname === "/health/live") {
        send(response, 200, { status: "UP", service: "moyuan-search-ads-recs-agent" });
        return;
      }
      if (request.method === "GET" && url.pathname === "/legacy-demo") {
        sendHtml(response, DEMO_PAGE);
        return;
      }
      if (
        request.method === "GET" &&
        (url.pathname === "/" || url.pathname === "/demo" || url.pathname.startsWith("/assets/"))
      ) {
        if (await sendStatic(response, frontendDist, url.pathname)) return;
        if (url.pathname === "/" || url.pathname === "/demo") sendHtml(response, DEMO_PAGE);
        else send(response, 404, { error: "not_found" });
        return;
      }
      if (
        request.method === "GET" &&
        (url.pathname === "/health" || url.pathname === "/health/ready")
      ) {
        const runtime = await runtimePayload();
        const healthy = runtime.model.status !== "down" && runtime.dataPlane.status !== "down";
        send(response, healthy ? 200 : 503, {
          status: healthy ? "UP" : "DEGRADED",
          ...runtime,
        });
        return;
      }
      if (legacyV1Enabled && request.method === "GET" && url.pathname === "/api/v1/runtime") {
        send(response, 200, await runtimePayload());
        return;
      }
      if (request.method === "GET" && url.pathname === "/metrics") {
        send(response, 200, agent.metrics.snapshot());
        return;
      }
      if (request.method === "GET" && url.pathname === "/api/v2/quality") {
        try {
          send(response, 200, JSON.parse(await readFile(QUALITY_REPORT, "utf8")));
        } catch {
          send(response, 503, { error: "quality_report_unavailable" });
        }
        return;
      }
      if (request.method === "GET" && url.pathname === "/api/v2/session") {
        const identity = identities.ensure(request, response);
        send(response, 200, identity);
        return;
      }
      if (request.method === "GET" && url.pathname === "/api/v2/preferences") {
        const identity = identities.ensure(request, response);
        send(response, 200, {
          personalizationEnabled: repository.personalizationEnabled(identity.identityId),
          identityScope: "server_issued_anonymous",
          retainedInteractionLimit: 100,
        });
        return;
      }
      if (request.method === "PUT" && url.pathname === "/api/v2/preferences") {
        assertSameOrigin(request);
        const identity = identities.ensure(request, response);
        const payload = await jsonBody(request);
        if (
          Object.keys(payload).some((field) => field !== "personalizationEnabled") ||
          typeof payload.personalizationEnabled !== "boolean"
        ) {
          throw new RequestValidationError(
            "personalizationEnabled",
            "must be the only field and contain a boolean",
          );
        }
        repository.setPersonalization(identity.identityId, payload.personalizationEnabled);
        send(response, 200, { personalizationEnabled: payload.personalizationEnabled });
        return;
      }
      if (request.method === "POST" && url.pathname === "/api/v2/interactions") {
        assertSameOrigin(request);
        const identity = identities.ensure(request, response);
        const payload = await jsonBody(request);
        const allowed = new Set(["eventType", "productId", "metadata"]);
        const unknown = Object.keys(payload).filter((field) => !allowed.has(field));
        if (unknown.length > 0) {
          throw new RequestValidationError("request", `unknown fields: ${unknown.join(", ")}`);
        }
        const eventType = requiredString(payload, "eventType", 32);
        if (!["view", "click", "cart", "purchase", "dislike", "ad_impression"].includes(eventType)) {
          throw new RequestValidationError("eventType", "is unsupported");
        }
        const productId = payload.productId === undefined || payload.productId === null
          ? null
          : requiredString(payload, "productId", 128);
        if (
          payload.metadata !== undefined &&
          (typeof payload.metadata !== "object" || payload.metadata === null || Array.isArray(payload.metadata))
        ) throw new RequestValidationError("metadata", "must be an object");
        repository.recordInteraction({
          identityId: identity.identityId,
          sessionId: identity.sessionId,
          eventType,
          productId,
          payload: (payload.metadata ?? {}) as Record<string, unknown>,
        });
        send(response, 202, { accepted: true });
        return;
      }
      if (request.method === "DELETE" && url.pathname === "/api/v2/interactions") {
        assertSameOrigin(request);
        const identity = identities.ensure(request, response);
        const deleted = repository.clearInteractionHistory(identity.identityId);
        send(response, 200, { deleted, personalizationHistoryCleared: true });
        return;
      }
      if (request.method === "POST" && url.pathname === "/api/v2/runs") {
        assertSameOrigin(request);
        const identity = identities.ensure(request, response);
        if (!rateLimiter.allow(identity.identityId)) {
          response.setHeader("retry-after", "60");
          send(response, 429, { error: "rate_limit_exceeded" });
          return;
        }
        const input = v2RunRequest(await jsonBody(request));
        const rawKey = request.headers["idempotency-key"];
        const idempotencyKey = Array.isArray(rawKey) ? rawKey[0] : rawKey;
        if (idempotencyKey !== undefined && (idempotencyKey.length < 1 || idempotencyKey.length > 128)) {
          throw new RequestValidationError("Idempotency-Key", "must contain 1 to 128 characters");
        }
        const replay = idempotencyKey
          ? runs.findByIdempotency(identity.identityId, idempotencyKey)
          : null;
        if (!replay && !runs.canAccept()) {
          response.setHeader("retry-after", "5");
          send(response, 503, { error: "global_run_capacity_exhausted" });
          return;
        }
        if (!replay && repository.countActiveRuns(identity.identityId) >= 3) {
          response.setHeader("retry-after", "2");
          send(response, 429, { error: "concurrent_run_limit_exceeded" });
          return;
        }
        const creation = runs.create({
          identity,
          ...input,
          idempotencyKey: idempotencyKey ?? null,
        });
        send(response, creation.created ? 202 : 200, {
          runId: creation.run.runId,
          status: creation.run.status,
          eventsUrl: `/api/v2/runs/${encodeURIComponent(creation.run.runId)}/events`,
          runUrl: `/api/v2/runs/${encodeURIComponent(creation.run.runId)}`,
          idempotentReplay: !creation.created,
        });
        return;
      }
      const v2RunId = pathRunId(url.pathname);
      if (request.method === "GET" && v2RunId) {
        const identity = identities.ensure(request, response);
        const run = runs.get(v2RunId, identity.identityId);
        send(response, run ? 200 : 404, run ?? { error: "run_not_found" });
        return;
      }
      const diagnosticsRunId = pathRunId(url.pathname, "diagnostics");
      if (request.method === "GET" && diagnosticsRunId) {
        const identity = identities.ensure(request, response);
        const diagnostics = repository.runDiagnostics(diagnosticsRunId, identity.identityId);
        send(response, diagnostics ? 200 : 404, diagnostics ?? { error: "run_not_found" });
        return;
      }
      const eventRunId = pathRunId(url.pathname, "events");
      if (request.method === "GET" && eventRunId) {
        const identity = identities.ensure(request, response);
        const run = runs.get(eventRunId, identity.identityId);
        if (!run) {
          send(response, 404, { error: "run_not_found" });
          return;
        }
        const cursor = eventCursor(request, url);
        response.writeHead(200, {
          "content-type": "text/event-stream; charset=utf-8",
          "cache-control": "no-cache, no-transform",
          connection: "keep-alive",
          "x-accel-buffering": "no",
        });
        const writeEvent = (event: ReturnType<typeof runs.events>[number]) => {
          if (response.destroyed || response.writableEnded) return;
          response.write(`id: ${event.sequence}\nevent: ${event.eventType}\ndata: ${JSON.stringify(event)}\n\n`);
          if (["result", "run_failed", "run_cancelled"].includes(event.eventType)) {
            cleanup();
            response.end();
          }
        };
        let heartbeat: NodeJS.Timeout | undefined;
        let unsubscribe = () => {};
        const cleanup = () => {
          unsubscribe();
          if (heartbeat) clearInterval(heartbeat);
        };
        unsubscribe = runs.subscribe(eventRunId, writeEvent);
        request.on("close", cleanup);
        for (const event of runs.events(eventRunId, identity.identityId, cursor)) writeEvent(event);
        const current = runs.get(eventRunId, identity.identityId);
        if (current && ["completed", "failed", "cancelled"].includes(current.status)) {
          cleanup();
          if (!response.writableEnded) response.end();
          return;
        }
        heartbeat = setInterval(() => {
          if (!response.destroyed) response.write(": heartbeat\n\n");
        }, 15_000);
        heartbeat.unref();
        return;
      }
      const cancelRunId = pathRunId(url.pathname, "cancel");
      if (request.method === "POST" && cancelRunId) {
        assertSameOrigin(request);
        const identity = identities.ensure(request, response);
        const run = runs.cancel(cancelRunId, identity.identityId);
        send(response, run ? 202 : 404, run ?? { error: "run_not_found" });
        return;
      }
      const draftRunId = url.pathname.startsWith("/api/v2/cart-drafts/")
        ? decodeURIComponent(url.pathname.slice("/api/v2/cart-drafts/".length))
        : null;
      if (request.method === "GET" && draftRunId) {
        const identity = identities.ensure(request, response);
        const draft = agent.drafts.get(draftRunId);
        const visibleDraft = draft?.sessionId === identity.sessionId ? draft : null;
        send(response, visibleDraft ? 200 : 404, visibleDraft ?? { error: "cart_draft_not_found" });
        return;
      }
      if (
        legacyV1Enabled &&
        request.method === "GET" &&
        url.pathname.startsWith("/api/v1/cart-drafts/")
      ) {
        const draftId = decodeURIComponent(url.pathname.slice("/api/v1/cart-drafts/".length));
        const sessionId = request.headers["x-session-id"];
        if (typeof sessionId !== "string" || !sessionId) {
          throw new RequestValidationError("x-session-id", "header is required");
        }
        const draft = agent.drafts.get(draftId);
        const visibleDraft = draft?.sessionId === sessionId ? draft : null;
        send(
          response,
          visibleDraft ? 200 : 404,
          visibleDraft ?? { error: "cart_draft_not_found" },
        );
        return;
      }
      if (legacyV1Enabled && request.method === "POST" && url.pathname === "/api/v1/agent") {
        const reply = await agent.handle(buyerRequest(await jsonBody(request)));
        send(response, 200, reply);
        return;
      }
      if (
        legacyV1Enabled &&
        request.method === "POST" &&
        url.pathname === "/api/v1/agent/stream"
      ) {
        const turn = buyerRequest(await jsonBody(request));
        response.writeHead(200, {
          "content-type": "application/x-ndjson; charset=utf-8",
          "cache-control": "no-cache, no-transform",
          "x-content-type-options": "nosniff",
        });
        const write = (type: string, data: unknown) => {
          if (!response.destroyed) response.write(`${JSON.stringify({ type, data })}\n`);
        };
        write("runtime", await runtimePayload());
        const reply = await agent.handle(turn, {
          onTrace: (record) => write("trace", record),
        });
        write("result", reply);
        response.end();
        return;
      }
      send(response, 404, { error: "not_found" });
    } catch (error) {
      if (error instanceof RequestValidationError) {
        send(response, 400, {
          error: "validation_error",
          field: error.field,
          message: error.message,
        });
        return;
      }
      if (error instanceof Error && error.message === "cross_site_request_rejected") {
        send(response, 403, { error: "cross_site_request_rejected" });
        return;
      }
      console.error("Unhandled control-plane request error", {
        errorType: error instanceof Error ? error.name : "unknown",
      });
      send(response, 500, { error: "internal_server_error" });
    }
  });
  server.once("close", () => clearInterval(maintenance));
  if (ownsRepository) server.once("close", () => repository.close());
  return server;
}

function integerPort(value: string): number {
  const port = Number(value);
  if (!Number.isInteger(port) || port < 0 || port > 65535) {
    throw new Error(`invalid control-plane port: ${value}`);
  }
  return port;
}

export async function main(): Promise<void> {
  const discoveryMode = process.env.MOYUAN_DISCOVERY_MODE ?? "memory";
  if (!["memory", "python"].includes(discoveryMode)) {
    throw new Error("MOYUAN_DISCOVERY_MODE must be memory or python");
  }
  const dataPlane = discoveryMode === "python"
    ? new PythonDiscoveryAdapter(
        process.env.MOYUAN_DISCOVERY_BASE_URL ?? "http://127.0.0.1:18083",
      )
    : undefined;
  const runtime = runtimeFromEnvironment();
  const repository = new CommerceRepository(
    process.env.MOYUAN_DATABASE_PATH ?? ".runtime/commerce-agent.sqlite",
  );
  const agent = new SearchAdsRecsBuyerAgent({
    runtime,
    ...(dataPlane ? { channels: dataPlane, evidence: dataPlane } : {}),
    pending: new SqlitePendingDecisionStore(repository),
    drafts: new SqliteCartDraftStore(repository),
  });
  const server = buildControlPlaneServer({
    agent,
    repository,
    discoveryMode: discoveryMode as "memory" | "python",
    discoveryBaseUrl: process.env.MOYUAN_DISCOVERY_BASE_URL ?? "http://127.0.0.1:18083",
    secureCookie: process.env.MOYUAN_SECURE_COOKIE === "true",
    legacyV1Enabled: process.env.MOYUAN_ENABLE_V1_API === "true",
  });
  server.once("close", () => repository.close());
  const host = process.env.MOYUAN_CONTROL_HOST ?? "127.0.0.1";
  const port = integerPort(process.env.MOYUAN_CONTROL_PORT ?? "19090");
  server.listen(port, host, () => {
    const address = server.address();
    const actualPort = typeof address === "object" && address ? address.port : port;
    const descriptor = runtime.describe();
    console.log(
      `Search-ads-recs Agent is listening on http://${host}:${actualPort} ` +
      `[model=${descriptor.mode}:${descriptor.model}, discovery=${discoveryMode}]`,
    );
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await main();
}
