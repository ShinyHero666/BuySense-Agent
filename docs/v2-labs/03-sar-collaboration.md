# Lab 3：搜推广协作

## 目标

理解 Search、Recommendation、Ads 可以提出动态协作请求，但不能任意互调或无限消耗模型。

## 观察

比较“套装决策”和“不要广告”的协作拓扑，记录实际执行的通道和任务数。区分虚线的“允许委派边”和实线的“本次真实委派”。在真实 Pi 模式中查找 `delegation_proposal_reviewed` 与 `delegation_proposal_consumed`；在 Replay 模式中查找 Lead 的 `handoff_fallback_scheduled`。

## 动手

在 `collaboration.test.ts` 中尝试增加一条未授权委派，确认协调器拒绝；再在 `role-agent.test.ts` 中修改一次 `request_handoff` 的目标能力，验证 Pi 角色只能提议，最终审批权仍属于协调器。

## 检查

```bash
bash scripts/check-v2-lab.sh 3
```

完成证据：画出允许图与实际执行图，并标注 18 tasks、12 proposals、6 model calls、90 秒 deadline、1 revision 五个上限。
