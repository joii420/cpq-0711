# api · repair-260916 小计列无法选为字段引用

> 本返修**不新增、不删除、不改动任何接口的路径 / 请求结构 / 响应结构 / 状态码语义**。
> 变的是「公式文字」这份跨端数据契约（前端 `expressionToTokens` 与后端 `TabJoinPlanEvaluator` 各解析一份）。导出包版本号不变（D-15）。
> 验收以 `问题说明.md` ⑥ 为准；写法细则的唯一出处是 `问题说明.md` 5.1 / 5.3 / 5.4，本文件只做契约登记与测试入口说明。

## §1 涉及的既有接口（路径与结构均不变）

| 接口 | 本返修里的角色 | 变化 |
|---|---|---|
| `PUT /api/cpq/components/{id}` | 保存页签 / 小计组件的公式（`formulas[].expression` 为 token 数组） | 无。token 结构不变；前端按新写法规则产出 token |
| `GET /api/cpq/components/{id}` | 读公式 token（AC-1/2/3/6/8 断言用） | 无 |
| `GET /api/cpq/components/{id}/tab-defs` | 抽屉左栏数据（`detailFields` / `subtotalCols` / `rowKeyFields`）；AC-13 往返用的 `tabDefs` 来源 | 无 |
| `PUT /api/cpq/components/{id}`（Excel 组件的 `excelColumns`） | Excel 连表公式列文字（`source_type=TAB_JOIN_FORMULA`，`expression` 为文字） | 无结构变化；`expression` 可出现 `(小计)`（§2） |
| `PUT /api/cpq/templates/{id}/excel-view-config` | 模板级 Excel 配置保存，含 `validateTabJoinConfig` 校验 | 新增一种 400：`页签连表公式列 {col_key} 的「(小计)」要写在列名后面，如 [页签.列(小计)]`（AC-14f） |
| `GET /api/cpq/quotations/{id}/excel-view` | 后端重算的 Excel 视图（AC-15⑥） | 无 |
| `GET /api/cpq/component-directories/{id}/export` | 导出包 | 无变化（D-15：`bundleVersion` 保持 `"1.1"`） |
| `POST /api/cpq/component-directories/{id}/import` / `…/import/commit` | 导入预览 / 提交 | 无变化（D-15：Excel 连表公式文字原样写库，不改写；AC-16） |

⇒ `dev-docs/main-api.md` **无端点需要回写**；`test-report.md` 需写明「本次无接口契约变更，无需回写 main-api.md」。

## §2 公式文字写法（跨端契约）

完整规则见 `问题说明.md` 5.1。核心对应关系：

| 文字 | 页签 / 小计组件公式（前端解析为 token） | Excel 连表公式列（前端 `buildExcelSnapshot` 与后端 `TabJoinPlanEvaluator` 必须同值） |
|---|---|---|
| `[页签.列]` | `cross_tab_ref` `agg=NONE`，`match`=两页签行键交集；**与列是否勾小计无关** | 明细引用：按行键对齐，逐行取值（5.3） |
| `[页签.列(小计)]` | `component_subtotal`（`value=列`，`tab_name=component_code=页签编号`，`label={页签名}·{列}`，无 `is_tab_total`） | 列小计标量 |
| `[页签.列(总计)]` | `cross_tab_ref` `agg=SUM`（回显为 `SUM([页签.列])`） | 列小计标量（现行，不变） |
| `[页签(总计)]` | `component_subtotal`（`is_tab_total=true`，哨兵列键） | 页签合计标量（现行，不变） |
| `[页签(小计)]` | 报错 | 报错（后端 `IllegalArgumentException` / 保存 400） |
| `SUMIF` / `COUNTIF` / `AVGIF` / `MINIF` / `MAXIF` | 现行（不变） | **不支持**：Excel 组件抽屉保存拒绝（D-9），后端求值得 0（现行） |

**回显**（token → 文字，`tokensToDrawerExpression`）：`component_subtotal` 且非页签合计、列名非空 → `[{页签名}.{列}(小计)]`；其余不变。
**往返稳定性**：对任意已存 token，「回显 → 解析」结果与原 token 逐字段相等（AC-3③、AC-13）。

## §3 导出包版本（D-15 修订：不变）

- `bundleVersion` 保持 `"1.1"`；导入时不改写任何公式文字；导入抽屉「旧格式」提示判定保持原样。
- 升级前导出的包里，Excel 列的 `[页签.小计列]` 导入后按「本行的值」计算，需要用户手动改为 `(小计)` 写法（`问题说明.md` 5.4）；页签组件、小计组件公式存的是 token，不受影响。
- ⚠️ 含 `(小计)` 写法的 Excel 列导出后，导入到**未升级**的环境会算错 —— 部署时各环境同批升级。
- ~~原方案：升 `1.2` + 导入改写 + 前端判定改为「低于 1.1」~~（D-7 / P16 / P16b 已撤销）

## §4 共享对拍夹具 `tabjoin-excel-cases.json`

两份，内容逐字节相同：
- 后端：`cpq-backend/src/test/resources/tabjoin-excel-cases.json`（B-5 定稿）
- 前端：`cpq-frontend/src/pages/quotation/__fixtures__/tabjoin-excel-cases.json`（F-8 拷贝）

格式（B-5 可补字段，但不得删这些键）：

```json
{
  "tabs": [
    { "alias": "物料", "tabKey": "<组件id>", "rowKeyFields": ["销售料号", "料号"],
      "fields": [ { "name": "材料成本", "is_subtotal": true }, { "name": "X", "is_subtotal": false } ],
      "rows": [ { "销售料号": "S3120011203", "料号": "S3120011203", "材料成本": "0", "X": "…" } ],
      "subtotalByColumn": { "材料成本": "1.978941064" },
      "tabTotal": "…" }
  ],
  "cases": [
    { "id": "AC-14a", "expression": "[物料.材料成本(小计)]", "expected": "1.978941064" }
  ]
}
```

- `rows` 取 `QT-20260916-0881` 物料页签 6 行真实值（AC-14 前置），`X` 为补充的不勾小计列。
- 用例至少覆盖 AC-14 a~e 与 g（`/ 1.13` 除以小数，D-10），夹具追加用例后前端须重新逐字节拷贝。
- `expected` 为 12 位计算口径下的字符串；两端测试按各自现行比较方式断言（后端 `compareTo`，前端 decimal 字符串比较），**不许放宽容差**。

## §5 测试可直接调用的前端入口（测试工程师无需读实现）

| 函数 | 模块 | 用途 |
|---|---|---|
| `expressionToTokens(expr, tabDefs, selfRowKeyFields?, selfComponentId?)` | `cpq-frontend/src/pages/component/formulaSerialize.ts` | 文字 → token；非法写法时抛错，错误消息即 5.1 文案 |
| `tokensToDrawerExpression(tokens, tabDefs, selfComponentId?)` | 同上 | token → 文字 |
| `classifyRefSegment(body, tabDefs, selfRowKeyFields, enforceMappable, insideFn?, insideKsum?, insideSumif?)` | 同上 | 单个 `[...]` 块的颜色：返回 `{ kind, color }`，`color ∈ blue/yellow/green/purple/red` |
| `evaluateExpression(tokens, fieldValues, componentSubtotals, …)` | `cpq-frontend/src/utils/formulaEngine.ts` | 前端引擎求值（签名不变；参数顺序参考 `证据/离线判决/evalh85.mts`） |
| `buildExcelSnapshot(...)` | `cpq-frontend/src/pages/quotation/buildExcelSnapshot.ts` | Excel 快照求值（签名不变） |

`tabDefs` 的形状以 `GET /api/cpq/components/{id}/tab-defs` 的**实际响应**为准（`alias` / `tabKey` / `componentId` / `componentName` / `rowKeyFields` / `detailFields` / `allFields` / `subtotalCols` / `self`）。
