import {
  isBuyerReply,
  type AgentRun,
  type MetricSnapshot,
  type QualityReport,
  type RunEvent,
  type RuntimeStatus,
} from "../types";
import {
  assertContract,
  type CreateRunRequest,
  type CreateRunResponse,
  type DomainPackRegistryResponse,
} from "../generated/contracts-v2";

const API_ERROR_MESSAGES: Record<string, string> = {
  idempotency_key_reused: "该操作与已提交请求冲突，请刷新后重试。",
  proposal_run_not_found: "原方案不存在或不属于当前会话，请重新生成方案。",
  proposal_run_not_confirmable: "原方案当前不可确认，请重新生成方案。",
  proposal_extension_mismatch: "确认请求与原方案的领域配置不一致，请回到原方案后重试。",
};

async function json<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, { credentials: "same-origin", ...init });
  if (!response.ok) {
    const detail = await response.text();
    let message = detail;
    try {
      const parsed = JSON.parse(detail) as { message?: string; error?: string };
      message = parsed.error && API_ERROR_MESSAGES[parsed.error]
        ? API_ERROR_MESSAGES[parsed.error]
        : parsed.message ?? parsed.error ?? detail;
    } catch {
      // Keep the plain response body when it is not JSON.
    }
    throw new Error(response.status >= 500
      ? `服务暂时不可用（${response.status}）。请检查终端日志和 /health。`
      : `请求未完成（${response.status}）：${message}`);
  }
  return response.json() as Promise<T>;
}

export async function createRun(
  message: string,
  confirmed = false,
  domainPackId?: string,
  proposalRunId?: string,
  idempotencyKey?: string,
): Promise<CreateRunResponse> {
  if (confirmed && !proposalRunId) {
    throw new Error("确认请求缺少原方案标识，请刷新后重试。");
  }
  const request: CreateRunRequest = confirmed
    ? {
        message,
        confirmed: true,
        proposalRunId: proposalRunId as string,
        ...(domainPackId ? { domainPackId } : {}),
      }
    : {
        message,
        ...(domainPackId ? { domainPackId } : {}),
      };
  const payload = await json<unknown>("/api/v2/runs", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "idempotency-key": idempotencyKey ?? (proposalRunId
        ? `confirm-${proposalRunId}`
        : crypto.randomUUID()),
    },
    body: JSON.stringify(request),
  });
  assertContract("CreateRunResponse", payload);
  return payload;
}

export async function getRun(runId: string): Promise<AgentRun> {
  const payload = await json<unknown>(`/api/v2/runs/${encodeURIComponent(runId)}`);
  assertContract("AgentRun", payload);
  if (payload.result !== undefined && !isBuyerReply(payload.result)) {
    throw new Error("Invalid AgentRun.result for the commerce decision console");
  }
  return payload as AgentRun;
}
export const getMetrics = () => json<MetricSnapshot>("/metrics");
export const getRuntime = () => json<RuntimeStatus>("/health");
export const getQuality = () => json<QualityReport>("/api/v2/quality");

export async function getDomainPacks(): Promise<DomainPackRegistryResponse> {
  const payload = await json<unknown>("/api/v2/domain-packs");
  assertContract("DomainPackRegistryResponse", payload);
  return payload;
}

export async function cancelRun(runId: string): Promise<void> {
  await json(`/api/v2/runs/${encodeURIComponent(runId)}/cancel`, { method: "POST" });
}

export async function getPreference(): Promise<{ personalizationEnabled: boolean }> {
  return json("/api/v2/preferences");
}

export async function setPreference(personalizationEnabled: boolean): Promise<void> {
  await json("/api/v2/preferences", {
    method: "PUT",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ personalizationEnabled }),
  });
}

export function watchRun(
  eventsUrl: string,
  onEvent: (event: RunEvent) => void,
  onTerminal: () => void,
  onError: (message: string) => void = () => {},
): () => void {
  const source = new EventSource(eventsUrl);
  const types = [
    "run_created", "run_started", "task", "artifact", "model_execution",
    "policy_gate", "result", "run_failed", "run_cancelled",
  ];
  const listener = (raw: MessageEvent<string>) => {
    try {
      const event: unknown = JSON.parse(raw.data);
      assertContract("AgentRunEvent", event);
      onEvent(event);
      if (["result", "run_failed", "run_cancelled"].includes(event.eventType)) {
        source.close();
        onTerminal();
      }
    } catch {
      onError("收到无法解析的运行事件，事件流将继续自动重试。");
    }
  };
  types.forEach((type) => source.addEventListener(type, listener as EventListener));
  source.onerror = () => {
    if (source.readyState === EventSource.CLOSED) {
      onError("运行事件流已断开，正在读取服务端最终状态。");
      onTerminal();
    }
  };
  return () => source.close();
}
