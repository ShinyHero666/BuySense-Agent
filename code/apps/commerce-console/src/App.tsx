import { lazy, Suspense, useCallback, useEffect, useRef, useState } from "react";
import { Shell } from "./components/Shell";
import { LearningGuide } from "./components/LearningGuide";
import { cancelRun, createRun, getMetrics, getPreference, getQuality, getRun, getRuntime, setPreference, watchRun } from "./lib/api";
import type { AgentRun, MetricSnapshot, QualityReport, RunEvent, RuntimeStatus, ViewName } from "./types";

const DecisionView = lazy(() => import("./views/DecisionView").then((module) => ({ default: module.DecisionView })));
const CollaborationView = lazy(() => import("./views/CollaborationView").then((module) => ({ default: module.CollaborationView })));
const QualityView = lazy(() => import("./views/QualityView").then((module) => ({ default: module.QualityView })));
const LAST_RUN_KEY = "moyuan-last-run-id";

export default function App() {
  const [guideOpen, setGuideOpen] = useState(() => {
    try {
      return localStorage.getItem("moyuan-learning-guide") !== "dismissed";
    } catch {
      return true;
    }
  });
  const [view, setView] = useState<ViewName>("decision");
  const [run, setRun] = useState<AgentRun | null>(null);
  const [events, setEvents] = useState<RunEvent[]>([]);
  const [metrics, setMetrics] = useState<MetricSnapshot | null>(null);
  const [runtime, setRuntime] = useState<RuntimeStatus | null>(null);
  const [quality, setQuality] = useState<QualityReport | null>(null);
  const [personalization, setPersonalizationState] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const closeStream = useRef<() => void>(() => {});

  const refresh = useCallback(async (runId?: string) => {
    const statusRequests = await Promise.allSettled([getMetrics(), getRuntime(), getQuality()]);
    if (statusRequests[0].status === "fulfilled") setMetrics(statusRequests[0].value);
    if (statusRequests[1].status === "fulfilled") setRuntime(statusRequests[1].value);
    if (statusRequests[2].status === "fulfilled") setQuality(statusRequests[2].value);
    if (runId) setRun(await getRun(runId));
    if (!runId && statusRequests.every((request) => request.status === "rejected")) {
      const failure = statusRequests.find(
        (request): request is PromiseRejectedResult => request.status === "rejected",
      );
      throw failure?.reason ?? new Error("服务状态读取失败");
    }
  }, []);

  const connectRun = useCallback((runId: string, eventsUrl: string) => {
    closeStream.current();
    closeStream.current = watchRun(
      eventsUrl,
      (event) => setEvents((current) => current.some((item) => item.eventId === event.eventId)
        ? current
        : [...current, event]),
      () => void refresh(runId).catch((reason) => setError(
        reason instanceof Error ? reason.message : "无法读取最终运行状态",
      )),
      setError,
    );
  }, [refresh]);

  useEffect(() => {
    const initialize = async () => {
      const initialized = await Promise.allSettled([
        refresh(),
        getPreference().then((value) => setPersonalizationState(value.personalizationEnabled)),
      ]);
      if (initialized.every((result) => result.status === "rejected")) {
        const failure = initialized[0];
        throw failure.status === "rejected" ? failure.reason : new Error("初始化失败");
      }
      let lastRunId: string | null = null;
      try {
        lastRunId = localStorage.getItem(LAST_RUN_KEY);
      } catch {
        // The console remains usable when browser storage is unavailable.
      }
      if (!lastRunId) return;
      try {
        const restored = await getRun(lastRunId);
        setRun(restored);
        if (restored.status === "queued" || restored.status === "running") {
          connectRun(lastRunId, `/api/v2/runs/${encodeURIComponent(lastRunId)}/events`);
        }
      } catch {
        try {
          localStorage.removeItem(LAST_RUN_KEY);
        } catch {
          // A stale run id is harmless when storage cannot be updated.
        }
      }
    };
    void initialize().catch((reason) => setError(
      reason instanceof Error ? reason.message : "初始化失败",
    ));
    return () => closeStream.current();
  }, [connectRun, refresh]);

  const submit = async (message: string, confirmed = false) => {
    try {
      setError(null);
      closeStream.current();
      setEvents([]);
      const created = await createRun(message, confirmed);
      setRun({ runId: created.runId, status: "queued", createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() });
      try {
        localStorage.setItem(LAST_RUN_KEY, created.runId);
      } catch {
        // Streaming still works when browser storage is unavailable.
      }
      connectRun(created.runId, created.eventsUrl);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "运行创建失败");
    }
  };

  const updatePreference = async (enabled: boolean) => {
    try {
      await setPreference(enabled);
      setPersonalizationState(enabled);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "偏好更新失败");
    }
  };

  const closeGuide = () => {
    try {
      localStorage.setItem("moyuan-learning-guide", "dismissed");
    } catch {
      // The guide still closes when storage is unavailable.
    }
    setGuideOpen(false);
  };

  return (
    <Shell active={view} onChange={setView} onGuide={() => setGuideOpen(true)} runtime={runtime}>
      {guideOpen && <LearningGuide runtime={runtime} onClose={closeGuide} />}
      {error && <div className="error-toast" onClick={() => setError(null)}>{error}<span>×</span></div>}
      <Suspense fallback={<div className="page"><div className="empty-state">正在装载决策视图…</div></div>}>
        {view === "decision" && <DecisionView run={run} events={events} metrics={metrics} busy={run?.status === "queued" || run?.status === "running"} onSubmit={submit} onCancel={() => run && void cancelRun(run.runId)} />}
        {view === "collaboration" && <CollaborationView run={run} events={events} />}
        {view === "quality" && <QualityView metrics={metrics} runtime={runtime} quality={quality} personalization={personalization} onPersonalization={updatePreference} />}
      </Suspense>
    </Shell>
  );
}
