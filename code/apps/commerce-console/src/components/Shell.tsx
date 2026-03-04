import type { ReactNode } from "react";
import type { RuntimeStatus, ViewName } from "../types";

const VIEWS: Array<{ id: ViewName; index: string; label: string; detail: string }> = [
  { id: "decision", index: "01", label: "决策驾驶舱", detail: "需求 · 候选 · 组合" },
  { id: "collaboration", index: "02", label: "协作拓扑", detail: "任务 · 委派 · 复议" },
  { id: "quality", index: "03", label: "质量与运行", detail: "指标 · 门禁 · 降级" },
];

export function Shell(props: {
  active: ViewName;
  onChange: (view: ViewName) => void;
  onGuide: () => void;
  runtime: RuntimeStatus | null;
  children: ReactNode;
}) {
  return (
    <div className="app-shell">
      <aside className="rail">
        <div className="brand-mark"><span>B</span><i /></div>
        <div className="brand-copy">
          <strong>BUYSENSE</strong>
          <small>SEARCH · ADS · RECS</small>
        </div>
        <nav>
          {VIEWS.map((view) => (
            <button
              type="button"
              key={view.id}
              className={props.active === view.id ? "active" : ""}
              onClick={() => props.onChange(view.id)}
            >
              <span>{view.index}</span>
              <div><strong>{view.label}</strong><small>{view.detail}</small></div>
            </button>
          ))}
        </nav>
        <button type="button" className="guide-trigger" onClick={props.onGuide}><span>?</span> 新生导览</button>
        <div className="rail-runtime">
          <span className={`signal ${props.runtime?.status === "UP" ? "up" : "warn"}`} />
          <div>
            <strong>{props.runtime?.model.mode === "modelport" ? "RouteSmith" : (props.runtime?.model.mode ?? "连接中")}</strong>
            <small>{props.runtime?.model.model ?? "runtime probe"}</small>
          </div>
        </div>
      </aside>
      <main>{props.children}</main>
    </div>
  );
}
