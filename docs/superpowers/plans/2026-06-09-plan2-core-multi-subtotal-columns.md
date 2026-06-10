# Plan 2-核心 — 多小计列（每列各算总计 + 独立成线 + 最终总价） Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 一个组件支持多个小计列，每列各自计算总计；产品/报价单层每个 `(组件, 小计列)` 独立成线，最终总价 = 所有小计线之和。

**Architecture:** 加法式、向后兼容。新增"按列"小计计算函数 `computeTabSubtotalsByColumn`（前端 + 后端），原 `computeTabSubtotal` 改为"所有小计列之和"（单小计列时 = 原值，零行为变化）。三处小计底座（前端 `allComponentSubtotals` / 详情 `compSubtotals` / 后端 `CardSnapshotService` PASS1 `componentSubtotals`）追加 `code#列名` per-column 键，同时保留 `code = 各列之和`。两侧 footer（编辑/详情）值源从单值改为按列查找。字段配置多选、后端放开"至多一个"校验。

**Tech Stack:** Java 17 / Quarkus（`FormulaCalculator` / `ComponentService` / `CardSnapshotService`）；React + TS（`QuotationStep2.tsx` / `ReadonlyProductCard.tsx` / `FieldConfigTable.tsx`）。

**关联 spec：** `docs/superpowers/specs/2026-06-09-multi-subtotal-conditional-formula-design.md` 设计 D / §2 结论 Q9=C·Q10=B·A1。

---

## 边界（本 Plan 明确不做）

- **2b（后续）`previous_row_subtotal` 累加 token**：本 Plan **不改**其语义。现有 `find(f => f.is_subtotal)`（`QuotationWizard:809` / `QuotationStep2:1834` / `FormulaCalculator:415`）保持现状 = 引用"第一个小计列"。多小计列 + 累加公式的组件，本 Plan 期间该 token 落第一个小计列（可用、非最终语义）。2b 改为"上一行本列"。
- **2c（后续）`[页签#SUBTOTAL]` 卡片/Excel 引用**：本 Plan **不改** `CardRef`/`CardFormulaEvaluator` 解析。但因本 Plan 把 `componentSubtotals[code]` 语义从"那个小计"改为"各小计列之和"，`[页签#SUBTOTAL]` 在多小计列时自动 = 各列之和（单列时不变）。2c 加按列名显式选 `[页签#列名]`。
- 这两条边界是有意的：单小计列组件 100% 维持现状；多小计列组件这两处遗留机制落到"合理的临时语义"，由 2b/2c 收尾。

## 已核对的既有事实（勿重新发明）

- 后端 `FormulaCalculator`（`cpq-backend/.../quotation/service/FormulaCalculator.java`）：`findSubtotalFieldName`（`:797`，返第一个）；`computeTabSubtotal`（`:330`，求第一个小计列之和）；`computeRows`（`:355`，逐行求值核心）；`ZERO4` 常量已有。`new FormulaCalculator()` 可独立构造（Plan 1 已验证）。
- 后端 `ComponentService.validateXxx`（`:433-441`）：`if (subtotalCount > 1) throw new BusinessException("At most one field can have is_subtotal=true")`。
- 后端 `CardSnapshotService`（`:692-707` PASS1）：逐 NORMAL tab 调 `computeTabSubtotal` → `componentSubtotals.put(cid/code/tabName, sub)`，供 cross_tab_ref 与 SUBTOTAL formula 引用。
- 前端 `QuotationStep2.tsx`：`computeTabSubtotal`（`:782`，`const subtotalField = comp.fields.find(f => f.is_subtotal)` 求和）；`allComponentSubtotals` 构建（`:1427-1445`）；`computeProductSubtotal`（`:818-890`，NORMAL 组件单值 → SUBTOTAL 组件 formula / fallback sum）；编辑 footer（`:2049-2073`，`fields.map` 已逐 `is_subtotal` 渲染格，值取 `allComponentSubtotals[code] ?? [tabName]`）；产品小计 bar（`:2092-2102`）。
- 前端 `ReadonlyProductCard.tsx`：`compSubtotals` 构建（`:275-303`）；详情 footer（`:595-612`，结构同上，值取 `compSubtotals[tabName]`）。
- 前端 `FieldConfigTable.tsx`：`handleSubtotalChange`（`:68-74`，`checked` 时 `is_subtotal: f.key===key` 把其余清掉 = 单选）；小计列 Checkbox（`:410-419`）。
- 前端 `types.ts:58` `is_subtotal?: boolean`（per-field，多选无需改类型）。
- 小计值底座的 per-column 键格式统一约定：**`` `${code}#${fieldName}` ``**（同时写 `` `${tabName}#${fieldName}` `` 与 `` `${componentId}#${fieldName}` `` 别名，与现有三键别名一致）。

## File Structure

- Modify 后端 `FormulaCalculator.java`：+ `findSubtotalFieldNames`、+ `computeTabSubtotalsByColumn`；`computeTabSubtotal` 改为委托求和。
- Modify 后端 `ComponentService.java`：删 `subtotalCount > 1` 限制。
- Modify 后端 `CardSnapshotService.java`：PASS1 追加 per-column 键。
- Modify 前端 `QuotationStep2.tsx`：+ `computeTabSubtotalsByColumn`；`computeTabSubtotal` 委托求和；`allComponentSubtotals` 加 per-column 键；footer 值源按列；产品小计 bar 改多行；`computeProductSubtotal` 保证 = 各线之和。
- Modify 前端 `ReadonlyProductCard.tsx`：`compSubtotals` 加 per-column 键；footer 值源按列。
- Modify 前端 `FieldConfigTable.tsx`：`handleSubtotalChange` 改多选。
- Test 后端 `FormulaCalculatorMultiSubtotalTest.java`（新，纯 JUnit）。
- Test 前端 `computeMultiSubtotal.test.ts`（新，vitest）。
- E2E：复用 `quotation-flow.spec.ts`。

---

## Task 1: 字段配置多选 + 后端放开校验

**Files:**
- Modify: `cpq-frontend/src/pages/component/FieldConfigTable.tsx`（`:68-74`）
- Modify: `cpq-backend/src/main/java/com/cpq/component/service/ComponentService.java`（`:433-441`）

- [ ] **Step 1: 前端 handleSubtotalChange 改多选**

把（`:68-74`）：

```tsx
  const handleSubtotalChange = (key: string, checked: boolean) => {
    if (checked) {
      onChange(fields.map((f) => ({ ...f, is_subtotal: f.key === key })));
    } else {
      updateField(key, { is_subtotal: false });
    }
  };
```

改为：

```tsx
  const handleSubtotalChange = (key: string, checked: boolean) => {
    // 多小计列：每个字段独立勾选，不再互斥（Plan 2-核心）。
    updateField(key, { is_subtotal: checked });
  };
```

- [ ] **Step 2: 后端删除"至多一个"限制**

把（`:438-441`）：

```java
        }
        if (subtotalCount > 1) {
            throw new BusinessException("At most one field can have is_subtotal=true");
        }
    }
```

改为（保留 `subtotalCount` 统计移除，避免 unused 警告 —— 一并删掉计数）：

```java
        }
        // 多小计列（Plan 2-核心）：不再限制 is_subtotal 数量，每个被标记字段各算一列总计。
    }
```

并删除上方 `:433-437` 的计数块：

```java
            // Count is_subtotal
            Object isSubtotal = field.get("is_subtotal");
            if (Boolean.TRUE.equals(isSubtotal) || "true".equals(String.valueOf(isSubtotal))) {
                subtotalCount++;
            }
```

以及该方法顶部 `int subtotalCount = 0;` 声明（grep 同方法内 `subtotalCount` 确认全部移除，避免编译错）。

- [ ] **Step 3: 验证**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json 2>&1 | tail -5`
Expected: 0 错误。
Run: `cd cpq-backend && ./mvnw -o -q compile 2>&1 | tail -5`
Expected: EXIT 0（无 `subtotalCount` 相关错误）。

- [ ] **Step 4: Commit**

```bash
git add cpq-frontend/src/pages/component/FieldConfigTable.tsx cpq-backend/src/main/java/com/cpq/component/service/ComponentService.java
git commit -m "feat(multi-subtotal): 字段配置多选 is_subtotal + 后端放开至多一个限制"
```

---

## Task 2: 后端按列小计 `computeTabSubtotalsByColumn`（TDD）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/FormulaCalculatorMultiSubtotalTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FormulaCalculatorMultiSubtotalTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final FormulaCalculator calc = new FormulaCalculator();

    private JsonNode j(String s) {
        try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    // 两个小计列：材料费 = 单价*数量；加工费 = 工时*费率。两行数据。
    private static final String FIELDS = """
        [ {"name":"单价","field_type":"INPUT_NUMBER"},
          {"name":"数量","field_type":"INPUT_NUMBER"},
          {"name":"工时","field_type":"INPUT_NUMBER"},
          {"name":"费率","field_type":"INPUT_NUMBER"},
          {"name":"材料费","field_type":"FORMULA","is_subtotal":true},
          {"name":"加工费","field_type":"FORMULA","is_subtotal":true} ]""";
    private static final String FORMULAS = """
        [ {"name":"材料费","expression":"单价*数量"},
          {"name":"加工费","expression":"工时*费率"} ]""";
    private static final String BASEROWS = """
        [ {"driverRow":{"单价":10,"数量":2,"工时":3,"费率":5}},
          {"driverRow":{"单价":4,"数量":5,"工时":1,"费率":7}} ]""";

    @Test
    void findSubtotalFieldNames_returnsAll() {
        List<String> names = calc.findSubtotalFieldNames(j(FIELDS));
        assertEquals(List.of("材料费", "加工费"), names);
    }

    @Test
    void computeTabSubtotalsByColumn_perColumnSums() {
        Map<String, BigDecimal> byCol = calc.computeTabSubtotalsByColumn(
            j(FIELDS), j(FORMULAS), null, null, j(BASEROWS), null, Map.of());
        // 材料费 = 10*2 + 4*5 = 40 ; 加工费 = 3*5 + 1*7 = 22
        assertEquals(0, byCol.get("材料费").compareTo(new BigDecimal("40")));
        assertEquals(0, byCol.get("加工费").compareTo(new BigDecimal("22")));
    }

    @Test
    void computeTabSubtotal_sumsAllSubtotalColumns() {
        BigDecimal sum = calc.computeTabSubtotal(
            j(FIELDS), j(FORMULAS), null, null, j(BASEROWS), null, Map.of());
        // 40 + 22 = 62
        assertEquals(0, sum.compareTo(new BigDecimal("62")));
    }

    @Test
    void singleSubtotal_backwardCompatible() {
        String oneSub = """
            [ {"name":"单价","field_type":"INPUT_NUMBER"},
              {"name":"数量","field_type":"INPUT_NUMBER"},
              {"name":"材料费","field_type":"FORMULA","is_subtotal":true} ]""";
        String f = "[ {\"name\":\"材料费\",\"expression\":\"单价*数量\"} ]";
        BigDecimal sum = calc.computeTabSubtotal(j(oneSub), j(f), null, null, j(BASEROWS), null, Map.of());
        assertEquals(0, sum.compareTo(new BigDecimal("40")), "单小计列 = 原行为");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -o test -Dtest=FormulaCalculatorMultiSubtotalTest 2>&1 | grep -E "cannot find|Tests run|BUILD" | head`
Expected: 编译失败 `cannot find symbol ... findSubtotalFieldNames / computeTabSubtotalsByColumn`。

- [ ] **Step 3: 实现 —— 加两个方法 + 重构 computeTabSubtotal**

在 `FormulaCalculator` 内，`findSubtotalFieldName`（`:797`）旁新增复数版：

```java
    /** 返回所有 is_subtotal 字段名（按字段顺序）。Plan 2-核心：多小计列。 */
    public List<String> findSubtotalFieldNames(JsonNode fields) {
        List<String> out = new ArrayList<>();
        if (fields == null || !fields.isArray()) return out;
        for (JsonNode f : fields) {
            boolean isSub = f.path("isSubtotal").asBoolean(false) || f.path("is_subtotal").asBoolean(false);
            if (isSub) out.add(fieldName(f));
        }
        return out;
    }

    /** 逐列求和：每个小计列 → 该列各行结果之和。Plan 2-核心。 */
    public java.util.Map<String, BigDecimal> computeTabSubtotalsByColumn(
            JsonNode fields, JsonNode formulas, JsonNode formulaAssignments,
            JsonNode rowKeyFields, JsonNode baseRows, JsonNode editRows,
            java.util.Map<String, Double> componentSubtotals) {
        java.util.Map<String, BigDecimal> out = new java.util.LinkedHashMap<>();
        List<String> subtotalFields = findSubtotalFieldNames(fields);
        if (subtotalFields.isEmpty()) return out;
        List<RowResult> rows = computeRows(fields, formulas, formulaAssignments, rowKeyFields, baseRows, editRows,
            componentSubtotals, new HashMap<>(), new HashMap<>(), Map.of());
        for (String sf : subtotalFields) {
            double sum = 0.0;
            for (RowResult rr : rows) {
                Double v = rr.formulaValues.get(sf);
                if (v != null) sum += v;
            }
            out.put(sf, BigDecimal.valueOf(sum).setScale(4, RoundingMode.HALF_UP));
        }
        return out;
    }
```

把现有 `computeTabSubtotal`（`:330-345`）改为委托求和（保持签名与所有调用方不变）：

```java
    public BigDecimal computeTabSubtotal(JsonNode fields, JsonNode formulas, JsonNode formulaAssignments,
                                         JsonNode rowKeyFields,
                                         JsonNode baseRows, JsonNode editRows,
                                         Map<String, Double> componentSubtotals) {
        java.util.Map<String, BigDecimal> byCol = computeTabSubtotalsByColumn(
            fields, formulas, formulaAssignments, rowKeyFields, baseRows, editRows, componentSubtotals);
        BigDecimal sum = ZERO4;
        for (BigDecimal v : byCol.values()) sum = sum.add(v);
        return sum.setScale(4, RoundingMode.HALF_UP);
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -o test -Dtest=FormulaCalculatorMultiSubtotalTest 2>&1 | grep -E "Tests run:|BUILD" | grep -v Shutdown | tail -4`
Expected: `Tests run: 4, Failures: 0` + `BUILD SUCCESS`。

- [ ] **Step 5: 回归既有公式测试（防委托重构破坏单小计列）**

Run: `cd cpq-backend && ./mvnw -o test -Dtest='FormulaCalculator*,CardSnapshot*' 2>&1 | grep -E "Tests run:|BUILD" | grep -v Shutdown | tail -6`
Expected: 全部 `Failures: 0` + `BUILD SUCCESS`。

- [ ] **Step 6: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java cpq-backend/src/test/java/com/cpq/quotation/service/FormulaCalculatorMultiSubtotalTest.java
git commit -m "feat(multi-subtotal): 后端 computeTabSubtotalsByColumn + computeTabSubtotal 委托求和"
```

---

## Task 3: 后端 snapshot PASS1 追加 per-column 键

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java`（`:692-707` PASS1）

- [ ] **Step 1: PASS1 循环内追加 per-column 键**

在 PASS1 循环（`computeTabSubtotal` 调用 + 三键 put 之后），追加按列计算与 per-column 键写入。把：

```java
            double sub = formulaCalculator.computeTabSubtotal(
                tab.path("fields"), tab.path("formulas"), tab.path("formula_assignments"),
                rkfByComp.get(cid), baseRows, editRows, componentSubtotals).doubleValue();
            if (!cid.isBlank()) componentSubtotals.put(cid, sub);
            String code = tab.path("componentCode").asText(null);
            if (code != null && !code.isBlank()) componentSubtotals.put(code, sub);
            componentSubtotals.put(tab.path("tabName").asText(""), sub);
```

改为：

```java
            java.util.Map<String, java.math.BigDecimal> byCol = formulaCalculator.computeTabSubtotalsByColumn(
                tab.path("fields"), tab.path("formulas"), tab.path("formula_assignments"),
                rkfByComp.get(cid), baseRows, editRows, componentSubtotals);
            double sub = 0.0;
            for (java.math.BigDecimal v : byCol.values()) sub += v.doubleValue();
            String code = tab.path("componentCode").asText(null);
            String tabName = tab.path("tabName").asText("");
            if (!cid.isBlank()) componentSubtotals.put(cid, sub);
            if (code != null && !code.isBlank()) componentSubtotals.put(code, sub);
            componentSubtotals.put(tabName, sub);
            // Plan 2-核心：per-column 键 `${key}#${列名}`，供按列引用/显示。
            for (java.util.Map.Entry<String, java.math.BigDecimal> e : byCol.entrySet()) {
                double cv = e.getValue().doubleValue();
                if (!cid.isBlank()) componentSubtotals.put(cid + "#" + e.getKey(), cv);
                if (code != null && !code.isBlank()) componentSubtotals.put(code + "#" + e.getKey(), cv);
                componentSubtotals.put(tabName + "#" + e.getKey(), cv);
            }
```

- [ ] **Step 2: 编译 + 回归**

Run: `cd cpq-backend && ./mvnw -o test -Dtest='CardSnapshot*,FormulaCalculator*' 2>&1 | grep -E "Tests run:|BUILD" | grep -v Shutdown | tail -6`
Expected: 全绿 `BUILD SUCCESS`。

- [ ] **Step 3: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java
git commit -m "feat(multi-subtotal): snapshot PASS1 追加 per-column 小计键"
```

---

## Task 4: 前端按列小计 + 底座 per-column 键（TDD）

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（`computeTabSubtotal :782`、`allComponentSubtotals :1427`）
- Test: `cpq-frontend/src/pages/quotation/computeMultiSubtotal.test.ts`（新）

> 注：`computeTabSubtotal` 当前是模块内 `function`（非 export）。为可单测，新增的 `computeTabSubtotalsByColumn` 用 `export function`，并让 `computeTabSubtotal` 调用它。

- [ ] **Step 1: 写失败测试**

```ts
import { describe, it, expect } from 'vitest';
import { computeTabSubtotalsByColumn } from './QuotationStep2';

const comp: any = {
  componentId: 'c1', componentCode: 'TOULIAO', tabName: '投料',
  fields: [
    { name: '单价', field_type: 'INPUT_NUMBER' },
    { name: '数量', field_type: 'INPUT_NUMBER' },
    { name: '工时', field_type: 'INPUT_NUMBER' },
    { name: '费率', field_type: 'INPUT_NUMBER' },
    { name: '材料费', field_type: 'FORMULA', is_subtotal: true, formula_name: '材料费' },
    { name: '加工费', field_type: 'FORMULA', is_subtotal: true, formula_name: '加工费' },
  ],
  formulas: [
    { name: '材料费', expression: '单价*数量' },
    { name: '加工费', expression: '工时*费率' },
  ],
  rows: [
    { 单价: 10, 数量: 2, 工时: 3, 费率: 5 },
    { 单价: 4, 数量: 5, 工时: 1, 费率: 7 },
  ],
};

describe('computeTabSubtotalsByColumn', () => {
  it('每个小计列各自求和', () => {
    const byCol = computeTabSubtotalsByColumn(comp);
    expect(byCol['材料费']).toBe(40); // 10*2 + 4*5
    expect(byCol['加工费']).toBe(22); // 3*5 + 1*7
  });

  it('单小计列向后兼容', () => {
    const one = { ...comp, fields: comp.fields.filter((f: any) => f.name !== '加工费') };
    const byCol = computeTabSubtotalsByColumn(one);
    expect(Object.keys(byCol)).toEqual(['材料费']);
    expect(byCol['材料费']).toBe(40);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/computeMultiSubtotal.test.ts 2>&1 | tail -15`
Expected: 失败 `computeTabSubtotalsByColumn is not a function` / 导入报错。

- [ ] **Step 3: 实现 —— 新增 export computeTabSubtotalsByColumn，computeTabSubtotal 委托**

在 `QuotationStep2.tsx` 把 `computeTabSubtotal`（`:782-815`）重构为：先抽出按列函数，再让原函数求和。新增（紧邻 `:782` 之上）：

```tsx
/** 按列求和：每个 is_subtotal 列 → 该列各行结果之和。Plan 2-核心多小计列。 */
export function computeTabSubtotalsByColumn(
  comp: ComponentDataItem,
  allComponentSubtotals?: Record<string, number>,
  quotationFields?: Record<string, number>,
  pathCache?: Record<string, number>,
  partNo?: string,
  driverExpansion?: import('./useDriverExpansions').DriverExpansion,
  globalVariableDefs?: Record<string, GlobalVariableDefinition>,
): Record<string, number> {
  const out: Record<string, number> = {};
  if (!comp?.fields || !comp?.rows) return out;
  const subtotalFields = comp.fields.filter(f => f.is_subtotal);
  if (subtotalFields.length === 0) return out;
  for (const sf of subtotalFields) out[sf.name] = 0;
  const s = splitRows(comp, driverExpansion as any);
  for (let i = 0; i < s.totalRows; i++) {
    const ra = rowAt(i, comp, s);
    const row = fillFixedDefaults(comp.fields, ra.row);
    const basicDataValues = ra.expIndex >= 0 ? driverExpansion!.rows[ra.expIndex]?.basicDataValues : undefined;
    const cache = computeAllFormulas(
      comp, row, allComponentSubtotals, quotationFields, pathCache, partNo, basicDataValues,
      undefined, globalVariableDefs,
    );
    for (const sf of subtotalFields) out[sf.name] += cache[sf.name] ?? 0;
  }
  return out;
}
```

把原 `computeTabSubtotal` 函数体改为委托求和（保留签名 + 所有调用方）：

```tsx
function computeTabSubtotal(
  comp: ComponentDataItem,
  allComponentSubtotals?: Record<string, number>,
  quotationFields?: Record<string, number>,
  pathCache?: Record<string, number>,
  partNo?: string,
  driverExpansion?: import('./useDriverExpansions').DriverExpansion,
  globalVariableDefs?: Record<string, GlobalVariableDefinition>,
): number {
  const byCol = computeTabSubtotalsByColumn(
    comp, allComponentSubtotals, quotationFields, pathCache, partNo, driverExpansion, globalVariableDefs);
  let sum = 0;
  for (const v of Object.values(byCol)) sum += v;
  return sum;
}
```

- [ ] **Step 4: `allComponentSubtotals` 构建追加 per-column 键**

把（`:1438-1444`）：

```tsx
    const subtotal = computeTabSubtotal(
      comp, allComponentSubtotals, undefined, undefined, item.productPartNo, expansion,
      globalVariableDefs,
    );
    if (comp.componentId) allComponentSubtotals[comp.componentId] = subtotal;
    if (comp.componentCode) allComponentSubtotals[comp.componentCode] = subtotal;
    allComponentSubtotals[comp.tabName] = subtotal;
```

改为：

```tsx
    const byCol = computeTabSubtotalsByColumn(
      comp, allComponentSubtotals, undefined, undefined, item.productPartNo, expansion,
      globalVariableDefs,
    );
    const subtotal = Object.values(byCol).reduce((s, v) => s + v, 0);
    if (comp.componentId) allComponentSubtotals[comp.componentId] = subtotal;
    if (comp.componentCode) allComponentSubtotals[comp.componentCode] = subtotal;
    allComponentSubtotals[comp.tabName] = subtotal;
    // Plan 2-核心：per-column 键，供 footer 按列显示 + 按列引用。
    for (const [colName, colVal] of Object.entries(byCol)) {
      if (comp.componentId) allComponentSubtotals[`${comp.componentId}#${colName}`] = colVal;
      if (comp.componentCode) allComponentSubtotals[`${comp.componentCode}#${colName}`] = colVal;
      allComponentSubtotals[`${comp.tabName}#${colName}`] = colVal;
    }
```

- [ ] **Step 5: 跑测试确认通过 + tsc**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/computeMultiSubtotal.test.ts 2>&1 | tail -8`
Expected: 2 passed。
Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json 2>&1 | tail -5`
Expected: 0 错误。

- [ ] **Step 6: Commit**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx cpq-frontend/src/pages/quotation/computeMultiSubtotal.test.ts
git commit -m "feat(multi-subtotal): 前端 computeTabSubtotalsByColumn + allComponentSubtotals per-column 键"
```

---

## Task 5: 编辑视图 footer 按列显示 + 产品小计 bar 多行

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（footer `:2054-2068`、产品小计 bar `:2092-2102`）

- [ ] **Step 1: footer 值源改按列**

把（`:2055-2058`）：

```tsx
                        if (field.is_subtotal) {
                          const tabSubtotal = allComponentSubtotals[activeComponent.componentCode]
                            ?? allComponentSubtotals[activeComponent.tabName]
                            ?? 0;
```

改为：

```tsx
                        if (field.is_subtotal) {
                          // Plan 2-核心：按列取本列总计，回退到组件级（单小计列）兼容。
                          const tabSubtotal =
                            allComponentSubtotals[`${activeComponent.componentCode}#${field.name}`]
                            ?? allComponentSubtotals[`${activeComponent.tabName}#${field.name}`]
                            ?? allComponentSubtotals[activeComponent.componentCode]
                            ?? allComponentSubtotals[activeComponent.tabName]
                            ?? 0;
```

- [ ] **Step 2: 产品小计 bar 改多行（每条 (组件,小计列) 一行 + 最终总价）**

把（`:2092-2102`）：

```tsx
      {/* Subtotal Bar */}
      <div className="qt-subtotal-bar">
        <span className="qt-subtotal-label">产品小计</span>
        <span className="qt-subtotal-value">
          {formatCurrency(
            item.componentData.length > 0
              ? computeProductSubtotal(item, driverExpansions, customerId)
              : item.subtotal
          )}
        </span>
      </div>
```

改为：

```tsx
      {/* Subtotal Bar：多小计列 → 每条 (组件·小计列) 一行 + 最终总价（Plan 2-核心）。 */}
      <div className="qt-subtotal-bar-multi">
        {item.componentData.length > 0 && (() => {
          const lines: { label: string; value: number }[] = [];
          for (const comp of item.componentData) {
            if (!comp?.fields || comp.componentType === 'SUBTOTAL') continue;
            for (const f of comp.fields) {
              if (!f.is_subtotal) continue;
              const v = allComponentSubtotals[`${comp.componentCode}#${f.name}`]
                ?? allComponentSubtotals[`${comp.tabName}#${f.name}`] ?? 0;
              lines.push({ label: `${comp.tabName} · ${f.name}`, value: v });
            }
          }
          return lines.map((ln, i) => (
            <div className="qt-subtotal-line" key={i}>
              <span className="qt-subtotal-label">{ln.label}</span>
              <span className="qt-subtotal-value">{formatCurrency(ln.value)}</span>
            </div>
          ));
        })()}
        <div className="qt-subtotal-line qt-subtotal-total">
          <span className="qt-subtotal-label">产品小计</span>
          <span className="qt-subtotal-value">
            {formatCurrency(
              item.componentData.length > 0
                ? computeProductSubtotal(item, driverExpansions, customerId)
                : item.subtotal
            )}
          </span>
        </div>
      </div>
```

> `allComponentSubtotals` 在该 ProductCard 渲染作用域已存在（`:1427` 构建）。`qt-subtotal-bar-multi` / `qt-subtotal-line` / `qt-subtotal-total` 样式见 Step 3。

- [ ] **Step 3: 加最小样式**

在 `cpq-frontend/src/pages/quotation/`（与 QuotationStep2 同目录的 css，grep `qt-subtotal-bar` 找到现有样式文件）追加：

```css
.qt-subtotal-bar-multi { padding: 8px 16px; border-top: 1px solid #f0f0f0; }
.qt-subtotal-line { display: flex; justify-content: space-between; padding: 2px 0; color: #666; }
.qt-subtotal-line.qt-subtotal-total { margin-top: 4px; padding-top: 6px; border-top: 1px dashed #d9d9d9; font-weight: 600; color: #000; }
```

- [ ] **Step 4: 验证（tsc + Vite transform 200）**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json 2>&1 | tail -5`
Expected: 0 错误。
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/QuotationStep2.tsx`
Expected: `200`（若 dev server 未起，先 `cd cpq-frontend && npm run dev` 后台起）。

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx cpq-frontend/src/pages/quotation/*.css
git commit -m "feat(multi-subtotal): 编辑视图 footer 按列 + 产品小计 bar 多行"
```

---

## Task 6: 详情视图 footer 按列（ReadonlyProductCard，AP-50 同步）

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx`（`compSubtotals` `:275-303`、footer `:598-604`）

> AP-50：详情/只读视图必须与编辑视图同步，否则僵尸数据掩盖。

- [ ] **Step 1: `compSubtotals` 构建追加 per-column 键**

定位（`:297-303`）的 `computeComponentSubtotal`（详情侧按字段求和）调用与三键 put。把该处单值 `st` 的写入扩展为 per-column。**先读 `:285-303` 确认 `computeComponentSubtotal` 的实际函数名/签名**（详情侧等价于 `computeTabSubtotal`），然后：
- 若详情侧已有等价"按字段求和"逻辑：抽出 per-column 版（仿 Task 4 Step 3，对 `comp.rows` 逐行调 `computeReadonlyFormulas`/对应函数，按 `comp.fields.filter(f=>f.is_subtotal)` 累加），返回 `Record<colName, number>`；`st = sum(byCol)`；并追加：

```tsx
    compSubtotals[comp.tabName] = st;
    if (comp.componentCode) compSubtotals[comp.componentCode] = st;
    for (const [colName, colVal] of Object.entries(byCol)) {
      compSubtotals[`${comp.tabName}#${colName}`] = colVal;
      if (comp.componentCode) compSubtotals[`${comp.componentCode}#${colName}`] = colVal;
    }
```

- [ ] **Step 2: footer 值源改按列**

把（`:599-604`）：

```tsx
                        if (field.is_subtotal) {
                          return (
                            <td key={fi} className="qt-subtotal-cell">
                              {formatCurrency(compSubtotals[activeComp.tabName] || 0)}
                            </td>
                          );
                        }
```

改为：

```tsx
                        if (field.is_subtotal) {
                          const v = compSubtotals[`${activeComp.componentCode}#${field.name}`]
                            ?? compSubtotals[`${activeComp.tabName}#${field.name}`]
                            ?? compSubtotals[activeComp.tabName] ?? 0;
                          return (
                            <td key={fi} className="qt-subtotal-cell">
                              {formatCurrency(v)}
                            </td>
                          );
                        }
```

- [ ] **Step 3: 验证**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json 2>&1 | tail -5`
Expected: 0 错误。
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/ReadonlyProductCard.tsx`
Expected: `200`。

- [ ] **Step 4: Commit**

```bash
git add cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx
git commit -m "feat(multi-subtotal): 详情视图 footer 按列显示(AP-50 同步)"
```

---

## Task 7: 验证 `computeProductSubtotal` = 各小计线之和（回归 + 断言）

**Files:**
- Inspect/Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（`computeProductSubtotal` `:818-890`）
- Test: 追加到 `cpq-frontend/src/pages/quotation/computeMultiSubtotal.test.ts`

**背景：** `computeProductSubtotal` 现有三档：① SUBTOTAL 组件 formula ② legacy `subtotalFormula` ③ fallback = `Σ componentSubtotals`。本 Plan 把 `componentSubtotals[code]` 改为"各小计列之和"，故 fallback ③ 自动 = 各线之和（A1）。① ② 引用组件 code 也自动拿到"各列之和"（单列不变；多列 = 合计）。**本 Task 仅加测试锁定该不变量，不改逻辑**（除非测试暴露问题）。

- [ ] **Step 1: 加测试**

```ts
import { computeProductSubtotal } from './QuotationStep2'; // 若未 export，则改为 export

it('多小计列：产品小计 = 各列之和(fallback)', () => {
  const item: any = {
    productPartNo: 'P1',
    componentData: [{
      componentId: 'c1', componentCode: 'TOULIAO', tabName: '投料', componentType: 'NORMAL',
      fields: [
        { name: '单价', field_type: 'INPUT_NUMBER' }, { name: '数量', field_type: 'INPUT_NUMBER' },
        { name: '工时', field_type: 'INPUT_NUMBER' }, { name: '费率', field_type: 'INPUT_NUMBER' },
        { name: '材料费', field_type: 'FORMULA', is_subtotal: true, formula_name: '材料费' },
        { name: '加工费', field_type: 'FORMULA', is_subtotal: true, formula_name: '加工费' },
      ],
      formulas: [ { name: '材料费', expression: '单价*数量' }, { name: '加工费', expression: '工时*费率' } ],
      rows: [ { 单价: 10, 数量: 2, 工时: 3, 费率: 5 }, { 单价: 4, 数量: 5, 工时: 1, 费率: 7 } ],
    }],
    productAttributes: [], productAttributeValues: {},
  };
  // 40 + 22 = 62
  expect(computeProductSubtotal(item)).toBe(62);
});
```

- [ ] **Step 2: 若 `computeProductSubtotal` 未 export，加 `export`**

`function computeProductSubtotal(` → `export function computeProductSubtotal(`（仅加关键字，签名不变）。

- [ ] **Step 3: 跑测试**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/computeMultiSubtotal.test.ts 2>&1 | tail -8`
Expected: 全部 passed（含本 Task 的 62 断言）。若失败说明 fallback 被 ①/② 抢先返回错值 → 检查测试 item 是否含 SUBTOTAL 组件/subtotalFormula（本测试故意不含，应走 fallback ③）。

- [ ] **Step 4: Commit**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx cpq-frontend/src/pages/quotation/computeMultiSubtotal.test.ts
git commit -m "test(multi-subtotal): 锁定产品小计=各小计列之和不变量"
```

---

## Task 8: AP-44 census 核对 + E2E + 自检

**Files:**
- 验证：`docs/E2E测试方法.md` 流程；E2E `cpq-frontend/e2e/quotation-flow.spec.ts`

- [ ] **Step 1: AP-44 census 核对（确认无遗漏 find→filter）**

Run: `grep -rniE "find\(.*is_subtotal\)|find\(.*isSubtotal\)" cpq-frontend/src --include=*.tsx --include=*.ts`
逐条确认每个 `find(f=>f.is_subtotal)` 命中点的归属：
- `QuotationWizard:809` / `QuotationStep2:1834` → **保留**（previous_row_subtotal，属 2b，本 Plan 不动）。
- `ReadonlyProductCard:83/278/453` → 若是 previous_row_subtotal/取首小计名用途 → 保留（2b）；若是显示/求和用途 → 已在 Task 6 改。**逐一判定并在 commit message 记录每条归属**。
后端：`grep -rn "findSubtotalFieldName\b" cpq-backend/src/main` 确认单数版残留调用点的归属（previous_row_subtotal 在 `FormulaCalculator:415` 保留）。

- [ ] **Step 2: 跑 E2E（协议级改动强制）**

```bash
cd cpq-frontend
rm -f e2e/screenshots/qf-*.png
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list 2>&1 | tail -25
```
Expected: `1 passed`，`'加载中' final count = 0`，8 Tab `'加载中'=0`。（现有模板多为单小计列 → 回归证明零行为变化。）

- [ ] **Step 3: 手工验证多小计列（新建一个含 2 个小计列的组件）**

通过组件管理新建/改一个组件，勾两个 FORMULA 字段为小计，存模板 → 报价单加该产品 → 确认：
- 编辑视图 footer 两列各显示**不同**总计；产品小计 bar 列出两条线 + 合计 = 两线之和；
- 详情视图（提交后/只读）两列同样各显示独立总计；
- `POST /api/cpq/components/{id}/refresh-template-snapshots` 后，admin 查 snapshot 两个字段 `isSubtotal=true` 均在。

- [ ] **Step 4: 自检声明（CLAUDE.md 强制）**

形如：
> 前端 tsc 0 错误 ✅；`FormulaCalculatorMultiSubtotalTest` 4 + `computeMultiSubtotal.test.ts` 3 passed ✅；后端 `FormulaCalculator*/CardSnapshot*` 回归绿 ✅；E2E `quotation-flow` 1 passed + 加载中=0 ✅；多小计列组件编辑/详情两视图各列独立总计 + 产品小计=各线之和 ✅。

---

## Self-Review（写后自检）

**Spec coverage（设计 D / Q9=C·Q10=B·A1）：**
- 多小计列：Task 1（配置多选 + 放开校验）✅
- 每列各算总计：Task 2（后端按列）+ Task 4（前端按列）✅
- 各列独立成线（Q10=B 不跨组件归并）：Task 5（产品小计 bar 每条 (组件·列) 一行）✅
- 最终总价 = 各线之和（A1）：Task 7（锁定不变量）✅
- 显示（编辑 + 详情，AP-50）：Task 5 + Task 6 ✅
- snapshot 一致：Task 3 ✅

**Placeholder scan：** Task 6 Step 1 含"先读 `:285-303` 确认函数名"——非占位，是详情侧函数名核对点（详情侧小计函数名本 Plan 未逐字读取，给了明确核对手段 + 仿 Task 4 的精确做法）。其余步骤均有完整代码。

**Type consistency：** per-column 键格式全程统一 `` `${code|tabName|componentId}#${fieldName}` ``；`computeTabSubtotalsByColumn` 前后端签名一致（返回 列名→数值 映射）；`computeTabSubtotal` 委托求和后签名与所有调用方不变。

**边界一致：** previous_row_subtotal（2b）与 `[页签#SUBTOTAL]`（2c）明确不在本 Plan；Task 8 Step 1 census 强制逐条判定 find→filter 归属，防误改 2b 范畴。

**风险登记：**
- `computeTabSubtotal` 委托重构后，`componentSubtotals[code]` 语义由"那个小计"变"各列之和"——单小计列不变；多小计列下 `[页签#SUBTOTAL]`/SUBTOTAL 组件 formula 引用该 code 得到合计（临时语义，2c 收尾）。已在边界声明。
- 详情侧 `compSubtotals` 构建函数名未逐字读取 → Task 6 Step 1 强制先读再仿写。
- 现有 `previous_row_subtotal` 若被非小计公式列引用，本 Plan 不改其语义（仍指第一个小计列）；2b 才改"上一行本列"。
