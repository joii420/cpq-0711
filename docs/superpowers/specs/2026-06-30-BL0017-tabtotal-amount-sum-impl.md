# 实现 Spec：BL-0017 `[页签(总计)]` 求值口径对齐「金额字段小计之和」

> 日期：2026-06-30 ｜ 模块：报价/核价 求值口径（component_subtotal）
> 类型：单 token 口径变更 ｜ 方案 **A′（加性哨兵键，不动裸键、不动求值器）** ｜ **2 步拆分**
> 硬约束：**不修改任何公式取值/计算步骤** —— 三个求值器零改动；**裸键 `componentSubtotals[code/cid/tabName]` 一字不动** → 其它读裸键的公式（previous_row_subtotal 累加、产品小计兜底、折扣）取值全部不变。
> 关联：前置 WYSIWYG 修复（3d671e5，引入 `is_tab_total`）；architect 评审（方案 A 框架 + 2 步拆分）；实现 spec 第一轮子代理评审（指出方案 A 全局重定义裸键会连带改 previous_row_subtotal/产品兜底 → 与本硬约束冲突；改动点欠覆盖：前端 4 处写裸键、后端 Meta 点错）。本 spec 据该评审 + 硬约束改为 A′。

---

## 1. 目标与约束

- **目标**：`[页签(总计)]` 求值 = 该页签所有「金额字段」(`is_amount && is_subtotal`)的列小计之和（与底部「本页签金额合计」`tabTotalLines.ts#sumTabColumns` 同口径同值）。
- **硬约束**：① 不改三个求值器（`formulaEngine.ts` / `FormulaCalculator.java` / `FormulaCalculationService.java`）；② **不改裸键值** → 不连带改任何其它公式取值。
- **现状**：`[页签(总计)]` token = `{is_tab_total:true, value=首个小计列, tab_name=首个小计列, component_code=alias}` → 求值命中 `code#首列` = 首个小计列的列小计。

---

## 2. 方案 A′（加性哨兵键）

引入哨兵列名 **`__amount_total__`**（双下划线，沿用既有哨兵约定 `__loading__`/`__seq_no__`/`__cardValueFailed__`，不与任何真实列名冲突）。

1. **各装配点新增哨兵列键**（加性，不碰任何现有键/裸键）：在所有登记 `code#列`/`name#列`/`cid#列` 的地方，**额外**登记
   `componentSubtotals[`${code}#__amount_total__`] = Σ over fields where (is_amount && is_subtotal) of 该列列小计`（`name#`/`cid#` 同理）。无金额列 → 登记为 `0`（显式登记，避免回退裸键）。
2. **解析器把 token value 指向哨兵**（Step 2）：`[alias(总计)]` 输出 `value: '__amount_total__'`、`tab_name: '__amount_total__'`、保留 `is_tab_total:true` + `component_code`、`label=componentName`。
3. **求值器不改**：按现有「`component_code#value` 列键」逻辑命中 `code#__amount_total__` = Σamount。
4. **序列化不改**：`tokensToDrawerExpression` 已优先 `is_tab_total` 回显 `[页签(总计)]`（不读 value），前置修复已就位。

> **为何满足硬约束**：求值器逻辑一字不变；**裸键 `componentSubtotals[code]` 完全不动** → `previous_row_subtotal`（formulaEngine:285 / FormulaCalculator:163）、无公式产品小计兜底（QuotationStep2:1277-1284）、按页签折扣（读 `code#列`）取值**全部不变**。改动只让 `[页签(总计)]` 这一个 token 从命中 `code#首列` 改命中 `code#__amount_total__`。
>
> **容错优于"清 value"方案**：若某装配点漏登记哨兵键，求值器 fallback 链 `code#哨兵(miss) → 裸 code` 会退到裸键（Σis_subtotal，旧口径近似值），**前端不为 0**（Excel/config 路径裸键缺失才为 0，故那两路必须覆盖，见风险 1）。

---

## 3. 2 步拆分

### Step 1（前置 · 纯加性 · 零行为变化）：哨兵键在所有装配点就位
- 在下列**全部**写键点新增 `…#__amount_total__ = Σamount`；**不动解析器**。
- token 仍 `value=首列` → 仍命中 `code#首列` → `[页签(总计)]` 求值**不变**；哨兵键暂无人读。
- 验证：每个装配点的 `componentSubtotals` 含 `…#__amount_total__` 且 = Σamount；`[页签(总计)]` 求值仍=首列（证明未切换）。

### Step 2（口径切换 · 单 token）：解析器 value 指向哨兵
- 解析器 `[alias(总计)]` 输出 `value/tab_name='__amount_total__'`。
- token 命中已就位的哨兵键 → 报价卡片 / Excel / 配置快照三视图**同时**切到 Σamount。其它公式不受影响。

---

## 4. 改动点清单（据评审补全：前端 4 处写键 + 后端 3 Meta 构造点 + 2 装配点）

### Step 1 — 哨兵键就位（加性）

**前端（4 处写键，建议抽共享助手 `sumAmountColumns(fields, colSums)` 与 `tabTotalLines.ts#sumTabColumns` 同口径，杜绝漂移）**
1. `QuotationStep2.tsx#getComponentSubtotals`（约 1219-1231）：循环内额外 `componentSubtotals[`${cid|code|tabName}#__amount_total__`] = Σamount`。
2. `QuotationStep2.tsx` 内联 PASS1（约 2047-2049）：同样登记哨兵键（卡片行公式 `computeAllFormulas`@2461 读此 map）。
3. `QuotationStep2.tsx#subtotalsFromResolvedRows`（约 1095，PASS2 回填）：**关键** —— 此处用 resolvedRows 回填会覆盖卡片最终生效值，必须同步登记哨兵键 = Σamount（按 is_amount 过滤 resolved 列和）。漏此点 → 卡片视图 Step 2 后口径不切换（评审 #1 致命漏点）。
4. `QuotationWizard.tsx#snapshotRows`（约 957）：保存时行内公式求值的 map，登记哨兵键。

**后端（2 装配点 + 3 Meta 构造点传播 is_amount）**
5. `ComponentDataEffectiveRows.java`（Pass1 约 128-156）：
   - `Meta` 增携「金额列名集合」（或 fields with is_amount）。
   - Pass1 在登记 `code#col`/`name#col` 处**额外**登记 `code#__amount_total__`/`name#__amount_total__` = Σamount。
   - **Meta 构造点传播 is_amount（评审纠正：spec 原点 `CardSnapshotService:268` 是错的，那只是快照字段拷贝、不构造 Meta）。真实构造点 3 个，全补**：
     - `ExcelViewService.java:457`（metaById from cdList，`Component c` 有 fields）
     - `ExcelViewService.java:502`（extraSubtotalMetas，`Component comp`）
     - `LineDiscountService.java:158`（折扣重算 loadMetas，`Component c`）——漏此点则折扣重算时 `[页签(总计)]` 哨兵键缺失 → 退裸键（Σis_subtotal），折后口径错。
6. `ConfigureSnapshotService.java#accumulateColumnSubtotals`（约 925-941）：入参补 `fields`（调用处 865 行 `convertRowsForCrossTab(fields, rows)` 已有 fields），额外登记 `code#__amount_total__`/`tabName#__amount_total__` = Σamount。

**不动**：三个求值器、`lineDiscount.ts`、所有**裸键**写入语义（裸键值保持 Σis_subtotal）。

### Step 2 — 解析器
7. `formulaSerialize.ts`（`expressionToTokens` 的 `[alias(总计)]` 分支，约 838-851）：`value: '__amount_total__'`、`tab_name: '__amount_total__'`、保留 `is_tab_total:true`/`component_code`/`label=componentName`。序列化侧（917-934）**不改**。
8. `tabTotalLines.ts`（文件头注释）：标注显示行与引擎 `[页签(总计)]` 现已同值同口径（经哨兵键），不再「有意分叉」。

---

## 5. 测试方案（含评审必加项）

### Step 1
- 前端单测 `getComponentSubtotals`：混合 is_amount/非金额 is_subtotal 列，断言新增 `…#__amount_total__` = Σ(仅 is_amount 列)；**裸键 `code` 仍 = Σ所有 is_subtotal（未变）**；列键 `code#列` 仍含全部 is_subtotal 列。
- **【评审必加】卡片视图 render 路径回归**：构造同上组件，走 render（inline PASS1 + PASS2 backfill `subtotalsFromResolvedRows` 之后）断言 map 含 `code#__amount_total__`=Σamount（防 1095 覆盖漏登记）。
- 后端 `ComponentDataEffectiveRows` 单测：哨兵键 = Σamount；裸键不变；Meta 三构造点传播覆盖。
- **【评审必加】`LineDiscountService` 折扣重算单测**：含 `[页签(总计)]` 的 SUBTOTAL 公式，折后哨兵键 = 缩放后 Σamount。
- `ConfigureSnapshotService` 单测：哨兵键 = Σamount。
- 零 is_amount 列：哨兵键 = 0（显式登记）；**裸键不变**（证明零金额不连锁影响 previous_row_subtotal/兜底）。

### Step 2
- `formulaSerialize.test.ts`：
  - `expressionToTokens('[产品(总计)]')` → `value==='__amount_total__'` && `tab_name==='__amount_total__'` && `is_tab_total===true`；
  - 往返 → 仍 `[产品(总计)]`（WYSIWYG 不回归）；
  - **求值切口径**：`componentSubtotals = { '产品#汇率':1, '产品#__amount_total__':10, 产品:99 }`，断言 `[产品(总计)]` 求值 = 10（哨兵键），不再是 1（首列）、也不是 99（裸键）。
  - `[产品.汇率]`（无标记，value='汇率'）求值/显示**不变**。
- **【评审必加】三视图一致性**：同一 line item，断言 Excel 快照该列 == 卡片 `[页签(总计)]` == 底部「本页签金额合计」== Σamount。
- **【评审必加】previous_row_subtotal 回归**：裸键未变 → 累加公式 row-0 兜底值**与修复前一致**（证明无连带）。

### 自检（CLAUDE.md 强制）
- 前端 `tsc --noEmit` 0 错；`vitest` 相关全绿；改动 `.tsx/.ts` curl 200。
- 后端 worktree 内 `cpq-backend` 亲跑 `mvnw test`（见 [[cpq-worktree-maven-test-tree]]）；endpoint 200/401；**无 Flyway 迁移**。
- **E2E**：改 `QuotationStep2.tsx`（CLAUDE.md 强制清单首项）+ `ComponentDataEffectiveRows.java`（后端求值链路）→ 强制 `quotation-flow.spec.ts`；**复测核价卡片侧**（走 FE 共享渲染，同 bug 面）+ 配置快照 admin 端点。三视图 `[页签(总计)]`=Σamount 且一致作为基线未破坏证据。

---

## 6. 兼容 / 迁移

- **无 DB 迁移**（`is_tab_total` 已在 types；哨兵键是运行期 map 键，不落库 schema）。
- **存量快照自愈式**：已存单据对外金额是持久化快照，不自动变；重开触发重算才切。**已 SUBMITTED/冻结单保持旧值**（冻结快照不被动重算）。**不强制批量重算** + PM 向业务通告「重开旧单 `[页签(总计)]` 相关金额可能修正」。
- **存量 token**：旧 `[页签(总计)]`（无 is_tab_total、value=列）仍命中列键、仍显示 `[页签.列]`、按首列求值 —— **BL-0018** 职责；过渡态须在验收说明标注。

---

## 7. 风险与缓解

| # | 严重度 | 风险 | 缓解 |
|---|---|---|---|
| 1 | 高·时序 | Excel/config 路径哨兵键缺失时若先上 Step 2 → 退裸键，Excel 裸键本就无 → 0 | **2 步拆分**：Step 1 哨兵键四装配点+两后端全就位再上 Step 2 |
| 2 | 高·业务 | 报价+核价 `[页签(总计)]` 对外金额变（=修正） | PM 通告；冻结单据保持旧值不强制重算 |
| 3 | 高·协议传播 | 后端 3 个 Meta 构造点（ExcelViewService:457/502 + LineDiscountService:158）漏补 is_amount → 该路径哨兵键缺失退裸键，口径错 | grep 全部 Meta 构造点逐一补；E2E + 核价 + 折扣单测兜底 |
| 4 | 中·欠覆盖 | 前端 4 处写键漏一处（尤其 PASS2 `subtotalsFromResolvedRows:1095`）→ 该视图不切换 | 抽共享助手 + 卡片 render 路径单测 + 三视图一致断言 |
| 5 | 中·语义 | 零 is_amount 列页签 `[页签(总计)]`=0 | 哨兵键显式登记 0；**因裸键不动，零金额不连锁影响 previous_row_subtotal/兜底**（A′ 相对 A 的优势）；配置期 lint 警告 → BL-0019 |

---

## 8. 验收标准

1. **Step 1 后**：四前端装配点 + 两后端装配点的 `componentSubtotals` 均含 `…#__amount_total__`=Σamount；裸键值**未变**；`[页签(总计)]` 求值**仍为首列**（证明拆分正确、未切换）。
2. **Step 2 后**：`[页签(总计)]` 在报价卡片 / Excel 视图 / 配置快照三处求值 = Σ(is_amount 列小计)，三者一致，且 = 底部「本页签金额合计」显示行值。
3. `[页签.某列]` 列引用解析/求值/显示**与修复前一致**；按页签折扣不变；**previous_row_subtotal 累加、无公式产品小计兜底取值与修复前逐值一致**（硬约束证据）。
4. 含 `[页签(总计)]` 的产品小计随新口径修正；导出舍入口径不变（4dp/2dp，COMPUTED_FALLBACK=4）。
5. WYSIWYG 不回归：`[页签(总计)]` 保存后仍显示 `[页签(总计)]`。
6. `tsc` 0 错；前后端相关单测全绿；E2E `quotation-flow` 通过 + 核价/配置快照复测一致。

---

## 9. 范围外（不做 → BACKLOG）

- 不动求值器、不动裸键、无 DB 迁移、不做存量 token 修复（BL-0018）、不强制批量重算。
- 零 is_amount 列页签的配置期 lint 警告 + 「是否回退 Σis_subtotal」PM 裁决 → **BL-0019**。
- config 路径 `[页签.列]` 经 `FormulaCalculationService` 只读裸 code 的既有粗化（评审 #7，pre-existing，非本次引入）→ **BL-0020**。

---

## 10. 实现 + 验证记录（2026-06-30 落地）

### 实际落地装配点（与计划一致，1 处新增）
- 前端 4 处哨兵键登记：`QuotationStep2.tsx` `getComponentSubtotals`(~1241)、inline PASS1(~2070)、`subtotalsFromResolvedRows`(~1101) + `tabTotalLines.ts` 助手 `sumAmountFromByCol` / 常量 `AMOUNT_TOTAL_KEY`。
- 后端 5 处：`ComponentDataEffectiveRows`(Meta 增 `amountCols` + Pass1 哨兵累加 + `subtotalWithDiscount` 折扣路径同源) + 3 个 Meta 构造点(`ExcelViewService:457/502`、`LineDiscountService:158`) + `ConfigureSnapshotService.accumulateColumnSubtotals`。
- 解析器：`formulaSerialize.ts` `[alias(总计)]` 分支 token `value/tab_name = __amount_total__`、保 `is_tab_total`；序列化侧不改。
- **【新增检查点·计划外】`CardSnapshotService` byColNode 序列化排除哨兵**：`subtotalByColumn` JSON 由 `componentSubtotals` 按 `prefix#` 前缀展开重建（CardSnapshotService:1532-1543）；哨兵键 `code#__amount_total__` 会被 `startsWith` 命中而泄漏成一个伪「列」→ 污染持久化快照。修法：该循环 `if (AMOUNT_TOTAL_KEY.equals(col)) continue;`。哨兵是供 `[页签(总计)]` 求值的**内部聚合键**，非真实列，不得进 `subtotalByColumn`。
  - 教训：凡「按 `prefix#列` 前缀展开 `componentSubtotals` 成列序列化」的点，都必须排除哨兵键。本工程仅此 1 处（ComponentDataEffectiveRows 的 `subtotalByColumn` 用真实 `colSums`、不含哨兵；ConfigureSnapshotService 只写 map 不展开列）。

### 测试 / 自检证据
- 后端 `ComponentDataEffectiveRowsTest` **6/6**：含 `bl0017AmountTotalSentinelKeyOnlySumsAmountColumns`（[来料(总计)]=金额列 10，非含非金额列的 17）+ `bl0017AmountTotalSentinelUnderPerColumnDiscount`（折扣 ×0.5 → 哨兵=5，覆盖 spec「评审必加 LineDiscountService 折扣重算单测」，走纯单测入口 `subtotalWithDiscount` 无需 DB）。
- 后端 `CardSnapshotSubtotalTest` / `CardSnapshotResolvedRowsTest` / `CardSnapshotDryRunParityTest` 全过（byColNode 排除哨兵不破坏 subtotalByColumn 装配）。
- 前端 `tsc --noEmit` 0 错；`vitest` `formulaSerialize.test.ts` + `unitConversion.resolvedSubtotal.test.ts` + `buildExcelSnapshot.test.ts` **3 文件 210/210**。
- **值中性硬证据（满足「不动求值」硬约束）**：`GoldenCardValuesEquivTest#rockwell`（170 行真实单逐位 md5）在**含 / 不含本次 BL-0017 后端改动**两种代码下，纯读 golden **逐位等价 = `52380a82…`**（确定性，各跑确认）。证明 BL-0017 纯加性、不改任何既有卡片/Excel 求值结果。

### 预存失败甄别（非本次引入，不阻断合并）
- `GoldenCardValuesEquivTest#rockwell` 相对其 golden 常量 `3837c2bd…`（2026-06-25 捕获）已漂移到 `52380a82…`，**在干净 HEAD（无 BL-0017）上同样漂移** → 系 2026-06-25 后 master 上其它提交（集合化落库 `2440ab3` / 失败行哨兵 `9dd6cbc` / 懒算 Excel `6928090`）及 LIVE DB 数据变动所致，**与 BL-0017 无关**，需 golden owner 重新校准常量 → BL（见 BACKLOG）。
- `RefreshCardSnapshotTest:206`（幽灵 editRow 丢弃）在干净 HEAD 同样 FAIL，走 editRow/baseRows 路径，BL-0017 未触碰 → **预存**。
