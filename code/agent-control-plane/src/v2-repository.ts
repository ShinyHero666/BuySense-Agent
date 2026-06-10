import { randomBytes, randomUUID } from "node:crypto";
import { mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { DatabaseSync } from "node:sqlite";
import type {
  AgentRun,
  AgentRunEvent,
  IdentitySession,
  RunEventType,
  RunStatus,
} from "./generated/contracts-v2.js";
import type {
  AgentTraceRecord,
  CartDraft,
  SearchAdsRecsReply,
} from "./contracts.js";
import type {
  CartDraftStore,
  PendingDecision,
  PendingDecisionStore,
} from "./session-store.js";

type SqlValue = string | number | bigint | Uint8Array | null;
type SqlRow = Record<string, SqlValue>;

function requiredString(row: SqlRow, field: string): string {
  const value = row[field];
  if (typeof value !== "string") throw new Error(`invalid SQLite field: ${field}`);
  return value;
}

function integer(row: SqlRow, field: string): number {
  const value = row[field];
  if (typeof value === "number") return value;
  if (typeof value === "bigint") return Number(value);
  throw new Error(`invalid SQLite integer field: ${field}`);
}

function nullableString(row: SqlRow, field: string): string | null {
  const value = row[field];
  return value === null ? null : requiredString(row, field);
}

function parseJson<T>(value: string): T {
  return JSON.parse(value) as T;
}

function runFromRow(row: SqlRow): AgentRun {
  const resultJson = nullableString(row, "result_json");
  return {
    runId: requiredString(row, "run_id"),
    identityId: requiredString(row, "identity_id"),
    sessionId: requiredString(row, "session_id"),
    status: requiredString(row, "status") as RunStatus,
    message: requiredString(row, "message"),
    confirmed: integer(row, "confirmed") === 1,
    idempotencyKey: nullableString(row, "idempotency_key"),
    ...(resultJson === null ? {} : { result: parseJson<unknown>(resultJson) }),
    errorCode: nullableString(row, "error_code"),
    createdAt: requiredString(row, "created_at"),
    updatedAt: requiredString(row, "updated_at"),
    cancelRequested: integer(row, "cancel_requested") === 1,
  };
}

function eventFromRow(row: SqlRow): AgentRunEvent {
  return {
    eventId: requiredString(row, "event_id"),
    runId: requiredString(row, "run_id"),
    taskId: nullableString(row, "task_id"),
    parentTaskId: nullableString(row, "parent_task_id"),
    sequence: integer(row, "sequence"),
    eventType: requiredString(row, "event_type") as RunEventType,
    timestamp: requiredString(row, "timestamp"),
    schemaVersion: "2.0",
    payload: parseJson<Record<string, unknown>>(requiredString(row, "payload_json")),
  };
}

export interface CreateRunResult {
  run: AgentRun;
  created: boolean;
}

export class CommerceRepository {
  readonly #db: DatabaseSync;

  constructor(
    databasePath = ":memory:",
    private readonly now: () => number = Date.now,
  ) {
    if (databasePath !== ":memory:") mkdirSync(dirname(databasePath), { recursive: true });
    this.#db = new DatabaseSync(databasePath);
    this.#db.exec("PRAGMA foreign_keys = ON");
    this.#db.exec("PRAGMA journal_mode = WAL");
    this.#db.exec("PRAGMA busy_timeout = 5000");
    this.#migrate();
  }

  #migrate(): void {
    this.#db.exec(`
      CREATE TABLE IF NOT EXISTS settings (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL,
        updated_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS identities (
        identity_id TEXT PRIMARY KEY,
        session_id TEXT NOT NULL UNIQUE,
        issued_at TEXT NOT NULL,
        expires_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS runs (
        run_id TEXT PRIMARY KEY,
        identity_id TEXT NOT NULL REFERENCES identities(identity_id),
        session_id TEXT NOT NULL,
        status TEXT NOT NULL CHECK(status IN ('queued','running','completed','failed','cancelled')),
        message TEXT NOT NULL,
        confirmed INTEGER NOT NULL CHECK(confirmed IN (0,1)),
        idempotency_key TEXT,
        result_json TEXT,
        error_code TEXT,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL,
        cancel_requested INTEGER NOT NULL DEFAULT 0 CHECK(cancel_requested IN (0,1))
      );
      CREATE UNIQUE INDEX IF NOT EXISTS runs_identity_idempotency
        ON runs(identity_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
      CREATE INDEX IF NOT EXISTS runs_identity_created
        ON runs(identity_id, created_at DESC);
      CREATE TABLE IF NOT EXISTS run_events (
        event_id TEXT PRIMARY KEY,
        run_id TEXT NOT NULL REFERENCES runs(run_id) ON DELETE CASCADE,
        task_id TEXT,
        parent_task_id TEXT,
        sequence INTEGER NOT NULL,
        event_type TEXT NOT NULL,
        timestamp TEXT NOT NULL,
        schema_version TEXT NOT NULL,
        payload_json TEXT NOT NULL,
        UNIQUE(run_id, sequence)
      );
      CREATE INDEX IF NOT EXISTS run_events_replay ON run_events(run_id, sequence);
      CREATE TABLE IF NOT EXISTS pending_decisions (
        session_id TEXT PRIMARY KEY,
        identity_id TEXT NOT NULL,
        decision_json TEXT NOT NULL,
        expires_at_ms INTEGER NOT NULL,
        updated_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS cart_drafts (
        draft_id TEXT PRIMARY KEY,
        session_id TEXT NOT NULL,
        draft_json TEXT NOT NULL,
        expires_at_ms INTEGER NOT NULL,
        created_at TEXT NOT NULL
      );
      CREATE INDEX IF NOT EXISTS cart_drafts_session ON cart_drafts(session_id);
      CREATE TABLE IF NOT EXISTS tasks (
        task_id TEXT PRIMARY KEY,
        run_id TEXT NOT NULL REFERENCES runs(run_id) ON DELETE CASCADE,
        parent_task_id TEXT,
        role TEXT NOT NULL,
        status TEXT NOT NULL,
        input_json TEXT NOT NULL,
        output_json TEXT,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS artifacts (
        artifact_id TEXT PRIMARY KEY,
        run_id TEXT NOT NULL REFERENCES runs(run_id) ON DELETE CASCADE,
        task_id TEXT,
        artifact_type TEXT NOT NULL,
        status TEXT NOT NULL,
        payload_json TEXT NOT NULL,
        created_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS model_executions (
        execution_id TEXT PRIMARY KEY,
        run_id TEXT NOT NULL REFERENCES runs(run_id) ON DELETE CASCADE,
        task_id TEXT,
        role TEXT NOT NULL,
        provider TEXT NOT NULL,
        model TEXT NOT NULL,
        outcome TEXT NOT NULL,
        latency_ms REAL NOT NULL,
        total_tokens INTEGER NOT NULL,
        error TEXT,
        created_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS constraints (
        constraint_id TEXT PRIMARY KEY,
        run_id TEXT NOT NULL REFERENCES runs(run_id) ON DELETE CASCADE,
        identity_id TEXT NOT NULL,
        field TEXT NOT NULL,
        value_json TEXT NOT NULL,
        source TEXT NOT NULL,
        strength TEXT NOT NULL,
        confidence REAL NOT NULL,
        turn_id TEXT NOT NULL,
        status TEXT NOT NULL,
        created_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS interaction_events (
        interaction_id TEXT PRIMARY KEY,
        identity_id TEXT NOT NULL REFERENCES identities(identity_id),
        session_id TEXT NOT NULL,
        event_type TEXT NOT NULL,
        product_id TEXT,
        payload_json TEXT NOT NULL,
        occurred_at TEXT NOT NULL
      );
      CREATE INDEX IF NOT EXISTS interaction_identity_time
        ON interaction_events(identity_id, occurred_at DESC);
      CREATE TABLE IF NOT EXISTS identity_preferences (
        identity_id TEXT PRIMARY KEY REFERENCES identities(identity_id) ON DELETE CASCADE,
        personalization_enabled INTEGER NOT NULL DEFAULT 1 CHECK(personalization_enabled IN (0,1)),
        updated_at TEXT NOT NULL
      );
    `);
  }

  close(): void {
    this.#db.close();
  }

  performMaintenance(options: {
    terminalRunRetentionMs?: number;
    interactionRetentionMs?: number;
  } = {}): {
    pendingDecisions: number;
    cartDrafts: number;
    terminalRuns: number;
    interactions: number;
    identities: number;
  } {
    const now = this.now();
    const runCutoff = new Date(
      now - (options.terminalRunRetentionMs ?? 30 * 24 * 60 * 60 * 1_000),
    ).toISOString();
    const interactionCutoff = new Date(
      now - (options.interactionRetentionMs ?? 180 * 24 * 60 * 60 * 1_000),
    ).toISOString();
    const pendingDecisions = Number(
      this.#db.prepare("DELETE FROM pending_decisions WHERE expires_at_ms <= ?").run(now).changes,
    );
    const cartDrafts = Number(
      this.#db.prepare("DELETE FROM cart_drafts WHERE expires_at_ms <= ?").run(now).changes,
    );
    const terminalRuns = Number(this.#db.prepare(`
      DELETE FROM runs
      WHERE status IN ('completed','failed','cancelled') AND updated_at < ?
    `).run(runCutoff).changes);
    const interactions = Number(this.#db.prepare(`
      DELETE FROM interaction_events WHERE occurred_at < ?
    `).run(interactionCutoff).changes);
    const identities = Number(this.#db.prepare(`
      DELETE FROM identities
      WHERE expires_at <= ?
        AND NOT EXISTS (SELECT 1 FROM runs WHERE runs.identity_id = identities.identity_id)
        AND NOT EXISTS (
          SELECT 1 FROM interaction_events
          WHERE interaction_events.identity_id = identities.identity_id
        )
    `).run(new Date(now).toISOString()).changes);
    this.#db.exec("PRAGMA wal_checkpoint(PASSIVE)");
    return { pendingDecisions, cartDrafts, terminalRuns, interactions, identities };
  }

  getOrCreateSetting(key: string, createValue = () => randomBytes(32).toString("base64url")): string {
    const existing = this.#db.prepare("SELECT value FROM settings WHERE key = ?").get(key) as SqlRow | undefined;
    if (existing) return requiredString(existing, "value");
    const value = createValue();
    this.#db.prepare("INSERT INTO settings(key, value, updated_at) VALUES (?, ?, ?)")
      .run(key, value, new Date(this.now()).toISOString());
    return value;
  }

  createIdentity(ttlMs = 180 * 24 * 60 * 60 * 1_000): IdentitySession {
    const now = this.now();
    const identity: IdentitySession = {
      identityId: `id_${randomUUID()}`,
      sessionId: `session_${randomUUID()}`,
      issuedAt: new Date(now).toISOString(),
      expiresAt: new Date(now + ttlMs).toISOString(),
    };
    this.#db.prepare(`
      INSERT INTO identities(identity_id, session_id, issued_at, expires_at)
      VALUES (?, ?, ?, ?)
    `).run(identity.identityId, identity.sessionId, identity.issuedAt, identity.expiresAt);
    return identity;
  }

  getIdentity(identityId: string, sessionId: string): IdentitySession | null {
    const row = this.#db.prepare(`
      SELECT identity_id, session_id, issued_at, expires_at
      FROM identities WHERE identity_id = ? AND session_id = ?
    `).get(identityId, sessionId) as SqlRow | undefined;
    if (!row) return null;
    const identity: IdentitySession = {
      identityId: requiredString(row, "identity_id"),
      sessionId: requiredString(row, "session_id"),
      issuedAt: requiredString(row, "issued_at"),
      expiresAt: requiredString(row, "expires_at"),
    };
    return Date.parse(identity.expiresAt) > this.now() ? identity : null;
  }

  createRun(input: {
    identity: IdentitySession;
    message: string;
    confirmed: boolean;
    idempotencyKey: string | null;
  }): CreateRunResult {
    if (input.idempotencyKey) {
      const existing = this.#db.prepare(`
        SELECT * FROM runs WHERE identity_id = ? AND idempotency_key = ?
      `).get(input.identity.identityId, input.idempotencyKey) as SqlRow | undefined;
      if (existing) return { run: runFromRow(existing), created: false };
    }
    const timestamp = new Date(this.now()).toISOString();
    const run: AgentRun = {
      runId: `run_${randomUUID()}`,
      identityId: input.identity.identityId,
      sessionId: input.identity.sessionId,
      status: "queued",
      message: input.message,
      confirmed: input.confirmed,
      idempotencyKey: input.idempotencyKey,
      errorCode: null,
      createdAt: timestamp,
      updatedAt: timestamp,
      cancelRequested: false,
    };
    this.#db.prepare(`
      INSERT INTO runs(
        run_id, identity_id, session_id, status, message, confirmed,
        idempotency_key, result_json, error_code, created_at, updated_at, cancel_requested
      ) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, 0)
    `).run(
      run.runId,
      run.identityId,
      run.sessionId,
      run.status,
      run.message,
      run.confirmed ? 1 : 0,
      run.idempotencyKey ?? null,
      run.createdAt,
      run.updatedAt,
    );
    return { run, created: true };
  }

  getRun(runId: string, identityId?: string): AgentRun | null {
    const row = (identityId
      ? this.#db.prepare("SELECT * FROM runs WHERE run_id = ? AND identity_id = ?").get(runId, identityId)
      : this.#db.prepare("SELECT * FROM runs WHERE run_id = ?").get(runId)) as SqlRow | undefined;
    return row ? runFromRow(row) : null;
  }

  findRunByIdempotency(identityId: string, idempotencyKey: string): AgentRun | null {
    const row = this.#db.prepare(`
      SELECT * FROM runs WHERE identity_id = ? AND idempotency_key = ?
    `).get(identityId, idempotencyKey) as SqlRow | undefined;
    return row ? runFromRow(row) : null;
  }

  listIncompleteRuns(): AgentRun[] {
    return (this.#db.prepare(`
      SELECT * FROM runs WHERE status IN ('queued', 'running') ORDER BY created_at
    `).all() as SqlRow[]).map(runFromRow);
  }

  countActiveRuns(identityId: string): number {
    const row = this.#db.prepare(`
      SELECT COUNT(*) AS count FROM runs
      WHERE identity_id = ? AND status IN ('queued','running')
    `).get(identityId) as SqlRow;
    return integer(row, "count");
  }

  updateRun(
    runId: string,
    update: { status: RunStatus; result?: unknown; errorCode?: string | null },
  ): AgentRun {
    this.#db.prepare(`
      UPDATE runs SET status = ?, result_json = ?, error_code = ?, updated_at = ?
      WHERE run_id = ?
    `).run(
      update.status,
      update.result === undefined ? null : JSON.stringify(update.result),
      update.errorCode ?? null,
      new Date(this.now()).toISOString(),
      runId,
    );
    const run = this.getRun(runId);
    if (!run) throw new Error(`run not found: ${runId}`);
    return run;
  }

  requestCancellation(runId: string, identityId: string): AgentRun | null {
    this.#db.prepare(`
      UPDATE runs SET cancel_requested = 1, updated_at = ?
      WHERE run_id = ? AND identity_id = ? AND status IN ('queued','running')
    `).run(new Date(this.now()).toISOString(), runId, identityId);
    return this.getRun(runId, identityId);
  }

  appendEvent(
    runId: string,
    eventType: RunEventType,
    payload: Record<string, unknown>,
    taskId: string | null = null,
    parentTaskId: string | null = null,
  ): AgentRunEvent {
    this.#db.exec("BEGIN IMMEDIATE");
    try {
      const row = this.#db.prepare(`
        SELECT COALESCE(MAX(sequence), 0) + 1 AS next_sequence
        FROM run_events WHERE run_id = ?
      `).get(runId) as SqlRow;
      const event: AgentRunEvent = {
        eventId: `event_${randomUUID()}`,
        runId,
        taskId,
        parentTaskId,
        sequence: integer(row, "next_sequence"),
        eventType,
        timestamp: new Date(this.now()).toISOString(),
        schemaVersion: "2.0",
        payload,
      };
      this.#db.prepare(`
        INSERT INTO run_events(
          event_id, run_id, task_id, parent_task_id, sequence,
          event_type, timestamp, schema_version, payload_json
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      `).run(
        event.eventId,
        event.runId,
        event.taskId ?? null,
        event.parentTaskId ?? null,
        event.sequence,
        event.eventType,
        event.timestamp,
        event.schemaVersion,
        JSON.stringify(event.payload),
      );
      this.#db.exec("COMMIT");
      return event;
    } catch (error) {
      this.#db.exec("ROLLBACK");
      throw error;
    }
  }

  listEvents(runId: string, identityId: string, afterSequence = 0): AgentRunEvent[] {
    const run = this.getRun(runId, identityId);
    if (!run) return [];
    return (this.#db.prepare(`
      SELECT * FROM run_events WHERE run_id = ? AND sequence > ? ORDER BY sequence
    `).all(runId, afterSequence) as SqlRow[]).map(eventFromRow);
  }

  runDiagnostics(runId: string, identityId: string): Record<string, number> | null {
    if (!this.getRun(runId, identityId)) return null;
    const count = (table: "run_events" | "tasks" | "artifacts" | "model_executions" | "constraints") => {
      const row = this.#db.prepare(`SELECT COUNT(*) AS count FROM ${table} WHERE run_id = ?`)
        .get(runId) as SqlRow;
      return integer(row, "count");
    };
    return {
      events: count("run_events"),
      tasks: count("tasks"),
      artifacts: count("artifacts"),
      modelExecutions: count("model_executions"),
      constraints: count("constraints"),
    };
  }

  persistTrace(runId: string, trace: AgentTraceRecord): void {
    const timestamp = new Date(this.now()).toISOString();
    const detail = trace.detail;
    const taskId = typeof detail.taskId === "string" ? detail.taskId : null;
    if (trace.event === "task_delegated" && taskId) {
      this.#db.prepare(`
        INSERT OR IGNORE INTO tasks(
          task_id, run_id, parent_task_id, role, status,
          input_json, output_json, created_at, updated_at
        ) VALUES (?, ?, ?, ?, 'queued', ?, NULL, ?, ?)
      `).run(
        taskId,
        runId,
        typeof detail.parentTaskId === "string" ? detail.parentTaskId : null,
        typeof detail.to === "string" ? detail.to : trace.role,
        JSON.stringify({ capability: detail.capability, revisionAttempt: detail.revisionAttempt }),
        timestamp,
        timestamp,
      );
    } else if (taskId && ["task_started", "task_completed", "task_failed"].includes(trace.event)) {
      const status = trace.event === "task_started"
        ? "running"
        : trace.event === "task_completed" ? "completed" : "failed";
      this.#db.prepare(`
        UPDATE tasks SET status = ?, output_json = ?, updated_at = ?
        WHERE task_id = ? AND run_id = ?
      `).run(status, JSON.stringify(detail), timestamp, taskId, runId);
    }
    if (["artifact_published", "tool_artifact_published"].includes(trace.event)) {
      const artifactId = typeof detail.artifactId === "string" ? detail.artifactId : null;
      if (artifactId) {
        this.#db.prepare(`
          INSERT OR IGNORE INTO artifacts(
            artifact_id, run_id, task_id, artifact_type, status, payload_json, created_at
          ) VALUES (?, ?, ?, ?, 'verified', ?, ?)
        `).run(
          artifactId,
          runId,
          typeof detail.parentTaskId === "string" ? detail.parentTaskId : null,
          typeof detail.artifactType === "string" ? detail.artifactType : "unknown",
          JSON.stringify(detail),
          timestamp,
        );
      }
    }
    if (trace.event === "model_execution") {
      this.#db.prepare(`
        INSERT INTO model_executions(
          execution_id, run_id, task_id, role, provider, model, outcome,
          latency_ms, total_tokens, error, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      `).run(
        `model_${randomUUID()}`,
        runId,
        taskId,
        trace.role,
        typeof detail.provider === "string" ? detail.provider : "unknown",
        typeof detail.model === "string" ? detail.model : "unknown",
        typeof detail.outcome === "string" ? detail.outcome : "unknown",
        typeof detail.latencyMs === "number" ? detail.latencyMs : 0,
        typeof detail.totalTokens === "number" ? detail.totalTokens : 0,
        typeof detail.error === "string" ? detail.error : null,
        timestamp,
      );
    }
  }

  persistResultConstraints(run: AgentRun, result: unknown): number {
    const value = result as {
      decision?: { plan?: { requirements?: { constraints?: unknown[] } } } | null;
    };
    const constraints = value?.decision?.plan?.requirements?.constraints;
    if (!Array.isArray(constraints)) return 0;
    let persisted = 0;
    for (const candidate of constraints) {
      if (typeof candidate !== "object" || candidate === null || Array.isArray(candidate)) continue;
      const item = candidate as Record<string, unknown>;
      if (
        typeof item.constraintId !== "string" ||
        typeof item.field !== "string" ||
        typeof item.source !== "string" ||
        typeof item.strength !== "string" ||
        typeof item.confidence !== "number" ||
        typeof item.turnId !== "string" ||
        typeof item.status !== "string"
      ) continue;
      this.#db.prepare(`
        INSERT OR REPLACE INTO constraints(
          constraint_id, run_id, identity_id, field, value_json, source,
          strength, confidence, turn_id, status, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      `).run(
        `${run.runId}:${item.constraintId}`,
        run.runId,
        run.identityId,
        item.field,
        JSON.stringify(item.value),
        item.source,
        item.strength,
        item.confidence,
        item.turnId,
        item.status,
        new Date(this.now()).toISOString(),
      );
      persisted += 1;
    }
    return persisted;
  }

  putPending(sessionId: string, identityId: string, decision: SearchAdsRecsReply, ttlMs: number): void {
    this.#db.prepare(`
      INSERT INTO pending_decisions(session_id, identity_id, decision_json, expires_at_ms, updated_at)
      VALUES (?, ?, ?, ?, ?)
      ON CONFLICT(session_id) DO UPDATE SET
        identity_id = excluded.identity_id,
        decision_json = excluded.decision_json,
        expires_at_ms = excluded.expires_at_ms,
        updated_at = excluded.updated_at
    `).run(
      sessionId,
      identityId,
      JSON.stringify(decision),
      this.now() + ttlMs,
      new Date(this.now()).toISOString(),
    );
  }

  getPending(sessionId: string, identityId: string): PendingDecision | null {
    const row = this.#db.prepare(`
      SELECT * FROM pending_decisions WHERE session_id = ? AND identity_id = ?
    `).get(sessionId, identityId) as SqlRow | undefined;
    if (!row || integer(row, "expires_at_ms") <= this.now()) {
      if (row) this.deletePending(sessionId);
      return null;
    }
    return {
      sessionId,
      userId: identityId,
      decision: parseJson<SearchAdsRecsReply>(requiredString(row, "decision_json")),
      expiresAtMs: integer(row, "expires_at_ms"),
    };
  }

  deletePending(sessionId: string): void {
    this.#db.prepare("DELETE FROM pending_decisions WHERE session_id = ?").run(sessionId);
  }

  putDraft(draft: CartDraft): void {
    this.#db.prepare(`
      INSERT INTO cart_drafts(draft_id, session_id, draft_json, expires_at_ms, created_at)
      VALUES (?, ?, ?, ?, ?)
      ON CONFLICT(draft_id) DO UPDATE SET draft_json = excluded.draft_json,
        expires_at_ms = excluded.expires_at_ms
    `).run(
      draft.draftId,
      draft.sessionId,
      JSON.stringify(draft),
      Date.parse(draft.expiresAt),
      draft.createdAt,
    );
  }

  getDraft(draftId: string): CartDraft | null {
    const row = this.#db.prepare("SELECT * FROM cart_drafts WHERE draft_id = ?")
      .get(draftId) as SqlRow | undefined;
    if (!row || integer(row, "expires_at_ms") <= this.now()) {
      if (row) this.#db.prepare("DELETE FROM cart_drafts WHERE draft_id = ?").run(draftId);
      return null;
    }
    return parseJson<CartDraft>(requiredString(row, "draft_json"));
  }

  personalizationEnabled(identityId: string): boolean {
    const row = this.#db.prepare(`
      SELECT personalization_enabled FROM identity_preferences WHERE identity_id = ?
    `).get(identityId) as SqlRow | undefined;
    return row ? integer(row, "personalization_enabled") === 1 : true;
  }

  setPersonalization(identityId: string, enabled: boolean): void {
    this.#db.prepare(`
      INSERT INTO identity_preferences(identity_id, personalization_enabled, updated_at)
      VALUES (?, ?, ?)
      ON CONFLICT(identity_id) DO UPDATE SET
        personalization_enabled = excluded.personalization_enabled,
        updated_at = excluded.updated_at
    `).run(identityId, enabled ? 1 : 0, new Date(this.now()).toISOString());
  }

  recordInteraction(input: {
    identityId: string;
    sessionId: string;
    eventType: string;
    productId: string | null;
    payload: Record<string, unknown>;
  }): void {
    this.#db.prepare(`
      INSERT INTO interaction_events(
        interaction_id, identity_id, session_id, event_type,
        product_id, payload_json, occurred_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?)
    `).run(
      `interaction_${randomUUID()}`,
      input.identityId,
      input.sessionId,
      input.eventType,
      input.productId,
      JSON.stringify(input.payload),
      new Date(this.now()).toISOString(),
    );
  }

  clearInteractionHistory(identityId: string): number {
    const result = this.#db.prepare("DELETE FROM interaction_events WHERE identity_id = ?")
      .run(identityId);
    return Number(result.changes);
  }

  discoveryContext(identityId: string): {
    personalizationEnabled: boolean;
    recentProductIds: string[];
    excludedProductIds: string[];
    adExposureProductIds: string[];
  } {
    const rows = this.#db.prepare(`
      SELECT event_type, product_id FROM interaction_events
      WHERE identity_id = ? AND product_id IS NOT NULL
      ORDER BY occurred_at DESC LIMIT 100
    `).all(identityId) as SqlRow[];
    const ids = (types: Set<string>, limit: number) => [...new Set(rows.flatMap((row) =>
      types.has(requiredString(row, "event_type"))
        ? [requiredString(row, "product_id")]
        : []
    ))].slice(0, limit);
    return {
      personalizationEnabled: this.personalizationEnabled(identityId),
      recentProductIds: ids(new Set(["view", "click", "cart", "purchase"]), 30),
      excludedProductIds: ids(new Set(["dislike"]), 50),
      adExposureProductIds: ids(new Set(["ad_impression"]), 50),
    };
  }
}

export class SqlitePendingDecisionStore implements PendingDecisionStore {
  constructor(
    private readonly repository: CommerceRepository,
    private readonly ttlMs = 15 * 60 * 1_000,
  ) {}

  put(sessionId: string, userId: string, decision: SearchAdsRecsReply): void {
    this.repository.putPending(sessionId, userId, decision, this.ttlMs);
  }

  get(sessionId: string, userId: string): PendingDecision | null {
    return this.repository.getPending(sessionId, userId);
  }

  delete(sessionId: string): void {
    this.repository.deletePending(sessionId);
  }
}

export class SqliteCartDraftStore implements CartDraftStore {
  constructor(private readonly repository: CommerceRepository) {}

  put(draft: CartDraft): void {
    this.repository.putDraft(draft);
  }

  get(draftId: string): CartDraft | null {
    return this.repository.getDraft(draftId);
  }
}
