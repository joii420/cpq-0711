# 单位换算（Unit Conversion）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给组件数值列配"单位来源字段"，公式/聚合计算时逐行把该列原值按硬编码预设表归一到 KG/PCS，明细输入列界面/落库仍显示原值。

**Architecture:** 一列配 `unit_source_field` 后，其"公式/聚合面取值 = canonical，展示/落库面 = 原值（输入列）/canonical（派生列）"。换算逻辑集中在**前后端各一个共享工具**（`UnitConversion`），在 6 个**持有 fields** 的物化点把 row 克隆后覆盖配换算列为 canonical；**绝不 mutate 原行**。无伴生键——下游按字段名读列即透明拿 canonical。设计依据见 spec `docs/superpowers/specs/2026-06-15-unit-conversion-design.md`（v5）。

**Tech Stack:** 后端 Java 17 + Quarkus + Jackson（`JsonNode` / `BigDecimal`）；前端 React + TS（Vitest）；E2E Playwright。

---

## File Structure

**新建：**
- `cpq-backend/src/main/java/com/cpq/engine/unit/UnitConversion.java` — 后端换算表 + 行换算工具
- `cpq-backend/src/test/java/com/cpq/engine/unit/UnitConversionTest.java` — 后端单测
- `cpq-frontend/src/utils/unitConversion.ts` — 前端换算表 + 行换算工具
- `cpq-frontend/src/utils/unitConversion.test.ts` — 前端单测（含跨端对拍 fixture）

**修改（每个是一个换算物化点 / 传播点）：**
- `cpq-backend/.../quotation/service/FormulaCalculator.java` — computeRows 换 mergedRow（点 3）
- `cpq-backend/.../quotation/service/ExcelViewService.java` — parseEffectiveRows 换输入列（点 4）
- `cpq-backend/.../quotation/service/CardSnapshotService.java` — backfillSubtotalsFromResolved 求和换（点 5）
- `cpq-frontend/src/pages/quotation/QuotationStep2.tsx` — computeAllFormulas 克隆换（点 1）+ computeNonSubtotalColumnSums（点 2）+ fieldsOverrideHash
- `cpq-frontend/src/pages/quotation/component/FieldConfigTable.tsx` — "单位换算来源" Select 配置 UI

> cross_tab（点 6）：源行由 computeRows / computeAllFormulas 产出，点 1/3 已覆盖；本计划在 Task 9/12 验证 cross_tab 一致性，不单列改动点（若验证暴露分叉再补）。

---

## Task 1: 后端换算工具 UnitConversion（表 + factorFor）

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/engine/unit/UnitConversion.java`
- Test: `cpq-backend/src/test/java/com/cpq/engine/unit/UnitConversionTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.cpq.engine.unit;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class UnitConversionTest {

    @Test
    void factorFor_allPresetUnits() {
        assertEquals(0, new BigDecimal("0.001").compareTo(UnitConversion.factorFor("克")));
        assertEquals(0, new BigDecimal("0.001").compareTo(UnitConversion.factorFor("g")));
        assertEquals(0, new BigDecimal("0.001").compareTo(UnitConversion.factorFor("G")));
        assertEquals(0, BigDecimal.ONE.compareTo(UnitConversion.factorFor("千克")));
        assertEquals(0, BigDecimal.ONE.compareTo(UnitConversion.factorFor("KG")));
        assertEquals(0, BigDecimal.ONE.compareTo(UnitConversion.factorFor("kG")));
        assertEquals(0, new BigDecimal("1000").compareTo(UnitConversion.factorFor("吨")));
        assertEquals(0, new BigDecimal("1000").compareTo(UnitConversion.factorFor("t")));
        assertEquals(0, BigDecimal.ONE.compareTo(UnitConversion.factorFor("片")));
        assertEquals(0, BigDecimal.ONE.compareTo(UnitConversion.factorFor("pcs")));
        assertEquals(0, new BigDecimal("1000").compareTo(UnitConversion.factorFor("KPCS")));
        assertEquals(0, new BigDecimal("1000").compareTo(UnitConversion.factorFor("千片")));
        assertEquals(0, new BigDecimal("0.001").compareTo(UnitConversion.factorFor("g/PCS")));
        assertEquals(0, new BigDecimal("0.001").compareTo(UnitConversion.factorFor("G/pcs")));
    }

    @Test
    void factorFor_unknownOrBlank_returnsOne() {
        assertEquals(0, BigDecimal.ONE.compareTo(UnitConversion.factorFor("mm")));
        assertEquals(0, BigDecimal.ONE.compareTo(UnitConversion.factorFor("")));
        assertEquals(0, BigDecimal.ONE.compareTo(UnitConversion.factorFor(null)));
        assertEquals(0, BigDecimal.ONE.compareTo(UnitConversion.factorFor("  ")));
    }

    @Test
    void factorFor_normalizesWhitespaceAndCase() {
        assertEquals(0, new BigDecimal("0.001").compareTo(UnitConversion.factorFor(" g / PCS ")));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw test -Dtest=UnitConversionTest -q`
Expected: 编译失败 / `UnitConversion` 不存在。

- [ ] **Step 3: 实现 UnitConversion（表 + 归一化 + factorFor）**

```java
package com.cpq.engine.unit;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 单位换算预设表（硬编码）。把列原值按同行单位文本归一到 KG / PCS。
 * 前端镜像见 cpq-frontend/src/utils/unitConversion.ts，两端由对拍测试守一致。
 * 设计见 docs/superpowers/specs/2026-06-15-unit-conversion-design.md §3。
 */
public final class UnitConversion {

    private UnitConversion() {}

    /** 归一化后的单位 token → 对 C 原值的系数。 */
    private static final Map<String, BigDecimal> FACTORS = Map.ofEntries(
        Map.entry("克", new BigDecimal("0.001")),
        Map.entry("G", new BigDecimal("0.001")),
        Map.entry("千克", BigDecimal.ONE),
        Map.entry("KG", BigDecimal.ONE),
        Map.entry("吨", new BigDecimal("1000")),
        Map.entry("T", new BigDecimal("1000")),
        Map.entry("片", BigDecimal.ONE),
        Map.entry("PCS", BigDecimal.ONE),
        Map.entry("KPCS", new BigDecimal("1000")),
        Map.entry("千片", new BigDecimal("1000")),
        Map.entry("G/PCS", new BigDecimal("0.001"))
    );

    /** 归一化：trim → 去所有内部空格 → 转大写（中文别名原样保留，已在表中）。 */
    static String normalize(String unitText) {
        if (unitText == null) return "";
        String s = unitText.trim().replaceAll("\\s+", "");
        return s.toUpperCase();
    }

    /** 单位 → 系数；未知 / 空 → 1（原值透传）。 */
    public static BigDecimal factorFor(String unitText) {
        String key = normalize(unitText);
        if (key.isEmpty()) return BigDecimal.ONE;
        return FACTORS.getOrDefault(key, BigDecimal.ONE);
    }
}
```

> 注意：`normalize` 转大写后，中文别名（克/千克/吨/片/千片）不受影响；`g/PCS` → `G/PCS` 命中。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw test -Dtest=UnitConversionTest -q`
Expected: PASS（3 tests）。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/engine/unit/UnitConversion.java \
        cpq-backend/src/test/java/com/cpq/engine/unit/UnitConversionTest.java
git commit -m "feat(unit): 后端单位换算预设表 factorFor (TDD)"
```

---

## Task 2: 后端行换算工具 convertNodeRow / convertObjectRow

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/engine/unit/UnitConversion.java`
- Test: `cpq-backend/src/test/java/com/cpq/engine/unit/UnitConversionTest.java`

> 集中"找配换算列、读同行 D、乘系数"的行换算逻辑，供 computeRows（`Map<String,JsonNode>`）与 parseEffectiveRows/小计（`Map<String,Object>`）复用。

- [ ] **Step 1: 加失败测试**

```java
    // 追加 import
    // import com.fasterxml.jackson.databind.ObjectMapper;
    // import com.fasterxml.jackson.databind.JsonNode;
    // import com.fasterxml.jackson.databind.node.*;
    // import java.util.*;

    private static final com.fasterxml.jackson.databind.ObjectMapper M =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private com.fasterxml.jackson.databind.JsonNode fieldsJson() throws Exception {
        // 字段 重量(配 unit_source_field=单位) + 单位(文本) + 普通列 数量
        return M.readTree("[" +
            "{\"name\":\"重量\",\"field_type\":\"INPUT_NUMBER\",\"unit_source_field\":\"单位\"}," +
            "{\"name\":\"单位\",\"field_type\":\"INPUT_TEXT\"}," +
            "{\"name\":\"数量\",\"field_type\":\"INPUT_NUMBER\"}]");
    }

    @Test
    void convertObjectRow_convertsConfiguredColumnByRowUnit() throws Exception {
        Map<String,Object> row = new HashMap<>();
        row.put("重量", "500"); row.put("单位", "g"); row.put("数量", 3);
        Map<String,Object> out = UnitConversion.convertObjectRow(fieldsJson(), row);
        assertEquals(0, new BigDecimal("0.5").compareTo(new BigDecimal(out.get("重量").toString())));
        assertEquals("g", out.get("单位"));   // D 原样
        assertEquals(3, ((Number) out.get("数量")).intValue()); // 未配列原样
        assertEquals("500", row.get("重量"));  // 原 row 未被 mutate
    }

    @Test
    void convertObjectRow_unknownUnit_passthrough() throws Exception {
        Map<String,Object> row = new HashMap<>();
        row.put("重量", "500"); row.put("单位", "mm");
        Map<String,Object> out = UnitConversion.convertObjectRow(fieldsJson(), row);
        assertEquals(0, new BigDecimal("500").compareTo(new BigDecimal(out.get("重量").toString())));
    }

    @Test
    void convertNodeRow_convertsConfiguredColumn() throws Exception {
        Map<String,com.fasterxml.jackson.databind.JsonNode> row = new HashMap<>();
        row.put("重量", new com.fasterxml.jackson.databind.node.TextNode("2"));
        row.put("单位", new com.fasterxml.jackson.databind.node.TextNode("吨"));
        Map<String,com.fasterxml.jackson.databind.JsonNode> out =
            UnitConversion.convertNodeRow(fieldsJson(), row);
        assertEquals(0, new BigDecimal("2000").compareTo(out.get("重量").decimalValue()));
        assertEquals("吨", out.get("单位").asText());
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw test -Dtest=UnitConversionTest -q`
Expected: 编译失败（`convertObjectRow` / `convertNodeRow` 不存在）。

- [ ] **Step 3: 实现两个行换算方法 + 内部小工具**

在 `UnitConversion` 类内追加：

```java
    // 追加 imports（文件顶部）
    // import com.fasterxml.jackson.databind.JsonNode;
    // import com.fasterxml.jackson.databind.node.DecimalNode;
    // import java.util.HashMap;
    // import java.util.LinkedHashMap;
    // import java.util.Map;

    /** 字段取值键：name 优先，回退 key（与各引擎 fieldName 口径一致）。 */
    private static String fieldKey(JsonNode f) {
        String n = f.path("name").asText(null);
        if (n != null && !n.isBlank()) return n;
        return f.path("key").asText(null);
    }

    /** 解析 (字段名 → 单位来源字段名) 仅含配了 unit_source_field 的列。 */
    private static Map<String, String> configuredColumns(JsonNode fields) {
        Map<String, String> m = new HashMap<>();
        if (fields == null || !fields.isArray()) return m;
        for (JsonNode f : fields) {
            String usf = f.path("unit_source_field").asText(null);
            if (usf == null || usf.isBlank()) continue;
            String c = fieldKey(f);
            if (c != null && !c.isBlank()) m.put(c, usf);
        }
        return m;
    }

    private static BigDecimal toBig(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        try { return new BigDecimal(v.toString().trim()); } catch (Exception e) { return null; }
    }

    /**
     * 返回换算后**新** Object 行（原 row 不变）。配换算列 C → rawC × factorFor(同行 D 文本)。
     * 用于 parseEffectiveRows / backfillSubtotalsFromResolved 等 Object 行。
     */
    public static Map<String, Object> convertObjectRow(JsonNode fields, Map<String, Object> row) {
        Map<String, String> cols = configuredColumns(fields);
        if (cols.isEmpty() || row == null) return row;
        Map<String, Object> out = new LinkedHashMap<>(row);
        for (Map.Entry<String, String> e : cols.entrySet()) {
            String c = e.getKey(), d = e.getValue();
            BigDecimal raw = toBig(row.get(c));
            if (raw == null) continue;
            Object dv = row.get(d);
            BigDecimal factor = factorFor(dv == null ? null : dv.toString());
            out.put(c, raw.multiply(factor));
        }
        return out;
    }

    /**
     * 返回换算后**新** JsonNode 行（原 mergedRow 不变）。用于 FormulaCalculator.computeRows。
     */
    public static Map<String, JsonNode> convertNodeRow(JsonNode fields, Map<String, JsonNode> mergedRow) {
        Map<String, String> cols = configuredColumns(fields);
        if (cols.isEmpty() || mergedRow == null) return mergedRow;
        Map<String, JsonNode> out = new LinkedHashMap<>(mergedRow);
        for (Map.Entry<String, String> e : cols.entrySet()) {
            String c = e.getKey(), d = e.getValue();
            JsonNode cn = mergedRow.get(c);
            if (cn == null || cn.isNull()) continue;
            BigDecimal raw = toBig(cn.isNumber() ? cn.numberValue() : cn.asText());
            if (raw == null) continue;
            JsonNode dn = mergedRow.get(d);
            BigDecimal factor = factorFor(dn == null ? null : dn.asText());
            out.put(c, com.fasterxml.jackson.databind.node.DecimalNode.valueOf(raw.multiply(factor)));
        }
        return out;
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw test -Dtest=UnitConversionTest -q`
Expected: PASS（6 tests）。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/engine/unit/UnitConversion.java \
        cpq-backend/src/test/java/com/cpq/engine/unit/UnitConversionTest.java
git commit -m "feat(unit): 后端行换算 convertObjectRow/convertNodeRow (TDD)"
```

---

## Task 3: 前端换算工具 unitConversion.ts（表 + 行换算 + 对拍 fixture）

**Files:**
- Create: `cpq-frontend/src/utils/unitConversion.ts`
- Test: `cpq-frontend/src/utils/unitConversion.test.ts`

- [ ] **Step 1: 写失败测试（含与后端同一组对拍断言）**

```typescript
import { describe, it, expect } from 'vitest';
import { factorFor, applyUnitConversion } from './unitConversion';

describe('factorFor', () => {
  // 与后端 UnitConversionTest 同一组输入（跨端对拍护栏）
  const cases: [string, number][] = [
    ['克', 0.001], ['g', 0.001], ['G', 0.001],
    ['千克', 1], ['KG', 1], ['kG', 1],
    ['吨', 1000], ['t', 1000],
    ['片', 1], ['pcs', 1],
    ['KPCS', 1000], ['千片', 1000],
    ['g/PCS', 0.001], ['G/pcs', 0.001],
    [' g / PCS ', 0.001],
    ['mm', 1], ['', 1], ['  ', 1],
  ];
  it.each(cases)('factorFor(%s) = %d', (unit, expected) => {
    expect(factorFor(unit)).toBeCloseTo(expected, 10);
  });
  it('null/undefined → 1', () => {
    expect(factorFor(undefined)).toBe(1);
    expect(factorFor(null as any)).toBe(1);
  });
});

describe('applyUnitConversion', () => {
  const fields = [
    { name: '重量', field_type: 'INPUT_NUMBER', unit_source_field: '单位' },
    { name: '单位', field_type: 'INPUT_TEXT' },
    { name: '数量', field_type: 'INPUT_NUMBER' },
  ];
  it('换配置列、保留 D 与未配列、不 mutate 原行', () => {
    const row = { 重量: '500', 单位: 'g', 数量: 3 };
    const out = applyUnitConversion(fields as any, row);
    expect(out.重量).toBeCloseTo(0.5, 10);
    expect(out.单位).toBe('g');
    expect(out.数量).toBe(3);
    expect(row.重量).toBe('500');   // 原行未被 mutate
    expect(out).not.toBe(row);       // 返回新对象
  });
  it('未知单位透传', () => {
    const out = applyUnitConversion(fields as any, { 重量: '500', 单位: 'mm' });
    expect(out.重量).toBeCloseTo(500, 10);
  });
  it('无配置列时原样返回', () => {
    const row = { a: 1 };
    expect(applyUnitConversion([{ name: 'a', field_type: 'INPUT_NUMBER' }] as any, row)).toBe(row);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/utils/unitConversion.test.ts`
Expected: FAIL（模块不存在）。

- [ ] **Step 3: 实现 unitConversion.ts**

```typescript
// 单位换算预设表（硬编码）。后端镜像 com.cpq.engine.unit.UnitConversion，对拍测试守一致。
// 设计见 docs/superpowers/specs/2026-06-15-unit-conversion-design.md §3。

const FACTORS: Record<string, number> = {
  '克': 0.001, 'G': 0.001,
  '千克': 1, 'KG': 1,
  '吨': 1000, 'T': 1000,
  '片': 1, 'PCS': 1,
  'KPCS': 1000, '千片': 1000,
  'G/PCS': 0.001,
};

function normalize(unitText: string | null | undefined): string {
  if (unitText == null) return '';
  return String(unitText).trim().replace(/\s+/g, '').toUpperCase();
}

/** 单位 → 系数；未知 / 空 → 1（原值透传）。 */
export function factorFor(unitText: string | null | undefined): number {
  const key = normalize(unitText);
  if (key === '') return 1;
  return FACTORS[key] ?? 1;
}

type FieldLike = { name?: string; key?: string; unit_source_field?: string };

function fieldKey(f: FieldLike): string {
  return f.name || f.key || '';
}

/**
 * 返回换算后**新**行（原 row 不变）。配 unit_source_field 的列 C → rawC × factorFor(同行 D)。
 * 无配置列时原样返回原对象（零开销 + 不破坏引用相等优化）。
 */
export function applyUnitConversion<T extends Record<string, any>>(
  fields: FieldLike[] | undefined,
  row: T,
): T {
  if (!fields || !row) return row;
  const configured: Array<[string, string]> = [];
  for (const f of fields) {
    const usf = f.unit_source_field;
    if (!usf) continue;
    const c = fieldKey(f);
    if (c) configured.push([c, usf]);
  }
  if (configured.length === 0) return row;
  const out: Record<string, any> = { ...row };
  for (const [c, d] of configured) {
    const raw = row[c];
    const num = typeof raw === 'number' ? raw : parseFloat(raw);
    if (raw == null || isNaN(num)) continue;
    out[c] = num * factorFor(row[d] == null ? '' : String(row[d]));
  }
  return out as T;
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/utils/unitConversion.test.ts`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add cpq-frontend/src/utils/unitConversion.ts cpq-frontend/src/utils/unitConversion.test.ts
git commit -m "feat(unit): 前端单位换算表+行换算+对拍fixture (TDD)"
```

---

## Task 4: 配置 UI — 字段加"单位换算来源" Select

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/component/FieldConfigTable.tsx`
- Modify: 字段类型声明处（先 grep 定位：见 Step 1）

- [ ] **Step 1: 定位字段类型声明并加 `unit_source_field`**

Run: `cd cpq-frontend && grep -rn "unit_source_field\|interface .*Field\|basic_data_path" src/pages/quotation/component/ src/types* 2>/dev/null | head`

在字段对象的 TS 接口（FieldConfigTable 里使用的 field 行类型，通常含 `field_type` / `name` / `key` / `basic_data_path`）加一行可选属性：

```typescript
  /** 单位换算来源：指向同组件内单位文本字段的 name；非空则该数值列在公式计算前按同行单位归一到 KG/PCS。 */
  unit_source_field?: string;
```

- [ ] **Step 2: 在 FieldConfigTable 加 Select 列/控件**

读 `FieldConfigTable.tsx` 现有列渲染方式（每行一个字段），新增一个"单位换算来源"列，渲染一个 Ant Design `Select`，选项 = 同组件内**除当前字段外**的字段（用其 name 作 value/label），允许清空：

```tsx
// 选项：同组件其它字段（可作单位来源）
const unitSourceOptions = fields
  .filter(f => (f.name || f.key) && (f.name || f.key) !== (record.name || record.key))
  .map(f => ({ label: f.name || f.key, value: f.name || f.key }));

<Select
  allowClear
  placeholder="无（不换算）"
  style={{ width: 160 }}
  value={record.unit_source_field}
  options={unitSourceOptions}
  onChange={(v) => updateField(record.key, { unit_source_field: v })}
/>
```

> `updateField` / `record` / `fields` 用本表既有的行更新机制与变量名（实现时对齐 FieldConfigTable 现有写法）。

- [ ] **Step 3: 编译自检**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误。
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/component/FieldConfigTable.tsx`
Expected: 200。

- [ ] **Step 4: 提交**

```bash
git add cpq-frontend/src/pages/quotation/component/FieldConfigTable.tsx <字段类型文件>
git commit -m "feat(unit): 组件字段配置加'单位换算来源'Select"
```

---

## Task 5: 后端物化点 3 — computeRows 换 mergedRow

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java:611`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/FormulaCalculatorUnitConversionTest.java`（新建）

- [ ] **Step 1: 写失败测试（直接测 computeRows 出口或 calculate）**

读 `FormulaCalculator` 现有测试（如 `FormulaCalculationTest` / 现成 calculate 测试）的构造方式，仿写一个：构造含 `重量(unit_source_field=单位)` + `单位` + `金额=重量×单价` 公式的 fields/formulas/baseRows（重量=500、单位=g、单价=2），断言 `金额 = 0.5×2 = 1.0`（而非 1000）。用与既有测试一致的入参组装。

```java
// 断言核心：金额列结果 = 换算后重量(0.5) × 单价(2) = 1.0
// （若不换算会得 500×2=1000）
assertEquals(1.0, 金额结果, 1e-6);
```

- [ ] **Step 2: 跑确认失败**

Run: `cd cpq-backend && ./mvnw test -Dtest=FormulaCalculatorUnitConversionTest -q`
Expected: FAIL（得 1000，未换算）。

- [ ] **Step 3: 在 computeRows 的 mergedRow 后插入换算**

`FormulaCalculator.java:611` 现状：

```java
            // mergedRow = driverRow + editRows（编辑覆盖）
            Map<String, JsonNode> mergedRow = mergeRow(driverRow, editValues);
```

改为（在其后立即换算，后续 collectFieldValues + toRawRowMap 都用换算后的 mergedRow）：

```java
            // mergedRow = driverRow + editRows（编辑覆盖）
            Map<String, JsonNode> mergedRow = mergeRow(driverRow, editValues);
            // 单位换算（物化点3）：配 unit_source_field 的列在喂公式前按同行单位归一到 KG/PCS。
            // 在 collectFieldValues / toRawRowMap 之前，使 fieldValues 与 currentRowRaw 同口径。
            mergedRow = com.cpq.engine.unit.UnitConversion.convertNodeRow(fields, mergedRow);
```

> 约束（spec §8）：被换算列不得同时是 rowKey/匹配键；computeRowKey 在 :589 已用原 driverRow 算键（在换算前），不受影响。

- [ ] **Step 4: 跑确认通过**

Run: `cd cpq-backend && ./mvnw test -Dtest=FormulaCalculatorUnitConversionTest -q`
Expected: PASS（金额=1.0）。

- [ ] **Step 5: 回归 + 提交**

Run: `cd cpq-backend && ./mvnw test -Dtest=FormulaCalculationTest,FormulaCalculatorUnitConversionTest -q`
Expected: PASS。

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java \
        cpq-backend/src/test/java/com/cpq/quotation/FormulaCalculatorUnitConversionTest.java
git commit -m "feat(unit): computeRows mergedRow 单位换算 (物化点3, TDD)"
```

---

## Task 6: 后端物化点 4 — parseEffectiveRows 换输入列

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java:279`
- Modify: `cpq-backend/.../quotation/service/card/CardEffectiveRows.java`（在 TabRows 装配后对每行换算）
- Test: `cpq-backend/src/test/java/com/cpq/quotation/ExcelViewUnitConversionTest.java`（新建）

> parseEffectiveRows 持有 `componentsSnapshot`（含 fields/unit_source_field）。最干净的换算点是 `CardEffectiveRows.parse` 装配每个 TabRows 后，按该 tab 的 fields 对 `rows` 逐行 `convertObjectRow`。

- [ ] **Step 1: 写失败测试**

构造一个含 `componentsSnapshot`（某 tab 字段 `重量 unit_source_field=单位` + `单位`）+ `cardValues`（resolvedRows 有 `重量=500,单位=g`）的输入，调用 `CardEffectiveRows.parse(cardValues, componentsSnapshot, cid->null)`，断言该 tab 的 `rows.get(0).get("重量")` 数值 = 0.5、`单位` 仍 = "g"。

- [ ] **Step 2: 跑确认失败**

Run: `cd cpq-backend && ./mvnw test -Dtest=ExcelViewUnitConversionTest -q`
Expected: FAIL（重量仍 500）。

- [ ] **Step 3: 在 CardEffectiveRows.parse 装配 TabRows 处换算**

读 `CardEffectiveRows.parse`（含 fields 的 4 参/3 参重载），在每个 tab 的 `rows`（`List<Map<String,Object>>`）构造完、放进 `TabRows` 之前，用该 tab 的 fields 数组逐行换算：

```java
// 该 tab 的 fields（从 componentsSnapshot 对应组件取，含 unit_source_field）
JsonNode tabFields = /* 该 tab 组件的 fields 节点 */;
List<Map<String,Object>> convertedRows = new ArrayList<>(rows.size());
for (Map<String,Object> r : rows) {
    convertedRows.add(com.cpq.engine.unit.UnitConversion.convertObjectRow(tabFields, r));
}
// 用 convertedRows 构造 TabRows
```

> 若 `parse` 内当前没有把每个 tab 的 fields 取出，按 componentsSnapshot 里组件↔tab 映射取该组件 fields（parse 已在用 componentsSnapshot，定位其 fields 字段即可）。注意只换 `rows` 的数值，不动 rowCount（AP-51）。

- [ ] **Step 4: 跑确认通过**

Run: `cd cpq-backend && ./mvnw test -Dtest=ExcelViewUnitConversionTest -q`
Expected: PASS。

- [ ] **Step 5: 回归 + 提交**

Run: `cd cpq-backend && ./mvnw test -Dtest='*CardEffectiveRows*,ExcelViewUnitConversionTest' -q`
Expected: PASS。

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/card/CardEffectiveRows.java \
        cpq-backend/src/test/java/com/cpq/quotation/ExcelViewUnitConversionTest.java
git commit -m "feat(unit): CardEffectiveRows 有效行单位换算 (物化点4, TDD)"
```

---

## Task 7: 后端物化点 5 — backfillSubtotalsFromResolved 求和换算

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java:1681`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/SubtotalUnitConversionTest.java`（新建）

> 小计列若是 FORMULA，其 resolvedRows 值已 canonical（Task 5）。但若 is_subtotal 列**直接是配换算的输入列**，resolvedRows 存原值，求和时需换。

- [ ] **Step 1: 写失败测试**

构造 fields（`重量 is_subtotal=true unit_source_field=单位` + `单位`）+ resolvedRows（两行：500/g、1000/g）+ 空 componentSubtotals map，调 `backfillSubtotalsFromResolved`，断言 `componentSubtotals.get(tabName+"#重量")` = 0.5+1.0 = 1.5（而非 1500）。

> 该方法 private——测试可用反射调用，或在测试同包新增一个 package-private 包装；若 CardSnapshotService 已有同款 private 方法测试先例，仿其做法。

- [ ] **Step 2: 跑确认失败**

Run: `cd cpq-backend && ./mvnw test -Dtest=SubtotalUnitConversionTest -q`
Expected: FAIL（得 1500）。

- [ ] **Step 3: 求和前对每行按 fields 换算**

`CardSnapshotService.java` `backfillSubtotalsFromResolved` 内层求和循环改为读换算后行。在 `for (String col : subtotalFields)` 外、方法入口处把 resolvedRows 预换一份（只读求和用，不回写落库）：

```java
        List<String> subtotalFields = formulaCalculator.findSubtotalFieldNames(fields);
        if (subtotalFields.isEmpty()) return;

        // 单位换算（物化点5）：求和用换算后行（canonical）；resolvedRows 本身（落库）保持原值不动。
        List<Map<String, Object>> rowsForSum = new ArrayList<>(resolvedRows.size());
        for (Map<String, Object> r : resolvedRows) {
            rowsForSum.add(com.cpq.engine.unit.UnitConversion.convertObjectRow(fields, r));
        }
```

然后把内层 `for (Map<String, Object> row : resolvedRows)` 改为 `for (Map<String, Object> row : rowsForSum)`。

> 关键（spec §9 / 评审 A2）：`resolvedRows`（落库）不动，只新建 `rowsForSum` 求和——满足"落库存原值、小计存 canonical"。

- [ ] **Step 4: 跑确认通过**

Run: `cd cpq-backend && ./mvnw test -Dtest=SubtotalUnitConversionTest -q`
Expected: PASS（1.5）。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java \
        cpq-backend/src/test/java/com/cpq/quotation/SubtotalUnitConversionTest.java
git commit -m "feat(unit): backfillSubtotalsFromResolved 求和单位换算 (物化点5, TDD)"
```

---

## Task 8: 前端物化点 1 — computeAllFormulas 克隆换算

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（computeAllFormulas 函数体开头，约 :406 `if (!comp.fields || !comp.formulas) return {};` 之后）
- Test: `cpq-frontend/src/pages/quotation/unitConversion.computeAllFormulas.test.ts`（新建）

- [ ] **Step 1: 写失败测试**

读现有 computeAllFormulas 是否已有单测（搜 `computeAllFormulas` 的 import/export）。若未导出，本任务先把 `computeAllFormulas` 导出（加 `export`）。测试构造 comp（fields: `重量 unit_source_field=单位`、`单位`、FORMULA `金额` 引用 重量×单价 globalVar 或固定）+ row `{重量:'500', 单位:'g', 单价:2}`，断言返回的 `金额` = 1.0、且**入参 row.重量 仍 === '500'**（未被 mutate）。

- [ ] **Step 2: 跑确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/unitConversion.computeAllFormulas.test.ts`
Expected: FAIL。

- [ ] **Step 3: 在函数顶部克隆换算**

`QuotationStep2.tsx` computeAllFormulas 体开头（`if (!comp.fields || !comp.formulas) return {};` 之后）插入：

```typescript
  // 单位换算（物化点1）：配 unit_source_field 的列在算公式前按同行单位归一到 KG/PCS。
  // 必须克隆——入参 row 是渲染用同一对象 (:2236)，原地 mutate 会污染明细格子显示原值。
  row = applyUnitConversion(comp.fields as any, row);
```

并在文件顶部 import：

```typescript
import { applyUnitConversion } from '../../utils/unitConversion';
```

> `applyUnitConversion` 无配置列时返回原对象（零开销）；有配置列时返回克隆，后续逻辑读 `row[...]` 全是 canonical。覆盖渲染算值 + 前端小计 `computeTabSubtotalsByColumn` + 跨 Tab `buildCrossTabRows`（都经由本函数）。

- [ ] **Step 4: 跑确认通过 + 不 mutate 验证**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/unitConversion.computeAllFormulas.test.ts`
Expected: PASS（金额=1.0，row.重量 仍 '500'）。

- [ ] **Step 5: 编译自检 + 提交**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`（0 错）
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/QuotationStep2.tsx`（200）

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx \
        cpq-frontend/src/pages/quotation/unitConversion.computeAllFormulas.test.ts
git commit -m "feat(unit): computeAllFormulas 克隆换算 (物化点1, 不mutate, TDD)"
```

---

## Task 9: 前端物化点 2 — computeNonSubtotalColumnSums 输入列换算

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx:1119`（INPUT_NUMBER 直读分支）
- Test: 追加到 `unitConversion.computeAllFormulas.test.ts`

> 该函数对 INPUT_NUMBER 列 `row[colName]` 直读原值、绕过 computeAllFormulas 换算视图（评审 A3）。

- [ ] **Step 1: 加失败测试**

测 `computeNonSubtotalColumnSums`（若未导出先导出）：comp 有 `重量 INPUT_NUMBER unit_source_field=单位`（非 subtotal）+ 两行（500/g、1000/g），断言 `out['重量']` = 1.5（而非 1500）。

- [ ] **Step 2: 跑确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/unitConversion.computeAllFormulas.test.ts`
Expected: FAIL（1500）。

- [ ] **Step 3: INPUT_NUMBER 分支换算**

`QuotationStep2.tsx:1116-1122` 现状：

```typescript
      if (f.field_type === 'INPUT_NUMBER') {
        // INPUT_NUMBER 直接读行值（formulaCache 不算输入列）
        const raw = row[colName];
        val = typeof raw === 'number' && isFinite(raw) ? raw : (parseFloat(raw) || 0);
      } else {
```

改为对该列读换算后的值（`row` 是 `fillFixedDefaults` 后的行，先整行换算一次再取）。在循环体取 `row` 后、`for (const f of targetFields)` 之前加一行换算视图：

```typescript
    const row = fillFixedDefaults(comp.fields, ra.row);
    const convRow = applyUnitConversion(comp.fields as any, row); // 物化点2：输入列直读用 canonical
```

并把 INPUT_NUMBER 分支的 `const raw = row[colName];` 改为 `const raw = convRow[colName];`。

> import 已在 Task 8 加好。`applyUnitConversion` 无配置列时返回原 row，零行为变化。

- [ ] **Step 4: 跑确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/unitConversion.computeAllFormulas.test.ts`
Expected: PASS（1.5）。

- [ ] **Step 5: 编译自检 + 提交**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`（0 错）

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx \
        cpq-frontend/src/pages/quotation/unitConversion.computeAllFormulas.test.ts
git commit -m "feat(unit): computeNonSubtotalColumnSums 输入列换算 (物化点2, TDD)"
```

---

## Task 10: 缓存失效 — unit_source_field 纳入 fieldsOverrideHash

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（约 :1160 fieldsOverrideHash / driverExpansionKey 的 fields hash 维度）

> AP-37：改 unit_source_field 配置后，若 hash 不含该维度，旧 driver/公式缓存不失效 → 配置改了不生效。

- [ ] **Step 1: 定位 hash 构造**

Run: `cd cpq-frontend && grep -n "fieldsOverrideHash\|driverExpansionKey\|fieldsHash\|JSON.stringify" src/pages/quotation/QuotationStep2.tsx | head -20`

找到把 fields 序列化进 hash/key 的地方（含 `field_type`/`basic_data_path` 等维度的那段）。

- [ ] **Step 2: 把 unit_source_field 加入 hash 维度**

在序列化每个 field 的对象里追加 `unit_source_field`，例如：

```typescript
// 原：{ name: f.name, type: f.field_type, path: f.basic_data_path, ... }
// 改为追加 unit_source_field：
{ name: f.name, type: f.field_type, path: f.basic_data_path, usf: f.unit_source_field, /* ...其余维度不变 */ }
```

> 对齐该处现有写法，仅新增 `usf` 维度，不动其它。

- [ ] **Step 3: 编译自检**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`（0 错）

- [ ] **Step 4: 提交**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx
git commit -m "fix(unit): unit_source_field 纳入 fields hash (AP-37 缓存失效)"
```

---

## Task 11: 前端字段透传 — enrich/类型带上 unit_source_field

**Files:**
- Modify: 前端 `enrichComponentData`（先 grep 定位）+ 详情页 `ReadonlyProductCard.buildFormulaCache` / `useCardSnapshots`

- [ ] **Step 1: 确认 enrich 是否透传整个 field（多数情况自动带）**

Run: `cd cpq-frontend && grep -rn "enrichComponentData\|field_type:\|basic_data_path:" src/pages/quotation/*.ts src/pages/quotation/**/*.ts 2>/dev/null | grep -i "enrich\|map" | head`

若 enrich 是整对象 spread（`{...f}` / `...field`）→ `unit_source_field` 自动带，**无需改**，本步只确认。若是逐字段白名单 copy → 在白名单加 `unit_source_field`。

- [ ] **Step 2: 确认详情页公式缓存走 computeAllFormulas**

Run: `cd cpq-frontend && grep -n "computeAllFormulas\|buildFormulaCache" src/pages/quotation/ReadonlyProductCard.tsx`

若 `ReadonlyProductCard.buildFormulaCache` 调 `computeAllFormulas`（Task 8 已在其内换算）→ 详情页自动覆盖，无需改。若它自带独立求值 → 在其行物化处加 `applyUnitConversion(fields, row)`（同 Task 8 模式），并补一条详情页单测。

- [ ] **Step 3: 编译自检 + 提交（如有改动）**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`（0 错）

```bash
git add -p   # 只 add 本任务确有改动的文件
git commit -m "feat(unit): enrich/详情页透传 unit_source_field（如需）"
```

---

## Task 12: 跨端对拍 + E2E + 全量验收

**Files:**
- Test: 复用 Task 1/3 的对拍 fixture；E2E `cpq-frontend/e2e/quotation-flow.spec.ts`

- [ ] **Step 1: 跨端对拍一致性确认**

人工核对 `UnitConversionTest.factorFor_allPresetUnits` 与 `unitConversion.test.ts` 的 cases 列表逐档相同（同一组单位 → 同一系数）。如不一致，改齐并重跑两端单测。

- [ ] **Step 2: 后端全量回归**

Run: `cd cpq-backend && ./mvnw test -q`
Expected: BUILD SUCCESS（含本计划所有新测试）。

- [ ] **Step 3: 前端单测 + 类型**

Run: `cd cpq-frontend && npx vitest run src/utils/unitConversion.test.ts src/pages/quotation/unitConversion.computeAllFormulas.test.ts && npx tsc --noEmit -p tsconfig.json`
Expected: PASS + 0 类型错误。

- [ ] **Step 4: 配一个真实换算场景跑 E2E**

在 E2E 测试数据组件里给某重量列配 `unit_source_field`（指向单位列），录入不同单位行。

Run:
```bash
cd cpq-frontend
rm -f e2e/screenshots/qf-*.png
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```
Expected: 所有 test `passed`；`'加载中' final count = 0`；8 Tab `'加载中'=0`；换算行公式值为 canonical；明细输入列显示原值（刷新 3 次不变）。

- [ ] **Step 5: 四视图复测 + RECORD 记录 + 提交**

手工/脚本复测：报价单视图、核价视图、详情页、Excel 视图——换算行公式/小计 = canonical 且四处口径一致；明细输入列 = 原值。

向 `docs/RECORD.md` 追加一条：`[2026-06-15] 单位换算 - 列配unit_source_field逐行归一KG/PCS | UnitConversion(前后端)+6物化点 | 明细原值/派生canonical, BNF直读不支持`。

```bash
git add docs/RECORD.md
git commit -m "docs(record): 单位换算功能落地记录"
```

---

## Self-Review 覆盖对照（spec v5 → task）

- §3 换算表 → Task 1/3；§6 双副本+对拍 → Task 1/3/12-Step1
- §4/§7 物化点：点1 computeAllFormulas → Task 8；点2 computeNonSubtotalColumnSums → Task 9；点3 computeRows → Task 5；点4 parseEffectiveRows → Task 6；点5 backfillSubtotals → Task 7；点6 cross_tab → Task 8/9（经由 computeAllFormulas）+ Task 12 验证
- §6/§7 配置 + 传播：FieldConfigTable → Task 4；类型/enrich/详情页 → Task 11；缓存失效 → Task 10
- §8 不变量（克隆不 mutate / BigDecimal / 禁匹配键）→ Task 2/5/8 代码 + 注释；§9 小计 canonical（落库不动）→ Task 7
- §10 异常单位透传 → Task 1/2/3（factorFor 兜底 1）；§11 验收用例 → Task 12
```
