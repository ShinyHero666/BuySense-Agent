# BuySense 智购引擎 · 3C 商品智能决策与搜广推 Agent

**BuySense** 是面向 3C 商品购买决策的自适应 AI 应用。当前 Java 17 主实现通过 Spring Boot、React/ECharts、确定性搜广推数据面与受限 Planner-Critic 角色，将意图理解、多路召回、融合排序、广告保护、套装优化、证据审核和离线评测组织为可运行链路。工作流与 Agent 按请求复杂度单路由执行，不在线上同步双跑。

项目同时提供版本化 Domain Pack、能力注册表和零售数据 Provider 契约，用同一套编排与质量门禁承载 `normal-3c-v1` 和 `outdoor-camping-v1`。内置快照用于确定性回归；Catalog、Pricing 与 Review 可显式切换到受控 HTTP Provider，并公开真实来源、健康状态与 fallback 结果。购物界面是可执行的工程样例，不声称连接真实商城库存或生产交易链路。

## 五分钟体验 Java 主实现

默认确定性模式不需要 GPU、ModelPort 或密钥：

~~~bash
cd code/java-control-plane
mvn test
mvn spring-boot:run
~~~

打开 `http://127.0.0.1:19090/`。接入模型时通过环境变量连接 ModelPort。完整架构、分层评测口径、方案取舍和面试材料见 [`code/java-control-plane/docs/PROJECT_REVIEW_CN.md`](./code/java-control-plane/docs/PROJECT_REVIEW_CN.md)。原 TypeScript/Python 版本保留为学习与行为参考。

扩展新的商品领域时，按 [`Domain Pack 扩展指南`](./docs/DOMAIN_PACKS.md) 提交版本化 manifest 与目录、评测资产；可选 Shopify 接入先使用 `python3 code/scripts/shopify_readonly_canary.py --check` 验证配置，再执行只读连通性探针。探针通过只代表凭证、固定 API 版本和最小读权限可用，不代表业务 Provider 已通过验收。

## V2 学习主线

1. [新生指南](./START_HERE.md)：从零完成第一次离线运行。
2. [术语表](./docs/GLOSSARY.md)：理解 SAR、RRF、Slate、QDSR 等核心词。
3. [代码导览](./docs/V2_CODE_TOUR.md)：跟踪 UI 到 Python 算法服务的一次请求。
4. [六步实验](./docs/v2-labs/README.md)：从 Run/SSE 学到降级与评测。
5. [首次运行排障](./docs/TROUBLESHOOTING.md)：处理版本、依赖、端口和 ModelPort 问题。

## Java 17 可运行版

[code/java-control-plane](./code/java-control-plane/README.md) 是当前的 Java
重写入口。它保留 React/ECharts 工作台，将 Run/SSE、搜推广融合、全局套装约束、
澄清短路、确认草案、ModelPort 角色调用和持久化状态迁到 Spring Boot：

~~~bash
cd code/java-control-plane
mvn test
mvn spring-boot:run
~~~

打开 http://127.0.0.1:19090/。默认使用文件型 H2 并以确定性离线模式运行；
设置 MOYUAN_DB_URL 可切换 PostgreSQL，设置
MOYUAN_MODELPORT_ENABLED=true 可通过 ModelPort 执行最多两次软角色调用。
预算、品类、兼容性和广告门禁始终由确定性 Java 数据面执行，LLM 的澄清建议也必须通过策略门。

## 当前产品能力

参考数据目前集中在普通 3C 电商，但架构不绑定二手、3C 或单一平台。项目使用合成商品与本地评测资产，不声称对应任何真实平台的库存、价格或生产实现。

- 搜索：Query 理解、多路召回、过滤、粗排、精排、泛 Query 重排、筛选聚合。
- 推荐：触发器、多路召回、过滤、粗排、流量池、精排、模型/规则重排、曝光去重。
- 平台能力：特征、实验、配置、Debug Trace、事件反馈、HTTP API、容器与 Kubernetes。
- 工程迁移：把教学版内存实现替换为 Elasticsearch、Redis、Kafka/Flink、模型服务和配置中心。
- 决策编排：理解需求，选择 Search / Recommendation / Ads 通道，协调多 Agent、复议、解释与确认。
- 算法边界：召回、排序、广告保护、预算、兼容性和实时价格由可评测服务执行，LLM 不取代搜推算法。
- Legacy 教材：墨圆质选、二手 iPhone、回收估价、寄卖履约和只读诊断等历史教学案例。

## Legacy 教学目录

以下内容服务于搜索推荐原理和 V1 Agent 教学。产品演示、V2 API 与当前架构以 [`code/agent-control-plane/README.md`](./code/agent-control-plane/README.md) 为准。

| 文件 | 学习目标 | 配套代码 |
|---|---|---|
| [30 分钟快速上手](./QUICKSTART.md) | 安装、运行、读懂一次搜索和推荐 | CLI、场景回放 |
| [Linux 使用与运维指南](./LINUX_GUIDE.md) | Ubuntu/WSL、离线初始化、服务和容器运行 | Bash、Makefile、Docker、K8s |
| [搜索快速演示手册](./SEARCH_DEMO.md) | 一键导览、命令矩阵和 45 分钟课堂流程 | `quick-search.sh`、`search-tour` |
| [完整技术报告](./technical_report.md) | 先建立全局工程地图 | 全部 |
| [00 课程地图与学习方法](./chapters/00-course-map-and-study-guide.md) | 建立知识地图并选择学习路线 | CLI、Trace |
| [01 业务、领域模型与总体架构](./chapters/01-domain-and-architecture.md) | 把业务目标翻译成服务和数据对象 | `models.py`、`service.py` |
| [02 Query 理解](./chapters/02-query-understanding.md) | 归一化、纠错、同义词、意图和泛 Query | `text.py` |
| [03 搜索召回、排序与重排](./chapters/03-search-pipeline.md) | 完整搜索链路及实现细节 | `search.py`、`ranking.py` |
| [04 推荐召回、过滤与流量池](./chapters/04-recommendation-pipeline.md) | 完整推荐链路及实现细节 | `recommend.py` |
| [05 特征与模型](./chapters/05-features-and-models.md) | 特征口径、粗排、CTR/CVR、多目标排序 | `ranking.py` |
| [06 数据闭环与索引](./chapters/06-data-index-feedback.md) | 商品索引、行为流、实时/离线特征 | `sample_data.py`、`record_event` |
| [07 配置、AB 实验与运营干预](./chapters/07-config-and-experiments.md) | 稳定分桶、分层实验、配置发布 | `experiments.py`、`config/` |
| [08 Debug、可观测性与排障](./chapters/08-observability-troubleshooting.md) | 用数量漏斗和理由码定位问题 | `observability.py` |
| [09 API、云原生和平台工程](./chapters/09-api-cloud-k8s.md) | 服务化、容器化、K8s 和生产组件映射 | `server.py`、`Dockerfile`、`k8s/` |
| [10 实验课](./chapters/10-hands-on-labs.md) | 12 个循序渐进的动手任务 | 整个 `code/` |
| [11 生产落地路线](./chapters/11-production-roadmap.md) | 从 Demo 到生产平台的阶段计划 | 全部 |
| [12 面试与自测](./chapters/12-interview-and-review.md) | 检查是否真正掌握 | 全部 |
| [13 墨圆拟真业务案例](./chapters/13-moyuan-secondhand-business-case.md) | 墨圆质选、回收估价、寄卖、库存和信任搜推 | `valuation.py`、`quick-moyuan-case.sh` |
| [14 Agent 边界、工具与工作流](./chapters/14-agent-boundaries-tools-and-workflow.md) | 明确搜推 Agent 范围、确认和工具合同 | `shoprec_agent.py`、`agent_tools.py` |
| [15 iPhone 购买决策 Agent](./chapters/15-iphone-buyer-agent.md) | 跑通真实购买决策闭环 | CLI、HTTP、`/agent-lab` |
| [16 Grounding、安全、Trace、评测](./chapters/16-grounding-security-trace-evaluation.md) | 证据、注入防护和 100 条门禁 | `agent_grounding.py`、`agent_eval.py` |
| [17 优化、实验与 ModelPort](./chapters/17-agent-optimization-and-modelport-experiments.md) | 离线重放、真实路由和分层优化 | `model_port.py`、ModelPort |
| [18 只读搜推诊断 Agent](./chapters/18-readonly-searchrec-diagnostic-agent.md) | 从漏斗证据到单变量实验 | `diagnostic_agent.py` |
| [工程验收清单](./appendix-engineering-checklist.md) | 设计评审、上线和排障时逐项检查 | 全部 |
| [代码运行手册](./code/README.md) | 安装、运行、API 示例和代码导览 | 整个 `code/` |
| [实验参考解答](./code/labs/solutions.md) | 卡住时查看实现思路和关键代码 | 18 个实验 |

## Legacy 教学路线

如果你只有一天：

1. 读完整技术报告的“系统全景”“搜索主链路”“推荐主链路”。
2. 运行 `bash scripts/quick-search.sh --section rewrite`，再用完整导览观察六类搜索行为。
3. 运行 `bash scripts/quick-moyuan-case.sh`，理解墨圆质选、估价和寄卖链路。
4. 分别阅读 `search.py`、`recommend.py` 和 `valuation.py`。
5. 完成实验 1、2、4、7、9。

如果你有两周，每天 1.5 到 2 小时：

| 天 | 内容 | 完成标准 |
|---|---|---|
| 1 | 总报告、领域模型 | 能画出搜推公共架构 |
| 2 | Query 理解 | 能解释泛 Query 三道关 |
| 3-4 | 搜索召回与过滤 | 能新增召回器和理由码 |
| 5 | 搜索粗排、精排、重排 | 能解释三层排序的成本差异 |
| 6-7 | 推荐召回与过滤 | 能解释触发器和召回器的关系 |
| 8 | 流量池和多目标排序 | 能改配额且预测结果变化 |
| 9 | 特征与模型 | 能识别训练/推理不一致 |
| 10 | 数据闭环和索引 | 能画出事件到在线特征的链路 |
| 11 | AB 和配置 | 能设计互斥实验层 |
| 12 | Debug 与排障 | 能从数量漏斗定位空结果 |
| 13 | API、Docker、K8s | 能解释探针、资源和扩缩容 |
| 14 | 墨圆拟真案例、生产路线、自测 | 能写一份事实边界清楚的业务技术方案 |

## Legacy 教学资产运行入口

以下命令以 Linux Bash 为基准，要求 Python 3.10+。进入 [代码目录](./code/README.md) 后执行一键安装：

```bash
cd moyuan-sar-agent/code
bash scripts/bootstrap.sh
```

无需激活虚拟环境即可运行，也可以用 `make help` 查看统一入口：

```bash
bash scripts/quick-search.sh --section rewrite
bash scripts/quick-moyuan-case.sh
.venv/bin/moyuan agent
.venv/bin/moyuan-server --host 127.0.0.1 --port 18080
.venv/bin/shoprec search "苹果手机" --view summary
.venv/bin/shoprec search "手机" --view trace
.venv/bin/shoprec search "手机" --view explain
.venv/bin/shoprec compare-flowpool
bash scripts/verify.sh
```

旧版二手 Agent 默认使用完全离线、确定性的 ReplayModel。连接当前 WSL 中的 ModelPort：

```bash
cd moyuan-sar-agent/code
bash scripts/modelport-check.sh
bash scripts/modelport-smoke.sh
```

`modelport-smoke.sh` 会显式调用一次真实本地模型；普通测试和 CI 不会产生外部模型调用。安装了 GNU Make 时也可使用同名 `make` 快捷目标。

普通 3C 电商“搜、广、推”多 Agent V2 位于 [`code/agent-control-plane`](./code/agent-control-plane/README.md)。它覆盖 Python 算法数据面、TypeScript/Pi 有界协作、SQLite 异步 Run/SSE，以及 React/ECharts 决策工作台。默认离线启动：

```bash
cd moyuan-sar-agent/code
bash scripts/run-sar-agent.sh --offline
```

真实本地千问使用 `bash scripts/run-sar-agent.sh --local-qwen`。

离线 CI 与专项评测：

```bash
cd moyuan-sar-agent/code/agent-control-plane
npm ci --ignore-scripts --no-audit --no-fund
npm test
npm run demo:cart
npm run eval
```

安装了 GNU Make 时，也可在 `code/` 下使用同名 `sar-agent-*` 快捷目标。

所有课程内路径均相对于 `<project-root>/code`；示例不依赖某台机器的绝对目录。

## 如何理解教学实现

代码刻意保留了生产系统的阶段边界，但把基础设施换成内存对象：

| 教学实现 | 生产实现 |
|---|---|
| `list[Product]` 扫描 | Elasticsearch/OpenSearch、向量库 |
| 线程安全用户快照 | Redis/在线特征库 |
| Python 打分函数 | Java/C++ 排序服务或模型推理服务 |
| JSON 实验配置 | Apollo/Nacos/配置平台 |
| `DebugTrace` | OpenTelemetry + 日志/指标/内部 Debug 平台 |
| 带 TTL 的线程安全曝光存储 | Redis ZSet/Set、KV 特征服务 |
| `record_event` 直接更新 | Kafka/Pulsar → Flink → 湖仓/在线存储 |

因此，学习重点不是记住某个框架类名，而是掌握阶段契约、候选数量变化、特征口径、失败降级和实验隔离。这些能力换语言、换云、换中间件后仍然成立。
