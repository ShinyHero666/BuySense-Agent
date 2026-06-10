import { useMemo } from "react";
import { Chart } from "../components/Chart";
import type { MetricSnapshot, QualityReport, RuntimeStatus } from "../types";
import type { EChartsOption } from "echarts";

export function QualityView(props: {
  metrics: MetricSnapshot | null;
  runtime: RuntimeStatus | null;
  quality: QualityReport | null;
  personalization: boolean;
  onPersonalization: (enabled: boolean) => void;
}) {
  const layerEntries = Object.entries(props.metrics?.layers ?? {}).flatMap(([layer, values]) =>
    Object.entries(values).map(([name, value]) => ({ layer, name, value }))
  );
  const chart = useMemo<EChartsOption>(() => ({
    grid: { left: 28, right: 28, top: 24, bottom: 70, containLabel: true },
    xAxis: { type: "category", data: layerEntries.map((item) => item.name.replace(/Rate$/, "")), axisLabel: { color: "#a9bfd1", fontSize: 12, rotate: 28 }, axisLine: { lineStyle: { color: "#28435d" } } },
    yAxis: { type: "value", max: 1, axisLabel: { color: "#a9bfd1", fontSize: 12, formatter: "{value}" }, splitLine: { lineStyle: { color: "#172c40" } } },
    tooltip: { trigger: "axis", textStyle: { fontSize: 13 } },
    series: [{ type: "bar", data: layerEntries.map((item) => item.value), barMaxWidth: 28, itemStyle: { color: "#25cfb9", borderRadius: [4, 4, 0, 0] } }],
  }), [layerEntries]);
  return (
    <div className="page quality-page">
      <header className="page-head compact"><div><p>QUALITY &amp; OPERATIONS</p><h1>把“看起来能跑”，变成可量化的质量门禁。</h1></div><div className="north-star"><small><abbr title="QDSR：通过约束、证据和策略门禁的方案率；确认转化与草案成功率独立统计">QDSR</abbr></small><strong>{(props.metrics?.northStar.denominator ?? 0) > 0 ? Math.round((props.metrics?.northStar.value ?? 0) * 100) : "—"}{(props.metrics?.northStar.denominator ?? 0) > 0 && <em>%</em>}</strong><span>合格方案率</span></div></header>
      <div className="quality-grid">
        <section className="quality-chart-panel"><div className="section-title"><span>Q</span><div><small>SIX LAYERS</small><h2>在线质量剖面</h2></div></div><Chart option={chart} className="quality-chart" /></section>
        <section className="runtime-panel"><div className="section-title"><span>R</span><div><small>RUNTIME</small><h2>服务健康</h2></div></div>
          <div className="runtime-row"><span className={`signal ${props.runtime?.model.status === "up" || props.runtime?.model.status === "offline" ? "up" : "warn"}`} /><div><small>PI / MODELPORT</small><strong>{props.runtime?.model.model ?? "—"}</strong></div><b>{props.runtime?.model.latencyMs ?? 0} ms</b></div>
          <div className="runtime-row"><span className={`signal ${props.runtime?.dataPlane.status === "up" || props.runtime?.dataPlane.status === "embedded" ? "up" : "warn"}`} /><div><small>PYTHON DATA PLANE</small><strong>{props.runtime?.dataPlane.mode ?? "—"}</strong></div><b>{props.runtime?.dataPlane.latencyMs ?? 0} ms</b></div>
          <div className="runtime-row"><span className="signal up" /><div><small>PAYMENT BOUNDARY</small><strong>disabled by design</strong></div><b>SAFE</b></div>
        </section>
        <section className="gate-panel"><div className="section-title"><span>G</span><div><small>SYNTHETIC REGRESSION</small><h2>合成回归门禁</h2></div></div>
          <div className="gate-score"><strong>{(props.quality?.metrics.recall_at_10 ?? 0).toFixed(3)}</strong><span title="前 10 个结果覆盖目标商品的比例">Recall@10</span></div><div className="gate-score"><strong>{(props.quality?.metrics.ndcg_at_10 ?? 0).toFixed(3)}</strong><span title="前 10 个结果的排序质量，越接近 1 越好">NDCG@10</span></div><div className="gate-score"><strong>{props.quality?.metrics.p95_latency_ms ?? 0}<em>ms</em></strong><span title="95% 的评测请求不超过该延迟">P95 / {props.quality?.spu_count ?? 0} SPU</span></div>
          <p>{props.quality?.case_count ?? 0} 条规则生成场景 · 非人工金标 · {(props.quality?.metrics.filter_violations ?? 0) + (props.quality?.metrics.ad_policy_violations ?? 0)} 个硬过滤与广告策略违规</p>
          <small title={props.quality?.catalog_version}>仅用于确定性回归，不代表线上搜索质量；数据时间 {props.quality?.generated_at?.slice(0, 10) ?? "—"}</small>
        </section>
        <section className="privacy-panel"><div className="section-title"><span>P</span><div><small>PRIVACY CONTROL</small><h2>个性化开关</h2></div></div>
          <p>匿名身份由服务端签发。关闭后，召回不会使用会话、短期或长期亲和信号。</p>
          <button type="button" className={props.personalization ? "toggle on" : "toggle"} onClick={() => props.onPersonalization(!props.personalization)}><i /><span>{props.personalization ? "已启用个性化" : "已关闭个性化"}</span></button>
        </section>
      </div>
    </div>
  );
}
