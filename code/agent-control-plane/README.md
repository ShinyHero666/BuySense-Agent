# 墨圆智选 · Moyuan SAR Agent V2

“墨圆智选”是对外展示名称，**Moyuan SAR Agent** 是技术项目名。V2 是项目唯一产品主线，第一用户是搜广推与 Agent 工程师。它把 Agent 定位为“决策编排层”：理解需求、选择 Search / Recommendation / Ads 通道、协调同伴、发起一次有边界的复议并组织解释；召回、排序、广告保护、预算、兼容、价格和购物车门禁继续由可评估算法服务负责。购物界面是可执行工程样例，不是生产商城。

旧 Python 二手平台 Agent 与课程 Pipeline 保留在 `src/shoprec`，作为 `legacy/course` 教材，不再承担产品演示入口。V2 自动发现共享 `src/shoprec/data/*_domain_v1.json` Domain Pack；当前 3C 与户外露营包在同一进程中按 Run 隔离运行，TypeScript 与 Python 共用定义与资产。

## 新生离线启动

只需要 Linux/WSL、Python 3.10+ 和 Node.js 22.19+：

```bash
cd code
bash scripts/run-sar-agent.sh --offline
```

该模式使用 `Pi Replay + Python algorithms`，工作流、算法、证据、SQLite 和 UI 均正常运行，但不调用真实模型。它还会强制使用仓库内的确定性零售快照、关闭 fallback，并忽略 HTTP 数据源地址与密钥，因此不会访问零售上游。

## 进阶：连接真实零售数据源

Catalog、Review 和 Pricing 可以切换到通用 HTTP provider 或下一节的 Shopify
adapter。使用通用 provider 时，复制示例配置并填写 provider 基址：

```bash
cp code/.env.example code/.env
# MOYUAN_RETAIL_DATA_MODE=http
# MOYUAN_RETAIL_DATA_PROVIDER=generic
# MOYUAN_RETAIL_DATA_BASE_URL=https://retail-data.internal.example
# 可选：MOYUAN_RETAIL_DATA_API_KEY=...
bash code/scripts/run-sar-agent.sh --http-data --check
bash code/scripts/run-sar-agent.sh --http-data
```

`--http-data` 仍使用确定性的 Pi Replay，只替换零售数据源，适合把模型变量与数据接入变量分开验收。启动器只从 `code/.env` 读取受支持的 `MOYUAN_RETAIL_DATA_*` 与 `MOYUAN_SHOPIFY_*` 白名单配置，进程环境中的同名值优先。HTTP provider 契约为：

```text
GET  /v1/catalog/{domain_pack_id}
     → RetailCatalogSnapshotWireRecord
POST /v1/reviews/query
     {domain_pack_id, product_ids}
     → ReviewEvidenceWireResponse
POST /v1/prices/quote
     {domain_pack_id, offer_ids}
     → PricingQuoteWireResponse
```

字段约束以 [`commerce-agent-v2.schema.json`](../packages/contracts/commerce-agent-v2.schema.json) 的 `$defs.RetailCatalogSnapshotWireRecord`、`$defs.ReviewEvidenceWireResponse`、`$defs.PricingQuoteWireResponse` 和 `$defs.DataSourceMetadataWireRecord` 为准。三个响应都必须携带 `data_source`：HTTP provider 的 `source` 必须是 `remote_provider`，`source_version` 必须与响应自身的 catalog/review/quote 版本一致，`provider_id` 必须匹配由规范化 provider 基址计算的脱敏 fingerprint；任一不一致都会按非法上游响应失败关闭或进入显式 fallback。

客户端默认 2 秒超时、单响应最多 1 MiB（配置上限 4 MiB）、拒绝重定向。非 loopback provider 必须使用 HTTPS；`MOYUAN_RETAIL_DATA_ALLOW_INSECURE_HTTP=true` 只用于明确接受风险的开发环境。可选 API key 仅作为 Bearer 凭据传给 Python 数据面，不会进入 UI 或 health。

HTTP 模式默认 `MOYUAN_RETAIL_DATA_FALLBACK_ENABLED=false`：上游超时、失败或响应非法时数据面不可 ready，避免把本地样本误当成线上数据。只有显式设为 `true` 才会降级到领域包本地快照；此时服务保持 ready，但控制面 `/health` 的 `dataPlane.retailSources` 与工作台会标为 `degraded` 和“已降级到本地快照”。响应只公开远端配置指纹 `providerId`、实际来源标识 `effectiveProviderId` 与版本，不返回 provider URL、凭据或原始错误正文。

### 可选 Shopify provider：先验连通，再验业务

本轮 Shopify 接入只支持 **single-store custom app token**：每个部署绑定一个
`*.myshopify.com` 店铺，并用该店铺专用、至少有 `read_products` 且没有任何
`write_*` scope 的 Admin access token。公共应用的多租户授权、expiring offline
token 刷新、vault 保存与轮换均未实现，不能把当前接入表述为 public app
生产 OAuth 已完成。Admin GraphQL 契约固定为 `2026-07`，本轮不接受其他版本。

先在 `code/.env` 中配置：

```dotenv
MOYUAN_RETAIL_DATA_MODE=http
MOYUAN_RETAIL_DATA_PROVIDER=shopify
MOYUAN_SHOPIFY_STORE_DOMAIN=your-store.myshopify.com
MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN=replace-with-a-scoped-read-only-token
MOYUAN_SHOPIFY_API_VERSION=2026-07
```

凭据模式只通过 `run-sar-agent.sh --http-data` 启动。基础
`compose.v2.yaml` 固定为 `replay + static`，不会读取 ModelPort、通用 provider 或
Shopify 凭据。HTTP 模式也不会复用已经运行的控制面或 Python 数据面；token、
fallback 策略等敏感配置不进入 health，因此轮换凭据或切换策略后必须先停止旧栈再
重启。

从仓库根目录运行独立连通性探针：

```bash
python3 code/scripts/shopify_readonly_canary.py --check
python3 code/scripts/shopify_readonly_canary.py
```

缺少店铺或 token 时，两条命令都会明确输出 `SKIP`、返回 0，且不联网。
`--check` 只校验本地配置和只读查询不变量，输出 `READY` 也不代表凭据可用。
不带 `--check` 时，探针只向固定店铺发一次 Admin GraphQL `query`：读取当前
app scopes 和至多一个商品的 `id/updatedAt`。虽然 GraphQL 使用 HTTP POST，查询
中没有 mutation。探针要求响应版本仍为 `2026-07`、必须有 `read_products`，并
在发现任一 `write_*` scope 时失败关闭；它拒绝跳转和代理，限制超时与响应大小，
且不会输出 token、商品值、GraphQL 错误正文或原始异常。CI 只运行其单元测试与
`--check`，不依赖 Shopify 或公网。

`PASS Shopify read-only canary` **只证明店铺、API 版本、token 和最小读取链路
连通**，不证明三端业务转换正确。完整 Shopify 业务适配还必须分别验收：

- Catalog 只接收同时具有唯一 `moyuan-domain-pack-<packId>` tag、product
  metafield `moyuan.sar_product` 和每个 variant 的 `moyuan.sar_variant` 的商品。
  两个 metafield 都必须是 Shopify `json` 类型，最小结构分别为：

  ```json
  {"domain_pack_id":"normal-3c-v1","category":"phone","brand":"Moyuan","tags":["featured"]}
  ```

  ```json
  {"ecosystem":"universal","connectors":[],"protocols":[],"sponsored":false}
  ```

  `category` 必须属于对应 Domain Pack；商品必须是 active、已发布到 Online Store，
  `publishedInContext(country: CN)` 必须为 true，且不能是仅 selling-plan 商品或
  需要 components 的 bundle variant。M4 每个商品最多读取 25 个 variant；超过
  上限会使该次 Catalog 启动失败，而不是静默截断；
- Pricing 对首个候选读取 `contextualPricing(context: {country: CN}).price`，
  再次检查商品的 CN publication，并且只接受 CNY；`sellableOnlineQuantity` 只是
  在线渠道的保守库存信号，刷新结果仍是购物车草案，不是 checkout 库存保证；
- Reviews 同时读取 Shopify 标准 product metafields `reviews.rating`
  （`rating`）与 `reviews.rating_count`（`number_integer`），校验 value/scale 后
  只映射 `overall_rating`；`summary` 只是 rating/count 的确定性格式化，不是用户
  评论原文或生成式摘要；
- Catalog、Pricing、Reviews 的业务响应与 `/health` 都显示
  `remote_provider` 及匹配的脱敏 provider/version，strict 失败不得伪装成本地或
  远端成功。

Shopify 的 fallback 比通用 HTTP provider 更保守：只有启动阶段 Catalog cohort
失败且显式开启 fallback 时，所有 Domain Pack 才会整批切换到本地快照并标记
`degraded`。一旦远端 Catalog 已成功发布，运行期 Reviews/Pricing 失败就返回 503，
不会用 ID 空间不同的静态评分或报价拼接结果。鉴权失败、权限过宽/不足、API 版本
漂移、请求被拒或查询成本超限属于配置/安全错误，即使开启 fallback 也会失败关闭。

M4 是启动时只读快照，不含 webhook 或后台增量刷新；Catalog 变更需重启数据面，
确认阶段仍会实时重查 Pricing。限流目前采用单店串行请求与失败关闭，尚未实现基于
Shopify cost bucket 的退避重试。

业务适配通过本地 mock 集成测试后，再运行
`bash code/scripts/run-sar-agent.sh --http-data --check` 与真实环境验收。Shopify
Admin API 的版本、鉴权、scope 定义以官方
[API versioning](https://shopify.dev/docs/api/usage/versioning)、
[access tokens](https://shopify.dev/docs/apps/build/authentication-authorization/access-tokens)、
[`currentAppInstallation` query](https://shopify.dev/docs/api/admin-graphql/latest/queries/currentappinstallation)
和 [`products` query](https://shopify.dev/docs/api/admin-graphql/latest/queries/products)
文档为准。

## 进阶：连接真实本地千问

前提：ModelPort 已在 `127.0.0.1:38082` 提供本地千问模型，且其 `.env` 中有可用的 scoped client key。

```bash
cd code
bash scripts/run-sar-agent.sh --local-qwen
```

打开 <http://127.0.0.1:19090/>。脚本会构建 React 前端、按需启动 Python 数据面，并以 `Pi Agent + ModelPort + local_strict` 启动控制面；零售数据源继续遵循受支持的 `MOYUAN_RETAIL_DATA_*` 与 `MOYUAN_SHOPIFY_*` 配置，未配置时使用 static。状态写入 `.runtime/commerce-agent-local-qwen.sqlite`。

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
  identity / run / idempotency alias / event / pending decision / cart draft / preference
```

LLM 角色只有 Supervisor/Lead、Intent、Search/Recommendation/Ads Strategy、Critic 和最终响应。Pricing、Review、Compatibility、Cart 都是确定性工具，不消耗模型调用，也不能被 LLM 绕过。

## V2 API

身份由服务端签名 Cookie 签发，调用方不能提交任意 `userId`：

```text
GET    /api/v2/session
GET    /api/v2/domain-packs
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
  -d '{"domainPackId":"outdoor-camping-v1","message":"预算900元，搭配一套防风炉具、气罐和锅具"}' \
  http://127.0.0.1:19090/api/v2/runs
```

同一匿名身份、相同规范请求和 `Idempotency-Key` 的重复请求返回原 Run；同一 key 改换消息、领域包或确认目标会返回 `409 idempotency_key_reused`。幂等重放即使在全局队列已满时也不会被误判成新流量。SSE 断线后可携带最后一个事件序号恢复，React 工作台刷新页面后也会恢复该身份最近一次 Run。服务限制每身份最多 3 个并发 Run、每分钟 30 次新建请求；单进程全局最多同时执行 16 个 Run，并保留 256 个等待位。

`domainPackId` 可省略，默认 `normal-3c-v1`。服务把 `domainPackId` 与 `workflowId` 固化到 Run 和 `run_created` 事件；重启恢复不会改用当前 UI 选择。领域、能力与工作流扩展边界见 [`../../docs/DOMAIN_PACKS.md`](../../docs/DOMAIN_PACKS.md)。

确认请求必须显式绑定原方案 Run：

```bash
curl -i -b /tmp/moyuan-cookie.txt \
  -H 'content-type: application/json' \
  -H 'idempotency-key: confirm-run_PROPOSAL_ID' \
  -d '{"domainPackId":"outdoor-camping-v1","message":"确认生成购物车草案","confirmed":true,"proposalRunId":"run_PROPOSAL_ID"}' \
  http://127.0.0.1:19090/api/v2/runs
```

同一 `proposalRunId` 即使使用不同幂等 key，也至多解析到一个确认 Run；成功生成草案后，所有重放都会返回该 Run 中的同一草案。确认因瞬时错误失败或被取消时，只要原 pending proposal 仍在 TTL 内且未被新方案替换，使用新 key 就能重试同一确认 Run；工作台会显示“重试确认”，刷新后也能从 Run 中持久化的 `proposalRunId` 恢复。完整 attempt 历史保存在 SQLite event ledger，公开 SSE 与工作台默认只重放当前 attempt，避免旧 terminal event 提前截断新结果。

报价刷新在事务外执行；SQLite 用短事务一次提交精确 pending 消费、草案、Run 结果和 terminal SSE event。普通方案也用一个 owner-fenced 事务提交 pending 发布或 CAS 清理、约束、Run 结果与 terminal event，因此迟到的旧执行不能覆盖较新的方案。创建 Run 与 `run_created`、Trace 投影与对应 ledger event 也分别原子提交；进程内通知仅作提交后的快速路径，SSE 会从持久账本增量补齐跨进程事件。每次执行先取得有期限的 lease，Trace 与终态写入携带 fencing token；短暂双进程重叠恢复同一 Run 时只有 lease owner 能产生业务结果。取消会先原子落成 `cancelled` 与 `run_cancelled`，再中止本地工具调用；未退出的旧执行继续占用容量且无权改写终态。进程收到 `SIGINT`/`SIGTERM` 时会停止 Run manager、结束活跃 SSE 并排空 HTTP 连接。SQLite 部署仍明确限制为单副本；横向扩容前需迁移到共享数据库与消息系统。

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
- 健康语义：`/health/live` 只检查进程；`/health` 返回完整依赖诊断；
  `/health/dependencies` 返回同一依赖视图并以核心依赖决定状态码；
  `/health/ready` 只等待 Python 数据面与 Run 存储等必需依赖。ModelPort 故障会显示
  `DEGRADED`，但确定性角色策略可用时不会拖慢探针或被 Kubernetes 摘流。
- 零售来源：控制面 `/health` 的 `dataPlane.retailSources.catalog/reviews/pricing` 分别公开配置模式、实际来源、状态、fallback、版本、脱敏 provider 标识，以及真实发生的请求/错误/降级计数（直连 Python 数据面时位于顶层 `retailSources`）。`static` 为本地快照；HTTP strict 失败为 `down/unavailable`；只有显式允许 fallback 时才会 `degraded`。多领域包同时出现远端与本地 fallback 时，聚合健康显示 `mixed`，业务响应的 `data_source.source` 仍只会是 `local_snapshot` 或 `remote_provider`。工作台显示同一事实；`/metrics` 继续只承载业务 KPI，本轮不复制这些健康 telemetry，也不承担配置展示。
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
