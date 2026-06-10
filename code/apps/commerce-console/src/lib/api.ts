import type { AgentRun, MetricSnapshot, QualityReport, RunEvent, RuntimeStatus } from "../types";

async function json<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, { credentials: "same-origin", ...init });
  if (!response.ok) {
    const detail = await response.text();
    let message = detail;
    try {
      const parsed = JSON.parse(detail) as { message?: string; error?: string };
      message = parsed.message ?? parsed.error ?? detail;
    } catch {
      // Keep the plain response body when it is not JSON.
    }
    throw new Error(response.status >= 500
      ? `服务暂时不可用（${response.status}）。请检查终端日志和 /health。`
      : `请求未完成（${response.status}）：${message}`);
  }
  return response.json() as Promise<T>;
}

export async function createRun(message: string, confirmed = false): Promise<{
  runId: string;
  eventsUrl: string;
}> {
  return json("/api/v2/runs", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "idempotency-key": crypto.randomUUID(),
    },
    body: JSON.stringify({ message, confirmed }),
  });
}

export const getRun = (runId: string) => json<AgentRun>(`/api/v2/runs/${encodeURIComponent(runId)}`);
export const getMetrics = () => json<MetricSnapshot>("/metrics");
export const getRuntime = () => json<RuntimeStatus>("/health");
export const getQuality = () => json<QualityReport>("/api/v2/quality");

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
      const event = JSON.parse(raw.data) as RunEvent;
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
