import { useMemo, useState } from "react";
import type { AgentRun, MetricSnapshot, RunEvent } from "../types";
import { Chart } from "../components/Chart";
import type { EChartsOption } from "echarts";

const SUGGESTIONS = [
  { label: "套装决策", message: "总预算7000元，重视拍照和续航，选手机并搭配降噪耳机和充电器" },
  { label: "无广告单品", message: "预算5000元，不要广告，推荐一台适合游戏和拍照的手机" },
  { label: "通勤耳机", message: "预算1800元，推荐通勤降噪耳机，重视音质和佩戴舒适" },
];

export function DecisionView(props: {
  run: AgentRun | null;
  events: RunEvent[];
  metrics: MetricSnapshot | null;
  busy: boolean;
  onSubmit: (message: string, confirmed?: boolean) => void;
  onCancel: () => void;
}) {
  const [message, setMessage] = useState(SUGGESTIONS[0].message);
  const reply = props.run?.result;
  const decision = reply?.decision;
  const hasNorthStar = (props.metrics?.northStar.denominator ?? 0) > 0;
  const retrievedCandidateCount = useMemo(() => {
    const firstResultByChannel = new Map<string, number>();
    for (const event of props.events) {
      if (event.payload.event !== "data_plane_result") continue;
      const channel = event.payload.channel;
      const candidateCount = event.payload.candidateCount;
      if (
        typeof channel === "string" &&
        typeof candidateCount === "number" &&
        !firstResultByChannel.has(channel)
      ) firstResultByChannel.set(channel, candidateCount);
    }
    return [...firstResultByChannel.values()].reduce((sum, value) => sum + value, 0);
  }, [props.events]);
  const funnel = useMemo<EChartsOption>(() => ({
    backgroundColor: "transparent",
    tooltip: { trigger: "item", textStyle: { fontSize: 13 } },
    series: [{
      type: "funnel",
      left: "8%",
      width: "84%",
      top: 8,
      bottom: 8,
      minSize: "22%",
      maxSize: "100%",
      sort: "descending",
      gap: 4,
      label: { color: "#e3edf7", fontSize: 13, fontWeight: 600 },
      itemStyle: { borderColor: "#07111f", borderWidth: 2 },
      color: ["#28d7c0", "#37a5f2", "#7e82ff", "#f6a84b"],
      data: [
        { name: "多路候选", value: retrievedCandidateCount },
        { name: "融合 Slate", value: decision?.slate.length ?? 0 },
        { name: "约束组合", value: decision?.bundle.items.length ?? 0 },
        { name: "审核通过", value: decision?.critique.verdict === "approved" ? 1 : 0 },
      ],
    }],
  }), [decision, retrievedCandidateCount]);

  return (
    <div className="page decision-page">
      <header className="page-head">
        <div><p>BUYSENSE DECISION INTELLIGENCE / V2</p><h1>把一次搜索，变成可审计的购买决策。</h1></div>
        <div className="north-star">
          <small><abbr title="QDSR：通过约束、证据和策略门禁的方案数 ÷ 购买意图会话数；确认和草案成功率单独统计">QUALIFIED DECISION SUCCESS</abbr></small>
          <strong>{hasNorthStar ? Math.round((props.metrics?.northStar.value ?? 0) * 100) : "—"}{hasNorthStar && <em>%</em>}</strong>
          <span>{props.metrics?.northStar.numerator ?? 0} / {props.metrics?.northStar.denominator ?? 0} 有效会话</span>
        </div>
      </header>

      <section className="command-deck">
        <div className="command-index">ASK<br /><b>01</b></div>
        <div className="command-input">
          <label htmlFor="demand">描述购买目标、预算与偏好</label>
          <textarea id="demand" value={message} onChange={(event) => setMessage(event.target.value)} />
          <div className="suggestion-row">
            {SUGGESTIONS.map((item) => (
              <button type="button" key={item.label} title={item.message} onClick={() => setMessage(item.message)}>{item.label}</button>
            ))}
          </div>
        </div>
        <div className="command-actions">
          <button className="primary-action" disabled={props.busy || !message.trim()} onClick={() => props.onSubmit(message)}>
            {props.busy ? "编排中…" : "开始决策"}<span>↗</span>
          </button>
          {props.busy && <button className="quiet-action" onClick={props.onCancel}>取消运行</button>}
          {reply?.phase === "proposal" && decision?.critique.verdict === "approved" && (
            <button className="confirm-action" onClick={() => props.onSubmit("确认生成购物车草案", true)}>确认生成草案</button>
          )}
        </div>
      </section>

      {reply?.phase === "clarification" && (
        <section className="clarification-banner" role="status">
          <strong>还缺一项决策信息</strong>
          <p>{reply.message}</p>
        </section>
      )}

      <div className="decision-grid">
        <section className="intent-panel">
          <div className="section-title"><span>01</span><div><small>INTENT MAP</small><h2>约束雷达</h2></div></div>
          {decision ? (
            <>
              <div className="intent-line"><span>意图</span><strong>{decision.plan.intent}</strong></div>
              <div className="intent-line"><span>预算上限</span><strong>¥{decision.plan.requirements.budgetMax ?? "待确认"}</strong></div>
              <div className="token-cloud">
                {decision.plan.requirements.requestedCategories.map((item) => <b key={item}>{item}</b>)}
                {decision.plan.requirements.useCases.map((item) => <i key={item}>{item}</i>)}
                {decision.plan.requirements.preferredBrands.map((item) => <em key={item}>{item}</em>)}
              </div>
              <div className="constraint-list">
                {decision.plan.requirements.constraints.slice(0, 6).map((constraint) => (
                  <div key={constraint.constraintId}>
                    <span>{constraint.field}</span><small>{constraint.source}</small>
                    <b className={constraint.strength}>{constraint.strength}</b>
                  </div>
                ))}
              </div>
            </>
          ) : <div className="empty-state">提交需求后，这里会展示显式约束、模型推断和来源置信度。</div>}
        </section>

        <section className="funnel-panel">
          <div className="section-title"><span>02</span><div><small>RETRIEVAL FUNNEL</small><h2>搜 · 推 · 广融合</h2></div></div>
          <Chart option={funnel} className="funnel-chart" />
          <div className="channel-ledger">
            {[["search", "搜索"], ["recommendation", "推荐"], ["ads", "广告"]].map(([channel, label]) => (
              <div key={channel}><span title={channel}>{label}</span><b>{decision?.slate.filter((item) => item.sources.includes(channel)).length ?? 0}</b></div>
            ))}
          </div>
        </section>

        <section className="bundle-panel">
          <div className="section-title"><span>03</span><div><small>OPTIMAL BUNDLE</small><h2>最优决策</h2></div></div>
          {decision?.bundle.items.length ? (
            <>
              <div className="bundle-total"><small>组合总价</small><strong>¥{decision.bundle.totalPrice}</strong><span>{decision.bundle.optimization ?? "bounded optimizer"}</span></div>
              <div className="bundle-items">
                {decision.bundle.items.map((item, index) => (
                  <article key={item.product.skuId}>
                    <span>{String(index + 1).padStart(2, "0")}</span>
                    <div><small>{item.product.category} · {item.product.brand}</small><h3>{item.product.title}</h3><p>{item.reasons.slice(0, 2).join(" · ")}</p></div>
                    <strong>¥{item.product.price}</strong>
                  </article>
                ))}
              </div>
              <div className={`audit-stamp ${decision.critique.verdict}`}>
                <span>{decision.critique.verdict === "approved" ? "✓" : "!"}</span>
                <div><strong>{decision.critique.verdict === "approved" ? "独立审核通过" : "方案已拦截"}</strong><small>{decision.critique.violations.join(" · ") || "预算 / 库存 / 兼容 / 广告策略均通过"}</small></div>
              </div>
            </>
          ) : <div className="empty-state">候选会经过全局约束枚举，不再用逐项贪心拼接。</div>}
        </section>
      </div>

      <section className="candidate-ribbon">
        <div className="section-title"><span>04</span><div><small>DECISION SLATE</small><h2>候选证据带</h2></div></div>
        <div className="candidate-scroll">
          {(decision?.slate ?? []).map((item, index) => (
            <article key={`${item.product.skuId}-${item.channel}`}>
              <header><span>#{index + 1}</span>{item.sponsored && <b>赞助</b>}<small>{item.sources.join(" + ")}</small></header>
              <h3>{item.product.title}</h3><p>{item.product.tags.slice(0, 4).join(" / ")}</p>
              <footer><strong>¥{item.product.price}</strong><span title="融合后的归一化候选分，不等同于购买概率">{Math.round(item.normalizedScore * 100)} 融合分</span></footer>
            </article>
          ))}
          {!decision && <div className="empty-ribbon">暂无候选 · 等待一次决策运行</div>}
        </div>
      </section>

      <footer className="run-strip">
        <span className={`run-dot ${props.run?.status ?? "idle"}`} />
        <strong>{props.run?.status ?? "ready"}</strong>
        <small>{props.run?.runId ?? "尚未创建 Run"}</small>
        <em>{props.events.length} events</em>
        {reply?.cartDraft && <b>购物车草案 {reply.cartDraft.draftId} · 未支付</b>}
      </footer>
    </div>
  );
}
