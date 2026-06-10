# 墨圆智选 · Moyuan SAR Agent V2

“墨圆智选”是对外展示名称，**Moyuan SAR Agent** 是技术项目名。V2 是项目唯一产品主线，它把 Agent 定位为“决策编排层”：理解需求、选择 Search / Recommendation / Ads 通道、协调同伴、发起一次有边界的复议并组织解释；召回、排序、广告保护、预算、兼容、价格和购物车门禁继续由可评估算法服务负责。参考实现使用普通 3C 商品，但领域契约和编排方式不绑定具体品类。

旧 Python 二手平台 Agent 与课程 Pipeline 保留在 `src/shoprec`，作为 `legacy/course` 教材，不再承担产品演示入口。V2 的 3C 类目、品牌别名、用途和默认套装由共享的 `src/shoprec/data/normal_3c_domain_v1.json` Domain Pack 提供，TypeScript 与 Python 共用同一份定义。

## 新生离线启动

只需要 Linux/WSL、Python 3.10+ 和 Node.js 22.19+：

```bash
cd code
bash scripts/run-sar-agent.sh --offline
```

该模式使用 `Pi Replay + Python algorithms`，工作流、算法、证据、SQLite 和 UI 均正常运行，但不调用真实模型。

## 进阶：连接真实本地千问

前提：ModelPort 已在 `127.0.0.1:38082` 提供本地千问模型，且其 `.env` 中有可用的 scoped client key。

```bash
cd code
bash scripts/run-sar-agent.sh --local-qwen
```

打开 <http://127.0.0.1:19090/>。脚本会构建 React 前端、按需启动 Python 数据面，并以 `Pi Agent + ModelPort + local_strict` 启动控制面。状态写入 `.runtime/commerce-agent.sqlite`。

## 系统分层

```text
React / ECharts 决策工作台
            │ POST Run / SSE replay / cancel
TypeScript + Pi 决策编排层
  Intent ─ Search ─ Recommendation ─ Ads ─ Critic ─ Lead
            │ 有界任务板；≤18 tasks / ≤6 model calls / ≤1 revision
            ▼
Python 算法数据面
  Query/过滤/个性化 ─ 通道排序 ─ Weighted RRF ─ 广告保护
  ─ 组合约束优化 ─ 兼容图谱 ─ Review Aspect ─ 实时报价
            │
SQLite 状态层
  identity / run / event / pending decision / cart draft / preference
```

LLM 角色只有 Supervisor/Lead、Intent、Search/Recommendation/Ads Strategy、Critic 和最终响应。Pricing、Review、Compatibility、Cart 都是确定性工具，不消耗模型调用，也不能被 LLM 绕过。

## V2 API

身份由服务端签名 Cookie 签发，调用方不能提交任意 `userId`：

```text
GET    /api/v2/session
POST   /api/v2/runs
GET    /api/v2/runs/{runId}
GET    /api/v2/runs/{runId}/events     # SSE，支持 Last-Event-ID
POST   /api/v2/runs/{runId}/cancel
GET    /api/v2/cart-drafts/{draftId}
GET    /api/v2/preferences
PUT    /api/v2/preferences
POST   /api/v2/interactions
DELETE /api/v2/interactions
```

创建异步 Run：

```bash
curl -i -c /tmp/moyuan-cookie.txt \
  -H 'content-type: application/json' \
  -H 'idempotency-key: demo-turn-1' \
  -d '{"message":"预算7000元，选拍照手机并搭配降噪耳机和充电器"}' \
  http://127.0.0.1:19090/api/v2/runs
```

同一匿名身份和 `Idempotency-Key` 的重复请求返回原 Run，即使全局队列已满也不会把幂等重放误判成新流量。SSE 断线后可携带最后一个事件序号恢复，React 工作台刷新页面后也会恢复该身份最近一次 Run。服务限制每身份最多 3 个并发 Run、每分钟 30 次创建请求；单进程全局最多同时执行 16 个 Run，并保留 256 个等待位。

V1 同步 API 暂时保留供旧脚本迁移；直接启动 V2 服务时默认关闭，只有显式设置 `MOYUAN_ENABLE_V1_API=true` 才启用：

```text
POST /api/v1/agent
POST /api/v1/agent/stream
GET  /api/v1/cart-drafts/{draftId}
```

## 算法数据面

```text
POST /api/v2/discovery/search
POST /api/v2/discovery/recommend
POST /api/v2/discovery/ads
POST /api/v2/decision/fuse
POST /api/v2/decision/bundles
POST /api/v2/evidence/reviews
POST /api/v2/evidence/compatibility
POST /api/v2/pricing/quote
```

- 原始 Query 与模型改写同时进入检索，显式 use case 不允许被改写抹掉。
- 每个约束记录 `source / strength / confidence / turn / status`。
- 个性化分会话、近期行为和稳定匿名亲和；可关闭，并可清空行为历史。
- 融合采用带通道权重的 RRF；广告只有通过相关性、质量和自然结果保护线后才能进入 Slate，Top 3 最多一个并显式标注。
- 组合选择枚举有界候选的全局组合，校验预算与兼容，返回 Top 3，而不是逐类贪心。

## 失败与安全边界

- 单个 LLM 失败：该角色使用确定性策略，Trace 标记 `fallback`。
- Ads 失败：跳过广告，保留自然结果。
- Recommendation 失败：允许单品搜索继续；完整套装会因覆盖不足被 Critic 拒绝。
- Python 融合/组合端点失败：回退同契约的本地 RRF/约束枚举。
- Quote、兼容、评论证据缺失：失败关闭，不生成可确认草案。
- 用户取消：AbortSignal 终止正在进行的 Pi/HTTP 工作，Run 落为 `cancelled`。
- 全局运行期限：协调器使用统一的 90 秒 AbortSignal，排队委派和进行中的 Pi/HTTP 工作共享同一截止时间。
- 购物车必须显式确认后生成，且再次刷新 Quote；项目没有支付接口，`paymentAuthorized` 永远为 `false`。
- 状态保留：过期待确认项和草案按 TTL 清理，终态 Run 默认保留 30 天，匿名行为默认保留 180 天；SQLite 定时执行被动 WAL checkpoint。

## 评测与工程门禁

```bash
cd code
bash scripts/verify-vnext.sh
```

门禁包含：共享 Schema 漂移、101+ Python 测试、30+ TypeScript 测试、15 条人工编写的 Agent 场景、120 条合成确定性检索回归、1200 SPU 规模检索、Recall@10/NDCG@10、硬过滤、广告合规、P95 延迟、React 构建和 Linux 脚本检查。合成回归用于发现代码漂移，不作为线上搜索质量或人工金标结论。

当前固定基准见 `data/v2/retrieval_report.json`：

```text
Recall@10  0.958333
NDCG@10   0.995736
P95        以 committed report 为准；门禁 < 200 ms / 1200 SPU
违规       0
```

North Star 是 `qualified_decision_success_rate`（QDSR），表示通过约束、证据和策略门禁的方案率；确认转化率、确认后的草案创建成功率和端到端草案率独立统计，不再混用同一个分母。六层指标继续作为 guardrail。

## V2 容器启动

`compose.yaml` 与原 `Dockerfile` 是 Legacy 教材入口。当前产品主线使用独立的 V2 拓扑：

```bash
cd code
docker compose -f compose.v2.yaml up --build
curl -fsS http://127.0.0.1:19090/health/ready
docker compose -f compose.v2.yaml down
```

构建两个镜像并从控制面容器发起一次真实异步 Run：

```bash
bash scripts/test-v2-containers.sh
```

`docker/Dockerfile.discovery` 运行 Python 搜广推算法数据面，`docker/Dockerfile.control-plane` 构建 React 工作台并运行 Pi 控制面。Kubernetes 参考清单位于 `k8s/v2.yaml`；SQLite 控制面固定单副本，迁移 Postgres 与共享事件总线后才能横向扩容。

真实模型端到端验收：

```bash
cd code/agent-control-plane
bash ../scripts/with-modelport-env.sh env \
  MOYUAN_AGENT_MODEL_MODE=modelport \
  MOYUAN_DISCOVERY_MODE=python \
  MOYUAN_DISCOVERY_BASE_URL=http://127.0.0.1:18083 \
  npm run modelport:e2e
```

该命令必须得到 6 次真实 Pi/千问角色调用、0 fallback、完整三件套和确定性购物车确认；CI 不调用真实模型。
