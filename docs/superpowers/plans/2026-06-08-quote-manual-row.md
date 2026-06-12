# 报价单手动新增行 Phase 1 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 报价单页签里"手动新增行"除公式列外全空白、用户自填、持久化保留、计入小计、详情态一致显示；driver 展开行不受影响。

**Architecture:** 纯前端改动（已确认）。手动行用 `_origin:'manual'` 标记，存于 `comp.rows` 末尾，经 `snapshotRows` 序列化进 `row_data` JSONB 往返持久化。后端 `refreshQuoteCardValues` 只写 `quoteCardValues`、不碰 `row_data`，故手动行在 `row_data` 安全、Phase 1 无需动后端。核心是统一一个 `splitRows` helper，让 5 处"按 `exp.rowCount` 截断"的行迭代改为"driver 行 + 手动行拼接"。

**Tech Stack:** React + TypeScript（Vite）。验证以 Playwright E2E 为主（项目既有范式），纯函数 helper 用单测。

**已确认事实（实现依据）：**
- `comp.rows` ← `lineItem.componentData[].rowData`（row_data 列，JSON）；保存：`snapshotRows`(QuotationWizard.tsx:783) → `SaveDraftRequest.ComponentDataDraft.rowData` → `QuotationService.java:476` → `row_data`。
- 5 处行迭代均 `rowCount = exp.rowCount>0 ? exp.rowCount : comp.rows.length`，driver 页签**截断手动行**：
  - 编辑渲染 `QuotationStep2.tsx:723-730`（buildCrossTabRows）、`:1754`（`isDriverBound = useDriver && i < driverCount`）
  - `computeTabSubtotal` `QuotationStep2.tsx:801-803`
  - `snapshotRows` `QuotationWizard.tsx:806-808`
  - 详情态 `ReadonlyProductCard.tsx:85`（`effectiveCount`）
- `handleAddRow` `QuotationStep2.tsx:1091`（push 末尾，现填 FIXED_VALUE=content/DATA_SOURCE=null/INPUT=''）。
- `fillFixedDefaults` `QuotationStep2.tsx:763` 对任意行填 FIXED_VALUE content（手动行需短路）。
- `handleDeleteRow` `QuotationStep2.tsx:1082`（filter 移除）；删除按钮区 `:2017` 手动行 `isDriverBound=false` 走 ✕ 可删。
- ComponentCell 渲染分支：FIXED_VALUE `:530`、DATA_SOURCE `:439`、INPUT `:539`。
- 编辑态/详情态行数据均取 `comp.rows`；`quoteCardValues` 是另一套快照，详情态手动行从 `comp.rows` 取、不依赖 `quoteCardValues`。

**数据约定（贯穿全计划）：**
- 手动行 = 用户经 `handleAddRow` 新增的行，唯一标记 `row._origin === 'manual'`。
- `comp.rows` 布局：`[...driver行编辑值(_origin!=='manual', 按 index 对齐 exp.rows), ...手动行(_origin==='manual')]`。
- 有 driver 页签：`totalRows = driverCount + manualRows.length`；无 driver 页签：`totalRows = comp.rows.length`（手动行已在其中）。
- 仅 `_origin==='manual'` 行享受"全空白 / FIXED_VALUE 文本框 / DATA_SOURCE 空下拉 / 不富化 / 不被 fillFixedDefaults 填值"。

---

## Task 1: 新建 `manualRows` helper（行拆分/拼接的单一真相）

**Files:**
- Create: `cpq-frontend/src/pages/quotation/manualRows.ts`
- Test: `cpq-frontend/src/pages/quotation/manualRows.test.ts`

- [x] **Step 1: 写失败测试**

```typescript
// manualRows.test.ts
import { describe, it, expect } from 'vitest';
import { MANUAL_ORIGIN, splitRows, rowAt } from './manualRows';

const exp = (n: number) => ({ rowCount: n, rows: Array.from({ length: n }, (_, i) => ({ driverRow: { k: i }, basicDataValues: { b: i } })) });

describe('splitRows', () => {
  it('driver 页签: totalRows = driverCount + 手动行数', () => {
    const comp: any = { rows: [{ a: 1 }, { a: 2 }, { _origin: 'manual', a: 9 }] };
    const s = splitRows(comp, exp(2));
    expect(s.useDriver).toBe(true);
    expect(s.driverCount).toBe(2);
    expect(s.manualRows).toHaveLength(1);
    expect(s.totalRows).toBe(3);
  });

  it('无 driver 页签: totalRows = comp.rows.length(含手动行)', () => {
    const comp: any = { rows: [{ a: 1 }, { _origin: 'manual', a: 9 }] };
    const s = splitRows(comp, undefined);
    expect(s.useDriver).toBe(false);
    expect(s.totalRows).toBe(2);
  });

  it('rowAt: driver 段取 driverEditRows+exp, 手动段取 manualRows', () => {
    const comp: any = { rows: [{ a: 1 }, { _origin: 'manual', a: 9 }] };
    const s = splitRows(comp, exp(1));
    const r0 = rowAt(0, comp, s);
    expect(r0.isManual).toBe(false);
    expect(r0.expIndex).toBe(0);
    const r1 = rowAt(1, comp, s);
    expect(r1.isManual).toBe(true);
    expect(r1.row.a).toBe(9);
    expect(r1.expIndex).toBe(-1);
  });

  it('无 driver: rowAt 直接取 comp.rows[i], isManual 按 _origin', () => {
    const comp: any = { rows: [{ a: 1 }, { _origin: 'manual', a: 9 }] };
    const s = splitRows(comp, undefined);
    expect(rowAt(0, comp, s).isManual).toBe(false);
    expect(rowAt(1, comp, s).isManual).toBe(true);
  });
});
```

- [x] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/manualRows.test.ts`
Expected: FAIL（模块不存在）。若项目无 vitest，跳过单测、改由 Task 9 E2E 覆盖，并在 PR 注明。

- [x] **Step 3: 实现 helper**

```typescript
// manualRows.ts
export const MANUAL_ORIGIN = 'manual';

export interface DriverExpansionLike {
  rowCount: number;
  rows: Array<{ driverRow?: Record<string, any>; basicDataValues?: Record<string, any> }>;
}

export interface RowSplit {
  useDriver: boolean;
  driverCount: number;
  /** comp.rows 中 _origin==='manual' 的行 */
  manualRows: Array<Record<string, any>>;
  /** comp.rows 中非手动行（driver 页签下按 index 对齐 exp.rows；无 driver 页签下即全部行） */
  driverEditRows: Array<Record<string, any>>;
  totalRows: number;
}

export function isManualRow(row: any): boolean {
  return !!row && row._origin === MANUAL_ORIGIN;
}

export function splitRows(comp: { rows?: Array<Record<string, any>> }, exp: DriverExpansionLike | undefined): RowSplit {
  const rows = Array.isArray(comp.rows) ? comp.rows : [];
  const useDriver = !!(exp && exp.rowCount > 0);
  const driverCount = useDriver ? exp!.rowCount : 0;
  const manualRows = rows.filter(isManualRow);
  const driverEditRows = rows.filter((r) => !isManualRow(r));
  const totalRows = useDriver ? driverCount + manualRows.length : rows.length;
  return { useDriver, driverCount, manualRows, driverEditRows, totalRows };
}

export interface RowAt {
  row: Record<string, any>;
  isManual: boolean;
  /** driver 行对应的 exp.rows 下标；手动行/无 driver 为 -1 */
  expIndex: number;
}

export function rowAt(i: number, comp: { rows?: Array<Record<string, any>> }, s: RowSplit): RowAt {
  if (!s.useDriver) {
    const r = (comp.rows ?? [])[i] ?? {};
    return { row: r, isManual: isManualRow(r), expIndex: -1 };
  }
  if (i < s.driverCount) {
    return { row: s.driverEditRows[i] ?? {}, isManual: false, expIndex: i };
  }
  return { row: s.manualRows[i - s.driverCount] ?? {}, isManual: true, expIndex: -1 };
}
```

- [x] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/manualRows.test.ts`
Expected: PASS（4 个用例）

- [x] **Step 5: 提交**

```bash
git add cpq-frontend/src/pages/quotation/manualRows.ts cpq-frontend/src/pages/quotation/manualRows.test.ts
git commit -m "feat(quote-manual-row): add manualRows split helper (Phase 1 Task 1)"
```

---

## Task 2: `handleAddRow` 改为新增全空白手动行

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx:1091-1111`

- [x] **Step 1: 改写 handleAddRow**

把现有实现替换为：仅打 `_origin:'manual'`，公式列不存值，其余列一律不预填（不带 FIXED_VALUE content、不置 DATA_SOURCE null、不填 BASIC_DATA 默认）。

```typescript
  const handleAddRow = (tabIndex: number) => {
    onUpdate((prevItem: LineItem) => ({
      componentData: prevItem.componentData.map((comp, ci) => {
        if (ci !== tabIndex) return comp;
        // 手动新增行：除标记外不预填任何列。公式列由渲染层按用户手填值计算。
        const emptyRow: Record<string, any> = { _origin: 'manual', row_index: comp.rows.length };
        return { ...comp, rows: [...comp.rows, emptyRow] };
      }),
    }));
  };
```

- [x] **Step 2: 编译确认**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误

- [x] **Step 3: 提交**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx
git commit -m "feat(quote-manual-row): handleAddRow adds blank manual row (Phase 1 Task 2)"
```

---

## Task 3: `fillFixedDefaults` 短路手动行

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx:763-779`

- [x] **Step 1: 手动行不填 FIXED_VALUE 默认**

在 `fillFixedDefaults` 开头加手动行短路（保持其余逻辑不变）：

```typescript
function fillFixedDefaults(
  fields: ComponentField[],
  raw: Record<string, any>,
): Record<string, any> {
  if (raw && raw._origin === 'manual') return raw; // 手动行：FIXED_VALUE 不自动填，留空给用户手填
  let cloned: Record<string, any> | null = null;
  for (const f of fields) {
    if (f.field_type !== 'FIXED_VALUE') continue;
    if (f.content == null || f.content === '') continue;
    const k = f.name || f.key || '';
    if (!k) continue;
    if (raw[k] !== undefined && raw[k] !== null && raw[k] !== '') continue;
    if (!cloned) cloned = { ...raw };
    cloned[k] = f.content;
  }
  return cloned ?? raw;
}
```

- [x] **Step 2: 编译确认**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误

- [x] **Step 3: 提交**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx
git commit -m "feat(quote-manual-row): fillFixedDefaults skips manual rows (Phase 1 Task 3)"
```

---

## Task 4: `buildCrossTabRows` 行迭代拼接手动行

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx:723-740`

- [x] **Step 1: 用 splitRows/rowAt 替换截断式迭代**

import 顶部加：`import { splitRows, rowAt } from './manualRows';`

把 `buildCrossTabRows` 内的行循环（现 `const rowCount = useDriver ? exp!.rowCount : (comp.rows?.length ?? 0)` 起至 `rows.push(...)`）替换为：

```typescript
    const s = splitRows(comp, exp);
    const rows: Array<Record<string, any>> = [];
    for (let i = 0; i < s.totalRows; i++) {
      const ra = rowAt(i, comp, s);
      const row = fillFixedDefaults(comp.fields!, ra.row); // 手动行 fillFixedDefaults 已短路
      const basicDataValues = ra.expIndex >= 0 ? exp!.rows[ra.expIndex]?.basicDataValues : undefined;
      const driverRow = ra.expIndex >= 0 ? exp!.rows[ra.expIndex]?.driverRow : undefined;
      const formulaCache = computeAllFormulas(
        comp, row, allComponentSubtotals, undefined, undefined, partNo, basicDataValues,
        undefined, globalVariableDefs, store,
      );
      rows.push(buildResolvedRow(comp.fields!, row, driverRow, basicDataValues, formulaCache));
    }
```

（删除原 `const useDriver`/`const rowCount`/`const baseRow`/原 for 循环体。）

- [x] **Step 2: 编译确认**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误

- [x] **Step 3: 提交**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx
git commit -m "feat(quote-manual-row): buildCrossTabRows includes manual rows (Phase 1 Task 4)"
```

---

## Task 5: `computeTabSubtotal` 计入手动行

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx:781-803+`

- [x] **Step 1: 用 splitRows/rowAt 替换截断迭代**

把 `computeTabSubtotal` 内 `const useDriver = ...; const driverCount = ...; const rowCount = ...` 及随后的行循环改为基于 `splitRows`：

```typescript
  const s = splitRows(comp, driverExpansion as any);
  let subtotal = 0;
  for (let i = 0; i < s.totalRows; i++) {
    const ra = rowAt(i, comp, s);
    const row = fillFixedDefaults(comp.fields, ra.row);
    const basicDataValues = ra.expIndex >= 0 ? (driverExpansion as any)!.rows[ra.expIndex]?.basicDataValues : undefined;
    const formulaCache = computeAllFormulas(
      comp, row, allComponentSubtotals, undefined, undefined, partNo, basicDataValues,
    );
    const subtotalField = comp.fields.find((f: any) => f.is_subtotal);
    if (subtotalField) {
      const v = formulaCache[subtotalField.name];
      if (typeof v === 'number' && !Number.isNaN(v)) subtotal += v;
    }
  }
  return subtotal;
```

> 注：保留 `computeTabSubtotal` 原有的累加公式（`previousRowSubtotal`）传参逻辑——若原实现有逐行 `prevRowSubtotal` 传入 `computeAllFormulas`，在循环里同样按 `i` 顺序维护并传入，不要丢。先读原 781-840 段完整实现再改，保证只替换"行数/行取值"部分、不动累加语义。

- [x] **Step 2: 编译确认**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误

- [x] **Step 3: 提交**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx
git commit -m "feat(quote-manual-row): computeTabSubtotal includes manual rows (Phase 1 Task 5)"
```

---

## Task 6: 编辑态表格渲染迭代拼接手动行

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（渲染 `<tbody>` 行循环区，约 1700-2010，含 `isDriverBound` 定义 :1754 与删除按钮区 :2017）

- [x] **Step 1: 渲染循环改用 splitRows/rowAt**

先 Read 当前渲染行循环（约 1700-2010）。把驱动渲染的 `const useDriver`/`driverCount`/`rowCount`/`for i` 与 `const baseRow = comp.rows[i]`、`const isDriverBound = useDriver && i < driverCount` 改为：

```typescript
  const s = splitRows(activeComponent, activeDriverExpansion as any);
  // ...循环 for (let i = 0; i < s.totalRows; i++) {
  const ra = rowAt(i, activeComponent, s);
  const row = fillFixedDefaults(activeComponent.fields, ra.row);
  const isDriverBound = !ra.isManual && ra.expIndex >= 0; // 手动行 false → 走 ✕ 可删
  const basicDataValues = ra.expIndex >= 0 ? activeDriverExpansion!.rows[ra.expIndex]?.basicDataValues : undefined;
  // cellCtx 传入 isManual: ra.isManual（供 ComponentCell 区分，见 Task 7）
```

并把传给 `ComponentCell` 的 `context`（`cellCtx`）补一个字段 `isManualRow: ra.isManual`。

- [x] **Step 2: 单元格写回下标按真实下标映射（AP-54）**

确认 `onCellChange/onCellBlur/handleDeleteRow` 用的 `rowIndex` 是"拼接后下标 i"，而写回 `comp.rows` 时手动行的真实下标 = `comp.rows.indexOf(ra.row)`。在 `patchRowField`/`handleDeleteRow` 调用点改为传真实下标：

```typescript
  const realRowIndex = activeComponent.rows.indexOf(ra.row);
  // onCellChange/onCellBlur/删除 用 realRowIndex（非拼接 i）
```

> 背景：AP-54——渲染用拼接序、写回用 `comp.rows` 原集合，下标必须按对象引用映射回真实下标，否则写错行。

- [x] **Step 3: 编译确认**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误

- [x] **Step 4: Vite transform 200（修改的 tsx 必查）**

Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/QuotationStep2.tsx`
Expected: 200

- [x] **Step 5: 提交**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx
git commit -m "feat(quote-manual-row): edit-table render appends manual rows + AP-54 index map (Phase 1 Task 6)"
```

---

## Task 7: ComponentCell 手动行渲染分支（FIXED_VALUE 文本框 / DATA_SOURCE 空下拉）

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/components/ComponentCell.tsx`（FIXED_VALUE :530、DATA_SOURCE :439、context 类型）

- [x] **Step 1: context 增 isManualRow**

在 ComponentCell 的 `context` 类型加 `isManualRow?: boolean`，从 props 解构 `const isManual = context?.isManualRow && !readonly;`。

- [x] **Step 2: FIXED_VALUE 手动行渲染为文本输入框**

在 FIXED_VALUE 分支（:530）最前面插入：

```typescript
  if (field.field_type === 'FIXED_VALUE') {
    if (isManual) {
      return (
        <input
          type="text"
          value={row[key] ?? ''}
          onChange={(e) => onCellChange?.(rowIndex, key, e.target.value)}
          onBlur={() => onCellBlur?.(rowIndex, key)}
        />
      );
    }
    // ...原 FIXED_VALUE 只读逻辑不变
```

- [x] **Step 3: DATA_SOURCE 手动行保留下拉、默认空**

在 DATA_SOURCE 分支（:439）最前插入：手动行渲染为该字段原有的数据源下拉选择器，但初值空、不自动解析 driver 值。若该下拉是现有可编辑组件，则复用它并传 `value={row[key] ?? undefined}`；若 DATA_SOURCE 当前无独立下拉组件、仅展示，则手动行先渲染为可填文本框（与 FIXED_VALUE 同），并在 PR 注明"DATA_SOURCE 下拉选择器待 Phase 1.1 接入"。

```typescript
  if (isManual) {
    // 手动行：默认空、由用户从数据源选/填，不带 driver 自动值
    return renderDataSourceEditor
      ? renderDataSourceEditor({ field, value: row[key] ?? undefined, onChange: (v: any) => onCellChange?.(rowIndex, key, v), onBlur: () => onCellBlur?.(rowIndex, key) })
      : (<input type="text" value={row[key] ?? ''} onChange={(e) => onCellChange?.(rowIndex, key, e.target.value)} onBlur={() => onCellBlur?.(rowIndex, key)} />);
  }
```

- [x] **Step 4: 编译 + transform 确认**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/components/ComponentCell.tsx`
Expected: 200

- [x] **Step 5: 提交**

```bash
git add cpq-frontend/src/pages/quotation/components/ComponentCell.tsx
git commit -m "feat(quote-manual-row): ComponentCell manual-row editors for FIXED_VALUE/DATA_SOURCE (Phase 1 Task 7)"
```

---

## Task 8: `snapshotRows` 序列化包含手动行且不富化（持久化关键）

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationWizard.tsx:783-875`

- [x] **Step 1: 行迭代改 splitRows，手动行跳过 BASIC_DATA/FIXED_VALUE 富化**

import 顶部加 `import { splitRows, rowAt } from './manualRows';`。把 `const baseRows = ...; const rowCount = expansion?.rowCount>0 ? rowCount : baseRows.length` 与随后 for 循环改为：

```typescript
    const s = splitRows(cd, expansion as any);
    const out: Record<string, any>[] = [];
    let prevRowSubtotal: number | undefined = undefined;
    const subtotalFieldName = fields.find((f: any) => f.is_subtotal)?.name;
    for (let i = 0; i < s.totalRows; i++) {
      const ra = rowAt(i, cd, s);
      if (ra.isManual) {
        // 手动行：原样序列化（含 _origin 与用户已填值），不做 BASIC_DATA/FIXED_VALUE/FORMULA 富化
        out.push({ ...ra.row });
        continue;
      }
      const baseRow = ra.row;
      const basicDataValues = ra.expIndex >= 0 ? (expansion as any)!.rows[ra.expIndex]?.basicDataValues : undefined;
      const enriched: Record<string, any> = { ...baseRow };
      // ...原有 BASIC_DATA 快照 / FIXED_VALUE 默认 / FORMULA 计算逻辑保持不变（仅作用于非手动行）
      // ...原有 prevRowSubtotal 维护保持不变
      out.push(enriched);
    }
    return out;
```

> 关键：手动行必须进 `out`（即进 `row_data`），否则保存丢失。手动行带 `_origin:'manual'`，重开时 `comp.rows` 还原即含手动行。

- [x] **Step 2: 编译 + transform 确认**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/QuotationWizard.tsx`
Expected: 200

- [x] **Step 3: 提交**

```bash
git add cpq-frontend/src/pages/quotation/QuotationWizard.tsx
git commit -m "feat(quote-manual-row): snapshotRows persists manual rows without enrichment (Phase 1 Task 8)"
```

---

## Task 9: 详情只读态 `ReadonlyProductCard` 拼接手动行

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx:83-95`

- [x] **Step 1: effectiveCount 改为 driver + 手动行拼接**

import `splitRows, rowAt`。把 `const useDriver = ...; const effectiveCount = useDriver ? rowCount : rows.length;` 与行循环改为：

```typescript
  const s = splitRows({ rows }, driverExpansion as any);
  // for (let ri = 0; ri < s.totalRows; ri++) {
  const ra = rowAt(ri, { rows }, s);
  const bdv = ra.expIndex >= 0 ? driverExpansion!.rows[ra.expIndex]?.basicDataValues : undefined;
  // 渲染用 ra.row；手动行 ra.isManual=true → 单元格显示 row[key] 用户填的值（只读展示）
```

ComponentCell 在只读态（readonly=true）对手动行只显示 `row[key]`（用户填的值），无需输入框——Task 7 的 `isManual` 已 gated on `!readonly`，只读态自动走原显示分支取 `row[key]`，符合预期。

- [x] **Step 2: 编译 + transform 确认**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/ReadonlyProductCard.tsx`
Expected: 200

- [x] **Step 3: 提交**

```bash
git add cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx
git commit -m "feat(quote-manual-row): ReadonlyProductCard appends manual rows (Phase 1 Task 9)"
```

---

## Task 10: 核价侧确认/移除[新增行]入口

**Files:**
- Inspect: 核价侧渲染组件（`cpq-frontend/src/pages/costing/**`）

- [x] **Step 1: 确认核价侧有无新增行入口**

Run: `cd cpq-frontend && grep -rn "handleAddRow\|添加行\|qt-add-row-btn\|新增行" src/pages/costing src/components 2>/dev/null`
Expected: 预期为空（`handleAddRow` 仅在 QuotationStep2 与模板预览）。若为空 → 核价侧本无入口，本任务记"无需改动"并跳到 Step 3。

- [x] **Step 2: 若存在入口则移除**

若上步有命中：定位核价渲染该入口的 JSX（`+ 添加行` 按钮），删除该按钮（核价侧只读、不允许编辑）。

- [x] **Step 3: 提交（或记录无改动）**

```bash
git add -A && git commit -m "chore(quote-manual-row): confirm/remove costing-side add-row entry (Phase 1 Task 10)" || echo "核价侧无入口，无改动"
```

---

## Task 11: E2E 专项 + 双 spec 回归

**Files:**
- Create: `cpq-frontend/e2e/quote-manual-row.spec.ts`
- Regress: `e2e/quotation-flow.spec.ts` + `e2e/composite-product-flow.spec.ts`

- [x] **Step 1: 写专项 E2E（覆盖 4 个验收点）**

```typescript
// quote-manual-row.spec.ts 要点（按 docs/E2E测试方法.md 选择器约定补全）：
// 1. 进报价单某 driver 页签 → 记录 driver 行数 N → 点"+ 添加行" → 出现第 N+1 行，除公式列外全空白
// 2. 在手动行填 INPUT/FIXED_VALUE 值 → 公式列自动算出 → 页签小计 = 原小计 + 手动行公式值
// 3. 保存草稿 → 刷新/重开报价单 → 手动行与所填值仍在（driver 行仍 N 行）
// 4. 提交后进详情只读页 → 手动行显示用户填的值
// 断言 '加载中' final count = 0
```

- [x] **Step 2: 跑专项 + 双 spec**

```bash
cd cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/quote-manual-row.spec.ts --reporter=list
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts e2e/composite-product-flow.spec.ts --reporter=list
```
Expected: 专项 passed；双 spec `'加载中' final count = 0`，driver 行不受影响（无回归）。

- [x] **Step 3: 提交**

```bash
git add cpq-frontend/e2e/quote-manual-row.spec.ts
git commit -m "test(quote-manual-row): Phase 1 E2E + dual-spec regression (Phase 1 Task 11)"
```

---

## Task 12: 文档回写

**Files:**
- Modify: `docs/RECORD.md`（追加 Phase 1 条目）
- Modify: `docs/PRD-v3.md`（报价单页签新增行行为，对应章节 + 演进史）

- [x] **Step 1: RECORD 追加**

格式：`[2026-06-08] 报价单 - 手动新增行(除公式列全空白/持久化/计入小计/详情一致) Phase 1 | manualRows.ts + QuotationStep2/QuotationWizard/ComponentCell/ReadonlyProductCard | _origin=manual 标记 + splitRows helper 统一 5 处行迭代拼接; 纯前端经 row_data 往返; Phase 2(driver 行删除+指纹标记)待做`

- [x] **Step 2: PRD-v3 对应章节 + 演进史更新**

- [x] **Step 3: 提交**

```bash
git add docs/RECORD.md docs/PRD-v3.md
git commit -m "docs(quote-manual-row): record Phase 1 + PRD update"
```

---

## Self-Review（已执行）

- **Spec 覆盖**：需求 1(空白)→T2/T3/T7；2(仅手动行)→`_origin` 全程 gated；3(持久化)→T8；4(两类页签)→splitRows useDriver 分支；5(计入小计)→T5；6(手动行可删)→T6 Step2（driver 删除属 Phase 2）；7(详情一致)→T9；8(核价移除)→T10；9(输入方式)→T7。✅
- **占位扫描**：无 TBD/TODO；DATA_SOURCE 下拉若无现成编辑器组件，T7 Step3 给了明确降级（文本框 + PR 注明），非占位。
- **类型一致**：`splitRows`/`rowAt`/`MANUAL_ORIGIN`/`isManualRow` 在 T1 定义，T4/T5/T6/T8/T9 一致引用；`_origin:'manual'` 全程一致。

## Phase 1 边界（不含、留 Phase 2）

- driver 行删除 + `deleted_driver_keys` + driverRow 内容指纹匹配 + 重开不复活（Phase 2 单独计划）。
- 后端 `mergeRowDataInputsIntoEdits` 让 `quoteCardValues` 也含手动行（仅当后续发现 Excel 视图/其它消费方需要时才做；Phase 1 详情态已从 `comp.rows` 取，不需要）。
