import { useMemo } from "react";
import { Chart } from "../components/Chart";
import type { AgentRun, RunEvent } from "../types";
import type { EChartsOption } from "echarts";

const ROLES = ["lead", "intent_router", "search", "recommendation", "ads", "compatibility", "pricing", "review_evidence", "critic", "cart"];
const ROLE_LABELS: Record<string, string> = {
  lead: "主协调", intent_router: "意图", search: "搜索", recommendation: "推荐", ads: "广告",
  compatibility: "兼容", pricing: "价格", review_evidence: "评论证据", critic: "审核", cart: "购物车",
};
const EVENT_LABELS: Record<string, string> = {
  task_started: "开始任务", task_delegated: "委派任务", task_completed: "完成任务",
  model_budget_consumed: "使用模型预算", data_plane_result: "算法服务返回",
  artifact_published: "发布结构化产物", tool_artifact_published: "工具发布产物",
  peer_delegated: "同伴协作", run_completed: "运行完成",
};

const humanize = (value: unknown, labels: Record<string, string>) => {
  const raw = String(value ?? "");
  return labels[raw] ? `${labels[raw]} · ${raw}` : raw.replaceAll("_", " ");
};

export function CollaborationView({ run, events }: { run: AgentRun | null; events: RunEvent[] }) {
  const option = useMemo<EChartsOption>(() => {
    const activeRoles = new Set(events.flatMap((event) => {
      const role = event.payload.role;
      const to = event.payload.to;
      return [role, to].filter((value): value is string => typeof value === "string");
    }));
    const links = [
      ["lead", "intent_router"], ["lead", "search"], ["lead", "ads"],
      ["search", "recommendation"], ["recommendation", "compatibility"],
      ["compatibility", "pricing"], ["compatibility", "review_evidence"],
      ["review_evidence", "critic"], ["critic", "recommendation"], ["critic", "lead"],
    ];
    const executedLinks = new Set(events.flatMap((event) => {
      if (event.payload.event !== "task_delegated") return [];
      const source = event.payload.role;
      const target = event.payload.to;
      return typeof source === "string" && typeof target === "string"
        ? [`${source}->${target}`]
        : [];
    }));
    return {
      backgroundColor: "transparent",
      tooltip: { textStyle: { fontSize: 13 } },
      series: [{
        type: "graph",
        layout: "force",
        roam: true,
        force: { repulsion: 420, edgeLength: [90, 150], gravity: 0.08 },
        label: { show: true, color: "#edf5fc", fontSize: 13, fontWeight: 600, formatter: (params) => ROLE_LABELS[String(params.name)] ?? String(params.name) },
        lineStyle: { color: "#3a5875", width: 1.4, curveness: 0.08 },
        emphasis: { focus: "adjacency" },
        data: ROLES.map((role, index) => ({
          name: role,
          symbolSize: role === "lead" ? 70 : ["search", "recommendation", "ads"].includes(role) ? 55 : 44,
          itemStyle: {
            color: activeRoles.has(role) ? (role === "critic" ? "#f6a84b" : "#18bea9") : "#183047",
            borderColor: index % 2 ? "#4f7294" : "#2ddbc4",
            borderWidth: 1.5,
          },
        })),
        links: links.map(([source, target]) => ({
          source,
          target,
          lineStyle: executedLinks.has(`${source}->${target}`)
            ? { color: "#28d7c0", width: 3, opacity: 1 }
            : { color: "#3a5875", width: 1, opacity: 0.35, type: "dashed" },
        })),
      }],
    };
  }, [events]);

  const taskEvents = events.filter((event) => event.eventType === "task").slice(-24);
  return (
    <div className="page collaboration-page">
      <header className="page-head compact"><div><p>BOUNDED FREE COLLABORATION</p><h1>自由协作，但每一步都有边界。</h1></div><div className="policy-chips"><span>≤18 TASKS</span><span>≤6 MODEL CALLS</span><span>≤1 REVISION</span><span>90s DEADLINE</span></div></header>
      <div className="collaboration-grid">
        <section className="topology-panel">
          <div className="section-title"><span>A</span><div><small>LIVE TOPOLOGY</small><h2>角色与能力路由</h2></div></div>
          <Chart option={option} className="topology-chart" />
          <p className="panel-note">虚线是允许的委派方向，亮色实线是本次 Run 实际执行的委派；不在允许图中的调用会被协调器拒绝。</p>
        </section>
        <section className="event-panel">
          <div className="section-title"><span>B</span><div><small>EVENT LEDGER</small><h2>任务流水</h2></div></div>
          <div className="event-ledger">
            {taskEvents.map((event) => (
              <article key={event.eventId}>
                <time>{new Date(event.timestamp).toLocaleTimeString("zh-CN", { hour12: false })}</time>
                <i /><div><strong>{humanize(event.payload.role ?? event.payload.to ?? "agent", ROLE_LABELS)}</strong><span>{humanize(event.payload.event ?? event.eventType, EVENT_LABELS)}</span><small>{humanize(event.payload.capability ?? event.payload.taskId ?? "", {})}</small></div>
              </article>
            ))}
            {taskEvents.length === 0 && <div className="empty-state">运行一次决策，即可观察 Agent 动态委派、工具调用与复议。</div>}
          </div>
        </section>
      </div>
      <section className="boundary-band">
        <div><small>AGENT OWNS</small><strong>理解 · 策略 · 协调 · 复议 · 解释</strong></div>
        <span>→</span>
        <div><small>ALGORITHM OWNS</small><strong>召回 · 排序 · 预算 · 兼容 · 合规</strong></div>
        <span>→</span>
        <div><small>STATE OWNS</small><strong>身份 · Run · Event · Draft · Preference</strong></div>
      </section>
      <footer className="run-strip"><span className={`run-dot ${run?.status ?? "idle"}`} /><strong>{run?.status ?? "ready"}</strong><small>{run?.runId ?? "等待 Run"}</small><em>{events.length} persisted events</em></footer>
    </div>
  );
}
