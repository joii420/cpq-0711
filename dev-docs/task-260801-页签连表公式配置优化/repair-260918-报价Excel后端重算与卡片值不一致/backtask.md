# backtask · repair-260918 报价 Excel 视图后端重算改读卡片值

> 验收标准以 `问题说明.md` 的 `AC-x` **原文**为准；本文件是分解结果，两者有出入**以 AC 原文为准并报主线**。
> 🚫 本任务**无迁移、无 DDL、无前端改动**。改到的都是既有方法，不新增接口、不改响应结构。

## 总原则（每一项都适用）

1. **报价侧后端算 Excel 列值 = 读该行 `quote_card_values`（正式账）**，解析方式与核价侧同款（`CardEffectiveRows.parse` + `CardDataProvider.fromEffectiveRows`），求值规则一个字不改。
2. 🚫 **正式账不可用的行，绝不允许以 `effectiveRows=null` 去调 `buildRowData`** —— 那正是旧路径（`buildTabJoinEffectiveRows` 读 `row_data`）。必须显式走「该行这类列置 `null`」的分支。
3. **核价侧（`costingSide=true`、`costingExcelValues`、`buildLineTreeRows`）逐字不动**（AC-10）。
4. 改到的每个循环都要满足 `backend.md §1`：SQL 条数与报价行数无关（AC-7）。

## 任务分解

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-1** | AC-4 | **统一「正式账不可用」判定**：新增一个共用判定（建议放 `CardEffectiveRows` 或 `ExcelViewService` 静态方法，**全工程只此一处定义**），命中任一即「不可用」：值为 `null` / 空白；JSON 解析失败；对象含 `"__cardValueFailed": true`；`tabs` 字段缺失、非数组或长度为 0。B-2~B-5 全部复用它，🚫 不许各写一份 |
| **B-2** | AC-1, AC-2, AC-3, AC-4, AC-5, AC-8, AC-9, AC-11 | **`ExcelViewService.getExcelView` 报价侧改读正式账**：<br>① 行循环外按**模板**准备 `EffRowsCtx`（`loadEffRowsCtx`），同一请求内按 `templateId` 缓存复用（不同报价行可能属不同模板版本，按 `li.templateId` 取）；<br>② 逐行 `parseEffectiveRows(li.quoteCardValues, ctx)` → 传入 `buildRowData` 的 `effectiveRows` 参数；<br>③ 该行正式账不可用（B-1）→ 该行 `TAB_JOIN_FORMULA` 与 `CARD_FORMULA` 列值置 `null`，其余列类型按原逻辑（产品属性 / 组件字段 / 变量 / 固定值 / 公式 / Excel 公式），HTTP 仍 200；<br>④ `columns` 的解析与返回结构**不变**；<br>⑤ **核价侧分支（`costingSide=true`）不动**。<br>⚠️ 导出兜底（`exportExcelView`）不需要单独改，它取的就是本方法的行 |
| **B-3** | AC-6, AC-4 | **`ExcelViewService.dryRun`（`POST /quotations/{id}/excel-view/dry-run`）同款改造**：逐行取 `li.quoteCardValues`（`EffRowsCtx` 用 `li.templateId`；请求体的 `templateId` 仍只用于取模板公式），不可用 → 该行这类列 `null` |
| **B-4** | AC-6, AC-4 | **`ExcelViewService.dryRunTabFormula`**（模板级 `/templates/{id}/excel-view-config/dry-run-tab-formula` 与组件级 `/components/{id}/dry-run` 共用内核）：取数优先级改为 ① 入参 `cardValuesJson` 可用 → 用它；② 否则用该行 `quote_card_values`；③ 都不可用 → `{"value":null,"errors":["样本卡片的卡片值不可用（尚未计算或计算失败），无法试算"]}`（文案**逐字**照此，`api.md` 已登记）。🚫 删除 `new CardDataProvider(cdList)` 这条回退 |
| **B-5** | AC-12 | **`CardSnapshotService` 报价侧两处补算加守卫**（`:1169-1171` 懒算路径、`:1392-1400` `ensureExcelValuesBatch`）：该行 `quoteCardValues` 不可用（B-1）→ **跳过、不赋值**，让 `quote_excel_values` 保持 `NULL`（下次补算自愈）；🚫 不许写 `{"rows":[]}`（`ensureExcelValues` 的缺失谓词是 `IS NULL`，写了空结果等于把补算永久堵死）。**核价侧两处（`costingExcelValues`）不动** |
| **B-6** | AC-7 | **查询条数常数化**：`getExcelView` / `dryRun` 的行循环内不得再有查库。`buildRowData` 里的 `QuotationLineComponentData.list(lineItemId=…)` 改为整单一次批量取（`lineItemId IN ?1`，回内存按行分组），复用既有 `ExcelCompDataContext` 预取机制并在 `finally` 清理；模板公式、`Quotation`、`EffRowsCtx` 每请求各取一次。改完按 `backend.md §1` 写自检声明（逐个列出改动涉及的循环） |
| **B-7** | AC-10, AC-13 | **既有测试适配 + 新增测试**：<br>① 先在 **master 基线**上跑一遍与 Excel 视图相关的测试类留基线（至少：`ExcelViewTabJoinFormulaIT`、`ExcelViewCardFormulaIT`、`ExcelDryRunIT`、`ExportFromSnapshotTest`、`ExcelViewRawSourcePrecisionIT`、`QuotationOutputResourceTest`、`QuotationOutputPrecisionHttpContractTest`、`SubmitFreezeSnapshotTest`、`CardSnapshotAmountTotalTest`、`GetExcelViewCostingIT`、`CostingExcelTreeTabKeyIT`、`EffectiveRowsKeyContractTest`、`ComponentDataEffectiveRowsTest`、`RowDataMaterializerTest`），再在本分支跑一遍；<br>② 因行为改变而需要改断言的，**逐条列出「原断言 → 新断言 → 理由」**，🚫 不许直接改绿；<br>③ 新增测试至少覆盖：正式账可用且**与 `row_data` 不同**时读到的是正式账（夹具必须让两者不同，否则用例分辨不出读的是哪一份）、正式账四种不可用形态各返回 `null`、`ensureExcelValues` 遇不可用行不写值；<br>④ 核价侧三个测试类结果与 master 基线相同 |

## 约束与坑（动手前必读）

- **夹具纪律（AP-59）**：新增测试的 `quote_card_values` 夹具必须忠实复刻线上结构 —— 十进制值是**字符串**、含 `resolvedRows` / `formulaResults` / `subtotal` / `subtotalByColumn`；缺字段不许自己补全，否则红用例会变假绿。
- **判定基准**：`[页签(总计)]` → 该页签 `subtotal`；`[页签.列]` / `[页签.列(小计)]` → 该页签 `subtotalByColumn[列]`。立项期已实测：这两个基准与「按正式账试算」在开发库 135 格上**逐位相等**（`证据/输出/三方对账-260918-2012.txt`）。
- **单位换算**：正式账路径的换算由 `CardEffectiveRows.parse` → `UnitConversion.convertObjectRow` 完成，依赖 `EffRowsCtx` 里的 `fields`；`loadEffRowsCtx` 读 `template.components_snapshot`（立项实查：20 个带 Excel 配置的模板该字段全部非空且与冻结行一致）。ctx 拿不到时不要静默继续 —— 按 B-1 当「不可用」处理并记一行 WARN。**（`D-7` 修订）**：「拿不到」限于模板不存在或快照损坏；快照为空（DRAFT / 已发布未冻结）时照样求值、不做单位换算（同核价侧）。
- **测试库**：`mvnw test` 默认连 `cpq_db_test`（决策台账 `application-test.properties`）。🚫 **不要跑全量 `mvnw test`** —— `mat_*` 表在本库从未创建（决策台账 `mat_part`），全量永远不可能全绿；只跑上面点名的测试类。
- **`GoldenCardValuesEquivTest` 恒跳过**（决策台账 `DEC-0007`），🚫 不可拿它的 `BUILD SUCCESS` 当无回归证据。
- **worktree 自检**：共享 8081 是主工作区 master，拿它验证自己的改动 = 假绿。自检用**临时端口**起本分支实例：`./mvnw -q package -DskipTests` 后 `java -Dquarkus.http.port=8319 -jar target/quarkus-app/quarkus-run.jar`（🚨 `-D` 必须写在 `-jar` 之前，写在后面不生效、会去抢 8081 —— 2026-09-18 后端工程师实测更正）（`%prod` 下 `flyway.migrate-at-start=false`，不会动任何库结构；默认连 `cpq_db_0724`，本任务只读该库）。🚫 端口 `8081`/`5174` 留给主线与用户，`8318`/`8328`/`8338`/`5338` 留给测试片，你只用 `8319`。
- **交付物**：完成时执行一次 `./mvnw -q package -DskipTests`，把 `target/quarkus-app/` 留在 worktree 里并在回报中写明构建时间 —— 测试片直接 `java -jar` 用它，不再自己构建（`testing.md §4.2.5`：同一 worktree 内不要与别人并发构建）。
- 🚫 你不要执行 `git commit`，提交由主线统一做。
