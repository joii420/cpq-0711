# Plan 2b — `previous_row_subtotal` 改"上一行本列"（任意公式列） Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `previous_row_subtotal` 累加 token 的语义从"上一行的（唯一）小计列值"改为"上一行**本列**值"，对**任意公式列**生效。多小计列下每列各自独立累加。

**Architecture:** 双引擎对称改动，**token 求值器/handler 不动**。把"单标量 `prevRowSubtotal`（上一行小计列值，整行共用）"换成"上一行**全量公式值映射** `prevRowValues`"，在逐字段求值循环里把 `prevRowValues[当前字段名]` 喂给该字段的 `previous_row_subtotal` token。后端 `FormulaCalculator.computeRows`（1 处）+ 前端 `computeAllFormulas`（1 处）+ 4 个前端累加调用方迁移。单累加列结果不变（该列引用自己上一行 = 原行为），故 100% 向后兼容。

**Tech Stack:** Java 17 / Quarkus（`FormulaCalculator`）；React + TS（`QuotationStep2.tsx` / `ReadonlyProductCard.tsx` / `QuotationWizard.tsx` / `formulaEngine.ts` 不改）。

**关联：** spec `docs/superpowers/specs/2026-06-09-multi-subtotal-conditional-formula-design.md`；承接 Plan 2-核心边界声明（2b）。

---

## 设计依据（已核对现码，勿重新发明）

- **token 语义现状**：`previous_row_subtotal` 解析为单标量 `ctx.previousRowSubtotal`（后端 `FormulaCalculator:112-125`）/ `previousRowSubtotal` 入参（前端 `formulaEngine.ts:193-199`）。`!= null → 用它；否则 fallback_component_code`。**handler 本 Plan 不改**。
- **后端喂值**（`FormulaCalculator.computeRows`，Plan 2 后行号）：
  - `:388` `String subtotalField = findSubtotalFieldName(fields);`（单小计列）
  - `:390` `Double prevRowSubtotal = null;`
  - `:415` `ctx.previousRowSubtotal = prevRowSubtotal;`（整行求值前设一次）
  - `:422-428` 逐字段 `for (String name : order) { ... evaluateExpression(ff.expression, ctx) ... }`
  - `:432-435` `if (subtotalField != null && results.containsKey(subtotalField)) prevRowSubtotal = results.get(subtotalField);`
- **前端 `computeAllFormulas`**（`QuotationStep2.tsx:372`）：入参 8 `previousRowSubtotal?: number`（整行共用）；`:583-598` 逐字段 `for (const name of order) { evaluateExpression(..., previousRowSubtotal, ...) }`。入参顺序：1 comp,2 row,3 allComponentSubtotals,4 quotationFields,5 pathCache,6 partNo,7 basicDataValues,8 previousRowSubtotal,9 globalVariableDefs,10 crossTabRows。
- **4 个前端累加调用方**（模式统一：`subtotalFieldName = fields.find(is_subtotal).name` + `let prevRowSubtotal` + 每行 `prevRowSubtotal = cache[subtotalFieldName]`）：
  1. `ReadonlyProductCard.tsx#buildFormulaCache`（`:83-101`）
  2. `ReadonlyProductCard.tsx` 渲染预算（`:461-481`，`prevRowSubtotal` `:462` / 传参 `:476` / 累加 `:480`）
  3. `QuotationStep2.tsx` 渲染预算（`:1858-1876`）
  4. `QuotationWizard.tsx` 持久化富化（`:809-870`，computeAllFormulas 只传到第 9 参、无 crossTabRows）
- **不累加的调用方**（保持现状，走标量路径）：`computeTabSubtotalsByColumn`/`computeTabSubtotal`（位置 8 传 undefined）。其"累加列的列小计是否准确"是**先于 2b 的既有问题**（两侧 computeTabSubtotal 本就不喂 prev），2b 不碰。

## 迁移风险（必读 + 自检）

- **语义变更影响面**：若现有某个**非小计**公式列曾用 `previous_row_subtotal` 引用那唯一小计列的值，2b 后会改成引用它**自己列**上一行值 → 结果变化。Task 6 census 用 grep 找公式定义中 `previous_row_subtotal` 的使用，人工确认无"非自列引用"依赖；如有，与用户确认。
- 单累加列（token 在小计列自身公式里）：结果不变（自列上一行 = 原"那个小计列上一行"），向后兼容。后端 `FormulaCalculatorTest` T13/T14 应保持绿。

---

## File Structure

- Modify 后端 `FormulaCalculator.java#computeRows`：`prevRowSubtotal`(标量) → `prevRowValues`(Map)，逐字段喂 `prevRowValues.get(name)`。
- Modify 前端 `QuotationStep2.tsx#computeAllFormulas`：加入参 11 `previousRowValues`，逐字段解析。
- Modify 前端 `QuotationStep2.tsx`（渲染预算 :1858）/ `ReadonlyProductCard.tsx`（:83 + :461）/ `QuotationWizard.tsx`（:809）：累加改 `prevRowValues = cache`，传新参，删 `subtotalFieldName`。
- Test 后端 `FormulaCalculatorPrevRowPerColumnTest.java`（新，纯 JUnit）。
- Test 前端 `prevRowPerColumn.test.ts`（新，vitest）。
- E2E：复用 `quotation-flow.spec.ts`。

---

## Task 1: 后端 computeRows 改 per-column 累加（TDD）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java#computeRows`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/FormulaCalculatorPrevRowPerColumnTest.java`

- [ ] **Step 1: 写失败测试（两个独立累加列）**

```java
package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Plan 2b：previous_row_subtotal = 上一行本列值，多列各自独立累加。纯 JUnit。 */
class FormulaCalculatorPrevRowPerColumnTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final FormulaCalculator calc = new FormulaCalculator();
    private JsonNode j(String s) { try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); } }

    // 两个累加列：累计A = 上一行累计A + a；累计B = 上一行累计B + b。各自独立。
    private static final String FIELDS = "["
        + "{\"name\":\"a\",\"fieldType\":\"BASIC_DATA\",\"basicDataPath\":\"v.a\"},"
        + "{\"name\":\"b\",\"fieldType\":\"BASIC_DATA\",\"basicDataPath\":\"v.b\"},"
        + "{\"name\":\"累计A\",\"fieldType\":\"FORMULA\",\"isSubtotal\":true},"
        + "{\"name\":\"累计B\",\"fieldType\":\"FORMULA\",\"isSubtotal\":true}"
        + "]";
    private static final String FORMULAS = "["
        + "{\"name\":\"累计A\",\"expression\":[{\"type\":\"previous_row_subtotal\"},"
        + "{\"type\":\"operator\",\"value\":\"+\"},{\"type\":\"field\",\"value\":\"a\"}]},"
        + "{\"name\":\"累计B\",\"expression\":[{\"type\":\"previous_row_subtotal\"},"
        + "{\"type\":\"operator\",\"value\":\"+\"},{\"type\":\"field\",\"value\":\"b\"}]}"
        + "]";
    private static final String RKF = "[\"k\"]";
    private static final String BASEROWS = "["
        + "{\"driverRow\":{\"k\":\"r0\"},\"basicDataValues\":{\"{v.a}\":10,\"{v.b}\":1}},"
        + "{\"driverRow\":{\"k\":\"r1\"},\"basicDataValues\":{\"{v.a}\":20,\"{v.b}\":2}},"
        + "{\"driverRow\":{\"k\":\"r2\"},\"basicDataValues\":{\"{v.a}\":30,\"{v.b}\":3}}"
        + "]";

    @Test
    void twoColumnsAccumulateIndependently() {
        JsonNode fr = calc.calculate(j(FIELDS), j(FORMULAS), null, j(RKF), j(BASEROWS), j("[]"),
            new HashMap<>(), new HashMap<>(), new HashMap<>());
        Map<String, JsonNode> byKey = new HashMap<>();
        for (JsonNode r : fr) byKey.put(r.path("rowKey").asText(), r);
        // 累计A: 10, 30, 60 ; 累计B: 1, 3, 6（各自累加，互不串）
        assertEquals(10.0, byKey.get("r0").path("values").path("累计A").asDouble(), 1e-9);
        assertEquals(30.0, byKey.get("r1").path("values").path("累计A").asDouble(), 1e-9);
        assertEquals(60.0, byKey.get("r2").path("values").path("累计A").asDouble(), 1e-9);
        assertEquals(1.0, byKey.get("r0").path("values").path("累计B").asDouble(), 1e-9);
        assertEquals(3.0, byKey.get("r1").path("values").path("累计B").asDouble(), 1e-9);
        assertEquals(6.0, byKey.get("r2").path("values").path("累计B").asDouble(), 1e-9);
    }

    @Test
    void perColumnSubtotalSums() {
        // 累计A 末行=60 是列小计语义参考（注：computeTabSubtotal 不喂 prev，是既有问题，此处仅验 calculate 逐行值）
        JsonNode fr = calc.calculate(j(FIELDS), j(FORMULAS), null, j(RKF), j(BASEROWS), j("[]"),
            new HashMap<>(), new HashMap<>(), new HashMap<>());
        assertEquals(3, fr.size());
    }
}
```

- [ ] **Step 2: 跑测试确认失败（当前单标量累加 → 累计B 会串用累计A 的 prev 或全 fallback）**

Run: `cd cpq-backend && ./mvnw -o test -Dtest=FormulaCalculatorPrevRowPerColumnTest 2>&1 | grep -E "Tests run|expected|BUILD" | grep -v Shutdown | head`
Expected: `twoColumnsAccumulateIndependently` 失败（当前实现两列不独立：旧逻辑只把 `subtotalField`=第一个小计列“累计A”的值当 prev 喂全行 → 累计B 用到累计A 的 prev，值错）。

- [ ] **Step 3: 改 computeRows（标量 → Map，逐字段喂）**

把（`:390`）`Double prevRowSubtotal = null;` 改为：

```java
        Map<String, Double> prevRowValues = null;  // Plan 2b：上一行全量公式值（按字段名）
```

删除（`:415`）整行设值：

```java
            ctx.previousRowSubtotal = prevRowSubtotal;
```

把逐字段循环（`:421-428`）改为每字段先按本列喂 prev：

```java
            Map<String, Double> results = new LinkedHashMap<>();
            for (String name : order) {
                FormulaField ff = findByName(formulaFields, name);
                if (ff == null) continue;
                // Plan 2b：previous_row_subtotal = 上一行本列值；无则 null → token 走 fallback。
                ctx.previousRowSubtotal = (prevRowValues == null) ? null : prevRowValues.get(name);
                double val = evaluateExpression(ff.expression, ctx).doubleValue();
                results.put(name, val);
                ctx.fieldValues.put(name, val);
            }
```

把（`:432-435`）传下行逻辑改为传全量：

```java
            out.add(new RowResult(effKey, results));

            // Plan 2b：本行全量公式值传下行，各列下一行按本列取 prev。
            prevRowValues = results;
            idx++;
```

`String subtotalField = findSubtotalFieldName(fields);`（`:388`）现已无用 → 删除该行（`findSubtotalFieldName` 方法保留无妨；如触发 unused-private 告警再删方法）。

- [ ] **Step 4: 跑测试确认通过 + 回归 T13/T14（单累加列不变）**

Run: `cd cpq-backend && ./mvnw -o test -Dtest='FormulaCalculatorPrevRowPerColumnTest,FormulaCalculatorTest' 2>&1 | grep -E "Tests run:|BUILD" | grep -v Shutdown | tail -5`
Expected: 全 `Failures: 0`；新测试 2 passed；`FormulaCalculatorTest` 16 passed（T13/T14 单累加列结果不变）。

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java cpq-backend/src/test/java/com/cpq/quotation/service/FormulaCalculatorPrevRowPerColumnTest.java
git commit -m "feat(prev-row-subtotal): 后端 computeRows 改上一行本列累加(多列独立) + TDD"
```

---

## Task 2: 前端 computeAllFormulas 加 per-column 入参 + 逐字段解析

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx#computeAllFormulas`（签名 `:372-392`、求值循环 `:583-598`）

- [ ] **Step 1: 签名加入参 11 `previousRowValues`**

在 `computeAllFormulas` 参数表末尾（`crossTabRows?: ...,` 之后，`):` 之前）加：

```tsx
  // Plan 2b：上一行全量公式值（按字段名）。提供后 previous_row_subtotal 按"当前列"取上一行本列值；
  // 不传则退回 previousRowSubtotal 标量（旧行为）。
  previousRowValues?: Record<string, number | null>,
```

- [ ] **Step 2: 求值循环按本列喂 prev**

把（`:584-598`）：

```tsx
  const results: Record<string, number | null> = {};
  for (const name of order) {
    const ff = formulaFields.find(f => f.name === name)!;
    try {
      const val = evaluateExpression(
        ff.formula.expression, fieldValues,
        allComponentSubtotals || {}, undefined, quotationFields,
        pathCache, partNo, basicDataValues, previousRowSubtotal,
        globalVariableDefs, row, crossTabRows,
      );
      results[name] = val;
      fieldValues[name] = val; // feed result for downstream formulas
    } catch {
      results[name] = null;
    }
  }
  return results;
```

改为：

```tsx
  const results: Record<string, number | null> = {};
  for (const name of order) {
    const ff = formulaFields.find(f => f.name === name)!;
    try {
      // Plan 2b：previous_row_subtotal 按当前列取上一行本列值；无 map 时退回标量。
      const prevForField = previousRowValues
        ? (typeof previousRowValues[name] === 'number' ? (previousRowValues[name] as number) : undefined)
        : previousRowSubtotal;
      const val = evaluateExpression(
        ff.formula.expression, fieldValues,
        allComponentSubtotals || {}, undefined, quotationFields,
        pathCache, partNo, basicDataValues, prevForField,
        globalVariableDefs, row, crossTabRows,
      );
      results[name] = val;
      fieldValues[name] = val; // feed result for downstream formulas
    } catch {
      results[name] = null;
    }
  }
  return results;
```

- [ ] **Step 3: tsc**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json 2>&1 | tail -5`
Expected: 0 错误。

- [ ] **Step 4: Commit**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx
git commit -m "feat(prev-row-subtotal): computeAllFormulas 加 previousRowValues 逐字段解析"
```

---

## Task 3: 迁移 4 个前端累加调用方

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx`（`:83-101` + `:461-481`）
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（`:1858-1876`）
- Modify: `cpq-frontend/src/pages/quotation/QuotationWizard.tsx`（`:809-870`）

> 统一改法：删 `subtotalFieldName` find；`let prevRowSubtotal` → `let prevRowValues: Record<string, number | null> | undefined = undefined;`；computeAllFormulas 位置 8 传 `undefined`、末位（11）传 `prevRowValues`；每行后 `prevRowValues = cache`。

- [ ] **Step 1: ReadonlyProductCard#buildFormulaCache（`:83-101`）**

把：

```tsx
  const subtotalFieldName = comp.fields?.find((f: any) => f.is_subtotal)?.name;
  const useDriver = !!(driverExpansion && driverExpansion.rowCount > 0);
  const effectiveCount = useDriver ? driverExpansion!.rowCount : rows.length;
  const caches: Array<Record<string, number | null>> = [];
  let prevRowSubtotal: number | undefined = undefined;
  for (let ri = 0; ri < effectiveCount; ri++) {
    const row = rows[ri] ?? {};
    const bdv = useDriver ? driverExpansion!.rows[ri]?.basicDataValues : undefined;
    const cache = computeAllFormulas(
      comp, row, compSubtotals,
      undefined, undefined, partNo, bdv,
      prevRowSubtotal, globalVariableDefs, crossTabRows,
    );
    caches.push(cache);
    if (subtotalFieldName && typeof cache[subtotalFieldName] === 'number') {
      prevRowSubtotal = cache[subtotalFieldName] as number;
    }
  }
  return caches;
```

改为：

```tsx
  const useDriver = !!(driverExpansion && driverExpansion.rowCount > 0);
  const effectiveCount = useDriver ? driverExpansion!.rowCount : rows.length;
  const caches: Array<Record<string, number | null>> = [];
  // Plan 2b：上一行全量公式值，previous_row_subtotal 按本列取。
  let prevRowValues: Record<string, number | null> | undefined = undefined;
  for (let ri = 0; ri < effectiveCount; ri++) {
    const row = rows[ri] ?? {};
    const bdv = useDriver ? driverExpansion!.rows[ri]?.basicDataValues : undefined;
    const cache = computeAllFormulas(
      comp, row, compSubtotals,
      undefined, undefined, partNo, bdv,
      undefined, globalVariableDefs, crossTabRows, prevRowValues,
    );
    caches.push(cache);
    prevRowValues = cache;
  }
  return caches;
```

- [ ] **Step 2: ReadonlyProductCard 渲染预算（`:461-481`）**

把（锚点）：

```tsx
                      const subtotalFieldName = activeComp.fields?.find((f: any) => f.is_subtotal)?.name;
                      let prevRowSubtotal: number | undefined = undefined;
```

改为：

```tsx
                      let prevRowValues: Record<string, number | null> | undefined = undefined;
```

把该循环内 computeAllFormulas 调用的 `rowBdv, prevRowSubtotal, globalVariableDefs, crossTabRows,`（`:476`）改为 `rowBdv, undefined, globalVariableDefs, crossTabRows, prevRowValues,`；把累加 `prevRowSubtotal = cache[subtotalFieldName] as number;`（`:480` 及其 `if (subtotalFieldName && ...)` 守卫）整体替换为 `prevRowValues = cache;`。

> 执行时先读 `:459-482` 取该调用的完整实参列表，按上面位置精确替换（位置 8 → undefined、末位 → prevRowValues）。

- [ ] **Step 3: QuotationStep2 渲染预算（`:1858-1876`）**

把：

```tsx
                    const subtotalFieldName = activeComponent.fields?.find((f: any) => f.is_subtotal)?.name;
                    const preComputedCaches: Array<Record<string, number | null>> = [];
                    let prevRowSubtotal: number | undefined = undefined;
                    for (const r of effectiveRows) {
                      const snapFormula = useSnapEdit ? activeSnap?.formula.get(r.rowKey) : undefined;
                      const cache = (snapFormula && Object.keys(snapFormula).length > 0)
                        ? (snapFormula as Record<string, number | null>)
                        : computeAllFormulas(
                            activeComponent, r.row, allComponentSubtotals,
                            undefined, undefined, item.productPartNo, r.basicDataValues,
                            prevRowSubtotal, globalVariableDefs, crossTabRows,
                          );
                      preComputedCaches.push(cache);
                      if (subtotalFieldName && typeof cache[subtotalFieldName] === 'number') {
                        prevRowSubtotal = cache[subtotalFieldName] as number;
                      }
                    }
```

改为：

```tsx
                    const preComputedCaches: Array<Record<string, number | null>> = [];
                    // Plan 2b：上一行全量公式值，previous_row_subtotal 按本列取。
                    let prevRowValues: Record<string, number | null> | undefined = undefined;
                    for (const r of effectiveRows) {
                      const snapFormula = useSnapEdit ? activeSnap?.formula.get(r.rowKey) : undefined;
                      const cache = (snapFormula && Object.keys(snapFormula).length > 0)
                        ? (snapFormula as Record<string, number | null>)
                        : computeAllFormulas(
                            activeComponent, r.row, allComponentSubtotals,
                            undefined, undefined, item.productPartNo, r.basicDataValues,
                            undefined, globalVariableDefs, crossTabRows, prevRowValues,
                          );
                      preComputedCaches.push(cache);
                      prevRowValues = cache;
                    }
```

- [ ] **Step 4: QuotationWizard 持久化富化（`:809-870`）**

把（`:809-810`）：

```tsx
    const subtotalFieldName = fields.find((f: any) => f.is_subtotal)?.name;
    let prevRowSubtotal: number | undefined = undefined;
```

改为：

```tsx
    // Plan 2b：上一行全量公式值，previous_row_subtotal 按本列取。
    let prevRowValues: Record<string, number | null> | undefined = undefined;
```

把 computeAllFormulas 调用（`:854-858`，注意此处只传到第 9 参、无 crossTabRows）：

```tsx
        const formulaCache = computeAllFormulas(
          cd, enriched, componentSubtotals,
          undefined, undefined, partNo, basicDataValues, prevRowSubtotal,
          gvDefs   // B-GV-1 修复: 透传 globalVariableDefs，动态 key 公式不再兜底 0
        );
```

改为（位置 8 → undefined、补位置 10 crossTabRows=undefined、末位 11 → prevRowValues）：

```tsx
        const formulaCache = computeAllFormulas(
          cd, enriched, componentSubtotals,
          undefined, undefined, partNo, basicDataValues, undefined,
          gvDefs, undefined, prevRowValues,
        );
```

把累加（`:868-870`）：

```tsx
        if (subtotalFieldName && typeof formulaCache[subtotalFieldName] === 'number') {
          prevRowSubtotal = formulaCache[subtotalFieldName] as number;
        }
```

改为：

```tsx
        prevRowValues = formulaCache;
```

- [ ] **Step 5: tsc + Vite transform**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json 2>&1 | tail -5`
Expected: 0 错误。
Run: 对三文件各 `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/<file>`
Expected: 均 200。

- [ ] **Step 6: Commit**

```bash
git add cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx cpq-frontend/src/pages/quotation/QuotationStep2.tsx cpq-frontend/src/pages/quotation/QuotationWizard.tsx
git commit -m "feat(prev-row-subtotal): 4 个前端累加调用方迁移到上一行本列"
```

---

## Task 4: 前端 per-column 累加测试（TDD）

**Files:**
- Test: `cpq-frontend/src/pages/quotation/prevRowPerColumn.test.ts`（新）

- [ ] **Step 1: 写测试（两个独立累加列，复用 computeTabSubtotalsByColumn 不行——它不喂 prev；改测一个能喂 prev 的导出函数）**

> `computeAllFormulas` 未 export。为可单测 per-column 累加，本 Task 新增一个**薄导出**包装：在 `QuotationStep2.tsx` 末尾导出 `computeRowsCachesForTest`（按行序遍历 + prevRowValues 累加，返回每行 cache 数组）——纯逻辑、便于断言。

在 `QuotationStep2.tsx` 末尾追加：

```tsx
/** 测试用：按行序逐行 computeAllFormulas，previous_row_subtotal 按本列累加（Plan 2b）。 */
export function computeRowsCachesForTest(
  comp: ComponentDataItem,
  rows: Record<string, any>[],
): Array<Record<string, number | null>> {
  const caches: Array<Record<string, number | null>> = [];
  let prevRowValues: Record<string, number | null> | undefined = undefined;
  for (const row of rows) {
    const cache = computeAllFormulas(
      comp, row, undefined, undefined, undefined, undefined, undefined,
      undefined, undefined, undefined, prevRowValues,
    );
    caches.push(cache);
    prevRowValues = cache;
  }
  return caches;
}
```

测试：

```ts
import { describe, it, expect } from 'vitest';
import { computeRowsCachesForTest } from './QuotationStep2';

const comp: any = {
  componentId: 'c1', componentCode: 'C1', tabName: 'T',
  fields: [
    { name: 'a', field_type: 'INPUT_NUMBER' },
    { name: 'b', field_type: 'INPUT_NUMBER' },
    { name: '累计A', field_type: 'FORMULA', is_subtotal: true, formula_name: '累计A' },
    { name: '累计B', field_type: 'FORMULA', is_subtotal: true, formula_name: '累计B' },
  ],
  formulas: [
    { name: '累计A', expression: [
      { type: 'previous_row_subtotal' }, { type: 'operator', value: '+' }, { type: 'field', value: 'a' },
    ] },
    { name: '累计B', expression: [
      { type: 'previous_row_subtotal' }, { type: 'operator', value: '+' }, { type: 'field', value: 'b' },
    ] },
  ],
};

describe('previous_row_subtotal 上一行本列（Plan 2b）', () => {
  it('两个累计列各自独立累加', () => {
    const caches = computeRowsCachesForTest(comp, [
      { a: 10, b: 1 }, { a: 20, b: 2 }, { a: 30, b: 3 },
    ]);
    expect(caches.map(c => c['累计A'])).toEqual([10, 30, 60]);
    expect(caches.map(c => c['累计B'])).toEqual([1, 3, 6]);
  });
});
```

- [ ] **Step 2: 跑测试**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/prevRowPerColumn.test.ts 2>&1 | tail -8`
Expected: 1 passed（累计A [10,30,60]、累计B [1,3,6] 各自独立）。

- [ ] **Step 3: Commit**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx cpq-frontend/src/pages/quotation/prevRowPerColumn.test.ts
git commit -m "test(prev-row-subtotal): 前端两累计列独立累加 vitest"
```

---

## Task 5: census + E2E + 自检

- [ ] **Step 1: 语义变更影响 census**

Run: `grep -rniE "previous_row_subtotal" cpq-frontend/src cpq-backend/src/main --include=*.ts --include=*.tsx --include=*.java | grep -viE "test|//|\*"`
确认无残留 `find(f => f.is_subtotal)` 喂 previous_row_subtotal 的旧站点（4 个已迁移）。
Run: `grep -rnE "find\(\(?f:? ?a?n?y?\)? ?=> ?f\.is_subtotal\)" cpq-frontend/src --include=*.tsx --include=*.ts`
Expected: 仅剩 Plan 2-核心 census 已归类的**显示/求和**点（footer/bar 已是 filter）——不应再有"喂 prev"用途的 `find(is_subtotal)`。
**数据核对**：用 admin/DB 抽样现有组件公式 JSON，grep `previous_row_subtotal`，人工确认其所在公式列就是要累加的那一列（无"非自列引用"依赖）；如有，报用户。

- [ ] **Step 2: E2E（协议级 + 改了 QuotationStep2/ReadonlyProductCard/QuotationWizard 强制）**

```bash
cd cpq-backend && touch src/main/java/com/cpq/quotation/service/FormulaCalculator.java && sleep 8
cd ../cpq-frontend && rm -f e2e/screenshots/qf-*.png
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list 2>&1 | tail -25
```
Expected: `1 passed`，`'加载中' final count = 0`，8 Tab 加载中=0。现有模板的累加列（如工序小计）逐行值不变（单累加列向后兼容）。

- [ ] **Step 3: 自检声明（CLAUDE.md 强制）**

形如：
> 后端 `FormulaCalculatorPrevRowPerColumnTest` 2 + `FormulaCalculatorTest` 16 passed（T13/T14 单累加列不变）✅；前端 `prevRowPerColumn.test.ts` 1 passed（两列独立累加）✅；tsc 0 错误 ✅；3 文件 Vite 200 ✅；E2E `quotation-flow` 1 passed + 加载中=0 ✅。

---

## Self-Review（写后自检）

**Spec coverage：** previous_row_subtotal = 上一行本列、任意公式列 → Task 1（后端）+ Task 2/3（前端）；多列独立累加 → Task 1 Step 1 / Task 4 测试。

**Placeholder scan：** Task 3 Step 2 含"先读 :459-482 取完整实参列表再替换"——非占位，是该调用实参较长、需按位置精确替换的核对手段（位置 8 → undefined、末位 → prevRowValues 规则已明确）。其余步骤均完整代码。

**Type consistency：** 新入参 `previousRowValues?: Record<string, number | null>` 在 computeAllFormulas 定义、4 调用方传参、测试包装一致；后端 `prevRowValues` 为 `Map<String, Double>`，`ctx.previousRowSubtotal` 仍 `Double`。

**向后兼容：** 单累加列（token 在小计列自身公式）→ 自列上一行 = 原"那个小计列上一行" → T13/T14 + E2E 应不变。**风险**：非小计列引用 previous_row_subtotal 的旧公式语义会变（Task 5 census 人工核对）。

**边界：** `computeTabSubtotal`/`computeTabSubtotalsByColumn` 不喂 prev（累加列的"列小计"准确性是先于 2b 的既有问题），2b 不碰，已在依据中声明。
