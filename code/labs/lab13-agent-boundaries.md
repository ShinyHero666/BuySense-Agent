# Lab 13：Agent 边界与工具契约

目标：证明业务边界由代码守住，而不是只写在 Prompt。

1. 阅读 `agent_tools.py` 的六个工具定义。
2. 用错误 boolean、未知字段和未确认写入调用 registry。
3. 验证三个请求都抛出结构化 `ValidationError`。
4. 解释为什么购买 Agent 不暴露 `value_device`。

```bash
bash scripts/check-lab.sh 13
```

扩展：为工具契约增加一个新字段，并同时补 schema、handler、Trace 和边界测试。
