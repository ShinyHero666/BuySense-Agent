import type { RuntimeStatus } from "../types";

const TERMS = [
  ["SAR", "搜索、广告与推荐"],
  ["Slate", "多路融合后的候选列表"],
  ["RRF", "按通道排名融合候选"],
  ["Critic", "独立审核方案与证据"],
  ["QDSR", "合格决策成功率"],
  ["Replay", "不调用真实模型的确定性学习模式"],
];

export function LearningGuide(props: { runtime: RuntimeStatus | null; onClose: () => void }) {
  const mode = props.runtime?.model.mode === "modelport" ? "真实本地模型" : "离线 Replay";
  return (
    <div className="guide-backdrop" role="presentation">
      <section className="learning-guide" role="dialog" aria-modal="true" aria-labelledby="guide-title">
        <button type="button" className="guide-close" aria-label="关闭新手导览" onClick={props.onClose}>×</button>
        <header>
          <p>FIRST RUN · 约 5 分钟</p>
          <h2 id="guide-title">第一次使用，从一条购买需求开始。</h2>
          <span>当前运行模式：<strong>{mode}</strong></span>
        </header>
        <div className="guide-steps">
          <article><b>01</b><div><strong>选择示例</strong><p>先用“套装决策”，观察手机、耳机和充电器如何共同满足预算。</p></div></article>
          <article><b>02</b><div><strong>运行决策</strong><p>Agent 负责理解和协调；召回、排序、广告保护与预算由算法服务执行。</p></div></article>
          <article><b>03</b><div><strong>查看协作</strong><p>切换到“协作拓扑”，跟踪任务委派、结构化产物和独立复议。</p></div></article>
          <article><b>04</b><div><strong>检查质量</strong><p>在“质量与运行”理解 QDSR、离线检索门禁和服务降级状态。</p></div></article>
        </div>
        <div className="guide-terms">
          {TERMS.map(([term, explanation]) => <div key={term}><b>{term}</b><span>{explanation}</span></div>)}
        </div>
        <footer>
          <a href="https://github.com/ShinyHero666" target="_blank" rel="noreferrer">查看项目作者 GitHub ↗</a>
          <button type="button" className="primary-action" onClick={props.onClose}>开始探索</button>
        </footer>
      </section>
    </div>
  );
}
