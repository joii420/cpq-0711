# Footer 列小计单一来源化（方案 A 结构性根治）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让报价单卡片 footer（含非小计数值列）与 `allComponentSubtotals`、详情页 footer 全部从 `buildCrossTabRows` 的 resolvedRows 求和取数，废弃空-crossTabRows 旁路，使「footer 列值 === 该列已渲染行值之和」（WYSIWYG），并把 ¥ 符号改为严格按 `field.is_amount`。

**Architecture:** 后端 `backfillSubtotalsFromResolved` 早已是"从带完整 crossTabRows 的 resolvedRows 求和"的正确同源架构。本方案把前端 footer 拉齐到该口径：在 `buildCrossTabRows` 内对**每个组件**额外产出 `columnSumsByComp`（覆盖 is_subtotal + INPUT_NUMBER/FORMULA/DATA_SOURCE 全数值列），footer 与 `allComponentSubtotals` 的非小计列均从此单一来源取值；删除 `computeNonSubtotalColumnSums` 空旁路；同时修复 `buildCrossTabRows.computeRows` 未串 `prevRowValues` 的隐藏二次分叉。后端零改动（A-保守：非小计列合计纯前端运行时，不落库）。

**Tech Stack:** React + TypeScript（Vite）；Vitest 单测；Playwright E2E。仅前端改动。

**关键决策（用户已拍板）：**
- ¥ 符号**严格只看 `field.is_amount === true`**。勾了"小计(is_subtotal)"但没勾"金额列"的列（如管理费/利润）显示**纯数字无 ¥**。这与数据行单元格（ComponentCell 按 is_amount）口径一致。
- 后端不改（A-保守）。`subtotalByColumn` 落库语义维持只 is_subtotal 不变。
- `allComponentSubtotals` 仍**只接受 is_subtotal 列**（cross_tab_ref / component_subtotal token 求值依赖），新增 `columnSumsByComp` **不得**回灌 `allComponentSubtotals`。

**WYSIWYG 不变量（验收总纲）：** 对任一组件任一数值列 `col`，`columnSumsByComp[compKey][col] === Σ_i resolvedRows[i][col]`，且对 activeComponent 等于 footer 显示行（effectiveRows）逐行之和。

---

## 涉及文件结构

| 文件 | 责任 | 本计划处置 |
|---|---|---|
| `cpq-frontend/src/pages/quotation/QuotationStep2.tsx` | 报价/核价编辑卡片渲染 + 小计计算 | 改 `buildCrossTabRows`、`subtotalsFromResolvedRows`、删 `computeNonSubtotalColumnSums`、改 allComponentSubtotals 构建段、改 footer 渲染 |
| `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx` | 详情页只读卡片渲染 | footer 同源（显示非小计数值列合计 + ¥ 按 is_amount） |
| `cpq-frontend/src/pages/quotation/columnSumsByComp.test.ts` | 新增单测 | Task 1 创建 |
| `docs/三大核心模块基线.md` / `docs/反模式.md` / `docs/RECORD.md` | 文档登记 | Task 5 |

**自检命令（每个前端 Task 完成前必跑）：**
- `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 0 错误
- `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/<改动文件>` → 200（dev server 在主工作区，合并前可对主工作区同名路径自检；worktree 内改动以 tsc + 单测 + E2E 为准）
- E2E（协议级强制）：`npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list` → `1 passed` + `'加载中' final count = 0` + 8 Tab `'加载中'=0`

> **worktree 纪律**：node_modules 已软链主工作区，**不要** npm install / 另起 dev server。git add 只加本次明确改动文件，禁止 `git add -A`。

---

## Task 1: buildCrossTabRows 产出 columnSumsByComp + computeRows 串 prevRowValues（TDD）

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`
  - `buildCrossTabRows`（约 893–975 行）：改返回 `{ store, columnSumsByComp }`
  - 内部 `computeRows`（约 909–924 行）：串 `prevRowValues`
  - `subtotalsFromResolvedRows`（约 982–1010 行）：保持只写 is_subtotal 到 allComponentSubtotals（不变），新增同级 helper 算全数值列写 columnSumsByComp
- Test: `cpq-frontend/src/pages/quotation/columnSumsByComp.test.ts`（新建）

**实现要点（先读代码再动手）：**
- `buildCrossTabRows` 现签名返回 `Record<string, Array<Record<string, any>>>`（store）。改为返回 `{ store, columnSumsByComp }`，`columnSumsByComp: Record<string /*componentId|code|tabName*/, Record<string /*colName*/, number>>`。所有调用点（仅 QuotationStep2 1860 行 + ReadonlyProductCard 329 行附近）需同步解构——但**本 Task 只改函数本身 + 新增导出 helper + 单测**，调用点改动放 Task 2/3。为不破坏现有调用点编译，保留向后兼容：让 `buildCrossTabRows` 仍返回 store 为主对象，但**额外**通过第 6 个出参 / 或改为返回对象并同步改两个调用点。**采用"改为返回对象 `{store, columnSumsByComp}` 并在本 Task 同步修两个调用点的解构（仅取 `.store` 维持原行为）"**，Task 2/3 再消费 `.columnSumsByComp`。
- 列范围：`is_subtotal || field_type ∈ {INPUT_NUMBER, FORMULA, DATA_SOURCE}`。求和值来源：每组件 PASS2 的 `resolvedRows`（已带完整 crossTabRows store）。INPUT_NUMBER 取 resolvedRow 解析值（经 applyUnitConversion canonical，与 `computeNonSubtotalColumnSums` 现口径一致）；FORMULA/DATA_SOURCE 取 resolvedRow 字段值。
- 单位换算：求和用 `applyUnitConversion(comp.fields, resolvedRow)` 后的 canonical 副本（与 `subtotalsFromResolvedRows` 物化点对齐）；store / 落库副本不受影响（各换各副本纪律，929–936 行注释）。
- 行数纪律 AP-51：computeRows 内 `splitRows` + `rowAt` 已是权威行数口径（rowCount>0?rowCount:baseRows.length），不得引入 Math.max。
- prevRowValues 串行：computeRows 当前调 `computeAllFormulas(comp, row, allComponentSubtotals, undefined, undefined, partNo, basicDataValues, undefined, globalVariableDefs, store)`（约 917 行），**未传第 11 入参 prevRowValues**。改为每组件循环开头 `let prevRowValues: Record<string, number> = {}`，逐行把上一行结果作为 prevForField 串入（口径对齐渲染层 effectiveRows 2278/2294 行）。**prevRowValues 每组件独立 reset**，不跨组件复用。
- 4dp 舍入：columnSumsByComp 每列值 `Math.round(x*1e4)/1e4`（复用 subtotalsFromResolvedRows 的 round4，对齐后端 setScale(4,HALF_UP)）。
- B2 二阶列：columnSumsByComp 必须取**第二轮**最终 resolvedRows（含二阶列），不得取第一轮影子组件（缺二阶列）。在 `secondOrderFields.size>0` 分支用第二轮 `rows`（约 961 行）算 columnSumsByComp；无二阶列分支用一轮 `rows`（约 967 行）。

- [ ] **Step 1: 写失败单测 columnSumsByComp.test.ts**

参照同目录 `computeMultiSubtotal.test.ts` 的 import/构造方式。覆盖 5 个断言（用最小 fixture，避免依赖后端）：

```ts
import { describe, it, expect } from 'vitest';
import { buildCrossTabRows } from './QuotationStep2';

// 最小 fixture：两个 NORMAL 组件，B 含 cross_tab_ref 引用 A 的列；A 含 INPUT_NUMBER + FORMULA + is_subtotal。
// （实现时按 ComponentDataItem 结构补全 fields/formulas/rows——参照 computeMultiSubtotal.test.ts 既有 mock。）

describe('buildCrossTabRows columnSumsByComp', () => {
  it('① 非小计数值列(FORMULA/cross_tab)的列合计 == 各 resolvedRow 该列之和，非 0', () => {
    const { columnSumsByComp } = buildCrossTabRows(/* componentData */, {}, 'PART1', () => undefined);
    // SUMIF/cross_tab 列：期望 = 逐行 resolvedRow 之和（非 0，证明用了完整 store 不是空 crossTabRows）
    expect(columnSumsByComp['B']['crossCol']).toBeCloseTo(/* 期望逐行和 */, 4);
  });

  it('② is_subtotal 列的 columnSumsByComp 值 == allComponentSubtotals[`tab#col`]', () => {
    const acs: Record<string, number> = {};
    const { columnSumsByComp } = buildCrossTabRows(/* data */, acs, 'PART1', () => undefined);
    expect(columnSumsByComp['A']['aCost']).toBeCloseTo(acs['A#aCost'], 4);
  });

  it('③ previous_row_subtotal 累加列 footer == 末行累加值（证明 computeRows 串了 prevRowValues）', () => {
    const { columnSumsByComp } = buildCrossTabRows(/* 含累加列的 data */, {}, 'PART1', () => undefined);
    expect(columnSumsByComp['A']['runningCol']).toBeCloseTo(/* 末行累加期望 */, 4);
  });

  it('④ B2 二阶列取第二轮值（含引用本组件一阶小计的列）', () => {
    const { columnSumsByComp } = buildCrossTabRows(/* 含二阶列 data */, {}, 'PART1', () => undefined);
    expect(columnSumsByComp['A']['secondOrderCol']).toBeCloseTo(/* 第二轮期望 */, 4);
  });

  it('⑤ 配 unit_source_field 的列按 canonical 求和（与后端 setScale 对齐）', () => {
    const { columnSumsByComp } = buildCrossTabRows(/* 含单位列 data */, {}, 'PART1', () => undefined);
    expect(columnSumsByComp['A']['unitCol']).toBeCloseTo(/* canonical 和 */, 4);
  });
});
```

- [ ] **Step 2: 运行单测确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/columnSumsByComp.test.ts`
Expected: FAIL —— `buildCrossTabRows(...).columnSumsByComp` 为 undefined（函数尚未返回该字段）。

- [ ] **Step 3: 实现 columnSumsByComp + prevRowValues 串行**

按"实现要点"改 `buildCrossTabRows`：返回 `{store, columnSumsByComp}`；computeRows 串 prevRowValues；新增/扩展 helper 从 resolvedRows 按列范围求和（canonical + round4 + B2 第二轮）。同步修改本文件内 `buildCrossTabRows` 的两个调用点解构为 `const { store } = buildCrossTabRows(...)`（1860 行）以维持原行为（消费 columnSumsByComp 留待 Task 2）。ReadonlyProductCard 调用点（329 行附近）同样改解构取 `.store`（仅编译兼容，Task 3 再消费）。

- [ ] **Step 4: 运行单测确认通过 + tsc**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/columnSumsByComp.test.ts && npx vitest run src/pages/quotation/computeMultiSubtotal.test.ts && npx tsc --noEmit -p tsconfig.json`
Expected: 新测 5 PASS；`computeMultiSubtotal.test.ts` 等既有测试全绿；tsc 0 错误。
（若仓库另有 `prevRowPerColumn.test.ts` / `crossTab*.test.ts` 一并跑通。）

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx cpq-frontend/src/pages/quotation/columnSumsByComp.test.ts
git commit -m "feat(quotation): buildCrossTabRows 产出 columnSumsByComp + computeRows 串 prevRowValues (footer 同源根治 Task1)"
```

---

## Task 2: QuotationStep2 footer 接同源 + 删旁路 + ¥ 按 is_amount

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`
  - allComponentSubtotals 构建段（约 1831–1885 行）：消费 Task1 的 `columnSumsByComp`；删 `activeInputColSums`（1877 行）
  - 删除 `computeNonSubtotalColumnSums`（约 1124–1182 行）
  - footer 渲染（约 2525–2562 行）：is_subtotal + 非小计数值列统一读 columnSumsByComp；¥ 按 `field.is_amount`

**实现要点：**
- 1860 行 `buildCrossTabRows` 解构改为 `const { store: crossTabRows, columnSumsByComp } = buildCrossTabRows(...)`（替换 Task1 临时的 `.store` 兼容写法）。后续用 `crossTabRows` 的地方不变。
- activeComponent 的 footer 列从 `columnSumsByComp[activeKey]` 取，`activeKey` 用与 allComponentSubtotals 同 keying（优先 componentId，回退 componentCode/tabName）。删 `activeInputColSums` 及其对 `computeNonSubtotalColumnSums` 的调用。
- footer 渲染分支统一：

```tsx
{activeComponent.fields.map((field, fi) => {
  const colName = field.name || field.key || '';
  const compKey = activeComponent.componentId || activeComponent.componentCode || activeComponent.tabName;
  const colSums = columnSumsByComp[compKey] || {};
  const isNumericCol =
    field.is_subtotal ||
    field.field_type === 'INPUT_NUMBER' ||
    field.field_type === 'FORMULA' ||
    field.field_type === 'DATA_SOURCE';
  if (isNumericCol && colName in colSums) {
    const v = colSums[colName] ?? 0;
    // ¥ 严格按 is_amount；非金额列纯数字（最多4dp去尾0）
    const text = field.is_amount === true
      ? formatCurrency(v)
      : (v === 0 ? '0' : parseFloat(v.toFixed(4)).toString());
    return (
      <td key={colName || fi} className="qt-subtotal-cell"
          style={field.is_amount === true ? undefined : { color: '#595959' }}>
        {text}
      </td>
    );
  }
  if (fi === 0) return <td key={colName || fi} className="qt-subtotal-label-cell">小计</td>;
  return <td key={colName || fi} />;
})}
```

- 「本页签总计」行（约 2566–2573 行）仍用 `sumTabColumns(activeComponent, allComponentSubtotals)`（is_subtotal 之和，已同源），不改。
- 核价 BOM 树占位列（`activeComponentBomTree && <td/><td/><td/>`，2524 行）保留。
- allComponentSubtotals 段：is_subtotal 列小计仍由 buildCrossTabRows 的 `subtotalsFromResolvedRows` 回填（不变），不要把 columnSumsByComp 回灌 allComponentSubtotals。

- [ ] **Step 1: 改 allComponentSubtotals 段 + 删 computeNonSubtotalColumnSums/activeInputColSums**

按要点改 1860 行解构、删 1877 行 activeInputColSums、删 1124–1182 行函数定义及任何其它引用（grep `computeNonSubtotalColumnSums` 全工程确认仅此处用）。

Run（确认无残留引用）: `cd cpq-frontend && grep -rn "computeNonSubtotalColumnSums\|activeInputColSums" src/`
Expected: 仅注释或 0 命中（无未删调用）。

- [ ] **Step 2: 改 footer 渲染分支**

按要点替换 2525–2562 行的 map 逻辑。

- [ ] **Step 3: tsc + Vite 200 自检**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误。
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/QuotationStep2.tsx`
Expected: 200（dev server 服务主工作区；若 worktree 未挂 dev server，以 tsc + Task4 E2E 为准，记录说明）。

- [ ] **Step 4: 跑 E2E quotation-flow（协议级强制）**

Run:
```bash
cd cpq-frontend
rm -f e2e/screenshots/qf-*.png
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```
Expected: `1 passed`；`'加载中' final count = 0`；8 Tab `'加载中'=0`。附 qf-19 + qf-21~28 共 9 张截图。
人工/脚本断言：SUMIF 列 footer == 行值之和；管理费 footer ≠ 0（且与行值一致）；非金额列 footer 无 ¥。

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx
git commit -m "fix(quotation): footer 列小计单一来源(columnSumsByComp)+删空旁路+¥按is_amount (Task2)"
```

---

## Task 3: ReadonlyProductCard 详情页 footer 同源对齐

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx`
  - `buildCrossTabRows` 调用点（约 329 行）：解构 columnSumsByComp
  - footer 渲染（约 636–664 行）：扩显示非小计数值列合计 + ¥ 按 is_amount

**实现要点：**
- 详情页已调 `buildCrossTabRows` 得 `compSubtotals`（已同源）。改解构取 `columnSumsByComp`。
- footer 当前仅 `f.is_subtotal` 分支（642 行）。扩为与 Task2 相同的"is_subtotal + INPUT_NUMBER/FORMULA/DATA_SOURCE 统一读 columnSumsByComp + ¥ 按 is_amount"逻辑。
- 详情页的 `formatCurrency`（112 行）保持，仅在 `is_amount===true` 时用；否则纯数字。
- 目的：详情 footer 与编辑 footer **逐列一致**（含 SUMIF/管理费/非金额列）。

- [ ] **Step 1: 改解构 + footer 渲染**

按要点改 329 行解构与 636–664 行 footer map（复制 Task2 同款分支逻辑，键 keying 与本文件 compSubtotals 一致）。

- [ ] **Step 2: tsc + Vite 200**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json && curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/ReadonlyProductCard.tsx`
Expected: tsc 0 错误；HTTP 200。

- [ ] **Step 3: 跑详情 vs 编辑一致性 E2E**

Run（若存在）: `cd cpq-frontend && npx playwright test --config=e2e/playwright.config.ts e2e/bug-c-detail-vs-edit.spec.ts --reporter=list`
Expected: PASS —— 详情 footer == 编辑 footer 逐列一致。附详情 vs 编辑对比截图。
（若该 spec 不存在，则手测同一报价单"详情"与"编辑"两视图，截图对比 SUMIF/管理费/非金额列 footer 一致，写入复测记录。）

- [ ] **Step 4: Commit**

```bash
git add cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx
git commit -m "fix(quotation): 详情页 ReadonlyProductCard footer 同源(columnSumsByComp)+¥按is_amount (Task3)"
```

---

## Task 4: 核价 + COMPOSITE + 手动行 + 多产品回归

**Files:**
- 无新代码（除非回归暴露边界 bug，则定位最小修复并补单测）
- 验证：报价/核价/详情/组合/多产品/手动行/driver 行数纪律

- [ ] **Step 1: COMPOSITE E2E**

Run: `cd cpq-frontend && npx playwright test --config=e2e/playwright.config.ts e2e/composite-product-flow.spec.ts --reporter=list`
Expected: PASS（5 Tab，父级聚合子件 footer 同源，加载中=0）。附 COMPOSITE footer 截图。

- [ ] **Step 2: 多产品 E2E（Bug B 隔离）**

Run: `cd cpq-frontend && npx playwright test --config=e2e/playwright.config.ts e2e/multi-product-flow.spec.ts --reporter=list`
Expected: PASS —— 同 partNo 双产品 footer 不串（lineItemId 维度隔离）。
（若 spec 名不同，按 `e2e/` 下实际多产品 spec 跑。）

- [ ] **Step 3: 核价侧 + 手动行 + AP-51 行数稳定 手测**

- 核价单视图（cardSide=COSTING）：footer 同源；BOM 树（spine）行的列合计正确；BOM 树 footer 前置 3 占位列对齐。
- 手动行（_origin='manual'）：splitRows 拼接的手动行计入 columnSumsByComp。
- AP-51：刷新 3 次，行数与 footer 值稳定（不累加）。
截图 + 记录写入复测说明。

- [ ] **Step 4: 全量前端单测回归**

Run: `cd cpq-frontend && npx vitest run`
Expected: 全绿（含 computeMultiSubtotal / columnSumsByComp / crossTab 等）。

- [ ] **Step 5: Commit（仅当有边界修复）**

```bash
git add <被修文件>
git commit -m "fix(quotation): footer 同源回归边界修复 (Task4)"
```
（无修复则跳过 commit，仅在 RECORD 记录回归结论。）

---

## Task 5: 文档登记

**Files:**
- Modify: `docs/三大核心模块基线.md`（§四报价单渲染基线新增小节 + 附录版本历史一行）
- Modify: `docs/反模式.md`（新增一条 AP：footer 双口径族）
- Modify: `docs/RECORD.md`（追加开发记录）

- [ ] **Step 1: 基线文档登记**

在 `docs/三大核心模块基线.md` §四新增小节：

```
【2026-06-16 footer 列小计单一来源化】报价单/核价/详情 footer（含非小计数值列）与 allComponentSubtotals
统一从 buildCrossTabRows 的 resolvedRows 求和（columnSumsByComp），废弃 computeNonSubtotalColumnSums
空-crossTabRows 旁路；computeRows 补串 prevRowValues。¥ 严格按 field.is_amount（不再 is_subtotal 默认 ¥）。
后端 backfillSubtotalsFromResolved 维持只 is_subtotal 落库不变（A-保守）。
不变量：footer 列值 === 该列已渲染行 resolvedRow 之和。
未做（后续 Phase）：A-彻底（非小计列合计落库 subtotalByColumn + Excel 引用语义扩展 + Phase4 零计算读快照）。
```

- [ ] **Step 2: 反模式登记**

在 `docs/反模式.md` 末尾新增一条 AP（编号接续现有最大号）：

```
AP-XX footer 小计"另起炉灶空-crossTabRows 旁路"族（2026-06-16）：
症状——同一数值列，数据行值与 footer 列小计对不上（cross_tab/SUMIF 列尤甚），且非金额列被强行加 ¥。
根因——footer 用 computeNonSubtotalColumnSums/computeTabSubtotalsByColumn 以空 crossTabRows 二次重算，
与数据行(用 buildCrossTabRows 完整 store)分叉；¥ 无条件 formatCurrency 不看 is_amount；
computeRows 漏串 prevRowValues。
纪律——footer 列小计必须从 buildCrossTabRows 的 resolvedRows 单一来源求和(columnSumsByComp)；
任何"小计"显示值必须 === 已渲染行值之和；¥ 一律按 field.is_amount；
buildCrossTabRows.computeRows 必须串 prevRowValues 与渲染层 effectiveRows 对齐。
```

- [ ] **Step 3: RECORD 登记**

在 `docs/RECORD.md` 追加：

```
[2026-06-16] 报价渲染 - footer 列小计单一来源化(columnSumsByComp) | QuotationStep2.tsx / ReadonlyProductCard.tsx | 删空-crossTabRows旁路, ¥按is_amount, computeRows串prevRowValues; 后端A-保守不改; WYSIWYG不变量; E2E三spec绿
```

- [ ] **Step 4: Commit**

```bash
git add docs/三大核心模块基线.md docs/反模式.md docs/RECORD.md
git commit -m "docs: footer 列小计单一来源化登记(基线+AP+RECORD) (Task5)"
```

---

## Self-Review 结论

- **Spec 覆盖**：根因 1(非小计列空旁路)→Task1/2；根因 2(is_subtotal PASS2 增量回填)→Task1 同源后 footer 取 columnSumsByComp 不再依赖 PASS1，且 prevRowValues 修复；根因 3(¥)→Task2/3；详情页同源→Task3；核价/COMPOSITE/手动行/多产品→Task4；文档→Task5。
- **类型一致**：全程用 `columnSumsByComp: Record<string, Record<string, number>>`、`buildCrossTabRows` 返回 `{store, columnSumsByComp}`、`field.is_amount === true` 判定，前后一致。
- **无占位**：每步含具体命令/代码骨架与期望输出。单测期望值标注为 fixture 推导（实现者按构造的 mock 填确切数值）。
