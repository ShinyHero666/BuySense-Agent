# V2 代码导览：跟踪一次购买决策

不要从最大的文件逐行阅读。按请求流向理解，每一步只回答一个问题。

## 1. 先看跨语言契约

- Schema：[`code/packages/contracts/commerce-agent-v2.schema.json`](../code/packages/contracts/commerce-agent-v2.schema.json)
- TypeScript 类型：[`code/agent-control-plane/src/contracts.ts`](../code/agent-control-plane/src/contracts.ts)
- Python 生成类型：[`code/src/shoprec/generated_contracts_v2.py`](../code/src/shoprec/generated_contracts_v2.py)

先认识 `Run`、`RunEvent`、`Constraint`、`Candidate` 和 `Bundle`，暂时忽略所有实现细节。

## 2. 请求如何进入系统

```text
React createRun()
→ POST /api/v2/runs
→ PersistentRunManager
→ SearchAdsRecsBuyerAgent
→ SearchAdsRecsOrchestrator
```

对应入口：

- 前端 API：[`code/apps/commerce-console/src/lib/api.ts`](../code/apps/commerce-console/src/lib/api.ts)
- HTTP 路由：[`code/agent-control-plane/src/server.ts`](../code/agent-control-plane/src/server.ts)
- 异步运行：[`code/agent-control-plane/src/v2-runs.ts`](../code/agent-control-plane/src/v2-runs.ts)
- 购买决策门面：[`code/agent-control-plane/src/buyer-agent.ts`](../code/agent-control-plane/src/buyer-agent.ts)

## 3. Agent 如何协作

- 总编排：[`orchestrator.ts`](../code/agent-control-plane/src/orchestrator.ts)
- 委派预算与允许图：[`collaboration.ts`](../code/agent-control-plane/src/collaboration.ts)
- Pi 角色包装：[`role-agent.ts`](../code/agent-control-plane/src/role-agent.ts)
- 模型策略校正：[`model-policy.ts`](../code/agent-control-plane/src/model-policy.ts)

阅读时重点找六类事件：`delegation_proposal_reviewed`、`delegation_proposal_consumed`、`task_delegated`、`model_execution`、`artifact_published`、`run_completed`。Pi 角色可调用 `request_handoff` 提议下一位同伴，但协调器仍会检查委派边、目标能力、任务数、深度、并发、模型调用、全局截止时间和复议次数。Replay 模式没有模型提案时，Lead 会按确定性计划补齐必要任务并明确记录 fallback。

## 4. 搜索、推荐和广告在哪里执行

TypeScript 通过 [`python-adapter.ts`](../code/agent-control-plane/src/python-adapter.ts) 调用 Python：

- 多路召回与通道排序：[`retail_discovery.py`](../code/src/shoprec/retail_discovery.py)
- RRF、广告保护、证据与组合优化：[`retail_decision.py`](../code/src/shoprec/retail_decision.py)
- HTTP 数据面：[`server.py`](../code/src/shoprec/server.py)

TypeScript 的 [`fusion.ts`](../code/agent-control-plane/src/fusion.ts) 是同契约的本地降级实现，不是第二套产品主线。

当前领域词表来自共享的 [`normal_3c_domain_v1.json`](../code/src/shoprec/data/normal_3c_domain_v1.json)，由 TypeScript 的 [`domain-pack.ts`](../code/agent-control-plane/src/domain-pack.ts) 和 Python 的 [`retail_domain.py`](../code/src/shoprec/retail_domain.py) 同时加载。它是当前 3C 示例包，不是写死在 Agent 里的产品边界。

## 5. 状态为什么不会只存在内存里

- SQLite Repository：[`v2-repository.ts`](../code/agent-control-plane/src/v2-repository.ts)
- 匿名身份：[`v2-identity.ts`](../code/agent-control-plane/src/v2-identity.ts)
- Run 与 SSE 恢复：[`v2-runs.ts`](../code/agent-control-plane/src/v2-runs.ts)
- 确认与购物车草案：[`cart-agent.ts`](../code/agent-control-plane/src/cart-agent.ts)

先理解“身份隔离、幂等、TTL、取消、恢复”五个行为，再看表结构。确认必须绑定 `proposalRunId`；同一方案的不同重试解析到同一确认 Run。失败或取消后，只有原 pending proposal 仍在 TTL 内且未被替换时，新 key 才会开启该 Run 的下一 attempt。公开 SSE 与工作台默认只重放当前 attempt，完整历史保留在 SQLite 事件账本。

报价刷新不持有数据库锁，最终以一个短事务提交 pending 消费、草案、Run 终态和 terminal event。普通方案的 pending 发布或按 generation 清理、约束、Run 终态与 terminal event 也在同一事务。Run 创建与首事件、Trace 投影与 ledger event 采用相同的原子写入边界。执行前必须取得 lease，后续 Trace、方案发布和终态写入都校验 fencing token；取消先持久化终态，再以 `AbortSignal` 停止融合、兼容、报价与评论调用。未响应取消的旧 attempt 仍计入容量，SSE 从共享 SQLite ledger 增量补齐事件；这些机制支持发布时的短暂进程重叠，但不改变 SQLite 单副本部署约束。

## 6. 前端如何展示证据

- 应用状态：[`App.tsx`](../code/apps/commerce-console/src/App.tsx)
- 决策视图：[`DecisionView.tsx`](../code/apps/commerce-console/src/views/DecisionView.tsx)
- 协作视图：[`CollaborationView.tsx`](../code/apps/commerce-console/src/views/CollaborationView.tsx)
- 质量视图：[`QualityView.tsx`](../code/apps/commerce-console/src/views/QualityView.tsx)

前端不重新计算算法结果，只展示服务端发布的计划、候选、证据、审核和指标。

## 7. 最后看质量门禁

- TypeScript 测试：[`code/agent-control-plane/test`](../code/agent-control-plane/test)
- Python 测试：[`code/tests`](../code/tests)
- Agent 评测：[`evaluation.ts`](../code/agent-control-plane/src/evaluation.ts)
- 检索评测：[`evaluate_retail_v2.py`](../code/scripts/evaluate_retail_v2.py)
- 总门禁：[`verify-vnext.sh`](../code/scripts/verify-vnext.sh)

推荐边运行边读：先完成 Lab 1，再回到本导览继续下一段。

注意 `golden_queries_v2.json` 是可重复生成的合成回归集，相关性等级由规则产生。它适合阻止代码回退，不等价于人工标注金标，也不能直接证明线上业务收益。
