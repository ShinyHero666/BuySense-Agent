# Domain Pack 扩展指南

Domain Pack 是 Moyuan SAR Agent 的数据扩展单元。对现有 `commerce-decision-v1` 工作流增加新领域时，不需要修改控制面、编排器、Python 算法服务、公共契约或前端。

## 扩展边界

稳定 core 包含 Run/SSE/取消与恢复、身份和 SQLite、契约校验、决策阶段的 Capability/Workflow Policy Registry、有界协作、通用 Search/Recommendation/Ads、融合与组合优化。确认购物车草案是审核通过后的独立安全阶段，不属于 `commerce-decision-v1` 的能力图。领域包负责：

- 类目、同义词、用途、品牌和默认组合；
- catalog 的 SPU/SKU/Offer 快照；
- Review Aspect 快照；
- 主商品与配件的版本化兼容规则；
- 可直接在工程工作台运行的代表性 Query。

`normal-3c-v1` 和 `outdoor-camping-v1` 都位于 [`code/src/shoprec/data`](../code/src/shoprec/data)。Node 与 Python 注册表会扫描带 `pack_id` 的 JSON manifest，并验证其资产引用；前端通过 `GET /api/v2/domain-packs` 自动生成选择器。

## 新增一个包

在同一目录加入四个版本化 JSON 文件：

1. `<name>_domain_v1.json`：manifest，声明 `pack_id`、`workflow_id`、类目、词表、默认组合、资产和示例。
2. `<name>_catalog_v1.json`：SPU/SKU/Offer、库存、价格、广告质量和兼容属性。
3. `<name>_review_aspects_v1.json`：每个可选商品的版本化评论 Aspect。
4. `<name>_compatibility_graph_v1.json`：主类目到配件类目的 connector/protocol 规则。

类目 ID 使用小写字母、数字和下划线；pack/workflow ID 使用小写连字符并以 `-vN` 结尾。manifest 中的 `assets` 只能引用同目录 JSON，注册表拒绝路径穿越、重复 pack ID、未声明类目和缺失资产。

如配件在召回前需要最低兼容条件，在 `category_requirements` 中声明 `connectors_any` / `protocols_any`；不要在算法代码中按类目写分支。可参考 [`outdoor_camping_domain_v1.json`](../code/src/shoprec/data/outdoor_camping_domain_v1.json)。

当前 `commerce-decision-v1` 的商品合同仍限定 `CNY`，并使用 `ios | android | universal` 作为兼容生态字段；非 3C 领域暂以 `universal` 表示不受手机生态约束。因此这里的 data-only 扩展范围是“可复用现有人民币报价与 connector/protocol 兼容语义的零售领域”，不是任意币种或任意商品模型。放宽 currency/ecosystem 属于公共契约升级，应走新的版本化工作流评审。

## 验收与 VESR

```bash
cd code
PYTHONPATH="$PWD/src" python3 -m unittest discover -s tests -v
cd agent-control-plane
npm run check
npm test
cd ../..
bash code/scripts/test-frontend-e2e.sh
```

控制面测试会对每个正式注册包运行其 `queries` 资产中的全部场景；未声明该资产时运行全部 `example_queries`。每个场景都要求 Critic 通过、预期意图/广告开关/类目成立、Trace 记录正确 pack/workflow，且代表场景必须覆盖默认组合。输出 `VESR verified/registered` 与场景数；任何包失败都会使 CI 失败。Python 测试另行验证 catalog、Review Aspect、兼容图和 Query 的跨资产引用，容器烟测会遍历镜像中实际发现的所有 pack。临时第三包测试还会验证注册表没有 core 类目白名单。

若变更需要新增角色或改变执行顺序，它不是纯 Domain Pack 扩展，应新增版本化 Capability/Workflow 定义并单独评审；不要借领域 manifest 隐式改变安全边界。
