# Plan 3a — 条件公式引擎（数据模型 + 双引擎求值，无 UI） Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让任意 FORMULA 字段支持"条件模式"——挂一个有序规则列表 `[{条件树→公式}, …] + 默认公式`，运行时逐行按规则求第一条命中的条件、执行其公式，全不中走默认。条件树支持完整 AND/OR 嵌套、引用本行任意列（含其它公式列，按拓扑序）。本 Plan 只做引擎（数据模型 + 双引擎求值 + 拓扑 + 校验），不做配置 UI（Plan 3b）。

**Architecture:** 新增结构化 `CondTree`（递归 group/leaf）+ 双引擎**镜像**求值器（TS `condTree.ts` / Java `CondTreeEvaluator`），以共享真值表样本对账。字段配置加 `conditional_formula = { rules:[{when, formula}], default }`。公式解析从"静态取一条"升级为"行内按规则选一条"：前端 `computeAllFormulas`、后端 `computeRows`/`collectFormulaFields` 的逐字段求值循环里，遇条件字段先评估规则选表达式再求值。拓扑依赖 = 条件树引用列 ∪ 所有候选公式（rules+default）引用列（保守并集）。保存期 `ComponentService` 校验默认必填 + 环检测。

**Tech Stack:** Java 17 / Quarkus（`FormulaCalculator` / `ComponentService`）；React + TS（`QuotationStep2.tsx#computeAllFormulas` / 新 `utils/condTree.ts`）。

**关联：** spec `docs/superpowers/specs/2026-06-09-multi-subtotal-conditional-formula-design.md` 设计 A/B/C / §2 结论 Q2=C·Q4=A·Q5=B·Q6=C·Q11=B·Q12=B。

---

## 数据结构契约（本 Plan 落地的核心）

字段配置（FORMULA 字段，二选一模式）：

```jsonc
// 单一模式（现状，零改动）：field.formula_name / formula_assignments / 名称匹配 / 位置兜底
{ "field_type":"FORMULA", "name":"加工费", "formula_name":"proc_fee" }

// 条件模式（新增）：存在 conditional_formula 即走条件解析（优先级最高，高于 formula_name）
{ "field_type":"FORMULA", "name":"加工费", "is_subtotal":true,
  "conditional_formula": {
    "rules": [
      { "when": <CondTree>, "formula": "proc_turning" },
      { "when": <CondTree>, "formula": "proc_milling" }
    ],
    "default": "proc_zero"
  } }
```

`CondTree`（递归）：

```jsonc
// 分组（可嵌套）
{ "kind":"group", "logic":"and"|"or", "children":[ <CondTree>, … ] }
// 叶子
{ "kind":"leaf", "left":"列名", "op":"eq"|"ne"|"gt"|"gte"|"lt"|"lte"|"in",
  "rhs": { "type":"literal"|"column", "value":"…" } }
```

**求值语义**（双引擎必须逐分一致）：
- group `and`：所有子为真（空 children → true）；group `or`：任一子为真（空 children → false）。
- leaf：取 `left` 列值 `L`、`rhs` 值 `R`（`literal`=字面量；`column`=该列值）。
  - `gt/gte/lt/lte`：双方 parseFloat，任一非数 → false。
  - `eq/ne`：先 parseFloat 双方比较；任一非数 → 退字符串比较（`String(L) === String(R)`）。`ne` = `!eq`。
  - `in`：`R` 按逗号拆分，`String(L)` ∈ 集合（去空格）。
- 列值来源 `lookup(col)`：优先本行已算公式值 → 否则原始行值；缺失 → null（null 参与比较恒 false，eq null→null 视字符串 ""？— 统一：null → 比较 false，除 `ne` 对 null 为 true）。
- 解析/求值异常 → false（保守不命中，与 `conditionEngine` 一致）。

**规则选择**：rules 顺序求 `when`，第一条 true → 用其 `formula`；全不中 → `default`。`formula`/`default` 仍引用 `component.formulas[]` 具名公式。

---

## 已核对的既有事实（勿重新发明）

- 前端 `QuotationStep2.tsx`：`resolveFormula(comp, name)`（`:319-358`，0 `formula_name` → 1 `formula_assignments` → 2 名称 → 3 位置）；`getFormulaDeps(formula)`（`:361-366`，取 `expression` 里 `type==='field'` 的 value）；`computeAllFormulas`（`:372`）收集 formulaFields（`:396-402` 用 resolveFormula）、建依赖（`:408-410` getFormulaDeps）、拓扑（Kahn）、逐字段求值（`:584-598` `evaluateExpression(ff.formula.expression, fieldValues, …)`）。`fieldValues` 为数字图；原始行 = 入参 `row`。
- 后端 `FormulaCalculator.java`：`collectFormulaFields`（`:689-703`，每 FORMULA 字段经 `resolveFormulaExpression` → `FormulaField(name, expr)`）；`resolveFormulaExpression`（`:712-746`，同前端 4 级）；`topoOrder`（`:765-801`，deps = expression 里 `type==field` 且属公式字段名）；`computeRows`（`:422-428` 逐字段 `evaluateExpression(ff.expression, ctx)`，`ctx.fieldValues` 数字 + `ctx.currentRowRaw` 原始）。`FormulaField`（`:668` 区，`{name, expression}`）。
- 既有条件求值器 `cpq-frontend/src/utils/conditionEngine.ts`（扁平字符串式，**无嵌套**）—— 不复用（语义/形态不符），本 Plan 新建结构化嵌套求值器。
- 既有扁平 `CardRef.CondRow`（Excel 跨表）—— 不复用。
- `ComponentService.validateFields`（`:381`）：本 Plan 加默认必填 + 环检测校验点。

---

## File Structure

- Create 前端 `cpq-frontend/src/utils/condTree.ts`：`CondTree` 类型 + `evalCondTree(tree, lookup)` + `condTreeColumns(tree)`。
- Create 后端 `cpq-backend/src/main/java/com/cpq/formula/CondTreeEvaluator.java`：`eval(JsonNode tree, Function<String,Object> lookup)` + `columns(JsonNode tree)`。
- Create 共享对账样本 `cpq-frontend/src/utils/__fixtures__/condtree-cases.json`（双引擎同读）。
- Modify 后端 `FormulaCalculator.java`：`FormulaField` 加条件配置；`collectFormulaFields` 识别 `conditional_formula`；`computeRows` 逐字段选表达式；`topoOrder` 并集依赖。
- Modify 前端 `QuotationStep2.tsx`：`computeAllFormulas` 收集/依赖/求值支持条件字段（抽 `resolveFormulaForRow`）。
- Modify 后端 `ComponentService.java`：默认必填 + 环检测校验。
- Modify 类型：`component/types.ts`（`FieldItem`）+ `QuotationStep2.tsx`（`ComponentField`）加 `conditional_formula`。
- Tests：后端 `CondTreeEvaluatorTest` + `FormulaCalculatorConditionalTest`；前端 `condTree.test.ts` + `conditionalFormula.test.ts`。

---

## Task 1: CondTree 类型 + 双引擎求值器 + 对账样本（TDD）

**Files:**
- Create: `cpq-frontend/src/utils/condTree.ts`
- Create: `cpq-frontend/src/utils/__fixtures__/condtree-cases.json`
- Create: `cpq-frontend/src/utils/condTree.test.ts`
- Create: `cpq-backend/src/main/java/com/cpq/formula/CondTreeEvaluator.java`
- Create: `cpq-backend/src/test/java/com/cpq/formula/CondTreeEvaluatorTest.java`

- [ ] **Step 1: 写共享对账样本**

`cpq-frontend/src/utils/__fixtures__/condtree-cases.json`：

```json
{
  "cases": [
    { "name": "leaf eq string true",
      "tree": { "kind":"leaf","left":"类型","op":"eq","rhs":{"type":"literal","value":"车削"} },
      "values": { "类型":"车削" }, "expected": true },
    { "name": "leaf eq string false",
      "tree": { "kind":"leaf","left":"类型","op":"eq","rhs":{"type":"literal","value":"车削"} },
      "values": { "类型":"铣削" }, "expected": false },
    { "name": "leaf gt numeric true",
      "tree": { "kind":"leaf","left":"数量","op":"gt","rhs":{"type":"literal","value":"10"} },
      "values": { "数量":15 }, "expected": true },
    { "name": "leaf gt numeric false",
      "tree": { "kind":"leaf","left":"数量","op":"gt","rhs":{"type":"literal","value":"10"} },
      "values": { "数量":5 }, "expected": false },
    { "name": "leaf in true",
      "tree": { "kind":"leaf","left":"类型","op":"in","rhs":{"type":"literal","value":"车削,铣削"} },
      "values": { "类型":"铣削" }, "expected": true },
    { "name": "leaf column compare",
      "tree": { "kind":"leaf","left":"投料量","op":"gt","rhs":{"type":"column","value":"回料量"} },
      "values": { "投料量":8, "回料量":3 }, "expected": true },
    { "name": "leaf ne true",
      "tree": { "kind":"leaf","left":"类型","op":"ne","rhs":{"type":"literal","value":"车削"} },
      "values": { "类型":"铣削" }, "expected": true },
    { "name": "group and true",
      "tree": { "kind":"group","logic":"and","children":[
        { "kind":"leaf","left":"类型","op":"eq","rhs":{"type":"literal","value":"车削"} },
        { "kind":"leaf","left":"数量","op":"gt","rhs":{"type":"literal","value":"10"} } ] },
      "values": { "类型":"车削","数量":12 }, "expected": true },
    { "name": "group and false",
      "tree": { "kind":"group","logic":"and","children":[
        { "kind":"leaf","left":"类型","op":"eq","rhs":{"type":"literal","value":"车削"} },
        { "kind":"leaf","left":"数量","op":"gt","rhs":{"type":"literal","value":"10"} } ] },
      "values": { "类型":"车削","数量":5 }, "expected": false },
    { "name": "nested (A and B) or C",
      "tree": { "kind":"group","logic":"or","children":[
        { "kind":"group","logic":"and","children":[
          { "kind":"leaf","left":"类型","op":"eq","rhs":{"type":"literal","value":"车削"} },
          { "kind":"leaf","left":"数量","op":"gt","rhs":{"type":"literal","value":"10"} } ] },
        { "kind":"leaf","left":"加急","op":"eq","rhs":{"type":"literal","value":"是"} } ] },
      "values": { "类型":"铣削","数量":1,"加急":"是" }, "expected": true },
    { "name": "empty and group true",
      "tree": { "kind":"group","logic":"and","children":[] },
      "values": {}, "expected": true },
    { "name": "missing column false",
      "tree": { "kind":"leaf","left":"缺失","op":"gt","rhs":{"type":"literal","value":"1"} },
      "values": {}, "expected": false }
  ]
}
```

- [ ] **Step 2: 写前端测试（读样本）**

`cpq-frontend/src/utils/condTree.test.ts`：

```ts
import { describe, it, expect } from 'vitest';
import { evalCondTree } from './condTree';
import cases from './__fixtures__/condtree-cases.json';

describe('evalCondTree 对账样本', () => {
  for (const c of (cases as any).cases) {
    it(c.name, () => {
      const lookup = (col: string) => (c.values as any)[col];
      expect(evalCondTree(c.tree, lookup)).toBe(c.expected);
    });
  }
});
```

- [ ] **Step 3: 跑前端测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/utils/condTree.test.ts 2>&1 | tail -6`
Expected: 失败（`evalCondTree` 未定义 / 导入错）。

- [ ] **Step 4: 写前端 condTree.ts**

```ts
export type CondOp = 'eq' | 'ne' | 'gt' | 'gte' | 'lt' | 'lte' | 'in';

export type CondTree =
  | { kind: 'group'; logic: 'and' | 'or'; children: CondTree[] }
  | { kind: 'leaf'; left: string; op: CondOp; rhs: { type: 'literal' | 'column'; value: string } };

/** lookup(col) 返回该列原始值（数字/字符串/undefined）。解析或求值异常 → false（保守不命中）。 */
export function evalCondTree(tree: CondTree | null | undefined, lookup: (col: string) => any): boolean {
  if (!tree) return true; // 空条件 = 默认分支（总为真）
  try {
    return evalNode(tree, lookup);
  } catch {
    return false;
  }
}

function evalNode(t: CondTree, lookup: (col: string) => any): boolean {
  if (t.kind === 'group') {
    const children = t.children || [];
    if (children.length === 0) return t.logic === 'and';
    return t.logic === 'and'
      ? children.every(c => evalNode(c, lookup))
      : children.some(c => evalNode(c, lookup));
  }
  const L = lookup(t.left);
  const R = t.rhs.type === 'column' ? lookup(t.rhs.value) : t.rhs.value;
  return cmp(t.op, L, R);
}

function cmp(op: CondOp, L: any, R: any): boolean {
  if (op === 'in') {
    if (L == null) return false;
    const set = String(R ?? '').split(',').map(s => s.trim());
    return set.includes(String(L).trim());
  }
  const ln = toNum(L), rn = toNum(R);
  if (op === 'gt' || op === 'gte' || op === 'lt' || op === 'lte') {
    if (ln == null || rn == null) return false;
    if (op === 'gt') return ln > rn;
    if (op === 'gte') return ln >= rn;
    if (op === 'lt') return ln < rn;
    return ln <= rn;
  }
  // eq / ne：数值优先，否则字符串
  let eq: boolean;
  if (ln != null && rn != null) eq = ln === rn;
  else eq = String(L ?? '') === String(R ?? '');
  return op === 'eq' ? eq : !eq;
}

function toNum(v: any): number | null {
  if (typeof v === 'number') return isNaN(v) ? null : v;
  if (v == null) return null;
  const n = parseFloat(String(v));
  return isNaN(n) ? null : n;
}

/** 收集条件树引用的列名（leaf.left + column 型 rhs），供拓扑依赖。 */
export function condTreeColumns(tree: CondTree | null | undefined): string[] {
  const out: string[] = [];
  const walk = (t: CondTree) => {
    if (t.kind === 'group') (t.children || []).forEach(walk);
    else { out.push(t.left); if (t.rhs.type === 'column') out.push(t.rhs.value); }
  };
  if (tree) walk(tree);
  return out;
}
```

- [ ] **Step 5: 跑前端测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/utils/condTree.test.ts 2>&1 | tail -6`
Expected: 12 passed。

- [ ] **Step 6: 写后端 CondTreeEvaluator（镜像语义）**

`cpq-backend/src/main/java/com/cpq/formula/CondTreeEvaluator.java`：

```java
package com.cpq.formula;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** CondTree 求值器（镜像前端 condTree.ts）。Plan 3a。 */
public final class CondTreeEvaluator {

    private CondTreeEvaluator() {}

    /** 空树/null → true（默认分支）；异常 → false（保守不命中）。 */
    public static boolean eval(JsonNode tree, Function<String, Object> lookup) {
        if (tree == null || tree.isNull() || tree.isMissingNode()) return true;
        try {
            return evalNode(tree, lookup);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean evalNode(JsonNode t, Function<String, Object> lookup) {
        String kind = t.path("kind").asText("");
        if ("group".equals(kind)) {
            boolean and = !"or".equals(t.path("logic").asText("and"));
            JsonNode children = t.path("children");
            if (!children.isArray() || children.size() == 0) return and;
            if (and) {
                for (JsonNode c : children) if (!evalNode(c, lookup)) return false;
                return true;
            } else {
                for (JsonNode c : children) if (evalNode(c, lookup)) return true;
                return false;
            }
        }
        // leaf
        String op = t.path("op").asText("eq");
        Object L = lookup.apply(t.path("left").asText(""));
        JsonNode rhs = t.path("rhs");
        Object R = "column".equals(rhs.path("type").asText("literal"))
            ? lookup.apply(rhs.path("value").asText("")) : rhs.path("value").asText("");
        return cmp(op, L, R);
    }

    private static boolean cmp(String op, Object L, Object R) {
        if ("in".equals(op)) {
            if (L == null) return false;
            List<String> set = new ArrayList<>();
            for (String s : String.valueOf(R == null ? "" : R).split(",")) set.add(s.trim());
            return set.contains(String.valueOf(L).trim());
        }
        Double ln = toNum(L), rn = toNum(R);
        switch (op) {
            case "gt": case "gte": case "lt": case "lte":
                if (ln == null || rn == null) return false;
                if ("gt".equals(op)) return ln > rn;
                if ("gte".equals(op)) return ln >= rn;
                if ("lt".equals(op)) return ln < rn;
                return ln <= rn;
            default: // eq / ne
                boolean eq = (ln != null && rn != null)
                    ? ln.doubleValue() == rn.doubleValue()
                    : String.valueOf(L == null ? "" : L).equals(String.valueOf(R == null ? "" : R));
                return "eq".equals(op) ? eq : !eq;
        }
    }

    private static Double toNum(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    /** 收集条件树引用列名（leaf.left + column 型 rhs）。 */
    public static List<String> columns(JsonNode tree) {
        List<String> out = new ArrayList<>();
        walk(tree, out);
        return out;
    }

    private static void walk(JsonNode t, List<String> out) {
        if (t == null || !t.isObject()) return;
        if ("group".equals(t.path("kind").asText(""))) {
            for (JsonNode c : t.path("children")) walk(c, out);
        } else {
            out.add(t.path("left").asText(""));
            JsonNode rhs = t.path("rhs");
            if ("column".equals(rhs.path("type").asText(""))) out.add(rhs.path("value").asText(""));
        }
    }
}
```

- [ ] **Step 7: 写后端测试（读同一样本）**

`cpq-backend/src/test/java/com/cpq/formula/CondTreeEvaluatorTest.java`：

```java
package com.cpq.formula;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/** 与前端 condTree.test.ts 同读对账样本，逐例真值一致。 */
class CondTreeEvaluatorTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TestFactory
    List<DynamicTest> reconcile() throws Exception {
        Path fixture = Path.of("..", "cpq-frontend", "src", "utils", "__fixtures__", "condtree-cases.json");
        JsonNode root = M.readTree(Files.readString(fixture));
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : root.path("cases")) {
            String name = c.path("name").asText("");
            tests.add(dynamicTest(name, () -> {
                JsonNode values = c.path("values");
                boolean actual = CondTreeEvaluator.eval(c.path("tree"), col -> {
                    JsonNode v = values.path(col);
                    if (v.isMissingNode() || v.isNull()) return null;
                    return v.isNumber() ? (Object) v.numberValue() : v.asText();
                });
                assertEquals(c.path("expected").asBoolean(), actual, "条件树漂移: " + name);
            }));
        }
        return tests;
    }
}
```

- [ ] **Step 8: 跑后端测试确认通过**

Run: `cd cpq-backend && ./mvnw -o test -Dtest=CondTreeEvaluatorTest 2>&1 | grep -E "Tests run:|BUILD" | grep -v Shutdown | tail -3`
Expected: `Tests run: 12, Failures: 0` + `BUILD SUCCESS`（双引擎逐例一致）。

- [ ] **Step 9: Commit**

```bash
git add cpq-frontend/src/utils/condTree.ts cpq-frontend/src/utils/__fixtures__/condtree-cases.json cpq-frontend/src/utils/condTree.test.ts cpq-backend/src/main/java/com/cpq/formula/CondTreeEvaluator.java cpq-backend/src/test/java/com/cpq/formula/CondTreeEvaluatorTest.java
git commit -m "feat(conditional-formula): CondTree 类型 + 双引擎求值器 + 对账样本(12 例一致)"
```

---

## Task 2: 后端 computeRows 条件公式解析（TDD）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/FormulaCalculatorConditionalTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

/** Plan 3a：条件公式逐行选公式。纯 JUnit。 */
class FormulaCalculatorConditionalTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final FormulaCalculator calc = new FormulaCalculator();
    private JsonNode j(String s) { try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); } }

    // 加工费：类型==车削 → 单价*1.2；类型==铣削 → 单价*1.5；默认 → 单价。
    private static final String FIELDS = "["
        + "{\"name\":\"类型\",\"fieldType\":\"BASIC_DATA\",\"basicDataPath\":\"v.t\"},"
        + "{\"name\":\"单价\",\"fieldType\":\"BASIC_DATA\",\"basicDataPath\":\"v.p\"},"
        + "{\"name\":\"加工费\",\"fieldType\":\"FORMULA\",\"conditional_formula\":{"
        + "  \"rules\":["
        + "    {\"when\":{\"kind\":\"leaf\",\"left\":\"类型\",\"op\":\"eq\",\"rhs\":{\"type\":\"literal\",\"value\":\"车削\"}},\"formula\":\"f_turn\"},"
        + "    {\"when\":{\"kind\":\"leaf\",\"left\":\"类型\",\"op\":\"eq\",\"rhs\":{\"type\":\"literal\",\"value\":\"铣削\"}},\"formula\":\"f_mill\"}"
        + "  ],\"default\":\"f_base\"}}"
        + "]";
    private static final String FORMULAS = "["
        + "{\"name\":\"f_turn\",\"expression\":[{\"type\":\"field\",\"value\":\"单价\"},{\"type\":\"operator\",\"value\":\"*\"},{\"type\":\"number\",\"value\":\"1.2\"}]},"
        + "{\"name\":\"f_mill\",\"expression\":[{\"type\":\"field\",\"value\":\"单价\"},{\"type\":\"operator\",\"value\":\"*\"},{\"type\":\"number\",\"value\":\"1.5\"}]},"
        + "{\"name\":\"f_base\",\"expression\":[{\"type\":\"field\",\"value\":\"单价\"}]}"
        + "]";
    private static final String RKF = "[\"k\"]";
    private static final String BASEROWS = "["
        + "{\"driverRow\":{\"k\":\"r0\"},\"basicDataValues\":{\"{v.t}\":\"车削\",\"{v.p}\":100}},"
        + "{\"driverRow\":{\"k\":\"r1\"},\"basicDataValues\":{\"{v.t}\":\"铣削\",\"{v.p}\":100}},"
        + "{\"driverRow\":{\"k\":\"r2\"},\"basicDataValues\":{\"{v.t}\":\"钻孔\",\"{v.p}\":100}}"
        + "]";

    @Test
    void conditionalPicksFormulaPerRow() {
        JsonNode fr = calc.calculate(j(FIELDS), j(FORMULAS), null, j(RKF), j(BASEROWS), j("[]"),
            new HashMap<>(), new HashMap<>(), new HashMap<>());
        java.util.Map<String, JsonNode> byKey = new HashMap<>();
        for (JsonNode r : fr) byKey.put(r.path("rowKey").asText(), r);
        assertEquals(120.0, byKey.get("r0").path("values").path("加工费").asDouble(), 1e-9); // 车削 *1.2
        assertEquals(150.0, byKey.get("r1").path("values").path("加工费").asDouble(), 1e-9); // 铣削 *1.5
        assertEquals(100.0, byKey.get("r2").path("values").path("加工费").asDouble(), 1e-9); // 默认 *1
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -o test -Dtest=FormulaCalculatorConditionalTest 2>&1 | grep -E "Tests run:|expected|BUILD" | grep -v Shutdown | head`
Expected: 失败（当前 collectFormulaFields 不识别 conditional_formula，FORMULA 字段 expr=null 被跳过 → 加工费=0/缺失）。

- [ ] **Step 3: 实现 —— FormulaField 加条件配置 + collectFormulaFields 识别 + computeRows 选表达式 + topoOrder 并集依赖**

3.1 扩展 `FormulaField`（`:668` 区）：

```java
    private static class FormulaField {
        final String name;
        final JsonNode expression;          // 单一模式：表达式；条件模式：null
        final List<CondRule> rules;         // 条件模式：有序规则；单一模式：null
        final JsonNode defaultExpression;   // 条件模式：默认公式表达式
        FormulaField(String name, JsonNode expression) {
            this.name = name; this.expression = expression; this.rules = null; this.defaultExpression = null;
        }
        FormulaField(String name, List<CondRule> rules, JsonNode defaultExpression) {
            this.name = name; this.expression = null; this.rules = rules; this.defaultExpression = defaultExpression;
        }
        boolean isConditional() { return rules != null; }
    }

    private static class CondRule {
        final JsonNode when;        // CondTree
        final JsonNode expression;  // 命中后执行的公式表达式
        CondRule(JsonNode when, JsonNode expression) { this.when = when; this.expression = expression; }
    }
```

3.2 `collectFormulaFields`（`:689-703`）识别条件模式：

```java
    private List<FormulaField> collectFormulaFields(JsonNode fields, JsonNode formulas,
                                                    JsonNode formulaAssignments) {
        List<FormulaField> out = new ArrayList<>();
        if (fields == null || !fields.isArray()) return out;
        int fullIdx = 0;
        for (JsonNode f : fields) {
            if ("FORMULA".equals(fieldType(f))) {
                String name = fieldName(f);
                JsonNode cf = f.path("conditional_formula");
                if (cf.isObject() && cf.path("rules").isArray()) {
                    // 条件模式（优先级最高）
                    List<CondRule> rules = new ArrayList<>();
                    for (JsonNode rule : cf.path("rules")) {
                        JsonNode expr = exprOfFormula(formulas, rule.path("formula").asText(null));
                        if (expr != null) rules.add(new CondRule(rule.path("when"), expr));
                    }
                    JsonNode defExpr = exprOfFormula(formulas, cf.path("default").asText(null));
                    out.add(new FormulaField(name, rules, defExpr));
                } else {
                    JsonNode expr = resolveFormulaExpression(f, name, fields, formulas, formulaAssignments, fullIdx);
                    if (expr != null) out.add(new FormulaField(name, expr));
                }
            }
            fullIdx++;
        }
        return out;
    }

    /** 按公式名取 expression（null/找不到 → null）。 */
    private JsonNode exprOfFormula(JsonNode formulas, String name) {
        if (name == null || name.isEmpty()) return null;
        JsonNode found = findFormulaByName(formulas, name);
        return found != null ? found.path("expression") : null;
    }
```

3.3 `computeRows` 逐字段循环（`:422-428`）按条件选表达式：

```java
            Map<String, Double> results = new LinkedHashMap<>();
            for (String name : order) {
                FormulaField ff = findByName(formulaFields, name);
                if (ff == null) continue;
                ctx.previousRowSubtotal = (prevRowValues == null) ? null : prevRowValues.get(name);
                JsonNode expr = ff.isConditional() ? selectConditionalExpr(ff, ctx) : ff.expression;
                double val = expr != null ? evaluateExpression(expr, ctx).doubleValue() : 0.0;
                results.put(name, val);
                ctx.fieldValues.put(name, val);
            }
```

加选择器（用 CondTreeEvaluator + ctx 列值查找；优先已算字段值，否则原始行）：

```java
    private JsonNode selectConditionalExpr(FormulaField ff, RowContext ctx) {
        java.util.function.Function<String, Object> lookup = col -> {
            Double fv = ctx.fieldValues.get(col);
            if (fv != null) return fv;
            Object raw = ctx.currentRowRaw != null ? ctx.currentRowRaw.get(col) : null;
            return raw;
        };
        for (CondRule r : ff.rules) {
            if (com.cpq.formula.CondTreeEvaluator.eval(r.when, lookup)) return r.expression;
        }
        return ff.defaultExpression;
    }
```

3.4 `topoOrder`（`:765-801`）并集依赖：把 deps 提取改为覆盖条件字段：

```java
        for (FormulaField ff : formulaFields) {
            List<String> d = new ArrayList<>();
            java.util.function.BiConsumer<JsonNode, List<String>> addExprDeps = (expr, acc) -> {
                if (expr != null && expr.isArray())
                    for (JsonNode t : expr)
                        if ("field".equals(t.path("type").asText("")) && nameSet.contains(t.path("value").asText("")))
                            acc.add(t.path("value").asText(""));
            };
            if (ff.isConditional()) {
                for (CondRule r : ff.rules) {
                    for (String c : com.cpq.formula.CondTreeEvaluator.columns(r.when)) if (nameSet.contains(c)) d.add(c);
                    addExprDeps.accept(r.expression, d);
                }
                addExprDeps.accept(ff.defaultExpression, d);
            } else {
                addExprDeps.accept(ff.expression, d);
            }
            deps.put(ff.name, d);
        }
```

> 注：`RowContext.currentRowRaw` 已存在（`:418` 设置 `ctx.currentRowRaw = toRawRowMap(mergedRow)`）。

- [ ] **Step 4: 跑测试确认通过 + 回归**

Run: `cd cpq-backend && ./mvnw -o test -Dtest='FormulaCalculatorConditionalTest,FormulaCalculatorTest,FormulaCalculator*' 2>&1 | grep -E "Tests run:|BUILD" | grep -v Shutdown | tail -5`
Expected: 新测试 1 passed；`FormulaCalculatorTest` 16 + 其余条件/累加/多列回归全绿（单一模式不受影响）。

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java cpq-backend/src/test/java/com/cpq/quotation/service/FormulaCalculatorConditionalTest.java
git commit -m "feat(conditional-formula): 后端 computeRows 逐行选条件公式 + 并集拓扑依赖"
```

---

## Task 3: 前端 computeAllFormulas 条件公式解析（TDD）

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（`computeAllFormulas` `:372` / `getFormulaDeps` `:361`）
- Test: `cpq-frontend/src/pages/quotation/conditionalFormula.test.ts`

- [ ] **Step 1: 写失败测试（复用 computeRowsCachesForTest 或新薄包装）**

```ts
import { describe, it, expect } from 'vitest';
import { computeRowsCachesForTest } from './QuotationStep2';

const comp: any = {
  componentId: 'c1', componentCode: 'C1', tabName: 'T',
  fields: [
    { name: '类型', field_type: 'INPUT' },
    { name: '单价', field_type: 'INPUT_NUMBER' },
    { name: '加工费', field_type: 'FORMULA', conditional_formula: {
      rules: [
        { when: { kind: 'leaf', left: '类型', op: 'eq', rhs: { type: 'literal', value: '车削' } }, formula: 'f_turn' },
        { when: { kind: 'leaf', left: '类型', op: 'eq', rhs: { type: 'literal', value: '铣削' } }, formula: 'f_mill' },
      ],
      default: 'f_base',
    } },
  ],
  formulas: [
    { name: 'f_turn', expression: [{ type: 'field', value: '单价' }, { type: 'operator', value: '*' }, { type: 'number', value: '1.2' }] },
    { name: 'f_mill', expression: [{ type: 'field', value: '单价' }, { type: 'operator', value: '*' }, { type: 'number', value: '1.5' }] },
    { name: 'f_base', expression: [{ type: 'field', value: '单价' }] },
  ],
};

describe('条件公式逐行选公式（Plan 3a）', () => {
  it('车削*1.2 / 铣削*1.5 / 默认*1', () => {
    const caches = computeRowsCachesForTest(comp, [
      { 类型: '车削', 单价: 100 }, { 类型: '铣削', 单价: 100 }, { 类型: '钻孔', 单价: 100 },
    ]);
    expect(caches.map(c => c['加工费'])).toEqual([120, 150, 100]);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/conditionalFormula.test.ts 2>&1 | tail -8`
Expected: 失败（加工费 = null/0，conditional_formula 未识别）。

- [ ] **Step 3: 实现 computeAllFormulas 条件支持**

3.1 顶部 import：`import { evalCondTree, condTreeColumns, type CondTree } from '../../utils/condTree';`

3.2 收集 formulaFields（`:396-402`）改为带条件信息：把 `formulaFields: { name; formula }[]` 升级为 `{ name; formula?: ComponentFormula; conditional?: { rules: { when: CondTree; formula: ComponentFormula }[]; default?: ComponentFormula } }`：

```tsx
  const formulaFields: { name: string; formula?: ComponentFormula;
    conditional?: { rules: { when: CondTree; formula: ComponentFormula }[]; default?: ComponentFormula } }[] = [];
  for (const f of comp.fields) {
    if (f.field_type !== 'FORMULA') continue;
    const name = f.name || f.key || '';
    const cf = (f as any).conditional_formula;
    if (cf && Array.isArray(cf.rules)) {
      const rules = cf.rules
        .map((r: any) => ({ when: r.when as CondTree, formula: comp.formulas!.find(x => x.name === r.formula)! }))
        .filter((r: any) => r.formula);
      const def = cf.default ? comp.formulas!.find(x => x.name === cf.default) : undefined;
      formulaFields.push({ name, conditional: { rules, default: def } });
    } else {
      const formula = resolveFormula(comp, name);
      if (formula) formulaFields.push({ name, formula });
    }
  }
```

3.3 依赖（`:408-410`）并集：

```tsx
  const deps: Record<string, string[]> = {};
  for (const ff of formulaFields) {
    const d = new Set<string>();
    if (ff.conditional) {
      for (const r of ff.conditional.rules) {
        condTreeColumns(r.when).forEach(c => { if (formulaNameSet.has(c)) d.add(c); });
        getFormulaDeps(r.formula).forEach(c => { if (formulaNameSet.has(c)) d.add(c); });
      }
      if (ff.conditional.default) getFormulaDeps(ff.conditional.default).forEach(c => { if (formulaNameSet.has(c)) d.add(c); });
    } else if (ff.formula) {
      getFormulaDeps(ff.formula).forEach(c => { if (formulaNameSet.has(c)) d.add(c); });
    }
    deps[ff.name] = [...d];
  }
```

> `formulaNameSet`（`:406`）保持不变。

3.4 求值循环（`:584-598`）按条件选表达式（lookup 优先已算 fieldValues，否则原始 row）：

```tsx
  const results: Record<string, number | null> = {};
  for (const name of order) {
    const ff = formulaFields.find(f => f.name === name)!;
    try {
      const prevForField = previousRowValues
        ? (typeof previousRowValues[name] === 'number' ? (previousRowValues[name] as number) : undefined)
        : previousRowSubtotal;
      let expr: any[] | undefined;
      if (ff.conditional) {
        const lookup = (col: string) => (col in fieldValues ? fieldValues[col] : (row as any)[col]);
        const hit = ff.conditional.rules.find(r => evalCondTree(r.when, lookup));
        expr = (hit ? hit.formula : ff.conditional.default)?.expression;
      } else {
        expr = ff.formula!.expression;
      }
      const val = expr
        ? evaluateExpression(
            expr, fieldValues, allComponentSubtotals || {}, undefined, quotationFields,
            pathCache, partNo, basicDataValues, prevForField, globalVariableDefs, row, crossTabRows,
          )
        : null;
      results[name] = val;
      if (val != null) fieldValues[name] = val;
    } catch {
      results[name] = null;
    }
  }
  return results;
```

- [ ] **Step 4: 跑测试 + tsc**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/conditionalFormula.test.ts src/pages/quotation/computeMultiSubtotal.test.ts src/pages/quotation/prevRowPerColumn.test.ts 2>&1 | tail -8`
Expected: 全 passed（条件 [120,150,100] + 多小计/累加回归不变）。
Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json 2>&1 | tail -5`
Expected: 0 错误。

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx cpq-frontend/src/pages/quotation/conditionalFormula.test.ts
git commit -m "feat(conditional-formula): 前端 computeAllFormulas 逐行选条件公式 + 并集依赖"
```

---

## Task 4: 类型字段 + 保存期校验（默认必填 + 环检测）

**Files:**
- Modify: `cpq-frontend/src/pages/component/types.ts`（`FieldItem`）
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（`ComponentField` 类型，若与 FieldItem 不同源）
- Modify: `cpq-backend/src/main/java/com/cpq/component/service/ComponentService.java`（`validateFields`）

- [ ] **Step 1: 前端类型加 conditional_formula**

`component/types.ts` 的 `FieldItem`（`is_subtotal?: boolean` `:58` 旁）加：

```ts
  /** Plan 3a：条件公式。存在即走条件模式（优先于 formula_name）。 */
  conditional_formula?: {
    rules: { when: any; formula: string }[];
    default: string;
  };
```

`QuotationStep2.tsx` 的 `ComponentField`（含 `is_subtotal?`）同样加 `conditional_formula?`（若是独立类型；若 import 自 types.ts 则免）。先 grep `interface ComponentField` 确认。

- [ ] **Step 2: 后端 validateFields 校验默认必填 + 环检测**

`ComponentService.validateFields`（`:381` 循环内每 field）加：

```java
            // Plan 3a：条件公式校验。
            Object cf = field.get("conditional_formula");
            if (cf instanceof Map<?, ?> cfm) {
                Object def = cfm.get("default");
                if (def == null || String.valueOf(def).isBlank()) {
                    throw new BusinessException("字段「" + field.get("name") + "」条件公式缺少默认公式（default）");
                }
                Object rules = cfm.get("rules");
                if (!(rules instanceof java.util.List<?> rl) || rl.isEmpty()) {
                    throw new BusinessException("字段「" + field.get("name") + "」条件公式至少需 1 条规则");
                }
            }
```

> 环检测：本 Plan 依赖运行期 `topoOrder` 已把环成员追加尾部（不崩，但结果可能不准）。**保存期硬环检测**用一个独立校验：复用后端依赖图思路对 `conditional_formula` 涉及列建图跑 Kahn，剩余即环 → 抛错。鉴于该图构建需 fields+formulas 联合解析，作为 Task 4 的**第二步**实现（见下）。

- [ ] **Step 3: 后端保存期环检测**

在 `ComponentService` 加私有方法 `detectFormulaCycle(fields, formulas)`（镜像 `FormulaCalculator.topoOrder` 的并集依赖建图，含 conditional 列）；`validateFields` 末尾调用，发现环抛 `BusinessException("公式存在循环引用: …")`。实现照 `FormulaCalculator.topoOrder`（Task 2 Step 3.4）的 deps 提取逻辑（条件树列 ∪ 候选公式列），Kahn 后 `order.size() != formulaFields.size()` → 有环。

> 执行时：把 Task 2 的 deps 提取抽成可复用静态工具（如 `FormulaDependencyGraph.cyclicNodes(fields, formulas)`），ComponentService 与 FormulaCalculator 共用，避免两份漂移。

- [ ] **Step 4: 验证**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json 2>&1 | tail -5` → 0 错误。
Run: `cd cpq-backend && ./mvnw -o test -Dtest='ComponentServiceTest,FormulaCalculator*' 2>&1 | grep -E "Tests run:|BUILD" | grep -v Shutdown | tail -4` → 全绿（无则仅跑 FormulaCalculator*）。
补一个 ComponentService 校验单测（默认缺失抛错 / 环抛错）。

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/component/types.ts cpq-frontend/src/pages/quotation/QuotationStep2.tsx cpq-backend/src/main/java/com/cpq/component/service/ComponentService.java cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java
git commit -m "feat(conditional-formula): conditional_formula 类型 + 保存期默认必填/环检测"
```

---

## Task 5: census + E2E + 自检

- [ ] **Step 1: AP-44 census（conditional_formula 传播点）**

`conditional_formula` 是字段配置新键，沿用 `formula_name` 的传播链（snapshot / enrich / cache）。逐项确认 conditional_formula 不被中途丢弃：
Run: `grep -rnE "formula_name|formulaName" cpq-frontend/src/pages/quotation/enrichComponentData.ts cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java | head`
对照每个 formula_name 传播点，确认 conditional_formula 同样随 `field` 整体透传（多数处是整 field 对象透传，无需逐键——确认无"只挑选已知键"的白名单丢弃；若有，补 conditional_formula）。

- [ ] **Step 2: E2E（改了 computeAllFormulas / FormulaCalculator 协议级）**

```bash
cd cpq-backend && touch src/main/java/com/cpq/quotation/service/FormulaCalculator.java && sleep 9
cd ../cpq-frontend && rm -f e2e/screenshots/qf-*.png
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list 2>&1 | tail -8
```
Expected: `1 passed`，`'加载中' final count = 0`（现有模板均单一模式 → 回归证明条件分支零侵入）。

- [ ] **Step 3: 自检声明（CLAUDE.md 强制）**

形如：
> `CondTreeEvaluatorTest` 12 + `condTree.test.ts` 12 双引擎对账一致 ✅；后端 `FormulaCalculatorConditionalTest` 1 + 前端 `conditionalFormula.test.ts` 1（120/150/100）✅；`FormulaCalculator*`/多小计/累加回归绿 ✅；tsc 0 错误 ✅；E2E quotation-flow 1 passed + 加载中=0 ✅。

---

## Self-Review（写后自检）

**Spec coverage（设计 A/B/C）：**
- 绑定结构 1:N 条件→公式 + 默认 → Task 1（CondTree）+ Task 2/3（解析）✅
- 完整 AND/OR 嵌套（Q2=C）→ CondTree group 递归 ✅
- 任意公式列通用（Q11=B）→ collectFormulaFields/computeAllFormulas 对所有 FORMULA 字段识别 conditional_formula ✅
- 条件引用公式列（Q12=B）→ 并集依赖 + lookup 优先已算 fieldValues ✅
- 首条命中即停 + 默认兜底（Q6=C）→ selectConditionalExpr / rules.find ✅
- 双引擎一致 → 共享对账样本 ✅
- 默认必填 + 环检测 → Task 4 ✅

**Placeholder scan：** Task 4 Step 3 环检测"抽成可复用静态工具"——给了明确做法（镜像 topoOrder deps + Kahn）+ 复用动机，非占位；Task 4 Step 1 "grep interface ComponentField 确认"是类型同源核对手段。其余完整代码。

**Type consistency：** `CondTree`（TS）/`conditional_formula.rules[].when`（JSON）/`CondRule.when`（Java）一致；`evalCondTree(tree, lookup)` ↔ `CondTreeEvaluator.eval(tree, lookup)` 签名镜像；`condTreeColumns` ↔ `CondTreeEvaluator.columns`。

**边界/兼容：** 无 conditional_formula 的字段 100% 走旧 resolveFormula（零行为变化，回归测试守）；条件模式优先级最高（高于 formula_name）。

**后续（明确不在本 Plan）：** Plan 3b 配置 UI（FieldConfigTable 条件模式 + 嵌套 AND/OR 树构建器）；Plan 3c AP-44 三视图（ReadonlyProductCard 等）+ 完整 E2E。本 Plan 引擎就绪后，UI 产出的 conditional_formula JSON 即可被求值。
