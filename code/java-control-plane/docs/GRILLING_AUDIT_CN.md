# BuySense Grilling 反向审查

审查日期：2026-08-13。用户此前已经明确业务目标、时间边界、项目归属和“工作流/Agent 按业务选择”的原则，因此本次使用 Grilling 的设计树做构建后反向质询，不重复询问已经确定的决策。

## 设计树结论

| 决策节点 | 反向质询 | 代码证据 | 结论 |
|---|---|---|---|
| 业务范围 | 这是万能电商 Agent，还是有边界的产品？ | `normal-3c-v1` 为主包，`outdoor-camping-v1` 为扩展证明；币种和兼容语义仍受公共合同限制 | 通过：定位为多领域零售决策内核，不宣称任意商品 |
| 工作流/Agent | 为什么复杂请求一定用 Agent，简单请求也要付 Token 吗？ | `ExecutionRouter` 单路由 CLARIFICATION / WORKFLOW / HYBRID | 通过：按复杂度选择，不在线同步双跑 |
| 能力委派 | 所谓多 Agent 是否只是随意互调？ | 10 项能力、版本化 Workflow、未登记 delegation 直接拒绝 | 通过：角色边界可验证 |
| 硬约束 | Planner 或 Critic 能否改预算、品类、拒绝广告？ | 约束来源/强度、Query Rewrite 合并规则、Policy Gate | 通过：模型只增强软信息 |
| 组合质量 | 逐品类 Top1 为什么不够？ | 有界全局枚举、预算/库存/兼容图联合校验 | 通过：避免局部最优 |
| 外部数据 | Provider 返回重复键、未知报价或缺库存怎么办？ | 严格 JSON、唯一 ID、Review 分区、Quote 精确集合、远端库存复验 | 通过：脏数据失败关闭 |
| 降级真相 | 本地 fallback 会不会伪装成远端实时数据？ | `effectiveSource`、`fallbackActive`、错误码和低基数计数 | 通过：来源可审计 |
| Run 安全 | 能否读、取消或确认别人的 Run？ | 服务端会话所有权、跨会话 404、SSE 保护 | 通过 |
| 幂等/确认 | 相同幂等键能否换请求，确认金额能否被替换？ | 请求指纹绑定 Domain/Workflow；确认绑定原提案与金额 | 通过 |
| 指标真实性 | 指标是人工金标、合成回归还是真实线上？ | 报告记录 `evaluation_kind` 与 `label_provenance` | 通过：口径拆分，删除无原始报告的 DeepSeek 数字 |
| 测试运维 | E2E 结束会不会留下服务？ | Node 跨平台启动器、双端口所有权预检、`finally` 清理 | 通过 |

## 审查中发现并修复

1. 删除 3C manifest 中没有目录资产支撑的 tablet、monitor、camera、smartwatch，避免纸面能力大于实际能力。
2. 修正拒绝广告语义：只关闭 Ads 通道，不误删自然搜索/推荐召回的同一 Offer。
3. Provider 增加重复 JSON 键、重复 SPU/SKU/Offer、评论分区、报价精确集合与报价后默认库存校验。
4. 增加 Java 远端 Provider 成功合同测试以及重复键、重复 Offer、未知报价 ID 拒绝测试。
5. 补齐跨会话 Run/SSE/取消/确认保护、并发幂等请求一致性和原提案精确确认。
6. 修复 Windows `npm run test:e2e` 误调用 WSL、`.cmd` 启动失败及控制面残留问题。
7. 删除简历和作品集里缺少原始报告文件的 DeepSeek Token/延迟数字，改用可复跑的固定基准与合同测试。

## 剩余风险

- Shopify canary 因无真实凭证而跳过，不能声称已连通真实商城。
- 真实模型基准默认跳过；模型效果、Token 和延迟需要生成新的原始报告后再写简历。
- 3C 和露营目录仍是参考快照，合成检索标签来自目录属性，不能外推为线上 CTR/CVR 或人工相关性。
- 当前 Domain Pack 复用范围受 CNY 与 connector/protocol 兼容合同约束；运动器材若引入尺码或租赁语义，需要公共 Schema 升级。

最终判断：项目具备真实业务背景、明确的确定性/模型边界、可插拔数据合同、运行安全和分层评测，不再是固定节点串联的玩具工作流；面试时必须如实说明参考数据和真实 Provider/LLM 尚未验收的边界。