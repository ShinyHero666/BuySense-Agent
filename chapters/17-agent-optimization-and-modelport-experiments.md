# 17 Agent 优化、实验与 ModelPort

> **Legacy 教材**：本章保留 V1 Agent 实验方法；ModelPort 别名继续兼容，墨圆智选当前多 Agent 架构见 [Moyuan SAR Agent V2](../code/agent-control-plane/README.md)。

Agent 优化应从可复现失败出发，不从“换个更大模型试试”出发。推荐顺序是：

```text
失败样本与标签
→ 工具契约
→ 确定性状态机
→ 检索与证据
→ Prompt/工具描述
→ ModelPort 路由
→ 最后才考虑微调
```

## 1. 双模式开发

默认/CI 使用 `ReplayModel`：无网络、无费用、固定 ID/时间可注入、工具选择可重放。

真实模式只通过外部 ModelPort：

```text
/home/tiammomo/projects/dev/ModelPort
http://127.0.0.1:38082/v1/chat/completions
logical alias: moyuan-shoprec-agent
```

课程 Adapter 不导入任何 Provider SDK，也不接触 Provider Key。ModelPort 负责认证、路由、配额、Provider 健康和内部有界回退；Agent 侧不自动重试模型调用，失败后用确定性策略完成只读业务步骤。

## 2. ModelPort 配置

本机 `config.toml` 的静态别名：

```toml
[aliases]
"moyuan-shoprec-agent" = "local_qwen:qwen3.5-9b-q5km"
```

项目环境只需要：

```bash
export MOYUAN_MODELPORT_BASE_URL=http://127.0.0.1:38082
export MOYUAN_MODELPORT_API_KEY='<scoped ModelPort client key>'
export MOYUAN_MODELPORT_MODEL=moyuan-shoprec-agent
```

本机教学环境可用包装脚本读取既有 ModelPort 环境，且不会打印密钥：

```bash
bash scripts/modelport-check.sh
```

## 3. 真实烟测

```bash
bash scripts/modelport-smoke.sh
```

报告检查：ModelPort ready、别名可见、路由决策 ID、工具名、候选数、是否降级、延迟和 Token。脚本只有显式执行时才调用真实模型。

## 4. 实验设计

每次只改一个变量：

| 实验 | 主指标 | 安全护栏 |
|---|---|---|
| 预算优先 vs 风险优先澄清 | 首次有效候选轮次 | 硬约束违反=0 |
| 候选 2 vs 3 | 任务完成率、比较率 | 有据事实精确率 |
| 简洁 vs 取舍解释 | 保存确认率 | 未授权写入=0 |
| ModelPort routing profile | 延迟、Token、工具正确率 | 同一离线门禁全通过 |
| 工具描述 v1 vs v2 | 工具/参数正确率 | 最大工具数不变 |

不要同时改 Prompt、路由和候选数，否则无法归因。线上实验前先用同一隐藏集离线回放；失败样本必须进入版本化数据集，而不是只写在聊天记录里。

## 5. 何时优化哪一层

- 约束抽错：先修标签和抽取器；
- 模型选错工具：先缩小当前状态可见工具和改工具描述；
- 模型修改硬参数：业务代码归一化，不靠劝说；
- 商品事实幻觉：减少自由生成，补证据字段；
- 空结果多：查召回和过滤漏斗，不调 Prompt；
- ModelPort 超时：查路由/Provider 健康，Agent 保持单次降级；
- 同类错误在大规模样本持续存在，且 Prompt/工具契约已稳定：才评估微调。

## 6. 五天学习路线

| 天 | 约 2 小时内容 | 完成标准 |
|---|---|---|
| 1 | 边界、工具、状态机 | 未确认写入测试通过 |
| 2 | iPhone 购买闭环、CLI/HTTP/UI | 完成搜索→比较→保存 |
| 3 | Grounding、安全、Trace | 注入和 PII 回归通过 |
| 4 | 100 条评测、失败修复 | 所有离线门禁通过 |
| 5 | ModelPort 真实烟测、诊断 Agent | 有路由证据和只读诊断报告 |

Part I 的搜索、推荐、实验和可观测性是先修内容。Part II 约 10–12 小时，不重复讲基础召回和排序。

对应实验：[Lab 17](../code/labs/lab17-modelport-optimization.md)。
