# 行键唯一性消歧（撞键→#序号）修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 `computeRowKey` 在「行键字段值为空/重复」时多行算出**同一个 rowKey** → editRows 写覆盖 + 读串行 → resolvedRows 塌缩成「末值×行数」、cross_tab 逐行匹配错（线上症状：外购件源两行 `(料件=空,要素=单价)` 行键都成 `||单价`，编辑两行料件只活下来 1 条，两行都变末值 → 来料 外购件2=0.802×2=1.604、外购件1=0）。

**Architecture:** 在「按组件成批计算行键」的位置加一道**唯一化消歧 pass**：对一个组件全部行的 rowKey 列表，**只对出现 ≥2 次的键**追加出现序号 `#0/#1/...`（唯一键保持原样 → 向后兼容；现有非撞键报价单 editRows 仍绑定）。前后端用**逐字节等价**的同一算法，应用在所有「rowKey 用于 editRows 写/读、formulaResults 查表、cross_tab 源行解析」的站点。rowKey 由 **driver 内容**派生（非编辑后内容），出现序号按 **baseRows 数组序**（前后端同序）→ 跨刷新稳定。

**Tech Stack:** React + TS / Vitest（`useCardSnapshots.ts` `QuotationStep2.tsx` `ReadonlyProductCard.tsx`）；Java 17 / Quarkus（`FormulaCalculator.java` `CardSnapshotService.java` `CardEffectiveRows.java`）。

**复现基线（QT-20260615-1729 / 68188aad / li=c61bc05a-77f6-435a-85e2-ed135549bfed）：** 外购件 `row_key_fields=[料件,要素]`，两行 baseRow `_料件=null,_要素=单价,费用=0.6892/0.802`。当前两行 rowKey 都 `||单价` → 编辑两行料件(外购件1/外购件2)只存活 1 条 editRow(`||单价`→外购件2) → resolvedRows 两行都 `料件=外购件2,费用=0.802` → 来料 外购件1=0、外购件2=1.604。修复目标：两行 rowKey = `||单价#0`/`||单价#1`，两条编辑各自绑定 → 来料 外购件1=0.6892、外购件2=0.802、小计 1.49=各行之和。

---

## 不变量（前后端逐字节一致）

1. `uniquifyRowKeys(keys)`：键出现 1 次 → 原样；出现 ≥2 次 → 按出现序追加 `#<0基序号>`。
2. raw key 计算口径不变（沿用现有 `computeRowKey` + 全空回退行号）；消歧只在 raw key 之上叠加。
3. 前后端对同一 (rowKeyFields, fields, baseRows[], basicDataValues[]) 必须产出**完全相同**的唯一化 rowKey 序列（editRows 跨端写/读查表依赖）。
4. 唯一化序号按 baseRows **数组下标序**（前后端同序遍历）。

---

## File Structure

- `cpq-frontend/src/pages/quotation/useCardSnapshots.ts` — 新增导出 `uniquifyRowKeys` + `buildUniqueRowKeys`；`rowKeyOf`/`getCell` 改用按组件预算的唯一化键表。
- `cpq-frontend/src/pages/quotation/QuotationStep2.tsx` — 活动组件渲染前用 `buildUniqueRowKeys` 预算唯一键，`:2184` 用 `uniqRowKeys[i]` 取代内联 `computeRowKey`。
- `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx` — 同款，循环前预算唯一键，`:488` 用 `uniqRowKeys[ri]`。
- `cpq-backend/.../service/FormulaCalculator.java` — 新增 `public static List<String> uniquifyRowKeys(List<String>)`；`computeRows`(:584-589) 加预扫，`effKey` 改取唯一化键。
- `cpq-backend/.../service/CardSnapshotService.java` — `:911` / `:959-961` / `:1443-1445` 三处按组件预算唯一化键后再用。
- `cpq-backend/.../service/card/CardEffectiveRows.java` — rowKey 计算循环（解析 cardValues 处）改用唯一化键。
- 测试：前端 `rowKeyUniquify.test.ts`（新）+ `useCardSnapshots.test.ts` 扩；后端 `FormulaCalculatorRowKeyUniqueTest.java`（新）+ 对拍硬编码相同期望串。

---

## Task 1：前端 — uniquifyRowKeys + buildUniqueRowKeys（失败测试 → 实现）

**Files:**
- Test: `cpq-frontend/src/pages/quotation/rowKeyUniquify.test.ts`（新建）
- Modify: `cpq-frontend/src/pages/quotation/useCardSnapshots.ts`（在 `computeRowKey` 定义下方加两个导出函数）

- [ ] **Step 1: 失败测试**

```ts
import { describe, it, expect } from 'vitest';
import { uniquifyRowKeys, buildUniqueRowKeys } from './useCardSnapshots';

describe('uniquifyRowKeys 撞键消歧', () => {
  it('唯一键保持原样（向后兼容）', () => {
    expect(uniquifyRowKeys(['a', 'b', 'c'])).toEqual(['a', 'b', 'c']);
  });
  it('撞键按出现序追加 #0/#1', () => {
    expect(uniquifyRowKeys(['||单价', '||单价'])).toEqual(['||单价#0', '||单价#1']);
  });
  it('混合：只动撞键，唯一键不动', () => {
    expect(uniquifyRowKeys(['x', 'k', 'x', 'k', 'x']))
      .toEqual(['x#0', 'k#0', 'x#1', 'k#1', 'x#2']);
  });
});

describe('buildUniqueRowKeys 复现外购件撞键', () => {
  const fields = [
    { name: '料件', field_type: 'INPUT_TEXT', default_source: { type: 'BASIC_DATA', path: '$wgj_view._料件' } },
    { name: '要素', field_type: 'INPUT_TEXT', default_source: { type: 'BASIC_DATA', path: '$wgj_view._要素' } },
  ];
  const baseRows = [
    { driverRow: { _料件: null, _要素: '单价', 费用: 0.6892 }, basicDataValues: {} },
    { driverRow: { _料件: null, _要素: '单价', 费用: 0.802 }, basicDataValues: {} },
  ];
  it('两行 (空料件+单价) → ||单价#0 / ||单价#1（不再塌缩）', () => {
    expect(buildUniqueRowKeys(fields, ['料件', '要素'], baseRows))
      .toEqual(['||单价#0', '||单价#1']);
  });
});
```

- [ ] **Step 2: 跑确认失败** → `cd cpq-frontend && npx vitest run src/pages/quotation/rowKeyUniquify.test.ts`，Expected: FAIL（`uniquifyRowKeys`/`buildUniqueRowKeys` 未导出）。

- [ ] **Step 3: 实现** — 在 `useCardSnapshots.ts` 的 `computeRowKey` 函数定义之后追加：

```ts
/** 行键唯一化：同一组件内出现 ≥2 次的 rowKey 按出现序追加 `#<0基序号>`；
 *  出现 1 次的键保持原样（向后兼容，现有非撞键报价单 editRows 仍绑定）。
 *  修复撞键导致 editRows 写覆盖/读串行 → resolvedRows「末值×行数」塌缩。 */
export function uniquifyRowKeys(keys: string[]): string[] {
  const counts = new Map<string, number>();
  for (const k of keys) counts.set(k, (counts.get(k) ?? 0) + 1);
  const running = new Map<string, number>();
  return keys.map((k) => {
    if ((counts.get(k) ?? 0) <= 1) return k;
    const n = running.get(k) ?? 0;
    running.set(k, n + 1);
    return `${k}#${n}`;
  });
}

/** 按组件 baseRows 成批算 rowKey 并唯一化。序号按 baseRows 数组序（与后端同序）。 */
export function buildUniqueRowKeys(
  fields: any[] | undefined,
  rowKeyFields: string[] | undefined | null,
  baseRows: Array<{ driverRow?: Record<string, any>; basicDataValues?: Record<string, any> }> | undefined,
): string[] {
  const raw = (baseRows ?? []).map((br, i) =>
    computeRowKey(fields, rowKeyFields, br?.driverRow, i, br?.basicDataValues),
  );
  return uniquifyRowKeys(raw);
}
```

- [ ] **Step 4: 跑通过** → `npx vitest run src/pages/quotation/rowKeyUniquify.test.ts`，Expected: PASS。

- [ ] **Step 5: tsc** → `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`，0 错误。

- [ ] **Step 6: 提交** `git add cpq-frontend/src/pages/quotation/rowKeyUniquify.test.ts cpq-frontend/src/pages/quotation/useCardSnapshots.ts && git commit -m "feat(rowkey): uniquifyRowKeys/buildUniqueRowKeys 撞键消歧 (TDD)"`

---

## Task 2：前端 — 三个渲染/查表站点接入唯一化键

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/useCardSnapshots.ts`（`rowKeyOf` :202 / `getCell` :218）
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（:2184 区）
- Modify: `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx`（:488 区）

> 关键：rowKey 现在依赖**整组件 baseRows** 才能定序号，单行函数必须先按组件预算唯一键表再按下标取。

- [ ] **Step 1: useCardSnapshots — 预算每组件唯一键表**

在 `const rowKeyOf = ...` 上方（`valByComp` 构建之后）加：

```ts
// 每组件唯一化 rowKey 表（撞键消歧）：rowKeyOf/getCell 按下标取，保证与写路径一致。
const uniqKeysByComp = new Map<string, string[]>();
for (const t of tabs) {
  const vt = valByComp.get(t.componentId);
  uniqKeysByComp.set(t.componentId, buildUniqueRowKeys(t.fields, t.rowKeyFields, vt?.baseRows));
}
```

把 `rowKeyOf`（:202）改为：

```ts
const rowKeyOf = (componentId: string, rowIndex: number): string => {
  const keys = uniqKeysByComp.get(componentId);
  if (keys && rowIndex < keys.length) return keys[rowIndex];
  // 兜底（无快照/越界）：退回单行算法
  const st = structByComp.get(componentId);
  const vt = valByComp.get(componentId);
  const baseRow = vt?.baseRows?.[rowIndex];
  return computeRowKey(st?.fields, st?.rowKeyFields, baseRow?.driverRow, rowIndex, baseRow?.basicDataValues);
};
```

把 `getCell`（:218）里的 `const rk = computeRowKey(...)` 改为：

```ts
const rk = (uniqKeysByComp.get(componentId)?.[rowIndex])
  ?? computeRowKey(st.fields, st.rowKeyFields, baseRow?.driverRow, rowIndex, baseRow?.basicDataValues);
```

确保文件顶部已 `import` 同文件函数（`buildUniqueRowKeys` 同文件定义，无需 import）。

- [ ] **Step 2: QuotationStep2 — 活动组件预算唯一键**

在渲染活动组件行的 `.map(...)` 之前（`activeRowKeyFields`（:2118）拿到之后、构造行数组之前）加：

```ts
// 撞键消歧：活动组件全部 baseRows 成批算唯一 rowKey，渲染/写回按下标取（与后端 + 快照查表一致）。
const activeUniqRowKeys = buildUniqueRowKeys(
  activeComponent.fields, activeRowKeyFields, baseRows,
);
```

把 `:2184` 的：

```ts
const rowKey = useSnapEdit ? computeRowKey(
  activeComponent.fields, activeRowKeyFields, baseRows[i]?.driverRow, i, baseRows[i]?.basicDataValues,
) : String(i);
```

改为：

```ts
const rowKey = useSnapEdit
  ? (activeUniqRowKeys[i] ?? String(i))
  : String(i);
```

> `baseRows` 为活动组件展开行数组（:2184 上下文已有同名变量；若变量名为 `baseRows` 之外请按现场实际名替换，对象引用须与 `:2184` 用的同一数组）。在文件顶部 `import { computeRowKey }` 处补 `buildUniqueRowKeys`：`import { computeRowKey, buildUniqueRowKeys } from './useCardSnapshots';`

- [ ] **Step 3: ReadonlyProductCard — 循环前预算唯一键**

在 `for (let ri = 0; ri < effectiveCount; ri++)`（:482 区）之前加：

```ts
// 撞键消歧：详情/核价侧也按组件成批算唯一 rowKey，与编辑页 + 后端一致。
const roUniqRowKeys = buildUniqueRowKeys(
  activeComp.fields,
  activeRowKeyFields,
  Array.from({ length: effectiveCount }, (_, ri) => {
    const ra = rowAt(ri, activeComp, s);
    const drv = (ra.expIndex >= 0 ? activeDriverExpansion!.rows[ra.expIndex]?.driverRow : undefined)
      ?? activeSnap?.driverRows[ri] ?? ra.row;
    const bdv = ra.expIndex >= 0 ? activeDriverExpansion!.rows[ra.expIndex]?.basicDataValues : undefined;
    return { driverRow: drv, basicDataValues: bdv };
  }),
);
```

把 `:488` 的 `const rowKey = useSnap ? computeRowKey(...) : String(ri);` 改为：

```ts
const rowKey = useSnap ? (roUniqRowKeys[ri] ?? String(ri)) : String(ri);
```

并在文件顶部 import 处补 `buildUniqueRowKeys`（来源 `./useCardSnapshots`）。

- [ ] **Step 4: tsc + 既有快照测试不回归**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json && npx vitest run src/pages/quotation/useCardSnapshots.test.ts src/pages/quotation/rowDedup.test.ts src/pages/quotation/buildCrossTabRows.test.ts src/pages/quotation/rowKeyUniquify.test.ts`
Expected: tsc 0 错误；全 PASS。

- [ ] **Step 5: Vite transform 自检（每个改动 .tsx/.ts）**

Run: `for f in src/pages/quotation/QuotationStep2.tsx src/pages/quotation/ReadonlyProductCard.tsx src/pages/quotation/useCardSnapshots.ts; do curl -s -o /dev/null -w "$f %{http_code}\n" http://localhost:5174/$f; done`
Expected: 全 200。

- [ ] **Step 6: 提交** `git add -- cpq-frontend/src/pages/quotation/useCardSnapshots.ts cpq-frontend/src/pages/quotation/QuotationStep2.tsx cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx && git commit -m "fix(rowkey): 前端三站点接入唯一化键(撞键消歧)"`

---

## Task 3：后端 — uniquifyRowKeys + computeRows 预扫（失败测试 → 实现）

**Files:**
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/FormulaCalculatorRowKeyUniqueTest.java`（新建）
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java`（新增静态方法 + `computeRows` :584-589）

- [ ] **Step 1: 失败测试**

```java
package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FormulaCalculatorRowKeyUniqueTest {
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void uniquifyRowKeys_uniqueUnchanged_collisionSuffixed() {
        assertEquals(List.of("a", "b"), FormulaCalculator.uniquifyRowKeys(List.of("a", "b")));
        assertEquals(List.of("||单价#0", "||单价#1"),
                FormulaCalculator.uniquifyRowKeys(List.of("||单价", "||单价")));
        assertEquals(List.of("x#0", "k#0", "x#1"),
                FormulaCalculator.uniquifyRowKeys(List.of("x", "k", "x")));
    }

    @Test
    void computeRows_collidingDriverKeys_editRowsBindPerRow() throws Exception {
        // 外购件: row_key_fields=[料件,要素], 两行 driver (空料件+单价), 费用 0.6892/0.802
        JsonNode fields = om.readTree("""
            [{"name":"料件","field_type":"INPUT_TEXT","default_source":{"type":"BASIC_DATA","path":"$wgj_view._料件"}},
             {"name":"要素","field_type":"INPUT_TEXT","default_source":{"type":"BASIC_DATA","path":"$wgj_view._要素"}},
             {"name":"费用","field_type":"INPUT_NUMBER","default_source":{"type":"BASIC_DATA","path":"$wgj_view.费用"}}]""");
        JsonNode formulas = om.readTree("[]");
        JsonNode rowKeyFields = om.readTree("[\"料件\",\"要素\"]");
        JsonNode baseRows = om.readTree("""
            [{"driverRow":{"_料件":null,"_要素":"单价","费用":0.6892},"basicDataValues":{}},
             {"driverRow":{"_料件":null,"_要素":"单价","费用":0.802},"basicDataValues":{}}]""");
        // 两条 editRow 分别按唯一化键绑定到两行
        JsonNode editRows = om.readTree("""
            [{"rowKey":"||单价#0","values":{"料件":"外购件1"}},
             {"rowKey":"||单价#1","values":{"料件":"外购件2"}}]""");

        FormulaCalculator fc = new FormulaCalculator();
        JsonNode out = fc.calculate(fields, formulas, om.readTree("[]"), rowKeyFields,
                baseRows, editRows, new java.util.HashMap<>());

        // 两行 rowKey 唯一、editRows 各自生效（料件分别为外购件1/外购件2）
        assertEquals("||单价#0", out.get(0).path("rowKey").asText());
        assertEquals("||单价#1", out.get(1).path("rowKey").asText());
    }
}
```

> 注：若 `calculate` 输出结构未含 editRows 合并后的字段值，可改断言 `computeRows`（私有则经 `calculate` 的 `rowKey` 输出）；核心是 **两行 rowKey = `||单价#0`/`||单价#1`**（修复前两行都 `||单价`）。子代理按 `calculate` 实际返回结构对齐断言路径。

- [ ] **Step 2: 跑确认失败** → `cd cpq-backend && ./mvnw -q -Dtest=FormulaCalculatorRowKeyUniqueTest test`，Expected: 编译失败（`uniquifyRowKeys` 不存在）或断言失败（两行都 `||单价`）。

- [ ] **Step 3: 实现 uniquifyRowKeys** — 在 `FormulaCalculator` 类内 rowKey 区（`computeRowKey` 附近）加：

```java
/** 行键唯一化：同一组件内出现 ≥2 次的 rowKey 按出现序追加 {@code #<0基序号>};
 *  出现 1 次的键保持原样（向后兼容）。前端 uniquifyRowKeys 逐字节等价。 */
public static java.util.List<String> uniquifyRowKeys(java.util.List<String> keys) {
    java.util.Map<String, Integer> counts = new java.util.HashMap<>();
    for (String k : keys) counts.merge(k, 1, Integer::sum);
    java.util.Map<String, Integer> running = new java.util.HashMap<>();
    java.util.List<String> out = new java.util.ArrayList<>(keys.size());
    for (String k : keys) {
        if (counts.getOrDefault(k, 0) <= 1) { out.add(k); continue; }
        int n = running.merge(k, 1, Integer::sum) - 1;
        out.add(k + "#" + n);
    }
    return out;
}
```

- [ ] **Step 4: computeRows 预扫** — 在 `computeRows`（:576 `editByKey` 之后、:584 主循环之前）加预扫，并把循环内 `:588-589` 的 effKey 改为取预扫结果：

```java
// 行键唯一化预扫(撞键→#序号)：先算全部 raw effKey 再消歧，保证 editRows 逐行绑定(修末值×行数塌缩)。
java.util.List<String> rawKeys = new java.util.ArrayList<>();
int pre = 0;
for (JsonNode baseRow : baseRows) {
    String rk = computeRowKey(rowKeyFields, fields, baseRow.path("driverRow"), baseRow.path("basicDataValues"));
    rawKeys.add((rk != null && !rk.isEmpty()) ? rk : String.valueOf(pre));
    pre++;
}
java.util.List<String> effKeys = uniquifyRowKeys(rawKeys);
```

把循环体内 `:588-589`：

```java
String rowKey = computeRowKey(rowKeyFields, fields, driverRow, basicDataValues);
String effKey = (rowKey != null && !rowKey.isEmpty()) ? rowKey : String.valueOf(idx);
```

替换为：

```java
String effKey = effKeys.get(idx);
```

（其余 `editByKey.containsKey(effKey)` / `new RowResult(effKey, ...)` 不变。）

- [ ] **Step 5: 跑通过 + 既有公式测试不回归** → `cd cpq-backend && ./mvnw -q -Dtest=FormulaCalculatorRowKeyUniqueTest,FormulaCalculatorTest,FormulaCalculatorComputeDedupKeyTest test`，Expected: 全 PASS。

- [ ] **Step 6: 提交** `git add cpq-backend/src/test/java/com/cpq/quotation/service/FormulaCalculatorRowKeyUniqueTest.java cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java && git commit -m "fix(rowkey): 后端 uniquifyRowKeys + computeRows 预扫消歧 (TDD)"`

---

## Task 4：后端 — CardSnapshotService + CardEffectiveRows 接入唯一化

**Files:**
- Modify: `cpq-backend/.../service/CardSnapshotService.java`（:911 / :959-961 / :1443-1445）
- Modify: `cpq-backend/.../service/card/CardEffectiveRows.java`（rowKey 计算循环）

> 三处后端 rowKey 都是「循环内对每个 driverRow 算 rk」。统一改成：**循环前对该组件全部 baseRows 成批算 raw key → `FormulaCalculator.uniquifyRowKeys` → 循环内按下标取**。保证与 `computeRows`、前端、editRows 存储键一致。

- [ ] **Step 1: CardSnapshotService :959-961 区（重算 newKeys 处）**

读 `:945-965` 上下文确认这是「按 snapshot 各 baseRow 算 rowKey 列表 `newKeys`」。把循环内逐行 `newKeys.add(rk != null && !rk.isEmpty() ? rk : String.valueOf(idx));` 改为：循环结束后整体唯一化——

```java
// 收集 raw 后唯一化（撞键→#序号），与 FormulaCalculator.computeRows/前端一致
newKeys = com.cpq.quotation.service.FormulaCalculator.uniquifyRowKeys(newKeys);
```

> 若 `newKeys` 此前已直接用于 editRows 重映射，确保唯一化在「写回 / 比对」之前。子代理按现场把 `uniquifyRowKeys` 插在收集完、使用前。

- [ ] **Step 2: CardSnapshotService :1443-1445 区（editByKey 重建处）**

读 `:1430-1460`。该处对每个 baseRow 算 `rowKey` 后 `editByKey.get(rowKey)` 取编辑行并回填。改为：先成批算 raw key 列表并 `uniquifyRowKeys`，循环内 `String rowKey = uniqKeys.get(i);`：

```java
// 预扫唯一化键（撞键消歧），保证与 computeRows/前端 editRows 键一致
java.util.List<String> rawKeys = new java.util.ArrayList<>();
for (int i = 0; i < snapshotRows.size(); i++) {
    JsonNode br = snapshotRows.get(i);
    String rk = formulaCalculator.computeRowKey(rkf, fieldsDef, br.path("driverRow"), br.path("basicDataValues"));
    rawKeys.add((rk != null && !rk.isEmpty()) ? rk : String.valueOf(i));
}
java.util.List<String> uniqKeys = com.cpq.quotation.service.FormulaCalculator.uniquifyRowKeys(rawKeys);
```

循环体内把 `String rowKey = (rk != null && !rk.isEmpty()) ? rk : String.valueOf(i);` 改 `String rowKey = uniqKeys.get(i);`（`snapshotRows`/变量名按 :1433-1454 现场实际）。

- [ ] **Step 3: CardSnapshotService :911 区**

读 `:895-915`。若 `:911` 的 `rk` 用于 editRows/formulaResults 查表或回写键，则同款：循环前预扫该组件全部 driverRow 的 raw key → uniquify → 按下标取。若 `:911` 只是临时诊断/日志（不参与键绑定），加注释说明无需改并跳过。**判定标准：该 rk 是否进入任何 editRows/formulaResults 的写或读键**。

- [ ] **Step 4: CardEffectiveRows rowKey 循环**

读 `:60-160` 的解析循环。其 `effectiveRow[i]` 用 `rowKey` 查 `formulaResults(rowKey)` / `editRows(rowKey)`。同款改：循环前对该组件 driverRows 成批算 raw key（沿用本文件现有 `resolveKeyPart`/字段感知逻辑）→ `FormulaCalculator.uniquifyRowKeys` → 循环内按下标取。保持本文件「全空→行号」兜底在 raw 阶段不变。

- [ ] **Step 5: 编译 + 跑既有快照/有效行测试** → `cd cpq-backend && ./mvnw -q -Dtest=RefreshCardSnapshotTest,SnapshotReconcileTest,CardEffectiveRowsTest,FormulaCalculatorRowKeyUniqueTest test`，Expected: 全 PASS。

- [ ] **Step 6: 后端 transform 自检** → `touch cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java`，等 6 秒，`curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health` → 200。

- [ ] **Step 7: 提交** `git add cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java cpq-backend/src/main/java/com/cpq/quotation/service/card/CardEffectiveRows.java && git commit -m "fix(rowkey): CardSnapshotService/CardEffectiveRows 接入行键唯一化"`

---

## Task 5：对拍 + E2E + 真机复测 + 存量清理

**Files:**
- Modify: 前端 `rowKeyUniquify.test.ts` / 后端 `FormulaCalculatorRowKeyUniqueTest.java`（同 fixture 硬编码相同期望串）

- [ ] **Step 1: 对拍断言** 前端 `buildUniqueRowKeys(fields, ['料件','要素'], baseRows)` 与后端 `uniquifyRowKeys(rawKeys)` 对同一外购件 fixture（费用 0.6892/0.802）都得 `["||单价#0","||单价#1"]`（两端硬编码相同字符串）。已在 Task1/Task3 覆盖 → 此步确认两端断言串**逐字节相同**。

- [ ] **Step 2: 跑两端** → 前端 `npx vitest run src/pages/quotation/rowKeyUniquify.test.ts`；后端 `./mvnw -q -Dtest=FormulaCalculatorRowKeyUniqueTest test`，均 PASS。

- [ ] **Step 3:（协议级强制）E2E** — `cd cpq-frontend && Remove-Item e2e/screenshots/qf-*.png -ErrorAction SilentlyContinue;（bash: rm -f e2e/screenshots/qf-*.png）npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list`
Expected: `1 passed`，`'加载中' final count = 0`，8 Tab 加载中=0（渲染管线无回归）。附 qf 截图。

- [ ] **Step 4: 真机复测（合并后，dev server 服务合并代码）** — 打开 QT-20260615-1729 外购件页签：两行料件填外购件1/外购件2（**因唯一化键变了，旧 `||单价` editRow 失配作废，需重填一次**）→ 保存 → 来料页签：外购件1 行 外购件材料费=0.6892、外购件2 行=0.802、小计 1.49=各行之和（不再 0 / 1.604）。

- [ ] **Step 5: 存量脏 editRow 排查（只查不改，给用户决策）** — 受影响 line_item 的外购件 editRows 含旧撞键 `||单价`：

```sql
SELECT q.quotation_number, tab->>'tabName', er->>'rowKey'
FROM quotation q JOIN quotation_line_item li ON li.quotation_id=q.id,
     jsonb_array_elements(li.quote_card_values->'tabs') tab,
     jsonb_array_elements(COALESCE(tab->'editRows','[]'::jsonb)) er
WHERE er->>'rowKey' !~ '#' AND er->>'rowKey' LIKE '%||%';
```

修复后新键带 `#序号`，旧无 `#` 的撞键 editRow 不再匹配 → 行回退 driver 空值 → 用户重填即按新键绑定。报告影响面，按用户决策重填/重存（不自动改存量数据）。

---

## Self-Review

- **Spec 覆盖**：撞键根因（多行同 rowKey）→ 唯一化算法(T1/T3) + 前端三站点(T2) + 后端三处(T4) + 对拍(T5)。✓
- **前后端一致**：`uniquifyRowKeys` 算法逐字节等价（唯一不变 / 撞键 #0 起按出现序）；raw key 口径沿用现有 computeRowKey + 全空回退；序号按 baseRows 数组序（两端同序）。✓
- **向后兼容**：唯一键不加后缀 → 现有非撞键报价单 editRows 仍绑定；仅撞键报价单旧 editRow 失配（本就是脏数据，T5 重填）。✓
- **稳定性**：rowKey 由 driver 内容派生（非编辑后内容），序号按稳定 driver 序 → 跨刷新稳定，编辑料件不改本行键。✓
- **AP-44/AP-51 协议面**：computeRowKey 全部使用点（editRows 写/读、formulaResults 查表、cross_tab 源行）均覆盖；强制 E2E（T5）。✓
- **范围外**：`RowKeyUniquenessService`（提交时冲突检测器）不改——它是报警，不参与绑定；唯一化后撞键自然消失，检测器更不会误报。✓
- **RECORD.md 纪律**：子代理**不提交 docs/RECORD.md**；记录由主线合并后追加。只 `git add` 本任务明确文件，禁 `git add -A`。
