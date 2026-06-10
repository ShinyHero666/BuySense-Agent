# Lab 18：只读搜推诊断 Agent

```bash
PYTHONPATH=src:. python3 -m shoprec.cli diagnose-search iphone --max-price 700
bash scripts/check-lab.sh 18
```

检查每条 finding 都有阶段、证据、假设和下一实验，并且报告标记 `read_only=true`。

扩展：构造一个 RERANK 多样性异常 Trace。诊断 Agent 只能建议单变量实验，不能修改实验配置或触发发布。
