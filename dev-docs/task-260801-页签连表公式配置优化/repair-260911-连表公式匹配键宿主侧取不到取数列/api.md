# api · repair-260911 连表公式匹配键宿主侧取不到取数列

## 结论：本次**零接口改动**

### 为什么不改

本缺陷发生在**求值期的内存计算**，不在任何请求/响应的边界上：

- 匹配键取值发生在后端 `FormulaCalculator.evalCrossTab` 内部、前端 `formulaEngine.evaluateExpression` 内部
- 修复新增的是**进程内的一份行视图**（后端 `RowContext` 字段 / 前端函数入参），不进任何 DTO
- 卡片值的落库结构 `costing_card_values` / `quote_card_values` 的 **schema 不变**，变的只是 `formulaResults[].values` 里某些数值由 `"0"` 变成真实值 —— 这是数据内容变化，不是契约变化
- 无新增端点、无参数增删、无字段改名、无枚举扩充、零迁移、零 DDL

### 前后端之间真正的「契约」在哪

不是 HTTP 接口，是**共享夹具** `cross-tab-cases.json`（后端 `cpq-backend/src/test/resources/`、前端 `cpq-frontend/src/utils/__fixtures__/`）。

🚫 **两份内容必须逐字一致** —— 它是前后端求值口径的唯一对拍基准。B-3 与 F-3 必须先对齐用例内容，再各自提交。这次修复引入的一条新口径（`""` 表示显式清空、不补 default_source）**只能靠它锁死**，没有别的地方能拦。

### 回归确认清单（无需改动，但要确认没被动到）

| 项 | 确认方式 |
|---|---|
| `GET /api/cpq/quotations/{id}` 的响应结构 | 改动前后 `jq 'paths'` 对比，键集合一致 |
| `costing_card_values` / `quote_card_values` 的 jsonb 键结构 | 同上；只允许 `formulaResults[].values` 的**数值**变化 |
| 报价侧数值 | `quote_card_values` 改动前后 `diff` 为空（AC-7） |

### 二期触发条件

若后续要把「匹配键取不到值」从诊断升级为**可配置的严格模式**（配置期直接拦截非法匹配字段），那会新增组件保存校验的错误码与响应体 —— 届时才需要动 `api.md`。本期不做（A0 已否决方案丙）。
