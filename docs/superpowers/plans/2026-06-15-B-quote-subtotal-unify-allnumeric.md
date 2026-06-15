# B. 报价小计统一(配置公式权威 + 所有数值列求和 + 二阶列) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 报价单页签里行值与小计都严格按**配置公式**算且口径一致；小计对**所有数值列**(FORMULA / DATA_SOURCE / INPUT_NUMBER)求和；输入类型列**失焦/回车后**重算小计与本页签总计。

**Architecture:** 决策 = 「配置公式才权威 + 小计=各显示行之和 + 同一求值路径」。当前残留：小计 pass `computeTabSubtotalsByColumn`(:956) 对每行调 `computeAllFormulas` 时传 `undefined crossTabRows`(:979)，且 `computeProductSubtotal`(:1032) 仍走旧 `computeTabSubtotal`(PASS1) —— 导致 (a) 需 crossTabRows 的 cross_tab 列小计≠渲染行值(外购件材料费 行1.604 vs 小计1.49)、(b) 依赖本组件其它小计列的二阶列(材料成本=ΣSUB) 在 PASS1 读 self 列小计=0 → 小计0(行却 515.4248)。修法 = **小计统一取 `buildCrossTabRows` 的 PASS2 resolvedRows 该列之和**，废 PASS1 脱离 crossTabRows 的重算；二阶列在组件内按依赖序计算；footer 渲染扩展到所有数值列。

**Tech Stack:** React + TS / Vitest（`QuotationStep2.tsx` `buildCrossTabRows`/`computeTabSubtotalsByColumn`/`computeProductSubtotal`/footer 渲染 + `ReadonlyProductCard.tsx`）；Java/Quarkus（`CardSnapshotService` PASS2 对齐）。

**复现基线（QT-20260615-1722, li=fb5cf45c-cbea-45ec-81b7-df07b431e752, 来料 tab）：**
- `材料成本` 列：行1=515.4248、其余 0；**小计当前 ¥0.00**，应 = Σ行 = **515.4248**。
- `外购件材料费` 列：行(外购件2)=1.604、其余 0；**小计当前 ¥1.49**，应 = Σ各显示行(与渲染一致)。
- 来料组件公式（库内）：`材料成本 = SUB(来料·来料材料费) + SUB(来料·外购件材料费) + SUB(来料·自制加工费) − SUB(来料·回收成本)`（component_subtotal 二阶列）；其余为 cross_tab。

---

## 根因（已确认，代码级）

1. `computeTabSubtotalsByColumn`(:956-984)：逐行 `computeAllFormulas(..., undefined /*crossTabRows*/, ...)`(:979) → cross_tab / 二阶列与渲染层(有 crossTabRows + self 列小计)割裂。
2. `computeProductSubtotal`(:1007-1038) 调 `computeTabSubtotal`(:1032 → :986 → :999 同样无 crossTabRows)。
3. `buildCrossTabRows`(:821) PASS2 已按拓扑序 + crossTabRows 出正确 resolvedRows 并回填 `allComponentSubtotals`（06-13 计划）；但**组件内二阶列(component_subtotal 引用 self 其它小计列)**的依赖序未保证 → self 列小计在算 材料成本 行时尚为 0。
4. footer 小计只渲染 `is_subtotal` 列（:2294-2300），输入数值列无小计。

---

## File Structure

- `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`
  - `buildCrossTabRows`(:821-868) — 组件内列计算保证**依赖序**：先算非 component_subtotal-of-self 的列并回填 self 列小计，再算二阶列；回填 helper(:870-) 复用。
  - `computeProductSubtotal`(:1032) / `computeTabSubtotal`(:986) — 改为消费 `buildCrossTabRows` 的 resolvedRows 之和（不再 PASS1 脱离 crossTabRows 重算）。
  - footer 小计渲染(:2294-2320) — 扩展到**所有数值列**(INPUT_NUMBER/FORMULA/DATA_SOURCE)，每列 = Σ resolvedRows 该列值。
  - 输入框 blur/enter(:handleInputBlur 等) — 触发该 tab 重算（小计 + 本页签总计），非逐键。
- `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx` — 详情/核价视图同口径(读 resolvedRows 之和)。
- `cpq-backend/.../quotation/service/CardSnapshotService.java` — PASS2 二阶列依赖序回填对齐（与前端逐字节一致）。
- 测试：`buildCrossTabRows.test.ts`（二阶列 + cross_tab 列小计=Σ行）、后端快照测试。

**不变量：** 任意 is_subtotal 列小计 == 该列各 resolvedRow 显示值之和（前后端逐字节对齐）；二阶列(component_subtotal of self)按依赖序后小计正确；输入数值列小计 = Σ该列输入值。

---

## Task B1：二阶列小计=Σ行 失败测试（RED）

**Files:**
- Test: `cpq-frontend/src/pages/quotation/buildCrossTabRows.test.ts`（补充）

- [ ] **Step 1: 读 :821-1004 现状**（buildCrossTabRows + 回填 helper + computeTabSubtotalsByColumn + computeTabSubtotal）。
- [ ] **Step 2: 写失败测试** — 构造 来料 最小 componentData：一阶 cross_tab 列(来料材料费=SUM(元素…) 行1=83.656…) + 二阶列 材料成本 = component_subtotal(self·来料材料费 + self·外购件材料费 + self·自制加工费 − self·回收成本)。断言 `buildCrossTabRows` 回填后：

```ts
// allComponentSubtotals 回填后, 二阶列小计 = Σ resolvedRows 该列 (非 0)
expect(allComponentSubtotals['来料#材料成本']).toBeCloseTo(515.4248, 3); // 修复前: 0
// cross_tab 列小计 = Σ各显示行(与渲染一致)
expect(allComponentSubtotals['来料#外购件材料费']).toBeCloseTo(/*Σ行*/ 1.604, 3); // 修复前: 1.49 割裂
```
（数值按构造 fixture 设定；关键断言 = 二阶列非 0 且 = Σ行、cross_tab 列 = Σ行。）

- [ ] **Step 3: 跑确认失败** → `cd cpq-frontend && npx vitest run src/pages/quotation/buildCrossTabRows.test.ts`，Expected: FAIL（材料成本 0 / 外购件材料费割裂）。
- [ ] **Step 4: 提交** `git commit -m "test(subtotal): 二阶列+cross_tab列 小计=Σ行 失败用例 (RED)"`

---

## Task B2：buildCrossTabRows 组件内依赖序 + 回填二阶列（GREEN）

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（`buildCrossTabRows` + 回填 helper）

- [ ] **Step 1: 组件内列计算分两阶段** — 在 buildCrossTabRows 算某组件 resolvedRows 时：先算所有**非 component_subtotal-引用-self** 的列、立即把这些列的列小计(Σ行)回填进本组件作用域可见的 `allComponentSubtotals[self#col]`；再算**引用 self 列小计的二阶列**（此时 self 列小计已就绪）。识别二阶列 = 该字段公式 token 含 `component_subtotal` 且 `component_code === 本组件 alias/code`。
- [ ] **Step 2: 回填 helper 统一** — 回填 = 从最终 resolvedRows 按每个 is_subtotal 列求 Σ行 写入 `allComponentSubtotals[cid#col]/[code#col]/[tabName#col]` 与总计（复用 06-13 的 `subtotalsFromResolvedRows`）。**唯一口径：列小计 = Σ resolvedRows 该列。**
- [ ] **Step 3: 跑 B1** → PASS（材料成本=515.4248、外购件材料费=Σ行）。既有 `buildCrossTabRows.test.ts`/`useCardSnapshots.test.ts`/`rowDedup.test.ts` 不回归。
- [ ] **Step 4: 提交** `git commit -m "fix(subtotal): buildCrossTabRows 组件内依赖序算二阶列 + 列小计统一Σ行 (GREEN)"`

---

## Task B3：computeProductSubtotal / computeTabSubtotal 改读 resolvedRows 之和

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx:986-1038`

- [ ] **Step 1: 废 PASS1 脱离 crossTabRows 重算** — `computeProductSubtotal`/`computeTabSubtotal` 改为：先 `buildCrossTabRows` 出各组件 resolvedRows，组件小计 = 该组件 is_subtotal 列 Σ行之和（即 Task B2 回填后的 `allComponentSubtotals[cid]`），不再调 `computeTabSubtotalsByColumn(..., undefined crossTabRows)`。若 computeTabSubtotalsByColumn 仅此一处用，标注其为「仅 driver-only 无 cross_tab 场景的兜底」或删除；避免两套口径。
- [ ] **Step 2: 跑既有产品小计/快照相关 vitest** → 全 PASS。
- [ ] **Step 3: tsc + Vite 200** → `npx tsc --noEmit -p tsconfig.json`；`curl ... /src/pages/quotation/QuotationStep2.tsx` 200。
- [ ] **Step 4: 提交** `git commit -m "fix(subtotal): 产品/页签小计统一取 resolvedRows 之和, 废 PASS1 重算"`

---

## Task B4：footer 渲染所有数值列小计 + ReadonlyProductCard 对齐

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（footer 小计行渲染 :2294-2320）、`ReadonlyProductCard.tsx`

- [ ] **Step 1: footer 扩展** — 小计行不再仅渲染 `is_subtotal` 列；对**每个数值列**(field_type ∈ {INPUT_NUMBER, FORMULA, DATA_SOURCE 且值为数} )渲染 Σ resolvedRows 该列值。非数值列(文本/单位)留空。`本页签总计` 维持 = Σ is_subtotal(成本)列（不含输入列），避免把输入量并进成本总计。
- [ ] **Step 2: ReadonlyProductCard 同步** — 详情/核价视图 footer 同口径（读同一 resolvedRows 之和）。
- [ ] **Step 3: tsc + Vite 200**（两个 .tsx 各 curl 200）。
- [ ] **Step 4: 提交** `git commit -m "feat(subtotal): footer 对所有数值列求和(含输入列), 详情视图对齐"`

---

## Task B5：输入类型失焦/回车重算

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（`handleInputBlur` / onPressEnter 链路）

- [ ] **Step 1: 定位输入提交** → `grep -n "handleInputBlur\|onPressEnter\|handleRowChange" QuotationStep2.tsx`。确认输入数值列 blur/enter 后会写回 row 值并触发该 tab 重算（小计 + 本页签总计 + 依赖该列的二阶列）。逐键不重算（性能）。
- [ ] **Step 2: 若缺重算触发** — 在 blur/enter 提交后 invalidate 该 tab 的小计派生（复用现有 recompute 入口；不要新建第二套）。
- [ ] **Step 3: tsc + Vite 200 + 提交** `git commit -m "fix(subtotal): 输入数值列失焦/回车后重算小计(非逐键)"`

---

## Task B6：后端 CardSnapshotService 二阶列依赖序对齐 + 对拍

**Files:**
- Modify: `cpq-backend/.../quotation/service/CardSnapshotService.java`（PASS2 回填）
- Test: 后端快照测试

- [ ] **Step 1: 失败测试** — 含二阶 component_subtotal 列的快照，断言 `subtotalByColumn["材料成本"]` ≈ 515.4248（修复前 0）。
- [ ] **Step 2: 实现** — PASS2 组件内按依赖序：先回填一阶列 componentSubtotals[self#col]，再算/回填二阶列（与前端 B2 同序）。`subtotalByColumn` 取 PASS2 resolved 之和。
- [ ] **Step 3: 对拍** — 同 fixture 前端 `allComponentSubtotals['来料#材料成本']` 与后端 `subtotalByColumn["材料成本"]` 数值一致。
- [ ] **Step 4: 跑两端 + 提交** `git commit -m "fix(subtotal): 后端PASS2 二阶列依赖序回填 + 前后端对拍"`

---

## Task B7：E2E（协议级强制）+ 真机复核（合并后）

- [ ] **Step 1: E2E** → `cd cpq-frontend && npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts e2e/composite-product-flow.spec.ts --reporter=list`，Expected: 全 `passed`、`加载中 final=0`、8 Tab 加载中=0。
- [ ] **Step 2: 真机复核 QT-20260615-1722 来料 tab** — `材料成本` 小计 = 515.4248（非 0）；`外购件材料费` 小计 = Σ各行；输入数值列(组成用量等)有小计并随 blur 更新。
- [ ] **Step 3: 附截图**（修复前/后 来料 tab + 1 个含输入列页签）。

---

## Self-Review

- **Spec 覆盖：** 配置公式权威+同一求值(B2/B3) / 所有数值列求和(B4) / 输入失焦重算(B5) / 二阶列(B1/B2/B6) / 前后端对拍(B6) / E2E(B7)。✓
- **DRY：** 单一口径「列小计 = Σ resolvedRows 该列」，前后端各一 helper；删 PASS1 双口径。✓
- **AP-51 行数纪律：** 仍遵 `rowCount>0 ? expansion.rowCount : baseRows.length`，不 Math.max 累加。
- **风险：** 核心求值/快照模块（AP-40/AP-50/AP-51 邻域）→ 强制双 spec E2E + 对拍；禁 `git add -A`。
- **依赖：** 与 A 计划同改 QuotationStep2/ReadonlyProductCard，合并时注意不互相覆盖（A 改 component 编辑器 + formulaSerialize；B 改 quotation 小计，文件交集小）。
