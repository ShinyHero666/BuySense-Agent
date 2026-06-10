# Lab 17：ModelPort 与参数归一化

先做无模型费用的预检：

```bash
bash scripts/modelport-check.sh
```

再显式运行一个真实本地模型烟测：

```bash
bash scripts/modelport-smoke.sh
bash scripts/check-lab.sh 17
```

检查路由决策 ID、工具名、Token、延迟、降级状态和候选数。解释为什么模型返回 `size=5` 时业务代码仍执行 `size=3`。

扩展：比较两个 routing profile，但保持 Prompt、工具 schema 和评测集不变。
