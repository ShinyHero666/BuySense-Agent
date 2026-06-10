# Lab 16：100 条 Agent 评测

```bash
PYTHONPATH=src:. python3 scripts/run_agent_eval.py \
  --json-out agent_eval/reports/offline-v1.json \
  --markdown-out agent_eval/reports/offline-v1.md
bash scripts/check-lab.sh 16
```

先阅读失败列表，再按“数据/标签 → 工具契约 → 状态机 → 证据 → Prompt”的顺序修复。禁止为了过评测在 runner 中硬编码 case ID。

扩展：新增 10 条表达方式不同但语义相同的隐藏式用例，并保持既有门禁。
