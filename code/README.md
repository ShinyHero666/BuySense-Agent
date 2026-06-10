# Moyuan SAR Agent 配套代码与运行手册

## 0. 当前产品主线：普通电商搜广推 Agent V2

普通电商 V2 是当前唯一产品主线；参考数据优先覆盖 3C，但架构不绑定二手、3C 或单一平台。原搜索/推荐课程、二手平台和旧 Agent 均保留为 `legacy/course` 教学资产。V2 使用 React/ECharts 工作台、TypeScript/Pi 决策编排层、Python 搜推算法数据面和 SQLite 持久化状态。

新生默认使用离线 V2，不需要模型或密钥：

```bash
cd moyuan-sar-agent/code
bash scripts/run-sar-agent.sh --offline
```

打开 `http://127.0.0.1:19090/`。完整离线验收：

```bash
bash scripts/verify-vnext.sh
```

V2 代码与接口详见 [agent-control-plane/README.md](./agent-control-plane/README.md)。下面章节是兼容保留的课程与旧版运行手册。

## 1. 一键安装

要求 Linux、Bash 5+ 和 Python 3.10+：

```bash
cd moyuan-sar-agent/code
bash scripts/bootstrap.sh
```

脚本会：

1. 创建 `.venv`。
2. 通过 `.pth` 链接本地 `src/`，生成 Linux CLI 启动器。
3. 编译源码。
4. 运行完整单元与集成测试。
5. 自动发现并回放全部搜索、推荐、估价和反馈场景。

项目没有第三方运行依赖，初始化不访问 PyPI。后续无需激活环境，直接使用 `.venv/bin` 下的命令。

## 2. 目录

```text
code
├── Makefile                      Linux 统一操作入口
├── config/experiments.json       实际运行的实验配置
├── scenarios/                    自动发现的可重复请求与期望
├── scripts/                      安装、快速导览、验证和实验保护
│   └── check_course.py           Markdown、JSON 和 Linux 路径自检
├── labs/                         实验索引和参考解答
├── k8s/deployment.yaml           Deployment/Service/PDB/HPA
├── src/shoprec
│   ├── models.py                 领域对象和解释字段
│   ├── text.py                   Query 理解
│   ├── search.py                 搜索八阶段 Pipeline
│   ├── recommend.py              推荐十阶段 Pipeline
│   ├── ranking.py                特征、排序和位置解释
│   ├── rankers.py                模型重排接口与失败降级样例
│   ├── experiments.py            配置校验和稳定分桶
│   ├── state.py                  用户快照和 TTL 曝光存储
│   ├── runtime.py                可注入 Clock/ID
│   ├── validation.py             请求校验和结构化错误
│   ├── scenarios.py              场景回放
│   ├── valuation.py              二手设备预估价和风险解释
│   ├── shoprec_agent.py          购买决策搜推 Agent 状态机
│   ├── agent_tools.py            搜索/推荐/比较/反馈/清单工具
│   ├── model_port.py             Replay 与外部 ModelPort Adapter
│   ├── agent_grounding.py        证据和版本化知识卡
│   ├── agent_eval.py             100 条离线评测门禁
│   ├── diagnostic_agent.py       高级只读搜推诊断 Agent
│   ├── service.py                应用门面
│   ├── cli.py                    摘要、解释、导览、实验比较
│   └── server.py                 可配置 HTTP 服务
├── tests/                        自动化测试和 18 个 Lab 检查点
├── compose.yaml                  Legacy 单体教学服务
├── compose.v2.yaml               V2 控制面 + 算法数据面
├── docker/                       V2 独立镜像
├── k8s/v2.yaml                   V2 Kubernetes 参考拓扑
├── Dockerfile                    Legacy 单体教学镜像
└── pyproject.toml
```

## 3. 一键搜索导览

```bash
bash scripts/quick-search.sh --section rewrite
```

如果 `.venv` 不存在，脚本会先运行初始化。完整导览：

```bash
bash scripts/quick-search.sh
```

可选部分：

```text
rewrite | broad | filters | fallback | experiments | safety
```

例如：

```bash
bash scripts/quick-search.sh --section broad --size 5
```

完整课堂命令和讲解顺序见 [搜索演示手册](../SEARCH_DEMO.md)。

### 墨圆拟真业务案例

```bash
bash scripts/quick-moyuan-case.sh
```

导览依次展示墨圆质选手机搜索、回收预估价、寄卖供给搜索和信任感知推荐。购买决策是主线，其他能力是合成教学扩展，不代表已经上线的墨圆生产实现。业务说明见[墨圆拟真业务案例](../chapters/13-moyuan-secondhand-business-case.md)。

## 4. CLI

### 搜索摘要

```bash
.venv/bin/shoprec search "苹果手机" --user u001 --size 5
```

### 只看 Trace

```bash
.venv/bin/shoprec search "手机" --view trace
.venv/bin/shoprec recommend --user u001 --view trace
```

### 教学解释视图

```bash
.venv/bin/shoprec search "手机" --size 5 --view explain
```

`explain` 把 Query 决策、阶段漏斗、过滤理由、Facets、商品属性、召回源、特征、分数和重排动作放在同一份输出中。

### 完整 JSON

```bash
.venv/bin/shoprec search "iphone" --view full
```

完整结果包含：

- `recall_sources`
- `features`
- `rough/rank/model_rerank`
- `pool`
- 重排前后位置和理由
- 实验版本
- 阶段输入、输出、耗时和元数据

### 完整搜索参数

```bash
.venv/bin/shoprec search "手机" \
  --category "手机" \
  --brand "Apple" \
  --condition "95新" \
  --city "上海" \
  --min-price 1000 \
  --max-price 7000 \
  --sort price_asc \
  --page 1 \
  --size 5 \
  --view explain
```

`--category`、`--brand` 和 `--condition` 可以重复。

二手特色参数：

```bash
.venv/bin/shoprec search "iphone" \
  --inspection-grade A --inspection-grade B \
  --min-battery-health 85 \
  --warranty-required \
  --service-mode recycle_inventory \
  --view explain
```

### 回收预估价

```bash
.venv/bin/shoprec value-device \
  --brand Apple \
  --model "iPhone 13" \
  --storage 128 \
  --age-months 36 \
  --condition-grade B \
  --battery-health 90 \
  --inspection-method door
```

输出是质检前区间估价，明确标记最终价仍需实物质检。

### 查看样例商品

```bash
.venv/bin/shoprec catalog
.venv/bin/shoprec catalog --category "摄影"
.venv/bin/shoprec catalog --brand "Apple"
```

### 实验比较

```bash
.venv/bin/shoprec compare-search "手机" --size 8
.venv/bin/shoprec compare-flowpool --user u001 --size 8
```

命令会自动寻找稳定命中每个 variant 的 token，并使用独立 Service 实例比较结果。

### 购买决策搜推 Agent

```bash
.venv/bin/moyuan agent \
  --message "预算不超过3600元，128G以上，电池至少87，必须质保，不接受维修" \
  --message "比较前三个" \
  --message "保存前两个" \
  --message "确认保存"
```

默认 `replay` 模式完全离线。`--full` 可查看需求状态、引用、工具参数、确认和 Trace。高级只读诊断：

```bash
.venv/bin/moyuan diagnose-search iphone --max-price 700
```

### ModelPort 真实模式

外部仓库是 `/home/tiammomo/projects/dev/ModelPort`，逻辑别名是 `moyuan-shoprec-agent`。预检和显式烟测：

```bash
bash scripts/modelport-check.sh
bash scripts/modelport-smoke.sh
```

真实交互：

```bash
bash scripts/with-modelport-env.sh \
  .venv/bin/moyuan agent --model-mode modelport
```

Agent 只拿 ModelPort client key，不持有 Provider Key。真实模式失败时不在应用层重试，而是标记降级并使用确定性业务策略。

## 5. 配置

运行时默认加载：

```text
config/experiments.json
```

也可以显式指定：

```bash
.venv/bin/shoprec --config config/experiments.json compare-flowpool
export SHOPREC_EXPERIMENT_CONFIG="$PWD/config/experiments.json"
```

启动时校验：

- 必需 experiment layer。
- variant 名称唯一。
- 每层流量之和为 100。
- 每个已知 layer 使用精确参数名。
- 参数必须为非负数字。
- 每个 variant 参数之和为 1。

修改配置后需要启动新的 CLI/HTTP 进程。配置不会在进程内自动热加载。

## 6. 场景回放

```bash
.venv/bin/shoprec scenario scenarios/search-basic.json
.venv/bin/shoprec scenario scenarios/search-learning-path.json
.venv/bin/shoprec scenario scenarios/search-sorts-and-filters.json
.venv/bin/shoprec scenario scenarios/recommend-pipeline.json
.venv/bin/shoprec scenario scenarios/feedback-loop.json
.venv/bin/shoprec scenario scenarios/moyuan-secondhand-business-case.json
```

场景支持 `search`、`recommend`、`valuation`、`event` 四类步骤，以及：

- 商品包含、排除、顶部商品和结果前缀。
- 最少数量、精确数量和总召回数量。
- Query 改写、泛词与风险判定。
- 召回源、过滤理由和重排理由。
- 所有结果字段一致性和升降序。
- 实验版本和完整阶段顺序。
- 顶层字段精确匹配；未知 expectation 名会立即失败，避免断言拼错后假通过。

## 7. HTTP

默认监听所有容器网卡：

```bash
.venv/bin/shoprec-server --port 18080
```

端口冲突时使用其他端口，或让操作系统分配：

```bash
.venv/bin/shoprec-server --host 127.0.0.1 --port 0
```

环境变量：

```text
SHOPREC_HOST
SHOPREC_PORT
SHOPREC_EXPERIMENT_CONFIG
MOYUAN_AGENT_MODEL_MODE
MOYUAN_MODELPORT_BASE_URL
MOYUAN_MODELPORT_API_KEY
MOYUAN_MODELPORT_MODEL
```

接口：

```text
GET  /health
GET  /health/live
GET  /health/ready
GET  /agent-lab
POST /api/search
POST /api/recommend
POST /api/valuation
POST /api/events
POST /api/agent
POST /api/v2/discovery/search
POST /api/v2/discovery/recommend
POST /api/v2/discovery/ads
POST /api/v2/evidence/reviews
POST /api/v2/evidence/compatibility
POST /api/v2/pricing/quote
```

参数错误返回：

```json
{
  "error": "validation_error",
  "field": "page",
  "message": "must be between 1 and 100, got 0"
}
```

未预期的内部异常返回 HTTP 500：

```json
{
  "error": "internal_server_error"
}
```

异常堆栈只记录在服务端日志，不进入响应体。

## 8. Docker 与 Kubernetes

V2 产品主线使用两服务拓扑：

```bash
docker compose -f compose.v2.yaml up --build
curl -fsS http://127.0.0.1:19090/health/ready
docker compose -f compose.v2.yaml down
```

其中控制面包含 React 构建产物、Pi Agent 和 SQLite Run 状态，数据面提供 Search、Recommendation、Ads、融合、组合、兼容、评论与 Quote。`k8s/v2.yaml` 明确将 SQLite 控制面限制为单副本。

以下入口属于 Legacy 教材。Docker Compose 使用主机端口 18080，避免常见的 8080 冲突：

```bash
docker compose up --build
curl -fsS http://127.0.0.1:18080/health/ready
docker compose down
```

容器内监听 `0.0.0.0:8080`，以非 root 用户运行，并带健康检查。

Kubernetes 示例包含：

- Startup、Readiness、Liveness Probe
- 资源 requests/limits
- PodDisruptionBudget
- CPU HPA

镜像地址仍是占位符，部署前替换为真实 Registry。

## 9. 测试

```bash
bash scripts/verify.sh
```

测试覆盖：

- Query 改写、泛词、风控。
- 搜索八阶段和推荐十阶段。
- 配置真实加载与非法配置。
- 稳定分桶与实验差异。
- Flow Pool 目标和实际池分布。
- 过滤数量守恒。
- 重排位置解释。
- 固定时间和确定性 ID。
- 曝光 TTL。
- 请求校验和结构化 400。
- 临时端口 HTTP 集成。
- 全部场景回放，包括完整搜索学习路径、筛选排序和墨圆拟真业务案例。
- CLI 解释视图、一键导览、完整筛选参数和商品目录。
- 质检信任特征、墨圆质选筛选、回收估价和估价 HTTP 接口。
- 18 个实验检查点，包括购买 Agent、ModelPort 和只读诊断。
- 200 条合成 iPhone 商品与 100 条 Agent 评测。
- 硬约束、显式确认、Grounding、Prompt Injection 和 PII 脱敏。

验证脚本会自动发现并回放 `scenarios/*.json`，新增场景不需要手工修改脚本。

常用 Linux 入口也封装在 `Makefile`：

```bash
make help
make verify
make quick-search
make business-case
make server
make agent
make agent-eval
make modelport-check
make modelport-smoke
```

`Makefile` 是可选快捷入口；未安装 GNU Make 时直接使用上面的 Bash 脚本。

## 10. 实验保护

开始实验前：

```bash
bash scripts/start-lab.sh 4
```

检查：

```bash
bash scripts/check-lab.sh 4
```

恢复：

```bash
bash scripts/reset-lab.sh
```

备份保存在 `.lab-backup`。恢复完成后脚本会校验路径并删除备份，因此可以立即开始下一个实验。

参考解答见 [labs/solutions.md](./labs/solutions.md)。

## 11. 生产映射

| 教学实现 | 生产替换 |
|---|---|
| `list[Product]` | Elasticsearch/OpenSearch/向量库 |
| `InMemoryUserStore` | Redis/在线 Feature Store |
| `InMemoryExposureStore` | Redis ZSet/Set + TTL |
| 本地打分 | 独立 Rank/Model Serving |
| 解释型预估价 | 设备识别 + 估价模型 + 质检工作流 |
| JSON 实验 | Apollo/Nacos/实验平台 |
| 同步事件 | Kafka/Pulsar + Flink |
| `DebugTrace` | OpenTelemetry + Debug 平台 |

核心 Engine 不依赖云 SDK，替换基础设施时应实现 Adapter，而不是改写业务 Pipeline。

## 12. Linux 路径约定

- 课程代码根目录是运行 `pwd` 后包含 `pyproject.toml` 的目录。
- 脚本、配置和场景使用相对路径，如 `scripts/verify.sh`、`config/experiments.json`。
- Python 命令固定从 `.venv/bin/` 调用，不要求执行 `source .venv/bin/activate`。
- 容器内应用根目录为 `/app`，实验配置为 `/app/config/experiments.json`。
- Kubernetes 清单只写容器路径，不引用开发机的 `$HOME` 或挂载盘路径。
- 自定义虚拟环境可通过 `SHOPREC_VENV=/absolute/path` 指定；CI 中建议放在工作区缓存目录。

本项目只维护 Linux Bash 入口，不提供其他操作系统包装器。

## 13. 本地千问驱动的普通 3C 搜广推多 Agent（V2）

`agent-control-plane/` 是普通电商产品主线：Python 提供召回、排序、RRF 融合、广告保护、Top-N 组合优化、评论 Aspect、兼容图谱和短期 Quote；TypeScript/Pi Agent 经 ModelPort 调用本地千问，执行有界协作与一次 Critic 复议。Pricing、Review、Compatibility 和 Cart 是确定性工具，不是 LLM 角色。运行状态、事件、偏好和购物车草案保存在 SQLite，并由 React/ECharts 工作台通过异步 Run/SSE 展示。

离线教学演示：

```bash
bash scripts/run-sar-agent.sh --offline
```

真实千问演示使用 `bash scripts/run-sar-agent.sh --local-qwen`。打开 `http://127.0.0.1:19090/`。脚本会把 Python 数据面放在 `18083`。

离线测试：

```bash
cd agent-control-plane
npm ci --ignore-scripts --no-audit --no-fund
npm test
npm run demo
npm run demo:cart
npm run eval
```

直接执行 `npm run server` 仍默认使用 Pi Replay，适合 CI；真实主路径由 `run-local-qwen-sar.sh` 显式设置 `modelport + python`。`npm test` 会自动启动 Python 服务完成跨语言集成测试，`npm run modelport:e2e` 则执行真实千问提案与购物车确认验收。完整 API、运行态、流式 Trace、指标和 ModelPort 配置见 [agent-control-plane/README.md](./agent-control-plane/README.md)。原二手 iPhone Agent 仍作为旧教学基线保留。
