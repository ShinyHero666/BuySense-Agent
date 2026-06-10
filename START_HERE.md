# 从这里开始：墨圆智选新生指南

这份指南面向第一次接触搜广推和 Agent 的学生。你不需要 GPU、本地大模型、ModelPort 或 API Key，也能先跑通完整的 V2 决策工作台。

## 1. 你会看到什么

墨圆智选把一次自然语言购买需求拆成六步：

```text
理解需求
→ Search / Recommendation / Ads 多路候选
→ 融合与广告保护
→ 预算和兼容性组合优化
→ 评论、价格等证据核验
→ Critic 审核与可解释决策
```

Agent 负责理解、选择、协调和解释；召回、排序、预算、广告合规、兼容性与价格仍由确定性算法服务负责。

当前演示数据使用 3C Domain Pack，但系统不是“二手平台 Agent”或“只能卖 3C 的 Agent”。品类、用途、品牌别名和默认套装集中在 `code/src/shoprec/data/normal_3c_domain_v1.json`；扩展新垂类时应新增版本化 Domain Pack 和对应契约/评测，不要把行业词继续散落到编排代码中。

## 2. 首次运行要求

| 项目 | 最低要求 | 用途 |
|---|---|---|
| 操作系统 | Linux 或 WSL2 | 项目只维护 Bash 入口 |
| Python | 3.10+ | 搜索、推荐、广告与证据数据面 |
| Node.js | 22.19+ | Pi Agent 控制面和 React 工作台 |
| npm | 随 Node.js 安装 | 首次下载锁定依赖 |
| curl | 任意近期版本 | 健康检查 |

默认离线模式不需要 Docker、GPU、ModelPort 或密钥。第一次安装 Node 依赖需要能够访问 NPM 仓库。

## 3. 五分钟跑通离线 V2

```bash
git clone https://github.com/tiammomo/moyuan-sar-agent.git
cd moyuan-sar-agent
bash code/scripts/run-sar-agent.sh --offline --check
bash code/scripts/run-sar-agent.sh --offline
```

看到下面两行即表示启动成功：

```text
[start] Opening the decision console at http://127.0.0.1:19090/
Search-ads-recs Agent is listening on http://127.0.0.1:19090
```

打开 <http://127.0.0.1:19090/>。选择“套装决策”并点击“开始决策”，然后依次查看：

1. 约束雷达：预算、品类和偏好来自哪里。
2. 搜推广融合：三个通道分别贡献了多少候选。
3. 最优决策：预算、库存、兼容和广告策略是否通过。
4. 协作拓扑：Agent 如何委派任务。
5. 质量与运行：离线质量门禁和服务健康。

离线模式会明确显示 `replay`。它使用确定性模型替身验证工作流，不是假装调用了真实大模型。按 `Ctrl+C` 会同时停止本次启动的数据面和控制面。

## 4. 再连接本地千问

完成离线路径后，再尝试真实模型。前提是 ModelPort 已在本机提供兼容端点和 scoped client key：

```bash
cp code/.env.example code/.env
# 编辑 code/.env，只填写 ModelPort client key，不要填写 Provider key。
bash code/scripts/run-sar-agent.sh --local-qwen --check
bash code/scripts/run-sar-agent.sh --local-qwen
```

也可以直接导出 `MOYUAN_MODELPORT_API_KEY`，或用 `MODELPORT_ENV_FILE` 指向受控配置文件。真实模式只是替换 LLM 角色运行时，硬约束、算法服务和安全门禁保持不变。

## 5. 推荐学习顺序

1. 阅读[术语表](./docs/GLOSSARY.md)，先认识十个核心词。
2. 阅读[V2 代码导览](./docs/V2_CODE_TOUR.md)，跟踪一条请求。
3. 完成[V2 六步实验](./docs/v2-labs/README.md)。
4. 运行完整门禁：`cd code && bash scripts/verify-vnext.sh`。
5. 有 Docker 时运行 V2 镜像烟测：`bash scripts/test-v2-containers.sh`。
6. 最后再选择性阅读根目录下标记为 Legacy 的传统搜推课程。

## 6. 遇到问题

先执行：

```bash
bash code/scripts/run-sar-agent.sh --offline --check
```

然后查看[首次运行排障](./docs/TROUBLESHOOTING.md)。不要为了“先跑起来”关闭约束、广告保护或确认门禁。
