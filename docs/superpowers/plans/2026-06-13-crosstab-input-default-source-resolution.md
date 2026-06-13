# cross_tab_ref 对 INPUT+default_source 字段解析修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 cross_tab_ref 求值前的"行解析"对 `INPUT_TEXT/INPUT_NUMBER + default_source` 字段也按字段名解析绑定值，使 match 键与目标列不再取到 undefined，聚合恢复正确（料8=94.5）。

**Architecture:** 四象限非对称缺陷（见 spec §4.2）。**后端源行 `resolveRowByFieldName` 已正确，不动**；只补**后端宿主行 `currentRowRaw`**（方案 B：增量补 INPUT default_source）。前端**源行 `buildResolvedRow`** 补 INPUT default_source（对齐后端 `resolveRowByFieldName` 全解析），**前端宿主行**只补 INPUT default_source（对齐后端 B，最小侵入）。前后端宿主侧均"裸行 + INPUT-only"，保持对称；DRY 落在单字段解析器。

**Tech Stack:** Java 17 / Quarkus（JUnit5）；React + TypeScript（Vitest）；前后端共享对拍夹具 `cross-tab-cases.json`。

**Spec:** `docs/superpowers/specs/2026-06-13-crosstab-input-default-source-resolution.md`（已评审定稿，含 §8 修订记录）

---

## 关键事实（实现前必读，已逐行核实）

1. **后端宿主行缺陷点**：`FormulaCalculator.java:553` `ctx.currentRowRaw = toRawRowMap(mergedRow)`（`mergedRow = driverRow ⊕ editValues`，不解析 default_source）。`toRawRowMap` 见 `:1145`。
2. **后端源行已正确**：`resolveRowByFieldName`（`:736`，分支 `:753-779`）已处理 INPUT+default_source —— **本计划不改它**。
3. **对拍夹具只测求值器**：`FormulaCalculatorCrossTabFixtureTest`（`:104-129`）把 `currentRow` 直接灌进 `ctx.currentRowRaw` 再调 `evaluateExpression`，**不经 `calculate`/`computeRows`** → 抓不到本 bug。真正的回归测试必须经 `calc.calculate(...)`（后端，自动建 currentRowRaw）或 `buildCrossTabRows(...)`（前端，自动建源行 + 调 computeAllFormulas）。
4. **后端可复用工具**：`fieldType(f)` / `fieldName(f)` / `defaultSource(f)` / `lookupBdv(bdv, key)` / `bnfDriverLookupKey(path)`（`:1258`，仅给路径包花括号）/ `nonEmpty(Object)`（`:658` 用法证明接受 Object/JsonNode）/ `unwrapNode(Object)`（`:837`，JsonNode→原生 String/Number，文本保留）。
5. **前端可复用工具**：`resolveBasicDataForRow`（`QuotationStep2.tsx:670`）/ `resolveDataSourceForRow`（`:692`）/ `bnfDriverLookupKey`（`useDriverExpansions.ts:441`，= `{` + trim路径 + `}`）/ `formatPathValue`（文本保留首值，不 parseFloat）。
6. **前端导出物**：`buildCrossTabRows` 与 `computeAllFormulas` 已 `export`；`buildResolvedRow` 未导出（经 `buildCrossTabRows` 间接测）。
7. **前端类型缺口**：`QuotationStep2.tsx:99-106` 内联 `default_source.type` 联合**漏 `'BASIC_DATA'`**（权威 `DefaultSource` 在 `component/types.ts:114` 有）。新代码比较 `ds.type === 'BASIC_DATA'` 前必须先把内联类型补上 `'BASIC_DATA'`，否则 TS2367。
8. **优先级（前后端一致）**：手填/driver 行值 > default_source 解析值 > content。即仅当 `行[字段名]` 为空（`null`/`undefined`/`''`）时才用绑定值。
9. **default_source 三子类型**：`GLOBAL_VARIABLE`（键 `@gvar:CODE`）/ `BNF_PATH`（键 `bnfDriverLookupKey(path)`）/ `BASIC_DATA`（键 `bnfDriverLookupKey(path)`）。`DATABASE_QUERY`/`HTTP_API` 不在范围（spec §6.4 待排查）。
10. **复现锚点**：`$ll_view._料件` → bnf 键 `{$ll_view._料件}`。料件(INPUT_TEXT, default_source.BASIC_DATA→`$ll_view._料件`) 是 match 行键；单价/重量(g)/损耗率(INPUT_NUMBER, default_source) 是目标列。

---

## File Structure

**后端：**
- Modify `cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java` — 新增 private `fillInputDefaultSourceByFieldName(...)`；在 `computeRows` `:553` 后调用。
- Modify `cpq-backend/src/test/java/com/cpq/quotation/service/FormulaCalculatorCrossTabTest.java` — 新增经 `calculate` 的宿主行解析回归测试。

**前端：**
- Modify `cpq-frontend/src/pages/quotation/QuotationStep2.tsx` — ① 内联 `default_source.type` 补 `'BASIC_DATA'`；② 新增 `resolveInputDefaultSourceForRow(...)`；③ `buildResolvedRow` 调用它（源行全解析）；④ `computeAllFormulas` 构造"裸 row + INPUT-only"的 currentRow 传 `evaluateExpression`（`:649`）。
- Create `cpq-frontend/src/pages/quotation/crossTabInputDefaultSource.test.ts` — 经 `buildCrossTabRows` 的源行 + 宿主行解析回归测试。

**对拍（防御纵深，证前后端求值器对多 source/KSUM 字段名键形态结果一致）：**
- Modify `cpq-frontend/src/utils/__fixtures__/cross-tab-cases.json` + `cpq-backend/src/test/resources/cross-tab-cases.json`（两份必须逐字相同）。

---

## Task 1: 后端宿主行 currentRowRaw 补 INPUT default_source（方案 B）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java`（新增方法 + `:553` 后调用）
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/FormulaCalculatorCrossTabTest.java`

- [ ] **Step 1: 写失败测试**（经 `calculate`，宿主 `料件` 走 default_source.BASIC_DATA）

加到 `FormulaCalculatorCrossTabTest`（类内，复用既有 `json(...)` helper）：

```java
    /**
     * 回归（spec 2026-06-13）：宿主字段 料件(INPUT_TEXT) 经 default_source.BASIC_DATA 绑定到驱动列
     * $ll_view._料件 —— driverRow 只有 _料件，没有 料件。calculate 必须把 default_source 解析进
     * currentRowRaw[料件]，cross_tab_ref match[料件=料件] 才能命中源行 → SUM 目标列。
     * 修复前：currentRowRaw 缺 料件 → 命中 0 行 → 结果 0（红）。
     */
    @Test void calculate_hostInputDefaultSource_resolvesMatchKey() {
        JsonNode fields = json("["
            + "{\"name\":\"料件\",\"fieldType\":\"INPUT_TEXT\","
            + "  \"default_source\":{\"type\":\"BASIC_DATA\",\"path\":\"$ll_view._料件\"}},"
            + "{\"name\":\"材料费\",\"fieldType\":\"FORMULA\"}"
            + "]");
        // 材料费 = SUM(元素.单价) over match[料件=料件]
        JsonNode formulas = json("[{\"name\":\"材料费\",\"expression\":["
            + "{\"type\":\"cross_tab_ref\",\"source\":\"元素\",\"target\":\"单价\","
            + "\"match\":[{\"a\":\"料件\",\"b\":\"料件\"}],\"agg\":\"SUM\"}]}]");
        JsonNode rkf = json("[\"料件\"]");
        // driverRow 只有 _料件（SQL 别名），无 料件；basicDataValues 提供 {$ll_view._料件}=料8
        JsonNode baseRows = json("[{\"driverRow\":{\"_料件\":\"料8\"},"
            + "\"basicDataValues\":{\"{$ll_view._料件}\":\"料8\"}}]");
        // 源组件 元素 已算行（按字段名，模拟 resolveRowByFieldName 产出）：料8 两条单价 60+34.5=94.5
        Map<String, List<Map<String, Object>>> crossTabRows = Map.of("元素", List.of(
            Map.of("料件", "料8", "单价", new java.math.BigDecimal("60")),
            Map.of("料件", "料8", "单价", new java.math.BigDecimal("34.5")),
            Map.of("料件", "料9", "单价", new java.math.BigDecimal("99"))));

        JsonNode fr = calc.calculate(fields, formulas, null, rkf, baseRows, json("[]"),
            new HashMap<>(), new HashMap<>(), new HashMap<>(), crossTabRows);

        assertEquals(1, fr.size());
        assertEquals(94.5, fr.get(0).path("values").path("材料费").asDouble(), 1e-4);
    }
```

- [ ] **Step 2: 跑测试确认红**

Run:
```bash
cd cpq-backend && ./mvnw -o test -Dtest='FormulaCalculatorCrossTabTest#calculate_hostInputDefaultSource_resolvesMatchKey'
```
Expected: FAIL — `expected: <94.5> but was: <0.0>`（currentRowRaw 缺 `料件` → match 0 命中 → SUM=0）。

- [ ] **Step 3: 实现 — 新增解析方法**

在 `FormulaCalculator.java` 的 `toRawRowMap`（`:1145`）附近（同一私有工具区）新增：

```java
    /**
     * 方案 B（spec 2026-06-13）：宿主行 currentRowRaw 增量补 INPUT 型 default_source
     * (GLOBAL_VARIABLE/BNF_PATH/BASIC_DATA) 按字段名解析的值，供 cross_tab_ref 的 match 键 b 命中。
     * 仅当 currentRowRaw 尚无该字段名（空）时写入——driver/手填值优先，不覆盖。文本保留（unwrapNode）。
     * 源行路径由 resolveRowByFieldName 覆盖，故此处只补宿主行，不动源行。
     */
    private void fillInputDefaultSourceByFieldName(JsonNode fields, JsonNode basicDataValues,
                                                   Map<String, Object> currentRowRaw) {
        if (fields == null || !fields.isArray() || basicDataValues == null) return;
        for (JsonNode f : fields) {
            String type = fieldType(f);
            if (!("INPUT_NUMBER".equals(type) || "INPUT_TEXT".equals(type) || "INPUT".equals(type))) continue;
            String name = fieldName(f);
            if (name.isEmpty()) continue;
            if (nonEmpty(currentRowRaw.get(name))) continue;   // driver/手填优先
            JsonNode ds = defaultSource(f);
            if (ds == null) continue;
            String dsType = ds.path("type").asText("");
            Object v = null;
            if ("GLOBAL_VARIABLE".equals(dsType)) {
                v = lookupBdv(basicDataValues, "@gvar:" + ds.path("code").asText(""));
            } else if ("BNF_PATH".equals(dsType) || "BASIC_DATA".equals(dsType)) {
                String p = ds.path("path").asText("");
                if (!p.isEmpty()) v = lookupBdv(basicDataValues, bnfDriverLookupKey(p));
            }
            if (nonEmpty(v)) currentRowRaw.put(name, unwrapNode(v));
        }
    }
```

- [ ] **Step 4: 实现 — 在 computeRows 调用**

`FormulaCalculator.java:553`，在 `ctx.currentRowRaw = toRawRowMap(mergedRow);` **下一行**插入：

```java
            ctx.currentRowRaw = toRawRowMap(mergedRow);
            fillInputDefaultSourceByFieldName(fields, basicDataValues, ctx.currentRowRaw);
```

- [ ] **Step 5: 跑测试确认绿 + 全相关后端套件零回归**

Run:
```bash
cd cpq-backend && ./mvnw -o test -Dtest='FormulaCalculator*Test,CardSnapshotCrossTabTest,CrossTab*Test,ResolveRowByFieldNameTest' 2>&1 | grep -E "Tests run:|BUILD"
```
Expected: 新测试 PASS；`Tests run: 103, Failures: 0, Errors: 0`（基线 102 + 新 1）；`BUILD SUCCESS`。

- [ ] **Step 6: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/FormulaCalculatorCrossTabTest.java
git commit -m "fix(formula): 宿主行 currentRowRaw 补 INPUT default_source 解析(方案B, cross_tab match 键)

spec 2026-06-13 §5.2 后端宿主行。源行 resolveRowByFieldName 已正确不动。
Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: 前端源行 + 宿主行补 INPUT default_source

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（类型补丁 + 新解析器 + buildResolvedRow + computeAllFormulas）
- Test: `cpq-frontend/src/pages/quotation/crossTabInputDefaultSource.test.ts`（新建）

- [ ] **Step 1: 写失败测试**（经 `buildCrossTabRows`，覆盖源行解析 + 宿主行 match）

Create `cpq-frontend/src/pages/quotation/crossTabInputDefaultSource.test.ts`：

```ts
import { describe, it, expect } from 'vitest';
import { buildCrossTabRows } from './QuotationStep2';
import { bnfDriverLookupKey } from './useDriverExpansions';

// 最小夹具：源组件 元素(driver $ys_view) + 宿主组件 来料(driver $ll_view)
// 料件 = INPUT_TEXT default_source.BASIC_DATA → $X_view._料件 (driverRow 只有 _料件)
// 单价 = INPUT_NUMBER default_source.BASIC_DATA → $ys_view._单价 (源行目标列)
// 来料.材料费 = SUM(元素.单价) over match[料件=料件]，料8 两条 60+34.5=94.5

const bdvYs = (liao: string, price: number) => ({
  [bnfDriverLookupKey('$ys_view._料件')]: liao,
  [bnfDriverLookupKey('$ys_view._单价')]: price,
});

const ysExpansion = {
  rowCount: 3,
  rows: [
    { driverRow: { _料件: '料8', _单价: 60 },   basicDataValues: bdvYs('料8', 60) },
    { driverRow: { _料件: '料8', _单价: 34.5 }, basicDataValues: bdvYs('料8', 34.5) },
    { driverRow: { _料件: '料9', _单价: 99 },   basicDataValues: bdvYs('料9', 99) },
  ],
} as any;

const llExpansion = {
  rowCount: 1,
  rows: [
    { driverRow: { _料件: '料8' }, basicDataValues: { [bnfDriverLookupKey('$ll_view._料件')]: '料8' } },
  ],
} as any;

const ysFields = [
  { name: '料件', field_type: 'INPUT_TEXT',  default_source: { type: 'BASIC_DATA', path: '$ys_view._料件' } },
  { name: '单价', field_type: 'INPUT_NUMBER', default_source: { type: 'BASIC_DATA', path: '$ys_view._单价' } },
] as any;

const llFields = [
  { name: '料件',   field_type: 'INPUT_TEXT', default_source: { type: 'BASIC_DATA', path: '$ll_view._料件' } },
  { name: '材料费', field_type: 'FORMULA',
    // formula 引擎按 comp.formulas 取；这里给字段同名公式
  },
] as any;

const componentData = [
  {
    componentId: '元素', componentCode: '元素', tabName: '元素', componentType: 'NORMAL',
    fields: ysFields, formulas: [], componentData: [], snapshotRows: 0,
  },
  {
    componentId: '来料', componentCode: '来料', tabName: '来料', componentType: 'NORMAL',
    fields: llFields,
    formulas: [{ name: '材料费', expression: [
      { type: 'cross_tab_ref', source: '元素', target: '单价',
        match: [{ a: '料件', b: '料件' }], agg: 'SUM' },
    ] }],
    componentData: [], snapshotRows: 0,
  },
] as any;

const lookupExpansion = (comp: any) =>
  comp.componentId === '元素' ? ysExpansion
  : comp.componentId === '来料' ? llExpansion
  : undefined;

describe('cross_tab INPUT+default_source 行解析', () => {
  it('源行按字段名解析 INPUT default_source（料件文本 / 单价数值）', () => {
    const store = buildCrossTabRows(componentData, {}, undefined, lookupExpansion);
    const ysRows = store['元素'];
    expect(ysRows).toHaveLength(3);
    // 修复前：源行只有 _料件/_单价，无 料件/单价 → undefined
    expect(ysRows[0]['料件']).toBe('料8');     // 文本保留
    expect(Number(ysRows[0]['单价'])).toBe(60); // 数值
  });

  it('宿主行 match 键经 INPUT default_source 解析 → SUM 命中正确（料8=94.5）', () => {
    const store = buildCrossTabRows(componentData, {}, undefined, lookupExpansion);
    const llRow = store['来料'][0];
    // 修复前：宿主 currentRow 缺 料件 → match 0 命中 → 材料费=0
    expect(Number(llRow['材料费'])).toBeCloseTo(94.5, 4);
  });
});
```

- [ ] **Step 2: 跑测试确认红**

Run:
```bash
cd cpq-frontend && npx vitest run src/pages/quotation/crossTabInputDefaultSource.test.ts
```
Expected: 两个 it 均 FAIL（源行 `ysRows[0]['料件']` = undefined；宿主 `材料费` = 0）。

- [ ] **Step 3: 实现 — 内联 default_source 类型补 BASIC_DATA**

`QuotationStep2.tsx:100`，把：
```ts
    type: 'GLOBAL_VARIABLE' | 'BNF_PATH' | 'HTTP_API';
```
改为：
```ts
    type: 'GLOBAL_VARIABLE' | 'BNF_PATH' | 'HTTP_API' | 'BASIC_DATA';
```

- [ ] **Step 4: 实现 — 新增单字段解析器 resolveInputDefaultSourceForRow**

在 `resolveDataSourceForRow`（`:692-722`）之后、`buildResolvedRow`（`:732`）之前插入：

```ts
/**
 * INPUT_TEXT/INPUT_NUMBER + default_source 行级取值 (RAW, 文本保留) —— 镜像后端
 * resolveRowByFieldName 的 INPUT 分支 (FormulaCalculator:757-772) + computeAllFormulas
 * default_source 链 (:573-599)。仅行级 basicDataValues (BASIC_DATA/BNF_PATH→{path} 键;
 * GLOBAL_VARIABLE→@gvar:CODE 键)。文本不 parseFloat (cross_tab 匹配键需原始文本)。
 */
function resolveInputDefaultSourceForRow(
  f: ComponentField,
  basicDataValues: Record<string, any> | undefined,
): any {
  const ds = f.default_source;
  if (!ds || !basicDataValues) return undefined;
  let resolved: any = undefined;
  if (ds.type === 'GLOBAL_VARIABLE' && ds.code) {
    const gvKey = `@gvar:${ds.code}`;
    if (Object.prototype.hasOwnProperty.call(basicDataValues, gvKey)) {
      const v = basicDataValues[gvKey];
      if (v != null && !(Array.isArray(v) && v.length === 0)) resolved = v;
    }
  } else if ((ds.type === 'BNF_PATH' || ds.type === 'BASIC_DATA') && ds.path) {
    const lookupKey = bnfDriverLookupKey(ds.path);
    if (Object.prototype.hasOwnProperty.call(basicDataValues, lookupKey)) {
      const v = basicDataValues[lookupKey];
      if (v != null && !(Array.isArray(v) && v.length === 0)) resolved = v;
    }
  }
  if (resolved == null) return undefined;
  if (typeof resolved === 'number') return resolved;
  return formatPathValue(resolved) ?? undefined;  // 文本保留
}
```

- [ ] **Step 5: 实现 — buildResolvedRow 源行加 INPUT 分支**

`QuotationStep2.tsx:750-755`，在 DATA_SOURCE 分支后追加 INPUT 分支。把：

```ts
    } else if (f.field_type === 'DATA_SOURCE' && f.datasource_binding) {
      if (out[key] == null || out[key] === '') {
        const v = resolveDataSourceForRow(f, basicDataValues);
        if (v != null) out[key] = v;
      }
    }
  }
```
改为：
```ts
    } else if (f.field_type === 'DATA_SOURCE' && f.datasource_binding) {
      if (out[key] == null || out[key] === '') {
        const v = resolveDataSourceForRow(f, basicDataValues);
        if (v != null) out[key] = v;
      }
    } else if ((f.field_type === 'INPUT_TEXT' || f.field_type === 'INPUT_NUMBER' || f.field_type === 'INPUT')
               && f.default_source) {
      if (out[key] == null || out[key] === '') {
        const v = resolveInputDefaultSourceForRow(f, basicDataValues);
        if (v != null) out[key] = v;
      }
    }
  }
```

- [ ] **Step 6: 实现 — computeAllFormulas 宿主 currentRow（裸 row + INPUT-only）**

`QuotationStep2.tsx`，在公式求值循环（`for (const name of order)`，约 `:618`）**之前**插入宿主行增量解析（与后端 B 对称：只补 INPUT default_source，不补 BASIC_DATA/DATA_SOURCE）：

```ts
  // cross_tab match 键 b 取宿主行字段名值；裸 row 只含驱动 _ 键，需按 INPUT default_source 补字段名键。
  // 与后端 FormulaCalculator.computeRows 方案 B 对称：仅补 INPUT default_source，不动 BASIC_DATA/DATA_SOURCE。
  let currentRowForEval: Record<string, any> = row;
  {
    let augmented: Record<string, any> | undefined;
    for (const f of comp.fields) {
      if ((f.field_type === 'INPUT_TEXT' || f.field_type === 'INPUT_NUMBER' || f.field_type === 'INPUT')
          && f.default_source) {
        const k = f.name || f.key || '';
        if (k && (row[k] == null || row[k] === '')) {
          const v = resolveInputDefaultSourceForRow(f, basicDataValues);
          if (v != null) { if (!augmented) augmented = { ...row }; augmented[k] = v; }
        }
      }
    }
    if (augmented) currentRowForEval = augmented;
  }
```

然后把求值调用（`:649`）的第 11 参 `row` 改为 `currentRowForEval`：
```ts
        ? evaluateExpression(
            expr, fieldValues, allComponentSubtotals || {}, undefined, quotationFields,
            pathCache, partNo, basicDataValues, prevForField, globalVariableDefs, currentRowForEval, crossTabRows,
            diag,
          )
```

> 注意：仅改这一处求值入参。`row` 的其它读取（FIXED_VALUE/INPUT_NUMBER fieldValues 兜底 `:559-611`、conditional lookup `:629`）**保持读裸 `row`**，不要替换，避免污染既有 numeric 通路。

- [ ] **Step 7: 跑测试确认绿**

Run:
```bash
cd cpq-frontend && npx vitest run src/pages/quotation/crossTabInputDefaultSource.test.ts
```
Expected: 两个 it 均 PASS（源行 `料件`='料8'/`单价`=60；宿主 `材料费`=94.5）。

- [ ] **Step 8: 前端零回归 + tsc + Vite transform 自检**

Run:
```bash
cd cpq-frontend
npx tsc --noEmit -p tsconfig.json                                   # 期望 0 错误
npx vitest run src/pages/quotation/ src/pages/component/ src/utils/ # 期望全绿(基线153 + 新2)
curl -s -o /dev/null -w "QuotationStep2 %{http_code}\n" http://localhost:5174/src/pages/quotation/QuotationStep2.tsx
```
Expected: tsc 0 错误；vitest 全 passed；QuotationStep2 → `200`。

- [ ] **Step 9: Commit**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx \
        cpq-frontend/src/pages/quotation/crossTabInputDefaultSource.test.ts
git commit -m "fix(crosstab): 前端源行+宿主行解析 INPUT default_source(按字段名)

源行 buildResolvedRow 全解析(对齐后端 resolveRowByFieldName); 宿主 currentRow 裸row+INPUT-only(对齐后端方案B)。spec 2026-06-13 §5.2。
Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: 对拍夹具新增用例（防御纵深，前后端求值器字段名键形态一致）

**Files:**
- Modify: `cpq-frontend/src/utils/__fixtures__/cross-tab-cases.json`
- Modify: `cpq-backend/src/test/resources/cross-tab-cases.json`（与前端逐字相同）

> 说明：对拍夹具喂"已解析行"测求值器（见关键事实 #3，本就对），此用例锁的是 **SUM over match[料件=料件] 在字段名键行上前后端结果一致**，作为 Task1/2 解析修复的下游护栏，**不替代** Task1/2 的解析层测试。

- [ ] **Step 1: 读现有夹具结构确认字段名**

Run:
```bash
cd cpq-frontend && head -40 src/utils/__fixtures__/cross-tab-cases.json
```
Expected: 看到数组，每条含 `name` / `tokens|token` / `crossTabRows|aRows` / `currentRow` / `expected` / 可选 `expectError`（对齐 `FormulaCalculatorCrossTabFixtureTest` 字段契约）。

- [ ] **Step 2: 在数组末尾追加用例（前端文件）**

在 `cross-tab-cases.json` 数组最后一个元素后插入（注意逗号）：

```json
  {
    "name": "SUM over 料件 match on 字段名键行 (INPUT+default_source 解析后形态)",
    "token": {
      "type": "cross_tab_ref", "source": "元素", "target": "单价",
      "match": [{ "a": "料件", "b": "料件" }], "agg": "SUM"
    },
    "crossTabRows": {
      "元素": [
        { "料件": "料8", "单价": 60 },
        { "料件": "料8", "单价": 34.5 },
        { "料件": "料9", "单价": 99 }
      ]
    },
    "currentRow": { "料件": "料8" },
    "expected": 94.5
  }
```

- [ ] **Step 3: 同步到后端资源（逐字相同）**

Run:
```bash
cp cpq-frontend/src/utils/__fixtures__/cross-tab-cases.json cpq-backend/src/test/resources/cross-tab-cases.json
diff cpq-frontend/src/utils/__fixtures__/cross-tab-cases.json cpq-backend/src/test/resources/cross-tab-cases.json && echo "IDENTICAL"
```
Expected: `IDENTICAL`（无 diff 输出）。

- [ ] **Step 4: 跑前后端对拍确认绿**

Run:
```bash
cd cpq-frontend && npx vitest run src/utils/ 2>&1 | tail -5
cd ../cpq-backend && ./mvnw -o test -Dtest='FormulaCalculatorCrossTabFixtureTest' 2>&1 | grep -E "Tests run:|BUILD"
```
Expected: 前端 utils 全绿；后端 `FormulaCalculatorCrossTabFixtureTest` `Tests run: 46`（基线 45 + 新 1），`BUILD SUCCESS`。

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/utils/__fixtures__/cross-tab-cases.json \
        cpq-backend/src/test/resources/cross-tab-cases.json
git commit -m "test(crosstab): 对拍新增 SUM/字段名键 料件 match 用例(前后端求值器一致护栏)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: 协议级自检 — E2E + 真机复现 + 全套回归

**Files:** 无代码改动（验收 + 自检声明）

- [ ] **Step 1: 后端宿主行 schema 改动后重启确认健康**

Run:
```bash
touch cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java && sleep 7
curl -s -o /dev/null -w "health %{http_code}\n" http://localhost:8081/q/health
```
Expected: `health 200`。

- [ ] **Step 2: 全相关后端套件**

Run:
```bash
cd cpq-backend && ./mvnw -o test -Dtest='FormulaCalculator*Test,CardSnapshot*Test,CrossTab*Test,ResolveRowByFieldNameTest' 2>&1 | grep -E "Tests run:|BUILD"
```
Expected: 末行 `Tests run: 104, Failures: 0, Errors: 0`（基线 102 + Task1 的 1 + Task3 的 1）；`BUILD SUCCESS`。

- [ ] **Step 3: 全相关前端套件 + tsc**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json && npx vitest run src/pages/quotation/ src/pages/component/ src/utils/ 2>&1 | tail -5
```
Expected: tsc 0 错误；vitest 全 passed（基线 153 + Task2 的 2 + Task3 的 1）。

- [ ] **Step 4: E2E（协议级强制）**

Run:
```bash
cd cpq-frontend
rm -f e2e/screenshots/qf-*.png
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list 2>&1 | tail -20
```
Expected: 所有 test `passed`；日志含 `'加载中' final count = 0`；8 Tab `'加载中'=0`。

- [ ] **Step 5: 真机复现确认（QT-20260613-1705 来料 材料费）**

> 报价单 `QT-20260613-1705` 在共享 DB 中。重算其 card snapshot 后读 来料 `材料费` 各料件行。
> 具体重算/读取端点按 `docs/报价单核价单功能总结.md` 与现有 admin 端点确定；最低限度用如下校验：

Run（示例，按实际端点调整）：
```bash
# 触发该报价单 line item 的 card 重算（refresh snapshots）后，读快照 来料 tab 的 材料费 列
curl -s "http://localhost:8081/api/cpq/quotations/QT-20260613-1705" -H "Authorization: ..." | \
  python3 -c "import sys,json; d=json.load(sys.stdin); print('inspect 来料.材料费 per 料件 row')"
```
Expected: 来料各料件行 `材料费` 非 0（料8≈94.5）；编辑视图 / 详情视图一致；核价单不渲染该 token 不受影响。

> 若无法在本会话直接重算真机数据，记录为"需用户在 UI 触发该报价单重算后复核"，并在完成报告中显式声明，不得默认通过。

- [ ] **Step 6: 写 RECORD.md 开发记录**

向 `docs/RECORD.md` 追加一行：
```
[2026-06-13] 报价单渲染/cross_tab - 修 INPUT+default_source 字段未按字段名解析进 cross_tab 行致聚合全0 | FormulaCalculator.java(宿主currentRowRaw补INPUT default_source 方案B) + QuotationStep2.tsx(源行buildResolvedRow全解析+宿主currentRow INPUT-only) + cross-tab-cases.json对拍 | 四象限非对称:后端源行resolveRowByFieldName已对不动;前后端宿主侧均裸行+INPUT-only保持对称;spec docs/superpowers/specs/2026-06-13-*
```

- [ ] **Step 7: Commit**

```bash
git add docs/RECORD.md
git commit -m "docs(record): cross_tab INPUT+default_source 行解析修复记录

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## 验收总清单（对照 spec §5.4）

- [ ] 后端 currentRowRaw 宿主行解析单测（经 calculate）先红后绿 ✅
- [ ] 前端 buildResolvedRow 源行 + 宿主行解析单测（经 buildCrossTabRows）先红后绿 ✅
- [ ] 集成断言：driver `_` 键 → 料8=94.5 ✅
- [ ] 前后端对拍 `cross-tab-cases.json` 两端 diff 空、结果一致 ✅
- [ ] 后端 `resolveRowByFieldName` **未改动**（仅宿主行）✅
- [ ] 不变量：N=1/手填优先/文本保留/AP-51 行数权威/AP-54 写路径下标 未破坏 ✅
- [ ] E2E `quotation-flow.spec.ts` 加载中=0 零回归 ✅
- [ ] 真机 QT-20260613-1705 来料 材料费 ≠ 0（或显式声明需用户 UI 触发复核）✅
- [ ] 自检声明：tsc 0 错误 / Vite 200 / 后端 health 200 / 测试计数 ✅

## 范围外（不在本计划，spec §6 待确认）

- `加工费`（agg=NONE 多工序多命中 ERR）= 语义问题，需产品单独确认。
- INPUT default_source 的 `DATABASE_QUERY`/`HTTP_API` 子类型按字段名解析（当前前后端均不处理，待确认是否存在此配法）。
