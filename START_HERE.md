# 从这里开始：BuySense 新生指南

这份指南面向第一次运行本项目的搜广推与 Agent 工程师，也可作为学生实验入口。你不需要 GPU、本地大模型、ModelPort 或 API Key，也能先跑通完整的 V2 决策工作台。

## 1. 你会看到什么

BuySense 把一次自然语言购买需求拆成六步：

```text
理解需求
→ Search / Recommendation / Ads 多路候选
→ 融合与广告保护
→ 预算和兼容性组合优化
→ 评论、价格等证据核验
→ Critic 审核与可解释决策
```

Agent 负责理解、选择、协调和解释；召回、排序、预算、广告合规、兼容性与价格仍由确定性算法服务负责。

工作台可在 `normal-3c-v1` 与 `outdoor-camping-v1` 之间切换；领域选择会随 Run 持久化并进入审计事件。两者共用同一套 Capability / Workflow Registry、编排器、契约和 UI。新增同工作流垂类时只提交版本化 Domain Pack 资产，不把行业词散落到 core；完整方法见 [Domain Pack 扩展指南](./docs/DOMAIN_PACKS.md)。

## 2. 首次运行要求

| 项目 | 最低要求 | 用途 |
|---|---|---|
| 操作系统 | Linux、macOS，或 Windows + Git Bash | 启动脚本使用 Bash；浏览器 E2E 提供跨平台 Node 入口 |
| Python | 3.10+ | 搜索、推荐、广告与证据数据面 |
| Node.js | 22.19+ | Pi Agent 控制面和 React 工作台 |
| npm | 随 Node.js 安装 | 首次下载锁定依赖 |
| curl | 任意近期版本 | 健康检查 |

默认离线模式不需要 Docker、GPU、ModelPort 或密钥。它强制使用仓库内确定性零售快照并关闭 fallback，不访问 Catalog、Review 或 Pricing 上游。第一次安装 Node 依赖需要能够访问 NPM 仓库。

## 3. 五分钟跑通离线 V2

```bash
git clone https://github.com/ShinyHero666/BuySense-Agent.git
cd BuySense-Agent
bash code/scripts/run-sar-agent.sh --offline --check
bash code/scripts/run-sar-agent.sh --offline
```

看到下面两行即表示启动成功：

```text
[start] Opening the decision console at http://127.0.0.1:19090/
Search-ads-recs Agent is listening on http://127.0.0.1:19090
```

打开 <http://127.0.0.1:19090/>。先选择 3C 或户外露营 Domain Pack，再选择示例并点击“开始决策”，然后依次查看：

1. 约束雷达：预算、品类和偏好来自哪里。
2. 搜推广融合：三个通道分别贡献了多少候选。
3. 最优决策：预算、库存、兼容和广告策略是否通过。
4. 协作拓扑：Agent 如何委派任务。
5. 质量与运行：离线质量门禁和服务健康。

离线模式会明确显示 `replay`，质量页还会把三个零售来源标为 `local_snapshot`。它使用确定性模型替身和静态零售快照验证工作流，不是假装调用了真实模型或真实商品数据。按 `Ctrl+C` 会同时停止本次启动的数据面和控制面。

## 4. 单独验收真实零售数据

如果已有兼容的 Catalog / Review / Pricing HTTP provider，可以保持 Pi Replay 不变，只切换数据源：

```bash
cp code/.env.example code/.env
# 填写 MOYUAN_RETAIL_DATA_BASE_URL；需要鉴权时再填写 scoped API key。
bash code/scripts/run-sar-agent.sh --http-data --check
bash code/scripts/run-sar-agent.sh --http-data
```

HTTP 模式默认失败关闭，不会悄悄使用本地样本。只有明确设置
`MOYUAN_RETAIL_DATA_FALLBACK_ENABLED=true` 才允许降级；质量页会显示实际来源、版本、脱敏 provider 标识、请求/错误/降级计数和 fallback 状态。完整 provider 契约与健康语义见[控制面 README](./code/agent-control-plane/README.md)。

## 5. 再连接本地千问

完成离线路径后，再尝试真实模型。前提是 ModelPort 已在本机提供兼容端点和 scoped client key：

```bash
cp code/.env.example code/.env
# 编辑 code/.env：模型侧只填写 ModelPort client key，不要填写原始模型 Provider key；
# 零售数据侧仅可填写 scoped read-only key。
bash code/scripts/run-sar-agent.sh --local-qwen --check
bash code/scripts/run-sar-agent.sh --local-qwen
```

也可以直接导出 `MOYUAN_MODELPORT_API_KEY`，或用 `MODELPORT_ENV_FILE` 指向受控配置文件。真实模式只是替换 LLM 角色运行时，硬约束、算法服务和安全门禁保持不变。

## 6. 推荐学习顺序

1. 阅读[术语表](./docs/GLOSSARY.md)，先认识十个核心词。
2. 阅读[V2 代码导览](./docs/V2_CODE_TOUR.md)，跟踪一条请求。
3. 完成[V2 六步实验](./docs/v2-labs/README.md)。
4. 运行完整门禁：`cd code && bash scripts/verify-vnext.sh`。
5. 有 Docker 时运行 V2 镜像烟测：`bash scripts/test-v2-containers.sh`。
6. 最后再选择性阅读根目录下标记为 Legacy 的传统搜推课程。

## 7. 遇到问题

先执行：

```bash
bash code/scripts/run-sar-agent.sh --offline --check
```

然后查看[首次运行排障](./docs/TROUBLESHOOTING.md)。不要为了“先跑起来”关闭约束、广告保护或确认门禁。
