import {
  createFauxCore,
  fauxAssistantMessage,
  fauxToolCall,
  type Model,
} from "@earendil-works/pi-ai";
import { streamSimple } from "@earendil-works/pi-ai/compat";
import type { StreamFn } from "@earendil-works/pi-agent-core";
import type { AgentRole, AgentRuntimeMode } from "./contracts.js";

export interface ReplayDirective {
  toolName: string;
  arguments: Record<string, unknown>;
}

export interface PiRuntimeBinding {
  model: Model<string>;
  streamFn: StreamFn;
  getApiKey?: (provider: string) => string | undefined;
}

export interface PiRuntimeDescription {
  mode: AgentRuntimeMode;
  provider: string;
  model: string;
  baseUrl: string | null;
  localOnly: boolean;
}

export interface PiRuntimeProbe extends PiRuntimeDescription {
  status: "up" | "down" | "offline";
  latencyMs: number;
  error: string | null;
}

export interface PiRuntimeFactory {
  readonly mode: AgentRuntimeMode;
  create(role: AgentRole, directive: ReplayDirective): PiRuntimeBinding;
  describe(role?: AgentRole): PiRuntimeDescription;
  probe(): Promise<PiRuntimeProbe>;
}

export class ReplayPiRuntimeFactory implements PiRuntimeFactory {
  readonly mode = "replay" as const;

  create(role: AgentRole, directive: ReplayDirective): PiRuntimeBinding {
    const faux = createFauxCore({
      api: `moyuan-replay-${role}`,
      provider: "moyuan-replay",
      models: [{ id: `replay-${role}`, name: `Replay ${role}` }],
    });
    faux.setResponses([
      fauxAssistantMessage(
        fauxToolCall(directive.toolName, directive.arguments, {
          id: `call-${role}`,
        }),
        { stopReason: "toolUse", timestamp: 0 },
      ),
      fauxAssistantMessage(`${role} artifact published`, {
        stopReason: "stop",
        timestamp: 0,
      }),
    ]);
    return {
      model: faux.getModel(),
      streamFn: faux.streamSimple,
    };
  }

  describe(role: AgentRole = "lead"): PiRuntimeDescription {
    return {
      mode: this.mode,
      provider: "moyuan-replay",
      model: `replay-${role}`,
      baseUrl: null,
      localOnly: true,
    };
  }

  async probe(): Promise<PiRuntimeProbe> {
    return {
      ...this.describe(),
      status: "offline",
      latencyMs: 0,
      error: null,
    };
  }
}

function normalizeBaseUrl(value: string): string {
  const trimmed = value.replace(/\/$/, "");
  return trimmed.endsWith("/v1") ? trimmed : `${trimmed}/v1`;
}

function roleEnvironmentName(role: AgentRole): string {
  return `MOYUAN_MODELPORT_MODEL_${role.toUpperCase()}`;
}

export class ModelPortPiRuntimeFactory implements PiRuntimeFactory {
  readonly mode = "modelport" as const;
  readonly #baseUrl: string;
  readonly #apiKey: string;
  readonly #defaultModel: string;
  readonly #environment: NodeJS.ProcessEnv;

  constructor(environment: NodeJS.ProcessEnv = process.env) {
    this.#environment = environment;
    this.#baseUrl = normalizeBaseUrl(
      environment.MOYUAN_MODELPORT_BASE_URL ?? "http://127.0.0.1:38082",
    );
    this.#apiKey = environment.MOYUAN_MODELPORT_API_KEY ?? "";
    this.#defaultModel = environment.MOYUAN_MODELPORT_MODEL ?? "moyuan-shoprec-agent";
    if (!this.#apiKey) {
      throw new Error("MOYUAN_MODELPORT_API_KEY is required for modelport mode");
    }
  }

  create(role: AgentRole, _directive: ReplayDirective): PiRuntimeBinding {
    const modelId = this.#environment[roleEnvironmentName(role)] ?? this.#defaultModel;
    const model: Model<"openai-completions"> = {
      id: modelId,
      name: `ModelPort ${role}`,
      api: "openai-completions",
      provider: "modelport",
      baseUrl: this.#baseUrl,
      reasoning: false,
      input: ["text"],
      cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
      contextWindow: 24_576,
      maxTokens: 768,
      headers: {
        "x-modelport-traffic-class": "business",
        "x-modelport-hybrid-mode": "local_strict",
        "x-modelport-data-classification": "internal",
        "x-modelport-agent-role": role,
      },
      compat: {
        supportsStore: false,
        maxTokensField: "max_completion_tokens",
        supportsDeveloperRole: false,
        supportsUsageInStreaming: true,
        supportsStrictMode: true,
      },
    };
    const boundedStream: StreamFn = (runtimeModel, context, options) =>
      streamSimple(runtimeModel, context, {
        ...options,
        maxTokens: 768,
        maxRetries: 0,
        timeoutMs: 45_000,
        toolChoice: "required",
      } as Parameters<typeof streamSimple>[2]);
    return {
      model,
      streamFn: boundedStream,
      getApiKey: () => this.#apiKey,
    };
  }

  describe(role: AgentRole = "lead"): PiRuntimeDescription {
    return {
      mode: this.mode,
      provider: "modelport",
      model: this.#environment[roleEnvironmentName(role)] ?? this.#defaultModel,
      baseUrl: this.#baseUrl,
      localOnly: true,
    };
  }

  async probe(): Promise<PiRuntimeProbe> {
    const startedAt = performance.now();
    try {
      const healthUrl = `${this.#baseUrl.replace(/\/v1$/, "")}/livez`;
      const response = await fetch(healthUrl, {
        signal: AbortSignal.timeout(2_000),
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      return {
        ...this.describe(),
        status: "up",
        latencyMs: Math.round((performance.now() - startedAt) * 10) / 10,
        error: null,
      };
    } catch (error) {
      return {
        ...this.describe(),
        status: "down",
        latencyMs: Math.round((performance.now() - startedAt) * 10) / 10,
        error: error instanceof Error ? error.message : "runtime probe failed",
      };
    }
  }
}
