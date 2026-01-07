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
  BuyerTurnReply,
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

function nullableInteger(row: SqlRow, field: string): number | null {
  const value = row[field];
  return value === null ? null : integer(row, field);
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
    domainPackId: requiredString(row, "domain_pack_id"),
    workflowId: requiredString(row, "workflow_id"),
    status: requiredString(row, "status") as RunStatus,
    message: requiredString(row, "message"),
    confirmed: integer(row, "confirmed") === 1,
    proposalRunId: nullableString(row, "proposal_run_id"),
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
  event: AgentRunEvent | null;
}

export interface CreateRunInput {
  identity: IdentitySession;
  message: string;
  confirmed: boolean;
  idempotencyKey: string | null;
  domainPackId?: string;
  workflowId?: string;
  proposalRunId?: string | null;
  activeRunLimit?: number;
  retryExecutionCapacityGranted?: boolean;
}

function creationFingerprint(input: {
  message: string;
  confirmed: boolean;
  domainPackId: string;
  workflowId: string;
  proposalRunId: string | null;
}): string {
  return JSON.stringify({
    message: input.message,
    confirmed: input.confirmed,
    domainPackId: input.domainPackId,
    workflowId: input.workflowId,
    proposalRunId: input.proposalRunId,
  });
}

export class IdempotencyConflictError extends Error {
  constructor() {
    super("idempotency_key_reused");
    this.name = "IdempotencyConflictError";
  }
}

export class ConfirmationTargetError extends Error {
  constructor(readonly code: string) {
    super(code);
    this.name = "ConfirmationTargetError";
  }
}

export class ConcurrentRunLimitError extends Error {
  constructor() {
    super("concurrent_run_limit_exceeded");
    this.name = "ConcurrentRunLimitError";
  }
}

export class GlobalRunCapacityError extends Error {
  constructor() {
    super("global_run_capacity_exhausted");
    this.name = "GlobalRunCapacityError";
  }
}

export class RunLeaseUnavailableError extends Error {
  constructor(readonly retryAfterMs: number) {
    super("run_lease_unavailable");
    this.name = "RunLeaseUnavailableError";
  }
}

export class StaleRunLeaseError extends Error {
  constructor() {
    super("stale_run_lease");
    this.name = "StaleRunLeaseError";
  }
}

export interface ConfirmationFinalizationResult {
  status: "committed" | "replayed" | "stale";
  result?: BuyerTurnReply;
  event: AgentRunEvent | null;
}

export interface RunCancellationResult {
  run: AgentRun;
  event: AgentRunEvent | null;
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
        domain_pack_id TEXT NOT NULL DEFAULT 'normal-3c-v1',
        workflow_id TEXT NOT NULL DEFAULT 'commerce-decision-v1',
        status TEXT NOT NULL CHECK(status IN ('queued','running','completed','failed','cancelled')),
        message TEXT NOT NULL,
        confirmed INTEGER NOT NULL CHECK(confirmed IN (0,1)),
        proposal_run_id TEXT,
        idempotency_key TEXT,
        result_json TEXT,
        error_code TEXT,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL,
        cancel_requested INTEGER NOT NULL DEFAULT 0 CHECK(cancel_requested IN (0,1)),
        execution_owner TEXT,
        execution_lease_expires_ms INTEGER,
        execution_attempt INTEGER NOT NULL DEFAULT 0
      );
      CREATE UNIQUE INDEX IF NOT EXISTS runs_identity_idempotency
        ON runs(identity_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
      CREATE INDEX IF NOT EXISTS runs_identity_created
        ON runs(identity_id, created_at DESC);
      CREATE TABLE IF NOT EXISTS run_idempotency_keys (
        identity_id TEXT NOT NULL,
        idempotency_key TEXT NOT NULL,
        run_id TEXT NOT NULL REFERENCES runs(run_id) ON DELETE CASCADE,
        request_fingerprint TEXT NOT NULL,
        created_at TEXT NOT NULL,
        PRIMARY KEY(identity_id, idempotency_key)
      );
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
    const runColumns = new Set(
      (this.#db.prepare("PRAGMA table_info(runs)").all() as SqlRow[])
        .map((row) => requiredString(row, "name")),
    );
    if (!runColumns.has("domain_pack_id")) {
      this.#db.exec("ALTER TABLE runs ADD COLUMN domain_pack_id TEXT NOT NULL DEFAULT 'normal-3c-v1'");
    }
    if (!runColumns.has("workflow_id")) {
      this.#db.exec("ALTER TABLE runs ADD COLUMN workflow_id TEXT NOT NULL DEFAULT 'commerce-decision-v1'");
    }
    if (!runColumns.has("proposal_run_id")) {
      this.#db.exec("ALTER TABLE runs ADD COLUMN proposal_run_id TEXT");
    }
    if (!runColumns.has("execution_owner")) {
      this.#db.exec("ALTER TABLE runs ADD COLUMN execution_owner TEXT");
    }
    if (!runColumns.has("execution_lease_expires_ms")) {
      this.#db.exec("ALTER TABLE runs ADD COLUMN execution_lease_expires_ms INTEGER");
    }
    if (!runColumns.has("execution_attempt")) {
      this.#db.exec("ALTER TABLE runs ADD COLUMN execution_attempt INTEGER NOT NULL DEFAULT 0");
    }
    this.#db.exec(`
      CREATE UNIQUE INDEX IF NOT EXISTS runs_identity_proposal_confirmation
      ON runs(identity_id, proposal_run_id) WHERE proposal_run_id IS NOT NULL
    `);
    const legacyIdempotentRuns = this.#db.prepare(`
      SELECT runs.* FROM runs
      LEFT JOIN run_idempotency_keys
        ON run_idempotency_keys.identity_id = runs.identity_id
       AND run_idempotency_keys.idempotency_key = runs.idempotency_key
      WHERE runs.idempotency_key IS NOT NULL
        AND run_idempotency_keys.run_id IS NULL
    `).all() as SqlRow[];
    const insertIdempotencyAlias = this.#db.prepare(`
      INSERT OR IGNORE INTO run_idempotency_keys(
        identity_id, idempotency_key, run_id, request_fingerprint, created_at
      ) VALUES (?, ?, ?, ?, ?)
    `);
    for (const row of legacyIdempotentRuns) {
      const run = runFromRow(row);
      if (!run.idempotencyKey) continue;
      insertIdempotencyAlias.run(
        run.identityId,
        run.idempotencyKey,
        run.runId,
        creationFingerprint({
          message: run.message,
          confirmed: run.confirmed,
          domainPackId: run.domainPackId,
          workflowId: run.workflowId,
          proposalRunId: run.proposalRunId ?? null,
        }),
        run.createdAt,
      );
    }
  }

  close(): void {
    this.#db.close();
  }

  healthStatus(): { status: "up" | "down"; error: string | null } {
    let transactionStarted = false;
    try {
      // A read-only SELECT stays green when every Run write would fail. Probe a
      // short-lived write transaction with a readiness-sized timeout, without
      // changing durable data or waiting for the normal five-second busy limit.
      this.#db.exec("PRAGMA busy_timeout = 100");
      this.#db.exec("BEGIN IMMEDIATE");
      transactionStarted = true;
      const row = this.#db.prepare("SELECT 1 AS healthy").get() as SqlRow | undefined;
      if (row === undefined || integer(row, "healthy") !== 1) {
        return { status: "down", error: "storage_probe_failed" };
      }
      this.#db.exec("ROLLBACK");
      transactionStarted = false;
      return { status: "up", error: null };
    } catch (error) {
      return {
        status: "down",
        error: error instanceof Error ? error.name : "storage_probe_failed",
      };
    } finally {
      if (transactionStarted) {
        try {
          this.#db.exec("ROLLBACK");
        } catch {
          // The original storage error is the useful readiness signal.
        }
      }
      try {
        this.#db.exec("PRAGMA busy_timeout = 5000");
      } catch {
        // A closed or corrupt database is already reported as down above.
      }
    }
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
    this.#db.prepare("INSERT OR IGNORE INTO settings(key, value, updated_at) VALUES (?, ?, ?)")
      .run(key, value, new Date(this.now()).toISOString());
    const persisted = this.#db.prepare("SELECT value FROM settings WHERE key = ?").get(key) as
      | SqlRow
      | undefined;
    if (!persisted) throw new Error(`setting was not persisted: ${key}`);
    return requiredString(persisted, "value");
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

  createRun(input: CreateRunInput): CreateRunResult {
    return this.#createRun(input, false);
  }

  /** Create the Run and its first durable ledger event in one transaction. */
  createRunWithEvent(input: CreateRunInput): CreateRunResult {
    return this.#createRun(input, true);
  }

  #createRun(input: CreateRunInput, includeCreatedEvent: boolean): CreateRunResult {
    const domainPackId = input.domainPackId ?? "normal-3c-v1";
    const workflowId = input.workflowId ?? "commerce-decision-v1";
    const proposalRunId = input.proposalRunId ?? null;
    const requestFingerprint = creationFingerprint({
      message: input.message,
      confirmed: input.confirmed,
      domainPackId,
      workflowId,
      proposalRunId,
    });
    if (input.confirmed !== (proposalRunId !== null)) {
      throw new ConfirmationTargetError(
        input.confirmed ? "proposal_run_id_required" : "proposal_run_id_requires_confirmation",
      );
    }

    this.#db.exec("BEGIN IMMEDIATE");
    try {
      if (input.idempotencyKey) {
        const row = this.#db.prepare(`
          SELECT runs.*, run_idempotency_keys.request_fingerprint
          FROM run_idempotency_keys
          JOIN runs ON runs.run_id = run_idempotency_keys.run_id
          WHERE run_idempotency_keys.identity_id = ?
            AND run_idempotency_keys.idempotency_key = ?
        `).get(input.identity.identityId, input.idempotencyKey) as SqlRow | undefined;
        if (row) {
          const existing = runFromRow(row);
          if (requiredString(row, "request_fingerprint") !== requestFingerprint) {
            throw new IdempotencyConflictError();
          }
          this.#db.exec("COMMIT");
          return { run: existing, created: false, event: null };
        }
      }

      if (proposalRunId) {
        const existingRow = this.#db.prepare(`
          SELECT * FROM runs WHERE identity_id = ? AND proposal_run_id = ?
        `).get(input.identity.identityId, proposalRunId) as SqlRow | undefined;
        if (existingRow) {
          const existing = runFromRow(existingRow);
          if (
            !existing.confirmed ||
            existing.sessionId !== input.identity.sessionId ||
            existing.domainPackId !== domainPackId ||
            existing.workflowId !== workflowId
          ) throw new ConfirmationTargetError("proposal_extension_mismatch");
          if (
            ["failed", "cancelled"].includes(existing.status) &&
            this.#hasLivePendingProposal(
              input.identity.sessionId,
              input.identity.identityId,
              proposalRunId,
            )
          ) {
            // The manager's capacity snapshot is carried into this write
            // transaction. This closes the cross-process race where a Run can
            // become failed after replay classification but before retry reset.
            if (input.retryExecutionCapacityGranted === false) {
              throw new GlobalRunCapacityError();
            }
            this.#assertActiveRunCapacity(input.identity.identityId, input.activeRunLimit);
            const timestamp = new Date(this.now()).toISOString();
            const previousStatus = existing.status;
            const reset = this.#db.prepare(`
              UPDATE runs SET status = 'queued', result_json = NULL, error_code = NULL,
                updated_at = ?, cancel_requested = 0, execution_owner = NULL,
                execution_lease_expires_ms = NULL
              WHERE run_id = ? AND status IN ('failed','cancelled')
            `).run(timestamp, existing.runId);
            if (Number(reset.changes) !== 1) throw new Error("confirmation_retry_race");
            if (input.idempotencyKey) {
              this.#insertIdempotencyAlias(
                input.identity.identityId,
                input.idempotencyKey,
                existing.runId,
                requestFingerprint,
                timestamp,
              );
            }
            const event = includeCreatedEvent
              ? this.#insertEvent(existing.runId, "run_created", {
                  status: "queued",
                  confirmed: true,
                  domainPackId,
                  workflowId,
                  proposalRunId,
                  retry: true,
                  previousStatus,
                }, null, null, timestamp)
              : null;
            const retried = this.getRun(existing.runId);
            if (!retried) throw new Error(`run not found: ${existing.runId}`);
            this.#db.exec("COMMIT");
            return { run: retried, created: true, event };
          }
          if (input.idempotencyKey) {
            this.#insertIdempotencyAlias(
              input.identity.identityId,
              input.idempotencyKey,
              existing.runId,
              requestFingerprint,
              new Date(this.now()).toISOString(),
            );
          }
          this.#db.exec("COMMIT");
          return { run: existing, created: false, event: null };
        }
        const proposalRow = this.#db.prepare(`
          SELECT * FROM runs WHERE run_id = ? AND identity_id = ? AND session_id = ?
        `).get(
          proposalRunId,
          input.identity.identityId,
          input.identity.sessionId,
        ) as SqlRow | undefined;
        if (!proposalRow) throw new ConfirmationTargetError("proposal_run_not_found");
        const proposal = runFromRow(proposalRow);
        const proposalResult = proposal.result as { phase?: unknown } | undefined;
        if (
          proposal.confirmed ||
          proposal.status !== "completed" ||
          proposalResult?.phase !== "proposal"
        ) throw new ConfirmationTargetError("proposal_run_not_confirmable");
        if (
          proposal.domainPackId !== domainPackId ||
          proposal.workflowId !== workflowId
        ) throw new ConfirmationTargetError("proposal_extension_mismatch");
      }

      this.#assertActiveRunCapacity(input.identity.identityId, input.activeRunLimit);
      const timestamp = new Date(this.now()).toISOString();
      const run: AgentRun = {
        runId: `run_${randomUUID()}`,
        identityId: input.identity.identityId,
        sessionId: input.identity.sessionId,
        domainPackId,
        workflowId,
        status: "queued",
        message: input.message,
        confirmed: input.confirmed,
        proposalRunId,
        idempotencyKey: input.idempotencyKey,
        errorCode: null,
        createdAt: timestamp,
        updatedAt: timestamp,
        cancelRequested: false,
      };
      this.#db.prepare(`
        INSERT INTO runs(
          run_id, identity_id, session_id, domain_pack_id, workflow_id, status, message,
          confirmed, proposal_run_id, idempotency_key, result_json, error_code,
          created_at, updated_at, cancel_requested
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, 0)
      `).run(
        run.runId,
        run.identityId,
        run.sessionId,
        run.domainPackId,
        run.workflowId,
        run.status,
        run.message,
        run.confirmed ? 1 : 0,
        run.proposalRunId ?? null,
        run.idempotencyKey ?? null,
        run.createdAt,
        run.updatedAt,
      );
      if (run.idempotencyKey) {
        this.#insertIdempotencyAlias(
          run.identityId,
          run.idempotencyKey,
          run.runId,
          requestFingerprint,
          timestamp,
        );
      }
      const event = includeCreatedEvent
        ? this.#insertEvent(run.runId, "run_created", {
            status: run.status,
            confirmed: run.confirmed,
            domainPackId: run.domainPackId,
            workflowId: run.workflowId,
            proposalRunId: run.proposalRunId ?? null,
          }, null, null, timestamp)
        : null;
      this.#db.exec("COMMIT");
      return { run, created: true, event };
    } catch (error) {
      this.#db.exec("ROLLBACK");
      throw error;
    }
  }

  #assertActiveRunCapacity(identityId: string, limit?: number): void {
    if (limit === undefined) return;
    if (!Number.isInteger(limit) || limit < 1) {
      throw new Error("activeRunLimit must be a positive integer");
    }
    const row = this.#db.prepare(`
      SELECT COUNT(*) AS count FROM runs
      WHERE identity_id = ? AND status IN ('queued','running')
    `).get(identityId) as SqlRow;
    if (integer(row, "count") >= limit) throw new ConcurrentRunLimitError();
  }

  #hasLivePendingProposal(
    sessionId: string,
    identityId: string,
    proposalRunId: string,
  ): boolean {
    const row = this.#db.prepare(`
      SELECT decision_json, expires_at_ms FROM pending_decisions
      WHERE session_id = ? AND identity_id = ?
    `).get(sessionId, identityId) as SqlRow | undefined;
    if (!row || integer(row, "expires_at_ms") <= this.now()) return false;
    const decision = parseJson<{ runId?: unknown }>(requiredString(row, "decision_json"));
    return decision.runId === proposalRunId;
  }

  #insertIdempotencyAlias(
    identityId: string,
    idempotencyKey: string,
    runId: string,
    requestFingerprint: string,
    timestamp: string,
  ): void {
    this.#db.prepare(`
      INSERT INTO run_idempotency_keys(
        identity_id, idempotency_key, run_id, request_fingerprint, created_at
      ) VALUES (?, ?, ?, ?, ?)
    `).run(identityId, idempotencyKey, runId, requestFingerprint, timestamp);
  }

  getRun(runId: string, identityId?: string): AgentRun | null {
    const row = (identityId
      ? this.#db.prepare("SELECT * FROM runs WHERE run_id = ? AND identity_id = ?").get(runId, identityId)
      : this.#db.prepare("SELECT * FROM runs WHERE run_id = ?").get(runId)) as SqlRow | undefined;
    return row ? runFromRow(row) : null;
  }

  findRunByIdempotency(identityId: string, idempotencyKey: string): AgentRun | null {
    const row = this.#db.prepare(`
      SELECT runs.* FROM run_idempotency_keys
      JOIN runs ON runs.run_id = run_idempotency_keys.run_id
      WHERE run_idempotency_keys.identity_id = ?
        AND run_idempotency_keys.idempotency_key = ?
    `).get(identityId, idempotencyKey) as SqlRow | undefined;
    return row ? runFromRow(row) : null;
  }

  findConfirmationByProposal(identityId: string, proposalRunId: string): AgentRun | null {
    const row = this.#db.prepare(`
      SELECT * FROM runs WHERE identity_id = ? AND proposal_run_id = ?
    `).get(identityId, proposalRunId) as SqlRow | undefined;
    return row ? runFromRow(row) : null;
  }

  confirmationRetryNeedsExecution(identityId: string, proposalRunId: string): boolean {
    const existing = this.findConfirmationByProposal(identityId, proposalRunId);
    if (!existing || !["failed", "cancelled"].includes(existing.status)) return false;
    return this.getPending(existing.sessionId, identityId)?.decision.runId === proposalRunId;
  }

  listIncompleteRuns(): AgentRun[] {
    return (this.#db.prepare(`
      SELECT * FROM runs WHERE status IN ('queued', 'running') ORDER BY created_at
    `).all() as SqlRow[]).map(runFromRow);
  }

  listClaimableRuns(): AgentRun[] {
    return (this.#db.prepare(`
      SELECT * FROM runs
      WHERE status = 'queued'
         OR (
           status = 'running'
           AND (execution_owner IS NULL OR execution_lease_expires_ms IS NULL
                OR execution_lease_expires_ms <= ?)
         )
      ORDER BY created_at
    `).all(this.now()) as SqlRow[]).map(runFromRow);
  }

  nextRunClaimDelay(): number | null {
    const row = this.#db.prepare(`
      SELECT MIN(execution_lease_expires_ms) AS next_expiry
      FROM runs
      WHERE status = 'running' AND execution_lease_expires_ms > ?
    `).get(this.now()) as SqlRow;
    const expiry = nullableInteger(row, "next_expiry");
    return expiry === null ? null : Math.max(1, expiry - this.now());
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

  /** Claim a queued/expired Run and persist its running transition atomically. */
  startRun(
    runId: string,
    resumed: boolean,
    executionOwner: string,
    leaseMs: number,
  ): AgentRunEvent {
    let transactionStarted = false;
    this.#db.exec("BEGIN IMMEDIATE");
    transactionStarted = true;
    try {
      // Read the lease clock only after BEGIN IMMEDIATE has acquired the write
      // lock. Otherwise lock wait time can consume a short lease before the
      // claim is even committed.
      const now = this.now();
      const timestamp = new Date(now).toISOString();
      const row = this.#db.prepare("SELECT * FROM runs WHERE run_id = ?").get(runId) as
        | SqlRow
        | undefined;
      if (!row) throw new Error(`run not found: ${runId}`);
      const status = requiredString(row, "status");
      const leaseExpiry = nullableInteger(row, "execution_lease_expires_ms");
      const claimable = integer(row, "cancel_requested") === 0 && (
        status === "queued" ||
        (
          status === "running" &&
          (nullableString(row, "execution_owner") === null || leaseExpiry === null || leaseExpiry <= now)
        )
      );
      if (!claimable) {
        this.#db.exec("ROLLBACK");
        transactionStarted = false;
        throw new RunLeaseUnavailableError(
          leaseExpiry === null ? leaseMs : Math.max(1, leaseExpiry - now),
        );
      }
      const attempt = integer(row, "execution_attempt") + 1;
      this.#db.prepare(`
        UPDATE runs SET status = 'running', updated_at = ?, execution_owner = ?,
          execution_lease_expires_ms = ?, execution_attempt = ?
        WHERE run_id = ?
      `).run(timestamp, executionOwner, now + leaseMs, attempt, runId);
      const event = this.#insertEvent(
        runId,
        "run_started",
        { resumed, executionAttempt: attempt },
        null,
        null,
        timestamp,
      );
      this.#db.exec("COMMIT");
      transactionStarted = false;
      return event;
    } catch (error) {
      if (transactionStarted) this.#db.exec("ROLLBACK");
      throw error;
    }
  }

  renewRunLease(runId: string, executionOwner: string, leaseMs: number): boolean {
    let transactionStarted = false;
    this.#db.exec("BEGIN IMMEDIATE");
    transactionStarted = true;
    try {
      const renewed = this.#renewRunLeaseLocked(runId, executionOwner, leaseMs);
      this.#db.exec("COMMIT");
      transactionStarted = false;
      return renewed;
    } catch (error) {
      if (transactionStarted) this.#db.exec("ROLLBACK");
      throw error;
    }
  }

  #renewRunLeaseLocked(runId: string, executionOwner: string, leaseMs: number): boolean {
    const now = this.now();
    const updated = this.#db.prepare(`
      UPDATE runs SET execution_lease_expires_ms = ?
      WHERE run_id = ? AND status = 'running' AND execution_owner = ?
        AND execution_lease_expires_ms > ? AND cancel_requested = 0
    `).run(now + leaseMs, runId, executionOwner, now);
    return Number(updated.changes) === 1;
  }

  /** Persist the terminal Run state and its terminal ledger event atomically. */
  finalizeRun(
    runId: string,
    update: { status: RunStatus; result?: unknown; errorCode?: string | null },
    eventType: RunEventType,
    payload: Record<string, unknown>,
    executionOwner?: string,
  ): AgentRunEvent {
    if (!["completed", "failed", "cancelled"].includes(update.status)) {
      throw new Error(`Run finalization requires a terminal status: ${update.status}`);
    }
    const timestamp = new Date(this.now()).toISOString();
    this.#db.exec("BEGIN IMMEDIATE");
    try {
      const values = [
        update.status,
        update.result === undefined ? null : JSON.stringify(update.result),
        update.errorCode ?? null,
        timestamp,
        runId,
      ] as SqlValue[];
      const updated = this.#db.prepare(`
        UPDATE runs SET status = ?, result_json = ?, error_code = ?, updated_at = ?,
          execution_owner = NULL, execution_lease_expires_ms = NULL
        WHERE run_id = ? AND status IN ('queued','running')
        ${executionOwner ? "AND execution_owner = ? AND execution_lease_expires_ms > ?" : ""}
        ${executionOwner && update.status !== "cancelled" ? "AND cancel_requested = 0" : ""}
      `).run(
        ...values,
        ...(executionOwner ? [executionOwner, this.now()] : []),
      );
      if (Number(updated.changes) !== 1) {
        if (executionOwner) throw new StaleRunLeaseError();
        throw new Error("run_already_terminal");
      }
      const event = this.#insertEvent(
        runId,
        eventType,
        payload,
        null,
        null,
        timestamp,
      );
      this.#db.exec("COMMIT");
      return event;
    } catch (error) {
      this.#db.exec("ROLLBACK");
      throw error;
    }
  }

  requestCancellation(runId: string, identityId: string): AgentRun | null {
    this.#db.prepare(`
      UPDATE runs SET cancel_requested = 1, updated_at = ?
      WHERE run_id = ? AND identity_id = ? AND status IN ('queued','running')
    `).run(new Date(this.now()).toISOString(), runId, identityId);
    return this.getRun(runId, identityId);
  }

  /** Make cancellation durable immediately; a late worker is fenced from writing afterwards. */
  cancelRun(runId: string, identityId: string): RunCancellationResult | null {
    const timestamp = new Date(this.now()).toISOString();
    this.#db.exec("BEGIN IMMEDIATE");
    try {
      const row = this.#db.prepare(`
        SELECT * FROM runs WHERE run_id = ? AND identity_id = ?
      `).get(runId, identityId) as SqlRow | undefined;
      if (!row) {
        this.#db.exec("COMMIT");
        return null;
      }
      const current = runFromRow(row);
      if (["completed", "failed", "cancelled"].includes(current.status)) {
        this.#db.exec("COMMIT");
        return { run: current, event: null };
      }
      const updated = this.#db.prepare(`
        UPDATE runs SET status = 'cancelled', cancel_requested = 1,
          error_code = 'run_cancelled', updated_at = ?, execution_owner = NULL,
          execution_lease_expires_ms = NULL
        WHERE run_id = ? AND identity_id = ? AND status IN ('queued','running')
      `).run(timestamp, runId, identityId);
      if (Number(updated.changes) !== 1) throw new Error("run_cancellation_race");
      const event = this.#insertEvent(
        runId,
        "run_cancelled",
        { reason: "client_requested" },
        null,
        null,
        timestamp,
      );
      const cancelled = this.getRun(runId, identityId);
      if (!cancelled) throw new Error(`run not found: ${runId}`);
      this.#db.exec("COMMIT");
      return { run: cancelled, event };
    } catch (error) {
      this.#db.exec("ROLLBACK");
      throw error;
    }
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
      const event = this.#insertEvent(runId, eventType, payload, taskId, parentTaskId);
      this.#db.exec("COMMIT");
      return event;
    } catch (error) {
      this.#db.exec("ROLLBACK");
      throw error;
    }
  }

  #insertEvent(
    runId: string,
    eventType: RunEventType,
    payload: Record<string, unknown>,
    taskId: string | null,
    parentTaskId: string | null,
    timestamp = new Date(this.now()).toISOString(),
  ): AgentRunEvent {
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
      timestamp,
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
    return event;
  }

  /** Repair terminal Runs written by older versions before state/event finalization was atomic. */
  ensureTerminalEvent(runId: string): AgentRunEvent | null {
    this.#db.exec("BEGIN IMMEDIATE");
    try {
      const row = this.#db.prepare("SELECT * FROM runs WHERE run_id = ?").get(runId) as
        | SqlRow
        | undefined;
      if (!row) {
        this.#db.exec("COMMIT");
        return null;
      }
      const run = runFromRow(row);
      if (!["completed", "failed", "cancelled"].includes(run.status)) {
        this.#db.exec("COMMIT");
        return null;
      }
      const attemptRow = this.#db.prepare(`
        SELECT MAX(sequence) AS attempt_start FROM run_events
        WHERE run_id = ? AND event_type = 'run_created'
      `).get(runId) as SqlRow;
      const attemptStart = nullableInteger(attemptRow, "attempt_start") ?? 1;
      const existing = this.#db.prepare(`
        SELECT 1 AS found FROM run_events
        WHERE run_id = ? AND event_type IN ('result','run_failed','run_cancelled')
          AND sequence >= ?
        LIMIT 1
      `).get(runId, attemptStart) as SqlRow | undefined;
      if (existing) {
        this.#db.exec("COMMIT");
        return null;
      }
      const result = run.result as { phase?: unknown } | undefined;
      const eventType: RunEventType = run.status === "completed"
        ? "result"
        : run.status === "failed" ? "run_failed" : "run_cancelled";
      const payload = run.status === "completed"
        ? {
            phase: typeof result?.phase === "string" ? result.phase : "unknown",
            result: run.result ?? null,
            recoveredTerminalEvent: true,
          }
        : run.status === "failed"
          ? { errorCode: run.errorCode, recoveredTerminalEvent: true }
          : { reason: "recovered_terminal_state", recoveredTerminalEvent: true };
      const event = this.#insertEvent(runId, eventType, payload, null, null);
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
    this.ensureTerminalEvent(runId);
    return (this.#db.prepare(`
      SELECT * FROM run_events WHERE run_id = ? AND sequence > ? ORDER BY sequence
    `).all(runId, afterSequence) as SqlRow[]).map(eventFromRow);
  }

  /**
   * Replay only the current execution attempt. A retried business operation
   * keeps its older terminal events for audit, but those events must not close
   * a fresh SSE stream before the retry's result arrives.
   */
  listCurrentAttemptEvents(
    runId: string,
    identityId: string,
    afterSequence = 0,
  ): AgentRunEvent[] {
    const run = this.getRun(runId, identityId);
    if (!run) return [];
    this.ensureTerminalEvent(runId);
    const row = this.#db.prepare(`
      SELECT MAX(sequence) AS attempt_start FROM run_events
      WHERE run_id = ? AND event_type = 'run_created'
    `).get(runId) as SqlRow;
    const attemptStart = nullableInteger(row, "attempt_start");
    const effectiveCursor = attemptStart === null
      ? afterSequence
      : Math.max(afterSequence, attemptStart - 1);
    return (this.#db.prepare(`
      SELECT * FROM run_events WHERE run_id = ? AND sequence > ? ORDER BY sequence
    `).all(runId, effectiveCursor) as SqlRow[]).map(eventFromRow);
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

  /** Persist trace projections and their durable ledger event in one transaction. */
  persistTraceAndEvent(
    runId: string,
    trace: AgentTraceRecord,
    eventType: RunEventType,
    payload: Record<string, unknown>,
    taskId: string | null = null,
    parentTaskId: string | null = null,
    executionOwner?: string,
    leaseMs = 30_000,
  ): AgentRunEvent {
    this.#db.exec("BEGIN IMMEDIATE");
    try {
      if (executionOwner && !this.#renewRunLeaseLocked(runId, executionOwner, leaseMs)) {
        throw new StaleRunLeaseError();
      }
      this.#persistTrace(runId, trace);
      const event = this.#insertEvent(
        runId,
        eventType,
        payload,
        taskId,
        parentTaskId,
      );
      this.#db.exec("COMMIT");
      return event;
    } catch (error) {
      this.#db.exec("ROLLBACK");
      throw error;
    }
  }

  persistTrace(runId: string, trace: AgentTraceRecord): void {
    this.#persistTrace(runId, trace);
  }

  #persistTrace(runId: string, trace: AgentTraceRecord): void {
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

  /**
   * Publish/clear the pending proposal and commit the decision Run atomically.
   * Run row order is the durable proposal generation: a slower older Run may
   * finish later, but it cannot overwrite or delete a newer completed decision.
   */
  finalizeDecisionRun(
    runId: string,
    result: BuyerTurnReply,
    executionOwner?: string,
    pendingTtlMs = 15 * 60 * 1_000,
  ): ConfirmationFinalizationResult {
    const timestamp = new Date(this.now()).toISOString();
    this.#db.exec("BEGIN IMMEDIATE");
    try {
      const row = this.#db.prepare(`
        SELECT rowid AS run_ordinal, * FROM runs WHERE run_id = ?
      `).get(runId) as SqlRow | undefined;
      if (!row) throw new Error(`run not found: ${runId}`);
      const run = runFromRow(row);
      if (run.status === "completed") {
        const canonical = run.result as BuyerTurnReply | undefined;
        if (!canonical || typeof canonical.phase !== "string") {
          throw new Error("completed_decision_missing_result");
        }
        this.#db.exec("COMMIT");
        return { status: "replayed", result: canonical, event: null };
      }
      if (
        executionOwner &&
        (
          nullableString(row, "execution_owner") !== executionOwner ||
          (nullableInteger(row, "execution_lease_expires_ms") ?? 0) <= this.now()
        )
      ) throw new StaleRunLeaseError();
      if (
        !["queued", "running"].includes(run.status) ||
        run.confirmed ||
        run.cancelRequested
      ) throw new StaleRunLeaseError();
      if (
        !result.decision ||
        result.decision.runId !== run.runId ||
        result.decision.domainPackId !== run.domainPackId ||
        result.decision.workflowId !== run.workflowId
      ) throw new Error("decision_result_extension_mismatch");

      const runOrdinal = integer(row, "run_ordinal");
      if (this.#mayMutatePendingForRun(run, runOrdinal)) {
        if (result.phase === "proposal") {
          this.putPending(
            run.sessionId,
            run.identityId,
            result.decision,
            pendingTtlMs,
          );
        } else {
          this.#db.prepare(`
            DELETE FROM pending_decisions WHERE session_id = ? AND identity_id = ?
          `).run(run.sessionId, run.identityId);
        }
      }
      this.persistResultConstraints(run, result);
      const updated = this.#db.prepare(`
        UPDATE runs SET status = 'completed', result_json = ?, error_code = NULL,
          updated_at = ?, execution_owner = NULL, execution_lease_expires_ms = NULL
        WHERE run_id = ? AND status IN ('queued','running') AND cancel_requested = 0
        ${executionOwner ? "AND execution_owner = ? AND execution_lease_expires_ms > ?" : ""}
      `).run(
        JSON.stringify(result),
        timestamp,
        runId,
        ...(executionOwner ? [executionOwner, this.now()] : []),
      );
      if (Number(updated.changes) !== 1) throw new StaleRunLeaseError();
      const event = this.#insertEvent(runId, "result", {
        phase: result.phase,
        result,
      }, null, null, timestamp);
      this.#db.exec("COMMIT");
      return { status: "committed", result, event };
    } catch (error) {
      this.#db.exec("ROLLBACK");
      throw error;
    }
  }

  #mayMutatePendingForRun(run: AgentRun, runOrdinal: number): boolean {
    const newerCompleted = this.#db.prepare(`
      SELECT MAX(rowid) AS newest_ordinal FROM runs
      WHERE identity_id = ? AND session_id = ? AND confirmed = 0
        AND status = 'completed'
    `).get(run.identityId, run.sessionId) as SqlRow;
    const newestOrdinal = nullableInteger(newerCompleted, "newest_ordinal");
    if (newestOrdinal !== null && newestOrdinal > runOrdinal) return false;

    const pendingRow = this.#db.prepare(`
      SELECT decision_json, expires_at_ms FROM pending_decisions
      WHERE session_id = ? AND identity_id = ?
    `).get(run.sessionId, run.identityId) as SqlRow | undefined;
    if (!pendingRow || integer(pendingRow, "expires_at_ms") <= this.now()) return true;
    const pendingDecision = parseJson<{ runId?: unknown }>(
      requiredString(pendingRow, "decision_json"),
    );
    if (pendingDecision.runId === run.runId) return true;
    if (typeof pendingDecision.runId !== "string") return true;
    const source = this.#db.prepare(`
      SELECT rowid AS source_ordinal FROM runs
      WHERE run_id = ? AND identity_id = ? AND session_id = ?
    `).get(pendingDecision.runId, run.identityId, run.sessionId) as SqlRow | undefined;
    return !source || integer(source, "source_ordinal") <= runOrdinal;
  }

  /**
   * Atomically consume the exact pending proposal and optionally create its draft.
   * Used by the legacy synchronous API, which has no durable outer Run to finalize.
   */
  commitConfirmation(
    pending: PendingDecision,
    result: BuyerTurnReply,
  ): ConfirmationFinalizationResult {
    this.#db.exec("BEGIN IMMEDIATE");
    try {
      if (!this.#pendingMatches(pending)) {
        this.#db.exec("COMMIT");
        return { status: "stale", event: null };
      }
      if (result.cartDraft) this.putDraft(result.cartDraft);
      if (!this.#deletePendingIfUnchanged(pending)) {
        throw new Error("pending_decision_changed_during_confirmation");
      }
      this.#db.exec("COMMIT");
      return { status: "committed", result, event: null };
    } catch (error) {
      this.#db.exec("ROLLBACK");
      throw error;
    }
  }

  /**
   * Commit the confirmation business effect and terminal Run ledger atomically.
   * Quote/model work is deliberately completed before entering this short transaction.
   */
  finalizeConfirmationRun(
    runId: string,
    pending: PendingDecision,
    result: BuyerTurnReply,
    executionOwner?: string,
  ): ConfirmationFinalizationResult {
    const timestamp = new Date(this.now()).toISOString();
    this.#db.exec("BEGIN IMMEDIATE");
    try {
      const row = this.#db.prepare("SELECT * FROM runs WHERE run_id = ?").get(runId) as
        | SqlRow
        | undefined;
      if (!row) throw new Error(`run not found: ${runId}`);
      const run = runFromRow(row);
      if (run.status === "completed") {
        const canonical = run.result as BuyerTurnReply | undefined;
        if (!canonical || typeof canonical.phase !== "string") {
          throw new Error("completed_confirmation_missing_result");
        }
        this.#db.exec("COMMIT");
        return { status: "replayed", result: canonical, event: null };
      }
      if (
        executionOwner &&
        (
          nullableString(row, "execution_owner") !== executionOwner ||
          (nullableInteger(row, "execution_lease_expires_ms") ?? 0) <= this.now()
        )
      ) throw new StaleRunLeaseError();
      if (
        run.status !== "running" ||
        !run.confirmed ||
        run.cancelRequested ||
        run.sessionId !== pending.sessionId ||
        run.identityId !== pending.userId ||
        run.proposalRunId !== pending.decision.runId ||
        run.domainPackId !== pending.decision.domainPackId ||
        run.workflowId !== pending.decision.workflowId
      ) {
        this.#db.exec("COMMIT");
        return { status: "stale", event: null };
      }
      if (!this.#pendingMatches(pending)) {
        this.#db.exec("COMMIT");
        return { status: "stale", event: null };
      }
      if (
        !result.decision ||
        result.decision.runId !== pending.decision.runId ||
        result.decision.domainPackId !== run.domainPackId ||
        result.decision.workflowId !== run.workflowId ||
        (result.cartDraft !== null && result.cartDraft.sessionId !== run.sessionId)
      ) throw new Error("confirmation_result_extension_mismatch");

      if (result.cartDraft) this.putDraft(result.cartDraft);
      if (!this.#deletePendingIfUnchanged(pending)) {
        throw new Error("pending_decision_changed_during_confirmation");
      }
      this.persistResultConstraints(run, result);
      const updated = this.#db.prepare(`
        UPDATE runs SET status = 'completed', result_json = ?, error_code = NULL, updated_at = ?,
          execution_owner = NULL, execution_lease_expires_ms = NULL
        WHERE run_id = ? AND status = 'running' AND cancel_requested = 0
        ${executionOwner ? "AND execution_owner = ? AND execution_lease_expires_ms > ?" : ""}
      `).run(
        JSON.stringify(result),
        timestamp,
        runId,
        ...(executionOwner ? [executionOwner, this.now()] : []),
      );
      if (Number(updated.changes) !== 1) {
        if (executionOwner) throw new StaleRunLeaseError();
        throw new Error("confirmation_run_changed");
      }
      const event = this.#insertEvent(runId, "result", {
        phase: result.phase,
        result,
      }, null, null, timestamp);
      this.#db.exec("COMMIT");
      return { status: "committed", result, event };
    } catch (error) {
      this.#db.exec("ROLLBACK");
      throw error;
    }
  }

  #pendingMatches(pending: PendingDecision): boolean {
    const row = this.#db.prepare(`
      SELECT decision_json, expires_at_ms FROM pending_decisions
      WHERE session_id = ? AND identity_id = ?
    `).get(pending.sessionId, pending.userId) as SqlRow | undefined;
    return Boolean(
      row &&
      integer(row, "expires_at_ms") > this.now() &&
      requiredString(row, "decision_json") === JSON.stringify(pending.decision),
    );
  }

  #deletePendingIfUnchanged(pending: PendingDecision): boolean {
    const deleted = this.#db.prepare(`
      DELETE FROM pending_decisions
      WHERE session_id = ? AND identity_id = ? AND decision_json = ? AND expires_at_ms > ?
    `).run(
      pending.sessionId,
      pending.userId,
      JSON.stringify(pending.decision),
      this.now(),
    );
    return Number(deleted.changes) === 1;
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
    const now = this.now();
    // Delete by the expiry predicate before reading. SQLite serializes this
    // statement with concurrent proposal publication, so a fresh replacement
    // can never be removed based on a stale snapshot.
    this.#db.prepare(`
      DELETE FROM pending_decisions
      WHERE session_id = ? AND identity_id = ? AND expires_at_ms <= ?
    `).run(sessionId, identityId, now);
    const row = this.#db.prepare(`
      SELECT * FROM pending_decisions WHERE session_id = ? AND identity_id = ?
    `).get(sessionId, identityId) as SqlRow | undefined;
    if (!row) return null;
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
