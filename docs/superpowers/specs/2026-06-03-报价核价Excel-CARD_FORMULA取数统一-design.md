# 报价/核价 Excel 视图 CARD_FORMULA 取数统一 — 设计方案

- 日期：2026-06-03
- 状态：Tasks 1-8 已实现并提交；Task 9 E2E 暴露"有效行字段键"缺口，补充设计见 §8（2026-06-03 增补）

## 8. 增补：有效行必须按字段名解析（通用引擎，非硬代码）— 2026-06-03

**E2E 暴露的缺口**：CARD_FORMULA 的 ref 按**字段名**取值（如 `类型`/`含量`），但快照里的行数据键是 driver/SQL 别名：
- `driverRow` 用 SQL 列别名（`含量`/`元素` 中文恰好=字段名 → 碰巧命中；`material_type` 英文别名≠字段名 `类型` → miss）。
- `basicDataValues` 用 `{$ys_view.含量}` 这种 path 键。
- 因此简单把 driverRow∪basicDataValues 并进有效行（Task 1 初版）对"别名≠字段名"的字段（如 `类型`）取不到值 → CARD_FORMULA 出"—"。

**根因澄清（已查证）**：核价 `ys_view`(component-scoped `6ae3181f`) **正确逐行投影 material_type**（case-when + declared_columns 声明），数据源没问题；QT-1528 那份"(共4项)"数组是**陈旧快照**（快照冻结 00:49 早于 ys_view 加 material_type 的 08:31）造成的假象，旧快照不在本次处理范围。

**解法（通用、配置驱动，零硬编码字段名）**：在快照生成时，按组件 `fields` 定义把每行解析成**按字段名的标量行(resolvedRow)** 并落库；Excel 直接读。规则对所有字段一视同仁：
- `BASIC_DATA`：`lookupBdv(basicDataValues, bnfDriverLookupKey(field.basic_data_path))`，回退 driverRow[name]/content，键=`field.name`。
- `INPUT_*`：editRows[name] 覆盖 → driverRow[name] → default_source/content。
- `FORMULA/LIST_FORMULA`：formulaResults[name]。
- `DATA_SOURCE`：按 `datasource_binding`(GLOBAL_VARIABLE/BNF_PATH/...) 解析。
- `FIXED_VALUE`：content。

**复用纪律**：后端 `FormulaCalculator.collectFieldValues`（注释 "port computeAllFormulas:420-548"，AP-37）已是这套字段定义驱动解析，只是只保留 `toNumber` 数值。新增一个**并行的 Object 版**（保留字符串）复用它的私有 helper（`bnfDriverLookupKey`/`basicDataPath`/`datasourceBinding`/`content`/`lookupBdv`/`nonEmpty`/`nodeToObject`），**不改既有数值路径**（防回归 AP-44）。

**落点**：
1. `FormulaCalculator` 新增 `resolveRowByFieldName(fields, driverRow, basicDataValues, editValues, formulaValues) → Map<String,Object>`（通用引擎）。
2. `CardSnapshotService.assembleTabsWithFormulaResults` PASS2 逐行调它，把 `resolvedRows`（与 baseRows 同序、按字段名标量）存进值快照 tab。
3. `CardEffectiveRows.parse`：tab 有 `resolvedRows` → 直接用；无（旧快照）→ 回退 Task 1 初版合并（向后兼容）。

---

- 日期：2026-06-03
- 状态：已与用户确认方向，待写实现计划
- **取代**：
  - `2026-06-03-核价单Excel-CARD_FORMULA-方案C修订.md`（方案 C「前端送合并行」**作废**）
  - `2026-06-03-核价单Excel视图CARD_FORMULA修复-design.md`（sortOrder 回退取数**作废**）
- 关联：`2026-06-02-Excel视图卡片引用公式配置-design.md`（CARD_FORMULA 原始设计）、`docs/superpowers/plans/2026-06-01-报价单整份快照-Phase*.md`（四份快照架构）

## 0. 命名纠正（本方案的出发点）

这**不是核价单专属逻辑**，而是**报价单 / 核价单两侧渲染共用的同一套逻辑**。原方案 C 把它定位成"核价单修复"是错的定位，导致设计偏向"前端送行给后端"。

统一后的口径——一句话：

> **Excel 视图的 CARD_FORMULA 列取数 = 同侧卡片快照的有效合并行(effectiveRows)，按卡片 componentId 精确命中。报价侧、核价侧对称共用同一套逻辑，唯一区别是"传哪一侧的卡片快照 + 哪个模板 id"。**

所有新增工具/方法/参数命名一律用中性词（`effectiveRows` / `cardValues` / `side`），**禁止带 `costing` 前缀**，避免再次把共用逻辑误植成核价专属。

## 1. 真因（已逐条用代码坐实，与方案 C 的判断不同）

四份快照**全部由后端在加产品时算好并落库**（`CardSnapshotService.snapshotLineValues`）：

| 快照字段 | 计算方式 | 是否含"类型" |
|---|---|---|
| `quote_card_values` | 复用 `snapshot_rows` | — |
| `quote_excel_values` | `buildExcelValues(报价模板)` | — |
| `costing_card_values` | `buildCostingCardValues` → **后端 expand 核价 driver 组件** | **✅ 自带（驱动核价 ys_view 产出）** |
| `costing_excel_values` | `buildExcelValues(核价模板)` | ❌ 取数取错源 |

**真正的 Bug 只有一处**：`ExcelViewService.buildRowData`（`ExcelViewService.java:203、225`）算 CARD_FORMULA 时，数据源**写死读 `quotation_line_component_data`（报价侧持久化、无"类型"）**：

```java
List<QuotationLineComponentData> componentDataList =
    QuotationLineComponentData.list("lineItemId = ?1 ...");        // ← 报价侧，无"类型"
cardFormulaValues = cardFormulaEvaluator.evaluateColumns(
    cardCols, componentDataList, customerId, partNo, null);
```

- 核价 Excel 快照计算时，本应用同一方法刚 expand 出的、**带"类型"的核价 baseRows**（已在 `costing_card_values` 里），却仍读报价侧 → B 列 `WHERE 类型=='非银点类'` 永远落空 → A/C 连锁「—」。
- 报价 Excel 之所以"碰巧对"，只因报价侧持久化数据恰好就是报价数据；但它同样是"读 `quotation_line_component_data`"而非"读同侧卡片快照的有效行"——口径未统一，仍有漂移隐患。

**第二处**：前端核价 Excel 视图走 `useBackendExcelRows → 实时 getExcelView`（`useBackendExcelRows.ts:62`），又绕回上面那条错误取数路径；而卡片视图早已改为读快照（`QuotationStep2.tsx:739-752` 从 `quoteCardValues/costingCardValues` 构造 DriverExpansionMap）。两类视图读法不一致是病根的放大器。

**结论**：合并行**后端早就算得出**（方案 C「只能前端算」的前提不成立）。不需要前端送行、不需要新端点、不需要把后端 Excel 计算改成吃前端 payload。只需把取数源接对，并让前端 Excel 视图与卡片视图一样读快照。

## 2. 方案：取数源统一接到"同侧卡片快照有效行"

### 2.1 新增共享工具：卡片快照 → 有效合并行

新增 `CardEffectiveRows`（中性命名，报价/核价共用；放 `quotation/service/card/`）：

输入：一份 `*_card_values` 快照 JSON（`{tabs:[{componentId, sortOrder?, baseRows, editRows, formulaResults, subtotal?}]}`）。

输出：`Map<tabKey, TabRows>`，`tabKey = componentId:sortOrder`，`TabRows = { List<Map<String,Object>> rows, BigDecimal subtotal }`。

每行 flat map 的合成口径**必须与卡片渲染逐字段一致**（这是 CARD_FORMULA 按字段名 `类型/含量/...` 取值的前提）：

```
effectiveRow[i] = driverRow[i] ∪ basicDataValues[i] ∪ editRows(rowKey 对齐).values ∪ formulaResults[i]
覆盖优先级（后者覆盖前者）：driverRow < basicDataValues < formulaResults < editRows.values
```

> 口径来源：与 `QuotationStep2.tsx` 从快照构造 DriverExpansionMap + ProductCard effectiveRows 的合成规则对齐；抽成后端共享工具，避免与前端渲染分叉。

### 2.2 `CardDataProvider` 增加"从有效行构造"入口

- 现状：`CardDataProvider(List<QuotationLineComponentData>)` 读 `rowData`。
- 新增：`CardDataProvider.fromEffectiveRows(Map<tabKey, TabRows>)`，`byTab` 直接吃 §2.1 结果，**精确 `componentId:sortOrder` 命中，不需要 sortOrder 回退**（核价 ref `b3359f70:2` ↔ 核价快照 `b3359f70:2` 天然对齐）。
- `evaluateColumns` 增加一个接受"已构造 provider"的重载（或接受 `Map<tabKey,TabRows>`），不改既有签名。

### 2.3 `ExcelViewService` 取数源切换

- `buildRowData` 的 CARD_FORMULA 分支：数据源从「查 `quotation_line_component_data`」改为「用传入的同侧卡片有效行 provider」。
- 透传链路加 `cardValues`（同侧卡片快照 JSON）：
  - `buildLineRowData(li, templateId, customerId, String cardValuesJson)`（重载；旧签名保留，cardValues 缺省时**降级**回原 `quotation_line_component_data` 路径，保证兼容/导出/旧调用不炸）。
  - `buildRowData(... , Map<tabKey,TabRows> effectiveRows)`：非空 → 用它建 provider；空 → 旧路径。

### 2.4 `CardSnapshotService.buildExcelValues` 透传同侧卡片快照

`snapshotLineValues` 里两侧对称改：

```java
// 报价侧：先有 quoteCardValues，再算 excel，把同侧卡片快照传进去
managed.quoteExcelValues  = buildExcelValues(managed, customerTemplateId, customerId, managed.quoteCardValues);
// 核价侧：先有 costingCardValues，再算 excel，传核价侧卡片快照
managed.costingExcelValues = buildExcelValues(managed, costingTemplateId, customerId, managed.costingCardValues);
```

`buildExcelValues` 增 `cardValuesJson` 形参 → 解析成 §2.1 有效行 → 传给 `buildLineRowData`。两侧走**同一方法、同一参数**，只是喂不同的 cardValues + templateId（命名共用纪律落地点）。

同步改 `refreshQuoteCardValues` / `editCardValue` 里的报价 Excel 重算（line 810、913）：用新算出的 `quoteCardValues` 喂 Excel，保证编辑后报价 Excel 与卡片同源。

### 2.5 前端：Excel 视图改读快照（与卡片视图对齐）

- 报价 Excel 视图读 `quote_excel_values`、核价 Excel 视图读 `costing_excel_values`，解析 `{rows:[{colKey:value}]}` 直接渲染——与 `useCardSnapshots` 读卡片快照同构。
- `useBackendExcelRows` 在"快照可用"时**不再发 `getExcelView` 实时请求**（参照卡片侧 `useSnapQuote/useSnapCosting` 的 `every(li => !!li.xxxValues)` 判定）；快照缺失时才降级实时（兼容旧单）。
- 新增/复用一个中性命名的 hook（如 `useExcelSnapshotRows`，报价/核价各传 `side` + 对应快照字段），不要起 `useCostingExcel` 这类侧偏命名。

## 3. 相对方案 C 的"保留 / 返工"

| 方案 C 的做法 | 本方案处理 |
|---|---|
| 前端组装 effectiveRows 送后端 | **作废**（后端用同侧卡片快照自有有效行，不收前端 payload） |
| 新增 `POST /excel-view/evaluate` 端点 | **不需要**（无新端点；快照即结果） |
| `CardDataProvider.fromTabs(payload)` | 改为 `fromEffectiveRows(同侧卡片快照解析结果)`，数据源是后端快照不是前端 |
| 两视图"统一走 evaluate" | 改为两视图"统一读同侧 excel 快照 + 计算时统一读同侧卡片有效行" |
| getExcelView 加 templateId 覆盖（Task2）| **保留**（实时降级路径仍按 templateId 算；快照缺失时兜底） |
| CardDataProvider sortOrder 回退（Task1）| **保留为无害兜底**（精确命中后非主路径） |
| 公式校验强化、试算按钮 | **保留**（试算改吃同侧卡片有效行算，与正式渲染同源） |

## 4. 命名修正清单（强制）

| 旧（侧偏 / 误导） | 新（中性共用） |
|---|---|
| 方案标题"核价单 Excel CARD_FORMULA 修复" | "报价/核价 Excel 视图 CARD_FORMULA 取数统一" |
| `CardDataProvider.fromTabs(payload)` | `CardDataProvider.fromEffectiveRows(Map<tabKey,TabRows>)` |
| （新工具）`CostingEffectiveRows` ✗ | `CardEffectiveRows`（报价/核价共用） |
| （新 hook）`useCostingExcel` ✗ | `useExcelSnapshotRows({ side })` |
| `buildExcelValues` 注释"核价侧 Excel" | 改注"按 side 计算 Excel，报价/核价共用" |

## 5. 验收

- 后端单测：
  - `CardEffectiveRows` 合成口径（driver/basicData/formulaResults/editRows 覆盖优先级；rowKey 对齐）。
  - `CardDataProvider.fromEffectiveRows` 按 `componentId:sortOrder` 精确命中，无需 sortOrder 回退。
  - 喂含"类型"的核价有效行 → B 按 `类型=='非银点类'` 命中、A=含量合计、C=A+B。
- E2E（非破坏，沿用 QT-1497 / 注入-还原-页签数守卫）：
  - 核价单 Excel A/B/C 出值且 **== 核价卡片该页签按同公式手算**（同源校验）。
  - **报价单 Excel 回归**（QT-1497=515.5632 不破）——本方案改了报价侧取数源（→ 读 quote_card_values），**必须证明报价侧不退化**。
  - 报价卡片编辑单元格后，报价 Excel 同步更新且与卡片一致（`editCardValue` 透传链路验证）。
- 一致性核对：四份快照"卡片 ↔ Excel"两两同源（报价卡片↔报价Excel、核价卡片↔核价Excel）。

## 6. 边界 / 风险

- **报价侧取数源迁移风险**：报价 Excel CARD_FORMULA 从「读 `quotation_line_component_data`」改为「读 `quote_card_values` 有效行」，两者字段集理论一致但需回归证明（QT-1497）；保留旧路径作 cardValues 缺失时的降级。
- **effectiveRows 口径漂移**：后端合成必须与前端 ProductCard / `QuotationStep2` 从快照构造的有效行**逐字段一致**；抽成单一共享工具 + 单测锁口径。
- **快照新鲜度**：前端 Excel 视图读快照后，依赖 `snapshotLineValues / refreshQuoteCardValues / editCardValue` 把 Excel 快照刷对；任一写卡片快照的路径都要同步刷同侧 Excel 快照（已在 §2.4 收口）。
- **导出**：`exportExcelView` 走实时 `getExcelView`；若也要核价导出，按同侧快照/同侧 cardValues 喂入（本方案先不扩，列为后续）。
- 不改卡片数据存储结构、不新增端点、不引入前端→后端数据上送。
