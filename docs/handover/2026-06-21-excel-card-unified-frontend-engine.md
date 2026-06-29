# 交接文档 — 报价/核价 Excel 与卡片统一前端单引擎计算（设计已定，待实现）

> 日期：2026-06-21 ｜ 状态：**两项后端/前端修复已合并 master；大改架构设计 spec 已写并确认，下一步 = writing-plans 出实现计划**。
> 本文供新会话接续。**接手第一步**：读本文 + 读 spec `docs/superpowers/specs/2026-06-21-excel-card-unified-frontend-engine-design.md` + 读 `docs/RECORD.md` 末尾 3 条（2026-06-21 Excel 相关）。

---

## 0. 一句话现状

用户报「卡片产品小计 0.93 与 Excel 视图 0.8527 对不上」。根因深挖后定性为**前后端两套计算引擎必然分叉**。已做两项过渡修复（都在 master），并与用户敲定**根治方案**：把 Excel 视图改为**与卡片同一套前端引擎同步计算**，Excel 值按构造恒等于卡片，`saveDraft` 统一存两份快照。**设计 spec 已写并经用户确认，新会话直接进 `writing-plans` 出实现计划 → 隔离 worktree + subagent-driven 执行。**

- **master HEAD = `40eab21`**（验收时；新会话先 `git log --oneline -6 master` 确认是否被并发会话推进）。

---

## 1. 本会话已完成（都在 master）

### 修复一：物化链跨页签单位换算（提交 `1fc411b` / merge `30938c7`）
- **根因**：row_data 物化链 `ConfigureSnapshotService.computeLineRowData`（L422 `crossTabRows.put` / L426 `accumulateColumnSubtotals`）直接喂 `RowDataMaterializer` 产出的**原始扁平行**（单价 1122 未按 g/PCS 换算），而卡片装配链 `CardSnapshotService.assembleTabsWithFormulaResults` 喂下游前调 `convertRowsForCrossTab`（L856/879）做单位换算。两链在「含 `unit_source_field` 列的跨页签引用」处分叉 → Excel 来料.材料成本 = 700.86（应 0.7441），C=708。
- **修法**：`computeLineRowData` 喂 `crossTabRows`/列小计前用 `UnitConversion.convertObjectRow` 换 canonical 副本（落库 flat 保持原值），对齐卡片纪律。TDD `LineRowDataMaterializeCrossTabTest` +1 用例。后端套件全绿。
- 效果：Excel C 708 → **0.8527**（= 后端按 `报价小计.总成本` 公式求值的权威值）。

### 修复二：Excel 视图取数随卡片编辑刷新（取值时机）（提交 `e54352a` / merge `788add3`）
- **根因**：`useBackendExcelRows` useEffect 依赖仅 `[enabled,quotationId,templateId]`（enabled=isV2 稳定不变），Excel 只在 mount(切入视图)时拉一次，**依赖里无编辑信号** → 编辑后不刷新 + 异步竞态读到旧 row_data（最初的 0.22）。
- **修法**：LineItem 加 `quoteValuesAt`（editCardValue 响应已返回的落库时间戳）+ `handleSnapshotCellEdit` patch 它 + `useBackendExcelRows` 新增纯函数 `excelRefreshSignal(lineItems)` 入 useEffect 依赖。TDD `useBackendExcelRows.refreshSignal.test.ts` 5/5。
- ⚠️ **注意**：此两项是「双引擎框架内」的过渡修复。**§2 的大改方案会把它们一并退役/重构**（Excel 不再走后端 GET，editCardValue 物化退役）。新会话实现大改时按 spec §5 退役清单收口，不要保留双口径活路径。

---

## 2. 待实现的大改（已确认设计，下一步 writing-plans）

**spec 全文**：`docs/superpowers/specs/2026-06-21-excel-card-unified-frontend-engine-design.md`（master 已提交 `40eab21`）。

### 已锁定决策（不可在实现期擅改）
1. **前端单引擎 + 两份快照**：一次编辑 → 前端同一套 token 引擎同时算卡片值 + Excel 列值 → **Excel 恒等卡片**（都走 0.93 口径，分叉按构造消除）。
2. **导出（XLSX/PDF）也读快照**（后端不再重算）。
3. **范围 = 报价(QUOTE) + 核价(COSTING) 两侧**。
4. **提交(submit) 冻结前端两份快照**，不再后端权威重算。
5. **量级**：退役后端「显示/导出计算引擎」职责，前端权威；**同步更新 `docs/三大核心模块基线.md`**。
6. **row_data** 降为「前端 Excel 快照的扁平存档」（导出/审计），非显示/导出权威源。

### 核心新增件
**前端 Excel 列求值器** `cpq-frontend/src/pages/quotation/buildExcelSnapshot.ts`（建议）：输入 Excel 模板列定义 + 本 lineItem 各组件数据 + 前端 `componentSubtotals`/`crossTabRows`（复用 `getComponentSubtotals`/`buildCrossTabRows`），逐列按 `source_type` 用 `evaluateExpression` 求值 → 产出 `{rows:[{col_key:value}]}` 快照。**复用卡片同一 token 引擎**，故 Excel 值=卡片口径。
> 前端已具备底层能力：`computeProductSubtotal`(算 SUBTOTAL)、`computeTabSubtotalsByColumn`(列和)、`evaluateExpression`+`buildCrossTabRows`(跨页签)。现状前端只能算 VARIABLE/FORMULA 列，`TAB_JOIN_FORMULA/CARD_FORMULA`「须后端求值」(正是 v2 走后端 GET 的原因) —— **把这些列搬到前端求值是本次主工作量与主风险**。

### 落地顺序（spec §9）
1. 前端 `buildExcelSnapshot` + vitest（恒等性 + 逐 source_type）。
2. `LinkedExcelView` 切 `useExcelSnapshotRows` + 快照来源接前端求值器（报价侧）。
3. `buildDraftPayload`/`saveDraft` 落两份快照（前端算值，后端原样存）。
4. 导出读快照渲染（XLSX/PDF）。
5. 提交冻结快照。
6. 核价侧复用同模式 + 用例。
7. 退役清单收口 + 更新基线文档。

### 两个待用户确认的 spec 评审问题（我问了但用户转去交接，新会话先问清再 writing-plans）
1. **§4.1 列覆盖**：模板在用的 Excel 列 source_type 是否就 `TAB_JOIN_FORMULA / CARD_FORMULA / EXCEL_FORMULA / VARIABLE / BASIC_DATA / FIXED`？有无服务端专属数据源列需提前纳入？（建议：实现期先 grep 全模板/`costing_template_column` 在用 source_type 清单）
2. **§9 节奏**：先报价侧打通(1–5)验恒等性，再推核价(6)，可否？

---

## 3. 关键技术上下文（接手必读）

### 0.93 vs 0.8527 的本质（务必理解，否则会修错方向）
- 卡片 footer「产品小计」= 前端 `computeProductSubtotal`(QuotationStep2.tsx L1268)→`evalProductSubtotalFromSubtotals` 求 `报价小计.总成本` 公式 = **0.93**。
- Excel C = 后端 `ComponentDataEffectiveRows.computeScaled` 读 row_data 列和 + 同公式求值 = **0.8527**。
- 公式（DB 实测）：`报价小计.总成本 = 来料.材料成本 + 来料.材料损耗成本 + 组装加工费.费用 + 其他费用.费用`；其中 `来料.材料成本 = Σ(元素:组成用量×净用量×单价) + 加工费 + Σ(外购件…)`（**材料成本已含加工费**）。
- 差 0.0774 = 来料两行加工费；前端 footer 比后端公式多算了一遍加工费（前端聚合 `computeTabSubtotal` 把 is_subtotal 列逐行求和的口径 vs 后端列和→公式 token 求值，两者在加工费上口径不同）。
- **用户立场（强调多次）：卡片(0.93)与公式配置都没问题，Excel 一直不对**。故方案不是「判谁对」，而是**让 Excel 用卡片同一前端引擎算 → 天然等于 0.93**。**切勿再去改卡片或质疑 0.93**。

### 两条 row_data 写入路径（现状，大改后收口为前端单写）
| 写入者 | 时机 | 引擎 |
|---|---|---|
| `CardSnapshotService.editCardValue`(失焦) | 即时 | 后端 FormulaCalculator（含修复一的换算物化） |
| `QuotationService.saveDraft`(防抖~1.5s) | 之后，**覆盖** | 前端 `computeAllFormulas`(buildDraftPayload L890 写 rowData) |
→ 最终落库 row_data = 前端算的（saveDraft 晚、覆盖 editCardValue）。**大改后：前端算两份快照，saveDraft 原样存，editCardValue 物化退役。**

### 产品卡片是前端算 + 防抖 saveDraft 存（已查证）
`buildDraftPayload`(QuotationWizard.tsx L717+) 对每行跑 `computeAllFormulas`(L890) 写进 rowData(L794) → saveDraft → 后端 `cd.rowData=cdDraft.rowData` 原样落。Excel 视图当前读 row_data(useBackendExcelRows 后端 GET)。

### 现有 Excel 显示三路径（LinkedExcelView 里选一）
- `useBackendExcelRows`（v2，后端 GET，**当前报价用**）→ 大改后退役。
- `useExcelSnapshotRows`（读 `quote_excel_values`/`costing_excel_values` 快照）→ **大改后改用这条**。
- `useLinkedExcelRows`（旧模型，前端算部分列）→ 其可复用解析逻辑下沉进 `buildExcelSnapshot`。

### 核心文件
- 后端：`com.cpq.quotation.service.card.ComponentDataEffectiveRows`(显示计算,退役)、`ExcelViewService`(GET+导出)、`CardSnapshotService`(editCardValue~1437/materializeWholeLineRowData)、`com.cpq.configure.service.ConfigureSnapshotService`(computeLineRowData,修复一在此)、`RowDataMaterializer`、`QuotationExportService`(PDF)、`QuotationService.saveDraft`(~494 cd.rowData=)、`quotationSnapshotService.submit`。
- 前端：`pages/quotation/QuotationStep2.tsx`(computeAllFormulas/computeProductSubtotal/getComponentSubtotals/computeTabSubtotalsByColumn/evalProductSubtotalFromSubtotals/handleSnapshotCellEdit/LineItem)、`useBackendExcelRows.ts`(+excelRefreshSignal,修复二)、`useExcelSnapshotRows.ts`、`useLinkedExcelRows.ts`、`LinkedExcelView.tsx`、`QuotationWizard.tsx`(buildDraftPayload/autoSaveDraft)、`services/quotationService.ts`。

---

## 4. Git / 工作区状态（重要）

- **master HEAD `40eab21`**（验收时；并发会话在推进 step3 等，新会话先确认）。本会话三提交 `30938c7`/`788add3`/`40eab21` 均在 master。两个本会话 worktree 已清理。
- **并发 worktree（勿动）**：`.claude/worktrees/card-subtotal-2dp-rest-4dp`、`.claude/worktrees/excel-component-ref-fix`（都不是本会话的）。
- **主工作区未提交 WIP（并发会话的，勿擅动，但与本次相关需留意）**：
  - `cpq-frontend/src/pages/quotation/LinkedExcelView.tsx`、`useLinkedExcelRows.ts` —— **大改要动这两个文件**，但它们现有并发未提交改动；实现期须与对应会话协调 / 在隔离 worktree 基于最新 master 开发并避开冲突。
  - `cpq-backend/.../engine/unit/UnitConversion.java` + 前端 `utils/unitConversion.ts` —— 并发会话在加单位换算（G/KPCS 等），**dev server 热加载已生效**（修复一/二的 live 验证就靠它）。
  - 其它：ComponentService.java、组件管理/模板配置若干 tsx、RECORD.md（我已追加 3 条，未提交，随并发 WIP 一起）、docs/table 等。
- **并发纪律**：提交只 `git add` 本次明确文件，**严禁 `git add -A`**；新功能必须隔离 worktree（本会话因 cwd 隔离用回退 `git worktree add .claude/worktrees/<name> -b worktree-<name> master`；前端 worktree 软链主树 node_modules：`ln -s /home/joii/project/cpq/cpq-frontend/node_modules <worktree>/cpq-frontend/node_modules`，勿重装）。

---

## 5. 验证环境速查

- DB：`export PGPASSWORD=joii5231; psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db`
- admin 登录(cookie)：`POST /api/cpq/auth/login {"username":"admin","password":"Admin@2026"}`（`curl -c jar -b jar`）
- dev server：前端 5174 / 后端 8081（**服务主树**；worktree 改动合并 master 后才被反映；后端 Quarkus 下次请求热编译）。探活用 `/api/cpq/quotations`（期望 401）。
- 后端单测：`cd cpq-backend && ./mvnw test -Dtest='...' -Dquarkus.http.test-port=0`（**须在 worktree 自己的 cpq-backend 跑**，见记忆 `cpq-worktree-maven-test-tree`）。
- 前端：`cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` + `npx vitest run <file>`；改动文件 Vite transform 自检 `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/<相对路径>`（须合并 master 后 dev server 才反映 worktree 改动）。
- **E2E 两 spec（`quotation-flow.spec.ts`/`composite-product-flow.spec.ts`）预存损坏**（合并前既存，见 RECORD 遗留），本会话功能验证靠 LIVE API + 单测，未用 E2E 闸门。大改属协议级需 E2E，可能要先修这两 spec（独立技术债）。
- **测试数据**：报价单 `QT-20260621-1787`（quotation id `145c4b2d-f7ed-4c97-9f1a-bd91cef91831`，line id `2354f7f8-d3a2-477a-8f2f-90021755a6b6`，customerTemplateId `8be8cc2c-2c1f-45f7-b401-d0e3b15abca2`）；元素组件 `1b2d1bdb-...`(rowKeyFields `["元素","料件"]` sep `||`)，来料 `e31bbdd1-...`，报价小计 SUBTOTAL `8ccd4a88-...`。复现场景：元素 Cu 单价=1122(g/PCS) → 卡片 footer 0.93 vs Excel C 0.8527。**注：本会话复现时已把 单价=1122 真实落库**（该草稿现 card 0.93 / Excel 0.8527，A 材料成本 0.7782）。

---

## 6. 接手第一步建议

1. `git log --oneline -6 master` 确认 HEAD（可能已被并发推进）。
2. 读 spec 全文 + 本文 §3「0.93 vs 0.8527 本质」。
3. 跟用户确认 §2 两个待评审问题（列 source_type 覆盖 / 报价先行节奏）。
4. 调 `superpowers:writing-plans` 出实现计划 → 默认 `superpowers:subagent-driven-development` 执行（隔离 worktree）。
5. **纪律**：不改卡片、不质疑 0.93；目标=Excel 用前端引擎算到恒等卡片；收口退役双口径路径；同步更新 `三大核心模块基线.md`。
