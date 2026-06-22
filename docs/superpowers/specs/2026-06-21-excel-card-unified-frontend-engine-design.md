# 设计：报价/核价 Excel 视图与产品卡片统一前端单引擎计算 + saveDraft 两份快照

> 日期：2026-06-21 ｜ 状态：**已落地（报价侧 Phase 1~6 + Phase 2.5）**，本文为初稿设计，部分假设落地时被修正（见下方横幅）｜ 类型：**核心渲染基线反转（破坏性）**
> 关联基线：`docs/三大核心模块基线.md` §4.7（报价单渲染）—— 本设计将其「后端权威」反转为「前端权威」，已同步更新该基线文档。

> ⚠️ **2026-06-21 Phase 2.5 落地修正（本初稿两处假设作废，以基线 §4.7 + 反模式 AP-59 为准）**：
> 1. **§4.1「`VARIABLE/BASIC_DATA/FIXED` 复用 `useLinkedExcelRows` 客户端解析」+ §4.2「退役 `useBackendExcelRows`」均作废**。报价模板的列是服务端 `excel_component_id` 引用，客户端拿不到列定义（会得空列 → Excel 全 0）。正解：**列定义仍由后端解析**（显示侧取 `useBackendExcelRows.parsedColumns`，saveDraft 侧取新端点 `GET .../excel-view-config/effective-columns`），**只「列值」走前端 `buildExcelSnapshot`**。`useBackendExcelRows` **未退役，改作列定义来源**。
> 2. **TAB_JOIN_FORMULA 列求值需完整 `TabDef`**（componentId + subtotalCols），`col.tabs` 子集不够，须从 `item.componentData` 补全，否则列值全 0（见 AP-59 坑 2）。
> 3. 不变量保持：列结构由后端解析不破坏恒等性——分叉只在「值」不在「列结构」。

## 1. 背景与问题

产品卡片由**前端引擎**计算（`computeAllFormulas` 显示，`computeProductSubtotal` 算产品小计 footer）；Excel 视图由**后端引擎**计算（`GET /excel-view` → `ExcelViewService` / `ComponentDataEffectiveRows` 读 `row_data` 列求和 + SUBTOTAL 跨页签合成）。**两套引擎对同一料号必然分叉**：

- 实测 QT-20260621-1787（单价 Cu=1122，计价单位 g/PCS）：卡片产品小计 footer = **0.93**（前端 `computeProductSubtotal`）；Excel C「报价小计(总计)」= **0.8527**（后端按 `报价小计.总成本` 公式 `来料.材料成本 + 来料.材料损耗成本 + 组装加工费.费用 + 其他费用.费用` 求值，各列读 `row_data`）。差值 0.0774 = 来料加工费两行（前后端聚合口径不同）。

此前已做的两项后端修复（物化链跨页签单位换算；Excel 取数随编辑刷新）只是在「双引擎」框架内对齐/刷新，无法根除分叉。

**用户决策**：不再用两套引擎，改为**前端单引擎同步计算卡片 + Excel 两份快照，saveDraft 统一持久化**，使 Excel 值**按构造恒等于卡片**。

## 2. 已确认决策（不可在实现期擅改）

1. **核心架构**：前端单引擎 + 两份快照。一次编辑 → 前端同一套 token 引擎同时算出卡片值与 Excel 列值 → Excel 恒等于卡片。
2. **导出（XLSX/PDF）**：也以前端快照为准（后端导出改为读快照渲染，不再重算）。
3. **范围**：报价单（QUOTE）与核价单（COSTING）**两侧一起改**。
4. **提交（submit）**：直接冻结草稿期前端两份快照为提交快照，**不再后端权威重算**。
5. **量级确认**：退役后端「显示/导出计算引擎」职责，走前端权威；**同步更新 `三大核心模块基线.md`**。
6. **row_data**：保留为「前端 Excel 快照的扁平存档」（导出/审计可用），**不再作显示/导出的权威源**；不删该写入，但其值来自前端快照（非后端重算）。

## 3. 架构（改后）

```
用户编辑卡片任意数据
   │（同一次渲染，同一前端 token 引擎）
   ├─ computeAllFormulas        → 卡片单元格值（瞬时显示）
   └─ buildExcelSnapshot(新)    → Excel 列值 {rows:[{colKey:value}]}（瞬时显示）
        │
   Excel 视图改读「前端算好的 Excel 快照」(useExcelSnapshotRows)，不再 GET 后端
        │（防抖 ~1.5s）
   saveDraft  →  后端原样落两份快照：quote_card_values + quote_excel_values
                                     （核价侧 costing_card_values + costing_excel_values）
                 row_data = 前端 Excel 快照对应的扁平行存档（非权威重算）
        │
   导出(XLSX/PDF) ← 读 quote_excel_values/costing_excel_values 快照 + 模板列定义渲染
   提交(submit)   ← 冻结上述两份快照为提交快照（无后端重算）
```

**权威单一化**：前端快照是草稿期唯一真值；后端 = 存储 + 按快照导出 + 提交冻结。彻底消除现有 `editCardValue`(后端物化) 与 `saveDraft`(前端) 对 `row_data` 的双写打架。

## 4. 组件设计

### 4.1 前端 Excel 列求值器（核心新增）
新增纯函数（建议 `cpq-frontend/src/pages/quotation/buildExcelSnapshot.ts`）：

- 输入：Excel 模板列定义（`columns[].{col_key, source_type, expression, tabs...}`）+ 本 lineItem 各组件数据（前端已有，含 driverExpansions/basicDataValues 快照）+ 前端算好的 `componentSubtotals` / `crossTabRows`（复用 `getComponentSubtotals` / `buildCrossTabRows`）。
- 处理：逐列按 `source_type` 求值：
  - `TAB_JOIN_FORMULA` / `CARD_FORMULA` / `EXCEL_FORMULA` → `evaluateExpression(col.expression, {}, componentSubtotals, productAttrs, crossTabRows)`（复用卡片同一 token 引擎，与 `computeProductSubtotal` / `computeTabSubtotalsByColumn` 同口径）。
  - `VARIABLE` / `BASIC_DATA` / `FIXED` → 复用 `useLinkedExcelRows` 现有前端解析分支。
- 输出：`{rows:[{<col_key>: value, _lineItemId, __hfPartNo}]}`，形态与现 `quote_excel_values` 快照一致。
- **不变量**：Excel 每个计算列值 == 卡片对应口径（同函数同输入）。验收含 0.93 场景：单价 1122 g/PCS → Excel C == 卡片 footer。

> 风险：必须覆盖**所有**在用的列 source_type；漏一类该列空/错。实现期先 grep 全模板在用 source_type 清单，逐类落用例。

### 4.2 Excel 视图显示
`LinkedExcelView` 统一走 `useExcelSnapshotRows`（读 `quote_excel_values`/`costing_excel_values` 快照）；其快照来源改为 §4.1 前端求值器的产出（内存即时 + saveDraft 落库）。退役 `useBackendExcelRows`（后端 GET 显示路径）。`useLinkedExcelRows` 中可复用的列解析逻辑下沉/复用进 §4.1。

### 4.3 持久化（saveDraft 两份快照）
`buildDraftPayload`（`QuotationWizard.tsx`）在现有 `computeAllFormulas` 写 `rowData` 基础上，**新增**对每个 lineItem 调 §4.1 产出 Excel 快照，payload 携带 `quoteExcelValues`/`costingExcelValues`（前端算好值，非透传旧值）。后端 `QuotationService.saveDraft` 原样落 `quote_card_values`/`quote_excel_values`（+核价两列）+ `row_data`（= Excel 快照扁平存档），**不重算覆盖**。

### 4.4 导出
后端 `ExcelViewService`（POI XLSX）/ `QuotationExportService`（Qute PDF）改为：读 `quote_excel_values`/`costing_excel_values` 快照的 `{rows:[{col_key:value}]}` + Excel 模板列定义（标题/顺序/合并/格式）→ 渲染。删除/绕过其内部对 `row_data` 的重算求值。小数位仍走 `NumberFormatUtil` 同口径格式化（值本身来自快照）。

### 4.5 提交
`quotationSnapshotService.submit`（后端）将草稿期 `quote_card_values`/`quote_excel_values`（+核价）原样冻结进 `submission_snapshot`，移除/跳过后端权威重算分支。

## 5. 退役/收口清单

| 项 | 改后 |
|---|---|
| `useBackendExcelRows`（后端 GET 显示） | 退役；显示走 `useExcelSnapshotRows` |
| `CardSnapshotService.editCardValue` 的 row_data 物化（`materializeWholeLineRowData`） | 退役（卡片编辑不再后端物化；前端算 + saveDraft 存） |
| `ExcelViewService.getExcelView` 显示计算 / `ComponentDataEffectiveRows` 显示职责 | 退役（仅保留列定义/取数装配供前端复用或导出渲染） |
| `RowDataMaterializer` + 本会话单位换算物化 | 显示/导出不再依赖；row_data 改由前端快照扁平化写入 |
| `editCardValue` 端点 + 取数刷新信号（quoteValuesAt，本会话所加） | 编辑改为前端即时算，刷新信号可简化/移除（实现期评估） |

> 退役 = 不再承担显示/导出/提交的计算职责；代码可保留或删除由实现期按引用面决定，但**不得留两条会产出不同值的活路径**（双口径残留是本次根因）。

## 6. 风险

1. **前端求值器列覆盖不全** → 某列空/错。缓解：实现期先列全在用 source_type 清单 + 逐类 vitest。
2. **基线反转**：前端成权威，后端不再校验显示值。缓解：提交期冻结即提交即真值；如需服务端复核另立任务（本次不做）。
3. **核价侧（COSTING）口径**：核价 Excel/BOM 树渲染较报价复杂（spine 全节点等，见记忆 `costing-bom-tree-full-spine-render`）；前端求值器须覆盖核价列。缓解：报价先通过再推核价，或并行但分别立用例。
4. **导出渲染**：快照 `{col_key:value}` → XLSX/PDF 需模板列结构（标题/合并/多行）。缓解：复用现有模板列定义装配，仅替换「值来源」为快照。

## 7. 测试策略

- **前端 vitest**：①`buildExcelSnapshot` 逐 source_type 用例；②「Excel 快照恒等于卡片」一致性用例，含 0.93 复现场景（单价 1122 g/PCS → Excel C == 卡片 footer == 0.93 口径）；③报价 + 核价两侧。
- **后端**：导出读快照渲染用例（值 == 快照值）；submit 冻结快照用例（提交后值 == 草稿快照，无重算漂移）。
- **一致性回归**：屏上卡片 == 屏上 Excel == 导出 XLSX == 导出 PDF（四口径一致，本次目标的反命题验证）。
- **E2E**：编辑 → 卡片与 Excel 同步刷新（`quotation-flow.spec.ts` 预存损坏需先修或单独验，见 RECORD 遗留）。

## 8. 不在本次范围

- 「报价小计.总成本 公式是否应含加工费」等**业务公式语义**问题（前端单引擎后，Excel 与卡片一致即按卡片口径走；若用户后续认为口径本身要变，属配置层另议）。
- 服务端对前端提交值的二次权威复核（提交冻结已满足「所见即所提」；如需风控复核另立任务）。
- 增/删行经 saveDraft 路径的细化（本设计天然覆盖：增删行 → 前端重算两份快照 → saveDraft 落库）。

## 9. 落地顺序（供实现计划参考）

1. 前端 `buildExcelSnapshot` 求值器 + vitest（恒等性 + 逐 source_type）。
2. `LinkedExcelView` 切 `useExcelSnapshotRows` + 快照来源接前端求值器（报价侧）。
3. `buildDraftPayload`/`saveDraft` 落两份快照（前端算值，后端原样存）。
4. 导出读快照渲染（XLSX/PDF）。
5. 提交冻结快照。
6. 核价侧（COSTING）复用同模式 + 用例。
7. 退役清单收口（删/绕双口径路径）+ 更新 `三大核心模块基线.md`。
