# 交接文档 — 报价/核价 Excel 视图三件套修复（导入即正确 + 全局小数位统一 + 编辑随动）

> 日期：2026-06-21 ｜ 状态：**三项均已完成、live 验证通过、已合并 master**。本文供新会话接续（剩余为非阻塞遗留项 + 可选硬化）。

---

## 0. 一句话现状

用户最初报的两个问题（① 刚导入报价单 Excel 产品小计不对 ② Excel 与卡片小数位不一致）+ 后续报的第三个问题（③ 填料号触发重算后 Excel 视图不随卡片更新）**全部修复并 live 验证通过，已合并到 master**。

- **master HEAD = `e8005a8`**（验收时；新会话请 `git log --oneline -5 master` 确认是否被并发会话推进）。
- 三轮改动都走了隔离 worktree + subagent-driven（每任务 spec + 代码质量两阶段评审）+ 合并后 live API 复验，worktree 均已清理。

---

## 1. 已完成的三项修复（都在 master）

### 修复一：Excel 视图导入即正确（产品小计）+ 全局小数位统一
合并提交区间约 `77657ad`（union 合并）、`dda89be`（SUBTOTAL 读时合成）。

- **问题①根因**：配置/导入时只写 `quotation_line_component_data.snapshot_rows`，从不算 FORMULA 叶子列（材料成本/费用…）写进 `row_data`；而 Excel 视图按 `row_data` 列求和 → 费用类缺失 → 产品小计只剩来料部分（0.077）或为 0。
- **修法**：
  - 新增 `RowDataMaterializer`（复用**真生产引擎** `FormulaCalculator`，**不是** `FormulaCalculationService.calculateRowFormulas`——那个只被测试调用、schema 是合成的），在 `ConfigureSnapshotService.snapshotLines` 收尾按 `CrossTabComponentOrder.topoOrder` 单遍物化各非 SUBTOTAL 组件 row_data。
  - 产品小计列 `[报价小计(总计)]` 引用的是 **SUBTOTAL 组件**，全新配置时它无 cd 记录 → 读时算不到 → C=0。补：`ComponentDataEffectiveRows.compute` 加 Pass3 + `extraSubtotalMetas`，**读时合成**无 cd 记录的 SUBTOTAL（0 行，对 Pass1 的 componentSubtotals 求值），`ExcelViewService.buildTabJoinEffectiveRows` 从 componentsSnapshot 补缺失 SUBTOTAL 元数据。
- **问题②根因 + 修法**：前后端计算精度本就是 4dp（不动），只在展示/导出取整。新增前端 `cpq-frontend/src/utils/formatNumber.ts` + 后端 `cpq-backend/.../common/NumberFormatUtil.java` **同口径**（HALF_UP + 至多 N 位去尾零）；字段级 `decimals` 配置（组件管理 `FieldConfigTable`，仅数值类）；计算列（FORMULA/CARD_FORMULA/**TAB_JOIN_FORMULA**/EXCEL_FORMULA，含产品小计）未配兜底 2 位、取数列（汇率 6.9755）保留原精度；列 override `display_format.decimals` 端到端贯通。接入点：`LinkedExcelView.renderCellValue`（抽 `isComputedExcelColumn`）、`ComponentCell`（AP-50 单源覆盖报价+核价+详情）、`QuotationStep2` formatCurrency+列小计、`formatPathValue`、`ExcelViewService` 导出 POI `0.##` 样式、`QuotationExportService` PDF 行级。
- **live 验证**：全新报价单 `df0823ef` 产品小计 C 0→0.1445（显示 0.14），A 材料成本 0.0774；四口径（卡片/Excel/导出 XLSX/PDF）一致、汇率原精度保留。

### 修复二：Excel 视图随卡片编辑更新（编辑随动）
合并提交 `92e7e60`（最小版）、`e8005a8`（整行 topo 重物化版）。

- **问题③根因（双写入源不同步）**：`editCardValue`（失焦即时、后端 FormulaCalculator）只写 `quote_card_values`（卡片读它）；`row_data` 只由 `saveDraft` 防抖（1.5s、前端 computeAllFormulas）写，而 Excel 视图只读 `row_data` → 编辑后早于/未触发自动保存切到 Excel 即旧值。
- **修法（option A，经 cpq-architect 评估，属 §4.2/4.6 基线 Phase4 演进方向收敛）**：`editCardValue` 失焦时用同一后端引擎**按 topoOrder 重物化整行**全部非 SUBTOTAL 组件 row_data（各套 editRows），使跨页签依赖逐级传播。抽 `ConfigureSnapshotService.computeLineRowData`（纯）+ `materializeLineRowData` 共享给配置/编辑两路；配置路径委托（editRows 空）行为 1:1 不变。`RowDataMaterializer` 加 editRows 重载（editRows 同时进 FORMULA 叶子与 INPUT 值、真实 rowKeyFields 对齐 effKey/AP-54）；写 row_data 后 `em.flush()/clear()` 重取 li 使 buildExcelValues 见新值。
- **关键教训**：最初"只重物化被编辑组件"不够——跨页签引用方（`来料.材料成本 = Σ 来料.组成用量×元素.净用量×元素.单价 + 加工费`，持久化在**来料** row_data，Excel 对 NORMAL 组件按 row_data 列和读、不读时重算）不更新。故必须整行重物化。
- **live 验证**：编辑 元素.Cu.单价 0.1→0.2 → Excel A 0.0774→**0.2023**（手算精确吻合）、C 0.1445→**0.2707**；来料 row_data 材料成本 DB 0.0433→0.1682 已重物化；卡片 = Excel 一致。

---

## 2. 关键架构心智模型（接续必读）

- **两个持久化源**：`quotation_line_item.quote_card_values`（editRows + formulaResults + quoteExcelValues，卡片读）；`quotation_line_component_data.row_data`（扁平 `[{字段名:值,...,row_index:N}]`，**Excel 视图读**）。
- **Excel 视图读路径**：前端 `LinkedExcelView`→（v2/新模型）`useBackendExcelRows`→`GET /api/cpq/quotations/{id}/excel-view?templateId=`→`ExcelViewService.getExcelView`→`buildTabJoinEffectiveRows`→`ComponentDataEffectiveRows.compute`（读 row_data；NORMAL 组件按列求和**不读时重算 FORMULA 叶子**；SUBTOTAL 组件读时按公式合成 Pass2/Pass3）。
- **写 row_data 的两条路**：配置 `ConfigureSnapshotService.snapshotLines`（新）；编辑 `CardSnapshotService.editCardValue`（新，整行重物化）；**外加** `saveDraft` 仍会用前端引擎覆盖 row_data（见遗留 §3.2）。
- **小数**：内部 4dp 不动，展示/导出经 `formatNumber`/`NumberFormatUtil` 同口径取整；计算列兜底 2、取数列保原精度。
- **行键/effKey**：`FormulaCalculator.computeRowKey + uniquifyRowKeys`，editRows 必须用真实 `rowKeyFields` 对齐，否则末值×行数塌缩（AP-54）。

### 核心文件
- 后端：`com.cpq.quotation.service.RowDataMaterializer`、`.../card/ComponentDataEffectiveRows`、`.../ExcelViewService`、`.../CardSnapshotService`(editCardValue ~1427)、`com.cpq.configure.service.ConfigureSnapshotService`(computeLineRowData/materializeLineRowData/writeRowData)、`com.cpq.common.NumberFormatUtil`、`.../FormulaCalculator`(calculate/resolveRowByFieldName/computeRows)、`.../CrossTabComponentOrder`(topoOrder/extractSourceRefs/extractSubtotalRefs)。
- 前端：`src/utils/formatNumber.ts`、`src/pages/quotation/LinkedExcelView.tsx`(isComputedExcelColumn/renderCellValue)、`useBackendExcelRows.ts`、`components/ComponentCell.tsx`、`components/formatPathValue.ts`、`QuotationStep2.tsx`、`pages/component/{types.ts,FieldConfigTable.tsx}`、`services/{quotationService.ts,costingTemplateService.ts}`。

---

## 3. 遗留项（均非阻塞；新会话可按需接续）

### 3.1 `editQuoteCardValue` 响应内嵌 `quoteExcelValues` 返 0
编辑响应里嵌的 excel 值是 `{A:0,B:0,C:0}`，但前端 Excel 视图走**独立 GET excel-view**取值（正确），故**显示不受影响**。疑似 `buildExcelValues` 在同事务内即便 em.clear 后仍未读到刚 REQUIRES_NEW 写入的 row_data。若有读 `quote_excel_values` 的下游（导出/快照）需校验。**优先级低。**

### 3.2 saveDraft 仍用前端引擎覆盖 row_data（架构师 §7，未做）
编辑后 ~1.5s 防抖 `saveDraft` 会用前端 computeAllFormulas 整份覆盖 row_data（`QuotationService.saveDraft` ~494 `cd.rowData=cdDraft.rowData`）。因前后端引擎已 1:1 对齐、且实测卡片实时值正确，**当前无可见偏差**。彻底单引擎收口方案：saveDraft 改为不覆盖已有行 row_data（后端权威，UPSERT 保留）或前端对已有行不发 rowData。**改 saveDraft 属协议级 → 必跑 E2E。**

### 3.3 编辑每次重物化整行有 N 次 `loadRowKeyFieldsNode` 查询
`CardSnapshotService.materializeWholeLineRowData` 按组件逐个查 rowKeyFields（N=组件数，通常 ≤10，无瓶颈）。可批量加载/缓存优化。**优先级低。**

### 3.4 E2E 规格预存损坏（**重要：不是本次回归**）
`quotation-flow.spec.ts` + `composite-product-flow.spec.ts` 当前 4/4 失败，均**合并前既存**：Step1 categoryId 回填 / TC-F1·F2 空草稿"刷新基础数据"按钮不在 Step1 层 / composite「组合产品 v1.16」测试数据缺失。**本次功能验证靠 live API + 单测，未用 E2E 作闸门。** 这几个 spec 的修复是独立存量技术债，新会话若要让 E2E 重新可用需单独处理（属另一条线）。

---

## 4. Git / 工作区状态（重要）

- **master HEAD `e8005a8`**（验收时）。三轮 worktree 均已 `git worktree remove` + 删分支。
- **未提交改动（主工作区，属并发会话的 WIP，勿擅动）**：
  - `cpq-frontend/src/pages/quotation/LinkedExcelView.tsx` —— 含并发会话的 **v2 屏幕 Excel 迁移**（`useBackendExcelRows` 路由 isV2 等），本次已与我的 `formatNumber`/`isComputedExcelColumn` 改动 **union 合并**（不同区段，import 取并集）。该文件仍为未提交 WIP，由对应会话提交。
  - `docs/RECORD.md` —— 我已追加本次两条记录到末尾（**未提交**，随并发 WIP 一起提交即可）。
  - 其它并发 WIP：ComponentService.java、单位换算、ComponentManagement、若干 docs 等（非本次范围）。
- **并发 worktree（勿动）**：`.claude/worktrees/excel-component-ref-fix`、`.claude/worktrees/step3-discount-rework`。
- **并发约束**：本仓多会话并行；提交只 `git add` 本次明确文件，**严禁 `git add -A`**；新功能/较大改动**必须**用隔离 worktree（本会话因带 cwd 隔离无法用原生 EnterWorktree，回退到手动 `git worktree add .claude/worktrees/<name> -b worktree-<name> master`，子代理用绝对路径在其中开发）。

---

## 5. 验证环境速查

- DB：`export PGPASSWORD=joii5231; psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db`
- admin 登录（cookie 会话）：`POST /api/cpq/auth/login` `{"username":"admin","password":"Admin@2026"}`（`curl -c jar -b jar`）
- dev server：前端 5174 / 后端 8081（**服务主树**；worktree 内改动需合并后才被 dev server 反映；后端 Quarkus 下次请求热编译）。`/q/health` 返 404 正常，用 `/api/cpq/quotations` 探活（期望 401）。
- 后端单测（避开 8081 占用）：`cd cpq-backend && ./mvnw test -Dtest='...' -Dquarkus.http.test-port=0`
- 前端：`cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` + `npx vitest run <file>`（node_modules 在主树；worktree 内需 `ln -s` 主树 node_modules，勿重装）。
- **E2E 跑全套 vitest 会有 44 个 `e2e/*.spec.ts` 文件被误收集报失败（"Playwright Test did not expect..."）——既有配置问题、与代码无关，看真实单测 `Tests N passed` 即可。**
- 报价测试数据：苏州西门子 + 报价模板0608；本次 live 用 罗克韦尔模板0617 v1.4 的报价单 `df0823ef`（line `d6a9978e`），元素组件 COMP-0029（rowKeyFields `["元素","料件"]` sep `||`）。

---

## 6. 决策档案（避免重复纠结）
- 用户对小数方案的取舍（10 问收敛结论）：内部精度保持 4dp 不动；展示/导出 HALF_UP「至多 N 位」去尾零；计算列兜底 2 位、汇率/比例/单价/单重等取数列保原精度；字段级 decimals 在组件管理配、结果列在 Excel 列 override 配；覆盖 报价 Excel + 核价 Excel + 导出(XLSX/PDF) + 卡片四面。
- 编辑随动选 **option A**（编辑时同步重写 row_data，统一后端引擎），非 B（Excel 读时叠加 editRows）/C（前端强制刷新）。
- row_data 为 QUOTE/COSTING **共享单表**（按 line_item_id+component_id，不分报价/核价）；QUOTE 编辑会影响核价 Excel 视图列求和（通常期望；若要两侧隔离属更大数据建模改动，需 PM 决策）。
</content>
