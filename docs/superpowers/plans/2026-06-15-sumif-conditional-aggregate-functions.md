# SUMIF 条件聚合函数族 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给报价/核价公式新增 `SUMIF/COUNTIF/AVGIF/MINIF/MAXIF` 条件聚合函数族，组件线分行对齐宿主行键、EXCEL/小计线出单值。

**Architecture:** 不另起引擎。新增一个**规范 predicate 模型（布尔树 JSON）+ 解析器 + 求值器**，作为后端 `com.cpq.formula.predicate` 包，被组件线 `FormulaCalculator.evalCrossTab` 与 EXCEL 线 `TemplateFormulaService` 两条**后端** Java 路径共用；前端 `formulaEngine.ts` 实现一份 TS 镜像求值器供组件线浏览器实时求值。组件线把 predicate 作为 `cross_tab_ref` token 的可选字段（缺省空 = 现状，纯增量零回归）；EXCEL 线把 `XXXIF(cond, expr)` 文本解析成 predicate 后逐行过滤。

**Tech Stack:** Java 17 + Quarkus（JUnit 5）；React + TypeScript（Vitest）；Playwright E2E。

**权威依据:** `docs/superpowers/specs/2026-06-15-sumif-conditional-aggregate-functions-design.md`（取向 A 已锁定）。

**强制纪律（来自 CLAUDE.md）:**
- 本特性属核心基线，**必须在隔离 worktree 分支**开发（执行计划前用 `superpowers:using-git-worktrees` 建）。
- 协议级改动跑**双 E2E spec**（`quotation-flow.spec.ts` + `composite-product-flow.spec.ts`）。
- 后端测试在 **worktree 的 `cpq-backend/`** 用 `./mvnw` 跑（见记忆 `cpq-worktree-maven-test-tree`）。
- 提交只 `git add` 本次明确改动文件，严禁 `git add -A`。

---

## Predicate 规范数据结构（全计划共用，先读这里）

predicate 是一棵布尔树 JSON，作为 `cross_tab_ref` token 的可选字段 `predicate`（缺省 `null` = 无附加过滤 = 现状）：

```jsonc
// 布尔节点
{ "bool": "AND" | "OR", "children": [<predicate>, ...] }
// 比较节点
{ "op": "=" | "!=" | "<>" | ">" | "<" | ">=" | "<=", "lhs": <operand>, "rhs": <operand> }
// 操作数（三选一）
{ "kind": "sourceField", "field": "字段名" }   // 取 source(A) 行字段（evalCrossTab 的 arow）
{ "kind": "hostField",   "field": "字段名" }   // 取宿主(B)行字段（currentRowRaw / hostRow）
{ "kind": "literal",     "value": "管理费" }   // 字面量（字符串或数字，统一存字符串，求值时再判数）
```

**求值语义（Java 与 TS 必须逐字一致）：**
- 取值：`sourceField` → arow[field]；`hostField` → hostRow[field]；`literal` → value 原文。
- `blank(x)` = `x == null || String(x).trim() == ""`。
- `=`：任一 blank → false；两边都可 `Number()` 解析 → 数值相等；否则 trim 文本相等。（与现有 `valEquals`/`keyEq` 同口径）
- `!=` / `<>`：等于 `!(=)`。
- `> < >= <=`：两边都能解析为数 → 数值比较；任一不可解析或 blank → false。
- 布尔 `AND`：children 全 true；`OR`：任一 true；children 为空 → AND→true、OR→false。
- predicate 为 `null`/缺省 → 视为 true（不过滤）。

**文本语法 ↔ predicate（解析器，仅 EXCEL 线文本录入用；组件线由抽屉直接产出 JSON）：**
```
SUMIF([页签A.字段a] = '管理费' AND [页签A.金额] > 1000, [页签A.金额]*[宿主.数量])
        └────────────── cond（→ predicate）──────────────┘ └──── valueExpr ────┘
```

---

## 文件结构

**后端（新增）**
- `cpq-backend/src/main/java/com/cpq/formula/predicate/ConditionPredicate.java` — 不可变模型（sealed 接口 + record）。
- `cpq-backend/src/main/java/com/cpq/formula/predicate/ConditionPredicateEvaluator.java` — 模型 + 行取值函数 → boolean。
- `cpq-backend/src/main/java/com/cpq/formula/predicate/ConditionPredicateParser.java` — 文本 → 模型（递归下降）。
- `cpq-backend/src/main/java/com/cpq/formula/predicate/ConditionPredicateJson.java` — JSON(JsonNode) ↔ 模型 互转。

**后端（修改）**
- `.../quotation/service/FormulaCalculator.java:264-296` — `evalCrossTab` hits 过滤叠加 predicate。
- `.../template/service/TemplateFormulaService.java` — 新增 `XXXIF` 识别 + SUMIF 分支。
- `.../component/formula/TokenMappabilityValidator.java` — 允许并校验 `predicate` 字段。

**前端（修改）**
- `cpq-frontend/src/utils/formulaEngine.ts` — `ExpressionToken.predicate` 字段 + TS predicate 求值器 + `aggregateRows` 叠加过滤。
- `cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx` — 条件构造器 UI + 点击生成 SUMIF token。

**测试（新增）**
- `cpq-backend/src/test/java/com/cpq/formula/predicate/ConditionPredicateEvaluatorTest.java`
- `cpq-backend/src/test/java/com/cpq/formula/predicate/ConditionPredicateParserTest.java`
- `cpq-backend/src/test/java/com/cpq/quotation/service/FormulaCalculatorPredicateTest.java`
- `cpq-backend/src/test/java/com/cpq/template/service/TemplateFormulaSumifTest.java`
- `cpq-frontend/src/utils/__tests__/formulaEnginePredicate.test.ts`（含与后端共享用例集 parity）
- E2E：扩 `cpq-frontend/e2e/quotation-flow.spec.ts`（SIMPLE）+ `composite-product-flow.spec.ts`（COMPOSITE）。

---

## Phase 1 — 后端 predicate 核心（纯逻辑，TDD）

### Task 1: predicate 模型

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/formula/predicate/ConditionPredicate.java`

- [ ] **Step 1: 写模型（无行为，先让后续测试可编译）**

```java
package com.cpq.formula.predicate;

import java.util.List;

/** 条件 predicate 不可变模型。见 plan「Predicate 规范数据结构」。 */
public sealed interface ConditionPredicate
        permits ConditionPredicate.Bool, ConditionPredicate.Comparison {

    enum BoolOp { AND, OR }

    /** 比较运算符；TEXT/parse 时把 "<>" 归一到 NE。 */
    enum CmpOp {
        EQ("="), NE("!="), GT(">"), LT("<"), GE(">="), LE("<=");
        public final String text;
        CmpOp(String t) { this.text = t; }
        public static CmpOp from(String s) {
            return switch (s.trim()) {
                case "=" -> EQ;
                case "!=", "<>" -> NE;
                case ">" -> GT;
                case "<" -> LT;
                case ">=" -> GE;
                case "<=" -> LE;
                default -> throw new IllegalArgumentException("未知运算符: " + s);
            };
        }
    }

    sealed interface Operand permits SourceField, HostField, Literal {}
    record SourceField(String field) implements Operand {}
    record HostField(String field) implements Operand {}
    record Literal(String value) implements Operand {}

    record Bool(BoolOp op, List<ConditionPredicate> children) implements ConditionPredicate {}
    record Comparison(CmpOp op, Operand lhs, Operand rhs) implements ConditionPredicate {}
}
```

- [ ] **Step 2: 编译验证**

Run（worktree 内）: `cd cpq-backend && ./mvnw -q -o compile`
Expected: BUILD SUCCESS（无报错）。

- [ ] **Step 3: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/formula/predicate/ConditionPredicate.java
git commit -m "feat(predicate): condition predicate model"
```

---

### Task 2: predicate 求值器

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/formula/predicate/ConditionPredicateEvaluator.java`
- Test: `cpq-backend/src/test/java/com/cpq/formula/predicate/ConditionPredicateEvaluatorTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.cpq.formula.predicate;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static com.cpq.formula.predicate.ConditionPredicate.*;
import static org.junit.jupiter.api.Assertions.*;

class ConditionPredicateEvaluatorTest {
    private final ConditionPredicateEvaluator ev = new ConditionPredicateEvaluator();

    private boolean eval(ConditionPredicate p, Map<String,Object> arow, Map<String,Object> host) {
        return ev.test(p, arow, host);
    }

    @Test void null_predicate_is_true() {
        assertTrue(eval(null, Map.of(), Map.of()));
    }

    @Test void source_field_eq_literal_text() {
        var p = new Comparison(CmpOp.EQ, new SourceField("类型"), new Literal("管理费"));
        assertTrue(eval(p, Map.of("类型","管理费"), Map.of()));
        assertFalse(eval(p, Map.of("类型","运费"), Map.of()));
    }

    @Test void numeric_eq_ignores_format() {
        var p = new Comparison(CmpOp.EQ, new SourceField("n"), new Literal("1000"));
        assertTrue(eval(p, Map.of("n", "1000.0"), Map.of()));
    }

    @Test void source_vs_host_field_eq() {
        var p = new Comparison(CmpOp.EQ, new SourceField("a"), new HostField("b"));
        assertTrue(eval(p, Map.of("a","X"), Map.of("b","X")));
        assertFalse(eval(p, Map.of("a","X"), Map.of("b","Y")));
    }

    @Test void blank_makes_eq_false_and_ne_true() {
        var eq = new Comparison(CmpOp.EQ, new SourceField("a"), new Literal("x"));
        var ne = new Comparison(CmpOp.NE, new SourceField("a"), new Literal("x"));
        var blankRow = new java.util.HashMap<String,Object>(); blankRow.put("a", null);
        assertFalse(eval(eq, blankRow, Map.of()));
        assertTrue(eval(ne, blankRow, Map.of()));
    }

    @Test void gt_numeric_only() {
        var p = new Comparison(CmpOp.GT, new SourceField("金额"), new Literal("1000"));
        assertTrue(eval(p, Map.of("金额","1500"), Map.of()));
        assertFalse(eval(p, Map.of("金额","500"), Map.of()));
        assertFalse(eval(p, Map.of("金额","非数字"), Map.of())); // 不可解析 → false
    }

    @Test void and_or_nesting() {
        var c1 = new Comparison(CmpOp.EQ, new SourceField("类型"), new Literal("管理费"));
        var c2 = new Comparison(CmpOp.GT, new SourceField("金额"), new Literal("1000"));
        var and = new Bool(BoolOp.AND, java.util.List.of(c1, c2));
        var or  = new Bool(BoolOp.OR,  java.util.List.of(c1, c2));
        assertTrue(eval(and, Map.of("类型","管理费","金额","1500"), Map.of()));
        assertFalse(eval(and, Map.of("类型","管理费","金额","500"), Map.of()));
        assertTrue(eval(or, Map.of("类型","运费","金额","1500"), Map.of()));
        assertFalse(eval(or, Map.of("类型","运费","金额","500"), Map.of()));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest=ConditionPredicateEvaluatorTest`
Expected: 编译失败（`ConditionPredicateEvaluator` 不存在）。

- [ ] **Step 3: 实现求值器**

```java
package com.cpq.formula.predicate;

import java.util.Map;
import static com.cpq.formula.predicate.ConditionPredicate.*;

/** predicate 求值：(arow 源行, hostRow 宿主行) → boolean。语义见 plan「求值语义」。 */
public class ConditionPredicateEvaluator {

    public boolean test(ConditionPredicate p, Map<String,Object> arow, Map<String,Object> hostRow) {
        if (p == null) return true;
        if (p instanceof Bool b) {
            if (b.op() == BoolOp.AND) {
                for (ConditionPredicate c : b.children()) if (!test(c, arow, hostRow)) return false;
                return true;
            } else {
                for (ConditionPredicate c : b.children()) if (test(c, arow, hostRow)) return true;
                return false;
            }
        }
        Comparison c = (Comparison) p;
        Object lv = resolve(c.lhs(), arow, hostRow);
        Object rv = resolve(c.rhs(), arow, hostRow);
        return switch (c.op()) {
            case EQ -> valEquals(lv, rv);
            case NE -> !valEquals(lv, rv);
            case GT -> cmp(lv, rv) != null && cmp(lv, rv) > 0;
            case LT -> cmp(lv, rv) != null && cmp(lv, rv) < 0;
            case GE -> cmp(lv, rv) != null && cmp(lv, rv) >= 0;
            case LE -> cmp(lv, rv) != null && cmp(lv, rv) <= 0;
        };
    }

    private Object resolve(Operand o, Map<String,Object> arow, Map<String,Object> hostRow) {
        if (o instanceof SourceField s) return arow == null ? null : arow.get(s.field());
        if (o instanceof HostField h)   return hostRow == null ? null : hostRow.get(h.field());
        return ((Literal) o).value();
    }

    private static boolean isBlank(Object o) {
        return o == null || (o instanceof String s && s.isBlank())
                || (!(o instanceof String) && String.valueOf(o).isBlank());
    }

    private static Double num(Object o) {
        if (o == null) return null;
        try { return Double.valueOf(String.valueOf(o).trim()); } catch (Exception e) { return null; }
    }

    /** 与现有 valEquals/keyEq 同口径。 */
    private boolean valEquals(Object a, Object b) {
        if (isBlank(a) || isBlank(b)) return false;
        Double na = num(a), nb = num(b);
        if (na != null && nb != null) return na.doubleValue() == nb.doubleValue();
        return String.valueOf(a).trim().equals(String.valueOf(b).trim());
    }

    /** 数值比较；任一不可解析/blank → null（调用方据此判 false）。 */
    private Integer cmp(Object a, Object b) {
        if (isBlank(a) || isBlank(b)) return null;
        Double na = num(a), nb = num(b);
        if (na == null || nb == null) return null;
        return Double.compare(na, nb);
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest=ConditionPredicateEvaluatorTest`
Expected: Tests run, 0 failures。

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/formula/predicate/ConditionPredicateEvaluator.java \
        cpq-backend/src/test/java/com/cpq/formula/predicate/ConditionPredicateEvaluatorTest.java
git commit -m "feat(predicate): evaluator (=,!=,>,<,>=,<=,AND,OR; valEquals 口径)"
```

---

### Task 3: predicate JSON 互转

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/formula/predicate/ConditionPredicateJson.java`
- Test: 追加到 `ConditionPredicateEvaluatorTest.java`（同包，复用）

- [ ] **Step 1: 写失败测试（追加）**

```java
    // —— ConditionPredicateJson round-trip ——
    @Test void json_parse_comparison() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
            "{\"op\":\"=\",\"lhs\":{\"kind\":\"sourceField\",\"field\":\"类型\"}," +
            "\"rhs\":{\"kind\":\"literal\",\"value\":\"管理费\"}}");
        var p = ConditionPredicateJson.fromJson(json);
        assertTrue(ev.test(p, Map.of("类型","管理费"), Map.of()));
    }

    @Test void json_parse_bool_tree() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
            "{\"bool\":\"AND\",\"children\":[" +
            "{\"op\":\">\",\"lhs\":{\"kind\":\"sourceField\",\"field\":\"金额\"},\"rhs\":{\"kind\":\"literal\",\"value\":\"1000\"}}]}");
        var p = ConditionPredicateJson.fromJson(json);
        assertTrue(ev.test(p, Map.of("金额","1500"), Map.of()));
    }

    @Test void json_null_or_missing_is_null_predicate() {
        assertNull(ConditionPredicateJson.fromJson(null));
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest=ConditionPredicateEvaluatorTest`
Expected: 编译失败（`ConditionPredicateJson` 不存在）。

- [ ] **Step 3: 实现 JSON 互转**

```java
package com.cpq.formula.predicate;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import static com.cpq.formula.predicate.ConditionPredicate.*;

/** JsonNode ↔ ConditionPredicate。null/缺省 → null predicate（不过滤）。 */
public final class ConditionPredicateJson {
    private ConditionPredicateJson() {}

    public static ConditionPredicate fromJson(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.has("bool")) {
            BoolOp op = BoolOp.valueOf(node.get("bool").asText().toUpperCase());
            List<ConditionPredicate> ch = new ArrayList<>();
            JsonNode children = node.path("children");
            if (children.isArray()) for (JsonNode c : children) {
                ConditionPredicate cp = fromJson(c);
                if (cp != null) ch.add(cp);
            }
            return new Bool(op, ch);
        }
        if (node.has("op")) {
            return new Comparison(
                CmpOp.from(node.get("op").asText()),
                operand(node.path("lhs")),
                operand(node.path("rhs")));
        }
        return null;
    }

    private static Operand operand(JsonNode n) {
        String kind = n.path("kind").asText("");
        return switch (kind) {
            case "sourceField" -> new SourceField(n.path("field").asText(""));
            case "hostField"   -> new HostField(n.path("field").asText(""));
            case "literal"     -> new Literal(n.path("value").asText(""));
            default -> throw new IllegalArgumentException("未知 operand kind: " + kind);
        };
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest=ConditionPredicateEvaluatorTest`
Expected: Tests run, 0 failures。

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/formula/predicate/ConditionPredicateJson.java \
        cpq-backend/src/test/java/com/cpq/formula/predicate/ConditionPredicateEvaluatorTest.java
git commit -m "feat(predicate): JsonNode <-> model converter"
```

---

### Task 4: predicate 文本解析器（EXCEL 线文本录入用）

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/formula/predicate/ConditionPredicateParser.java`
- Test: `cpq-backend/src/test/java/com/cpq/formula/predicate/ConditionPredicateParserTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.cpq.formula.predicate;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConditionPredicateParserTest {
    private final ConditionPredicateEvaluator ev = new ConditionPredicateEvaluator();

    private boolean run(String cond, Map<String,Object> arow, Map<String,Object> host) {
        return ev.test(new ConditionPredicateParser().parse(cond), arow, host);
    }

    @Test void field_eq_string_literal() {
        assertTrue(run("[页签A.类型] = '管理费'", Map.of("类型","管理费"), Map.of()));
        assertFalse(run("[页签A.类型] = '管理费'", Map.of("类型","运费"), Map.of()));
    }

    @Test void field_eq_field_cross_tab() {
        // 第二个 [..] 默认按 hostField 解析（B 侧）
        assertTrue(run("[页签A.a] = [页签B.b]", Map.of("a","X"), Map.of("b","X")));
    }

    @Test void and_or_with_number_and_parens() {
        var arow = Map.<String,Object>of("类型","管理费","金额","1500");
        assertTrue(run("[A.类型]='管理费' AND [A.金额] > 1000", arow, Map.of()));
        assertTrue(run("([A.类型]='运费') OR [A.金额] >= 1500", arow, Map.of()));
    }

    @Test void ne_operators() {
        assertTrue(run("[A.类型] <> '运费'", Map.of("类型","管理费"), Map.of()));
        assertTrue(run("[A.类型] != '运费'", Map.of("类型","管理费"), Map.of()));
    }

    @Test void malformed_throws() {
        assertThrows(RuntimeException.class, () -> new ConditionPredicateParser().parse("[A.x] = "));
    }
}
```

约定（写进解析器 javadoc）：
- `[页签.字段]` 操作数：**条件里第一个出现的页签前缀视为 source 侧（A）→ `SourceField`，其余不同页签前缀视为宿主侧（B）→ `HostField`**。语法1 只有 A 前缀；语法2 有两个不同前缀。字段名只取 `.` 后部分。
- `'...'` → `Literal`（字符串）；裸数字 → `Literal`（数字文本）。

- [ ] **Step 2: 运行确认失败**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest=ConditionPredicateParserTest`
Expected: 编译失败（`ConditionPredicateParser` 不存在）。

- [ ] **Step 3: 实现递归下降解析器**

```java
package com.cpq.formula.predicate;

import java.util.ArrayList;
import java.util.List;
import static com.cpq.formula.predicate.ConditionPredicate.*;

/**
 * 条件文本 → predicate 模型。文法见 spec §5。
 * [页签.字段]：首个出现的页签前缀=source(A)→SourceField，其余前缀=host(B)→HostField；字段取 '.' 后段。
 */
public class ConditionPredicateParser {

    private String src;
    private int pos;
    private String firstTab;   // 首个页签前缀，定 source 侧

    public ConditionPredicate parse(String cond) {
        this.src = cond == null ? "" : cond;
        this.pos = 0;
        this.firstTab = null;
        ConditionPredicate p = orExpr();
        skipWs();
        if (pos != src.length()) throw new IllegalArgumentException("条件解析残留: " + src.substring(pos));
        return p;
    }

    private ConditionPredicate orExpr() {
        List<ConditionPredicate> parts = new ArrayList<>();
        parts.add(andExpr());
        while (matchKeyword("OR")) parts.add(andExpr());
        return parts.size() == 1 ? parts.get(0) : new Bool(BoolOp.OR, parts);
    }

    private ConditionPredicate andExpr() {
        List<ConditionPredicate> parts = new ArrayList<>();
        parts.add(cmpExpr());
        while (matchKeyword("AND")) parts.add(cmpExpr());
        return parts.size() == 1 ? parts.get(0) : new Bool(BoolOp.AND, parts);
    }

    private ConditionPredicate cmpExpr() {
        skipWs();
        if (peek() == '(') {
            pos++;                       // 吃 '('
            ConditionPredicate inner = orExpr();
            skipWs();
            if (peek() != ')') throw new IllegalArgumentException("缺右括号");
            pos++;                       // 吃 ')'
            return inner;
        }
        Operand lhs = operand();
        String op = readOperator();
        Operand rhs = operand();
        return new Comparison(CmpOp.from(op), lhs, rhs);
    }

    private Operand operand() {
        skipWs();
        char c = peek();
        if (c == '[') {                  // [页签.字段]
            int close = src.indexOf(']', pos);
            if (close < 0) throw new IllegalArgumentException("缺 ]");
            String inner = src.substring(pos + 1, close).trim();
            pos = close + 1;
            int dot = inner.lastIndexOf('.');
            String tab = dot >= 0 ? inner.substring(0, dot) : "";
            String field = dot >= 0 ? inner.substring(dot + 1) : inner;
            if (firstTab == null) firstTab = tab;
            return tab.equals(firstTab) ? new SourceField(field) : new HostField(field);
        }
        if (c == '\'' || c == '"') {     // 字符串字面量
            char q = c; pos++;
            int close = src.indexOf(q, pos);
            if (close < 0) throw new IllegalArgumentException("字符串未闭合");
            String v = src.substring(pos, close);
            pos = close + 1;
            return new Literal(v);
        }
        // 数字字面量
        int start = pos;
        while (pos < src.length() && (Character.isDigit(peek()) || peek() == '.' || peek() == '-')) pos++;
        if (pos == start) throw new IllegalArgumentException("非法操作数 @" + pos);
        return new Literal(src.substring(start, pos).trim());
    }

    private String readOperator() {
        skipWs();
        for (String op : new String[]{">=", "<=", "<>", "!=", "=", ">", "<"}) {
            if (src.startsWith(op, pos)) { pos += op.length(); return op; }
        }
        throw new IllegalArgumentException("缺运算符 @" + pos);
    }

    private boolean matchKeyword(String kw) {
        skipWs();
        if (pos + kw.length() <= src.length()
                && src.substring(pos, pos + kw.length()).equalsIgnoreCase(kw)) {
            int after = pos + kw.length();
            // 关键字后须是空白/括号/结尾，避免吞掉 ANDxxx
            if (after == src.length() || Character.isWhitespace(src.charAt(after)) || src.charAt(after) == '(') {
                pos = after; return true;
            }
        }
        return false;
    }

    private void skipWs() { while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++; }
    private char peek() { return pos < src.length() ? src.charAt(pos) : '\0'; }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest=ConditionPredicateParserTest`
Expected: Tests run, 0 failures。

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/formula/predicate/ConditionPredicateParser.java \
        cpq-backend/src/test/java/com/cpq/formula/predicate/ConditionPredicateParserTest.java
git commit -m "feat(predicate): recursive-descent text parser"
```

---

## Phase 2 — 组件线集成（`evalCrossTab` + 校验）

### Task 5: `evalCrossTab` hits 过滤叠加 predicate

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java:280-296`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/FormulaCalculatorPredicateTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FormulaCalculatorPredicateTest {
    private final FormulaCalculator calc = new FormulaCalculator();
    private final ObjectMapper M = new ObjectMapper();

    private FormulaCalculator.RowContext ctxWith(List<Map<String,Object>> aRows, Map<String,Object> hostRow) {
        var ctx = new FormulaCalculator.RowContext();
        ctx.crossTabRows.put("compA", aRows);
        ctx.currentRowRaw = new java.util.HashMap<>(hostRow);
        return ctx;
    }

    @Test void sumif_filters_by_literal_predicate() throws Exception {
        // SUMIF([compA.类型]='管理费', [compA.金额])  → 只加管理费行
        var token = M.readTree("{"
            + "\"type\":\"cross_tab_ref\",\"source\":\"compA\",\"agg\":\"SUM\","
            + "\"match\":[],"
            + "\"target\":\"金额\","
            + "\"predicate\":{\"op\":\"=\",\"lhs\":{\"kind\":\"sourceField\",\"field\":\"类型\"},"
            + "\"rhs\":{\"kind\":\"literal\",\"value\":\"管理费\"}}}");
        var ctx = ctxWith(List.of(
            Map.of("类型","管理费","金额","10"),
            Map.of("类型","运费","金额","5"),
            Map.of("类型","管理费","金额","7")), Map.of());
        Object v = calc.evalCrossTab(token, ctx);
        assertEquals(0, new java.math.BigDecimal("17").compareTo((java.math.BigDecimal) v));
    }

    @Test void predicate_absent_keeps_legacy_behavior() throws Exception {
        // 无 predicate → 现状：match=[] 全量求和
        var token = M.readTree("{"
            + "\"type\":\"cross_tab_ref\",\"source\":\"compA\",\"agg\":\"SUM\",\"match\":[],\"target\":\"金额\"}");
        var ctx = ctxWith(List.of(Map.of("金额","10"), Map.of("金额","5")), Map.of());
        Object v = calc.evalCrossTab(token, ctx);
        assertEquals(0, new java.math.BigDecimal("15").compareTo((java.math.BigDecimal) v));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest=FormulaCalculatorPredicateTest`
Expected: `sumif_filters_by_literal_predicate` FAIL（返 22 而非 17，因 predicate 未生效）；`predicate_absent...` PASS。

- [ ] **Step 3: 改 `evalCrossTab` hits 过滤**

在 `FormulaCalculator.java` 顶部字段区加：
```java
    private final com.cpq.formula.predicate.ConditionPredicateEvaluator predicateEval =
            new com.cpq.formula.predicate.ConditionPredicateEvaluator();
```

把 `evalCrossTab` 内 `:280-296` 的 hits 过滤循环改为（在 match 通过后再叠加 predicate）：
```java
        // hits 过滤：KSUM 按 match⋈ctx.currentRowRaw；外层同旧；再叠加可选 predicate（SUMIF 族）
        com.cpq.formula.predicate.ConditionPredicate predicate =
                com.cpq.formula.predicate.ConditionPredicateJson.fromJson(
                        token.has("predicate") ? token.get("predicate") : null);
        List<Map<String, Object>> hits = new ArrayList<>();
        JsonNode matchNode = token.path("match");
        boolean hasMatch = matchNode.isArray() && matchNode.size() > 0;
        for (Map<String, Object> arow : rows) {
            boolean ok = true;
            if (hasMatch) {
                for (JsonNode pair : matchNode) {
                    Object av = arow.get(pair.path("a").asText(""));
                    Object bv = ctx.currentRowRaw.get(pair.path("b").asText(""));
                    if (isBlank(av) || isBlank(bv) || !valEquals(av, bv)) { ok = false; break; }
                }
            }
            if (ok && predicate != null) {
                ok = predicateEval.test(predicate, arow, ctx.currentRowRaw);
            }
            if (ok) hits.add(arow);
        }
```

- [ ] **Step 4: 运行确认通过**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest=FormulaCalculatorPredicateTest,ConditionPredicateEvaluatorTest`
Expected: 全部 PASS。

- [ ] **Step 5: 全量组件线回归（防 cross_tab_ref 既有行为被破坏）**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest='*CrossTab*,*FormulaCalculator*,*CardSnapshot*'`
Expected: 0 failures（既有 cross_tab_ref 测试全绿）。

- [ ] **Step 6: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/FormulaCalculatorPredicateTest.java
git commit -m "feat(component-formula): cross_tab_ref optional predicate filter (SUMIF family)"
```

---

### Task 6: 校验放行 + 结构校验 `predicate` 字段

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/component/formula/TokenMappabilityValidator.java`
- Modify: `cpq-backend/src/main/java/com/cpq/component/service/ComponentService.java:516-525`（cross_tab_ref 校验段）
- Test: `cpq-backend/src/test/java/com/cpq/formula/predicate/ConditionPredicateJsonValidateTest.java`

- [ ] **Step 1: 写失败测试（校验非法 predicate 结构被拒）**

```java
package com.cpq.formula.predicate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConditionPredicateJsonValidateTest {
    private final ObjectMapper M = new ObjectMapper();

    @Test void bad_operand_kind_rejected() throws Exception {
        var json = M.readTree("{\"op\":\"=\",\"lhs\":{\"kind\":\"WRONG\",\"field\":\"x\"},"
            + "\"rhs\":{\"kind\":\"literal\",\"value\":\"y\"}}");
        assertThrows(IllegalArgumentException.class, () -> ConditionPredicateJson.fromJson(json));
    }

    @Test void valid_predicate_accepted() throws Exception {
        var json = M.readTree("{\"op\":\">\",\"lhs\":{\"kind\":\"sourceField\",\"field\":\"金额\"},"
            + "\"rhs\":{\"kind\":\"literal\",\"value\":\"1000\"}}");
        assertNotNull(ConditionPredicateJson.fromJson(json));
    }
}
```

- [ ] **Step 2: 运行确认（valid 通过、bad 抛错）**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest=ConditionPredicateJsonValidateTest`
Expected: 2 tests PASS（`fromJson` 已对未知 kind 抛 `IllegalArgumentException`，见 Task 3）。

- [ ] **Step 3: 在两处校验里调用 `fromJson` 做结构校验**

`TokenMappabilityValidator.java` 处理 `cross_tab_ref` 的循环内（`:54`/`:66` 附近），在已有 match 校验后追加：
```java
            // SUMIF 族：predicate 字段存在时，结构必须可解析（复用模型转换做结构校验）
            Object pred = t.get("predicate");
            if (pred != null) {
                try {
                    com.cpq.formula.predicate.ConditionPredicateJson.fromJson(
                        new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(pred));
                } catch (Exception e) {
                    throw new IllegalArgumentException("cross_tab_ref.predicate 结构非法: " + e.getMessage());
                }
            }
```
> 注：`TokenMappabilityValidator` 的 token 是 `Map`（见现有 `t.get("type")`），故用 `valueToTree` 转 JsonNode。`ComponentService.java:516-525` 段同理追加一次（若该段已遍历 cross_tab_ref token）。两处都加，避免漏网（AP-44 校验点）。

- [ ] **Step 4: 运行校验相关测试**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest='*TokenMappability*,*ComponentService*,ConditionPredicateJsonValidateTest'`
Expected: 0 failures。

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/component/formula/TokenMappabilityValidator.java \
        cpq-backend/src/main/java/com/cpq/component/service/ComponentService.java \
        cpq-backend/src/test/java/com/cpq/formula/predicate/ConditionPredicateJsonValidateTest.java
git commit -m "feat(validate): accept + structurally validate cross_tab_ref.predicate"
```

---

## Phase 3 — 前端镜像（`formulaEngine.ts`）

### Task 7: TS predicate 求值器 + token 字段 + aggregateRows 叠加过滤

**Files:**
- Modify: `cpq-frontend/src/utils/formulaEngine.ts`（`ExpressionToken` `:6-48`；`aggregateRows` hits `:305`）
- Test: `cpq-frontend/src/utils/__tests__/formulaEnginePredicate.test.ts`

- [ ] **Step 1: 写失败测试（含与后端共享的 parity 用例）**

```ts
import { describe, it, expect } from 'vitest';
import { evalPredicate, type ConditionPredicate } from '../formulaEngine';

describe('evalPredicate (与后端 ConditionPredicateEvaluator 逐用例对齐)', () => {
  const arow = { 类型: '管理费', 金额: '1500' };
  const host = { b: 'X' };

  it('null → true', () => expect(evalPredicate(null, {}, {})).toBe(true));

  it('source = literal text', () => {
    const p: ConditionPredicate = { op: '=', lhs: { kind: 'sourceField', field: '类型' }, rhs: { kind: 'literal', value: '管理费' } };
    expect(evalPredicate(p, arow, host)).toBe(true);
    expect(evalPredicate(p, { 类型: '运费' }, host)).toBe(false);
  });

  it('gt numeric only', () => {
    const p: ConditionPredicate = { op: '>', lhs: { kind: 'sourceField', field: '金额' }, rhs: { kind: 'literal', value: '1000' } };
    expect(evalPredicate(p, arow, host)).toBe(true);
    expect(evalPredicate(p, { 金额: '非数字' }, host)).toBe(false);
  });

  it('AND/OR nesting', () => {
    const c1: ConditionPredicate = { op: '=', lhs: { kind: 'sourceField', field: '类型' }, rhs: { kind: 'literal', value: '管理费' } };
    const c2: ConditionPredicate = { op: '>', lhs: { kind: 'sourceField', field: '金额' }, rhs: { kind: 'literal', value: '1000' } };
    expect(evalPredicate({ bool: 'AND', children: [c1, c2] }, arow, host)).toBe(true);
    expect(evalPredicate({ bool: 'AND', children: [c1, c2] }, { 类型: '管理费', 金额: '500' }, host)).toBe(false);
  });

  it('blank → eq false, ne true', () => {
    const eq: ConditionPredicate = { op: '=', lhs: { kind: 'sourceField', field: 'a' }, rhs: { kind: 'literal', value: 'x' } };
    const ne: ConditionPredicate = { op: '!=', lhs: { kind: 'sourceField', field: 'a' }, rhs: { kind: 'literal', value: 'x' } };
    expect(evalPredicate(eq, { a: null }, {})).toBe(false);
    expect(evalPredicate(ne, { a: null }, {})).toBe(true);
  });
});
```

- [ ] **Step 2: 运行确认失败**

Run: `cd cpq-frontend && npx vitest run src/utils/__tests__/formulaEnginePredicate.test.ts`
Expected: FAIL（`evalPredicate` / `ConditionPredicate` 未导出）。

- [ ] **Step 3: 在 `formulaEngine.ts` 增类型 + 求值器 + token 字段**

`ExpressionToken` 接口内追加（`:47` 之后）：
```ts
  /** SUMIF 族：cross_tab_ref 的可选附加过滤条件（布尔树）。缺省 = 不过滤。 */
  predicate?: ConditionPredicate | null;
```

文件顶部（`ExpressionToken` 定义之后）新增导出：
```ts
export type PredicateOperand =
  | { kind: 'sourceField'; field: string }
  | { kind: 'hostField'; field: string }
  | { kind: 'literal'; value: string };

export type ConditionPredicate =
  | { bool: 'AND' | 'OR'; children: ConditionPredicate[] }
  | { op: '=' | '!=' | '<>' | '>' | '<' | '>=' | '<='; lhs: PredicateOperand; rhs: PredicateOperand };

/** 与后端 ConditionPredicateEvaluator 逐字一致。arow=source 行, hostRow=宿主行。 */
export function evalPredicate(
  p: ConditionPredicate | null | undefined,
  arow: Record<string, any>,
  hostRow: Record<string, any>,
): boolean {
  if (p == null) return true;
  if ('bool' in p) {
    return p.bool === 'AND'
      ? p.children.every((c) => evalPredicate(c, arow, hostRow))
      : p.children.some((c) => evalPredicate(c, arow, hostRow));
  }
  const resolve = (o: PredicateOperand) =>
    o.kind === 'sourceField' ? arow?.[o.field]
    : o.kind === 'hostField' ? hostRow?.[o.field]
    : o.value;
  const lv = resolve(p.lhs), rv = resolve(p.rhs);
  const blank = (x: any) => x == null || String(x).trim() === '';
  const num = (x: any) => { const n = Number(String(x).trim()); return isNaN(n) ? null : n; };
  const eq = (): boolean => {
    if (blank(lv) || blank(rv)) return false;
    const na = num(lv), nb = num(rv);
    if (na !== null && nb !== null) return na === nb;
    return String(lv).trim() === String(rv).trim();
  };
  const cmp = (): number | null => {
    if (blank(lv) || blank(rv)) return null;
    const na = num(lv), nb = num(rv);
    if (na === null || nb === null) return null;
    return na < nb ? -1 : na > nb ? 1 : 0;
  };
  switch (p.op) {
    case '=': return eq();
    case '!=': case '<>': return !eq();
    case '>': { const c = cmp(); return c !== null && c > 0; }
    case '<': { const c = cmp(); return c !== null && c < 0; }
    case '>=': { const c = cmp(); return c !== null && c >= 0; }
    case '<=': { const c = cmp(); return c !== null && c <= 0; }
  }
}
```

`aggregateRows` 内 hits 过滤（`:305`）改为叠加 predicate（`token` 在该闭包作用域可见）：
```ts
          const hits = rows.filter((ar) =>
            matchPairs.every((p) => keyEq(ar[p.a], hostRow?.[p.b]))
            && evalPredicate(token.predicate, ar, hostRow ?? {})
          );
```

- [ ] **Step 4: 运行确认通过**

Run: `cd cpq-frontend && npx vitest run src/utils/__tests__/formulaEnginePredicate.test.ts`
Expected: 全部 PASS。

- [ ] **Step 5: TS 编译 + Vite transform 自检（CLAUDE.md 强制）**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/utils/formulaEngine.ts
```
Expected: tsc 0 错误；curl HTTP 200。

- [ ] **Step 6: Commit**

```bash
git add cpq-frontend/src/utils/formulaEngine.ts \
        cpq-frontend/src/utils/__tests__/formulaEnginePredicate.test.ts
git commit -m "feat(frontend-formula): TS predicate evaluator + cross_tab_ref predicate filter"
```

---

## Phase 4 — EXCEL / 小计线（`TemplateFormulaService`）

### Task 8: 识别 `XXXIF(cond, expr)` 并按 predicate 逐行过滤聚合

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/template/service/TemplateFormulaService.java`（`OVER_FUNC_PATTERN` `:91`；`resolveAggregates` `:643`；`executeOverFunction` `:681`；`aggregate` `:1476`；`AGGREGATE_FUNCS` 集合 `:81`）
- Test: `cpq-backend/src/test/java/com/cpq/template/service/TemplateFormulaSumifTest.java`

- [ ] **Step 1: 写失败测试**（直接测内部分支；用反射/包内调用 `executeOverFunction` 或抽一个可测的内层方法）

> 实现提示：为可测，抽一个包内方法
> `BigDecimal aggregateWithPredicate(String funcName, List<Map<String,Object>> rows, ConditionPredicate pred, String valueExprText)`，
> `executeOverFunction` 在识别到 `XXXIF` 时调用它（rows=本 source 行，hostRow 传空 Map → 单值）。

```java
package com.cpq.template.service;

import com.cpq.formula.predicate.ConditionPredicateParser;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TemplateFormulaSumifTest {
    // 通过包内可测方法验证 SUMIF 行过滤 + 聚合（不依赖 DB / driver）。
    private final TemplateFormulaService svc = new TemplateFormulaService();

    @Test void sumif_filters_rows_single_value() {
        var rows = List.<Map<String,Object>>of(
            Map.of("类型","管理费","金额","10"),
            Map.of("类型","运费","金额","5"),
            Map.of("类型","管理费","金额","7"));
        var pred = new ConditionPredicateParser().parse("[A.类型] = '管理费'");
        BigDecimal r = svc.aggregateWithPredicate("SUMIF", rows, pred, "金额");
        assertEquals(0, new BigDecimal("17").compareTo(r));
    }

    @Test void countif_counts_matching_rows() {
        var rows = List.<Map<String,Object>>of(
            Map.of("类型","管理费"), Map.of("类型","运费"), Map.of("类型","管理费"));
        var pred = new ConditionPredicateParser().parse("[A.类型] = '管理费'");
        BigDecimal r = svc.aggregateWithPredicate("COUNTIF", rows, pred, null);
        assertEquals(0, new BigDecimal("2").compareTo(r));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest=TemplateFormulaSumifTest`
Expected: 编译失败（`aggregateWithPredicate` 不存在）。

- [ ] **Step 3a: 扩函数集合 + 正则**

`:81` 的 `Set.of(...)` 增加 `"SUMIF","COUNTIF","AVGIF","MINIF","MAXIF"`；
`OVER_FUNC_PATTERN`（`:91`）改为同时匹配两族：
```java
    private static final Pattern OVER_FUNC_PATTERN = Pattern.compile(
            "\\b(SUM_OVER|COUNT_OVER|AVG_OVER|MIN_OVER|MAX_OVER|SUMIF|COUNTIF|AVGIF|MINIF|MAXIF)\\s*\\(",
            Pattern.CASE_INSENSITIVE);
```

- [ ] **Step 3b: 实现包内可测方法 `aggregateWithPredicate`**

```java
    /** 包内可测：对给定行集按 predicate 过滤后求 valueExprText（COUNTIF 无需 valueExpr），单值聚合。 */
    BigDecimal aggregateWithPredicate(String funcName,
                                      java.util.List<java.util.Map<String,Object>> rows,
                                      com.cpq.formula.predicate.ConditionPredicate pred,
                                      String valueExprText) {
        var ev = new com.cpq.formula.predicate.ConditionPredicateEvaluator();
        java.util.List<BigDecimal> values = new java.util.ArrayList<>();
        java.util.Map<String,Object> emptyHost = java.util.Map.of();
        for (var row : rows) {
            if (!ev.test(pred, row, emptyHost)) continue;
            if ("COUNTIF".equals(funcName)) { values.add(BigDecimal.ONE); continue; }
            Object val = evalRowExpression(valueExprText, row);  // 复用现有行内 JEXL 求值
            BigDecimal bd = toBigDecimal(val);
            if (bd != null) values.add(bd);
        }
        // 复用 aggregate()：把 XXXIF 归一到对应 *_OVER reduce
        String overName = switch (funcName) {
            case "SUMIF" -> "SUM_OVER";
            case "COUNTIF" -> "COUNT_OVER";
            case "AVGIF" -> "AVG_OVER";
            case "MINIF" -> "MIN_OVER";
            case "MAXIF" -> "MAX_OVER";
            default -> "SUM_OVER";
        };
        return aggregate(overName, values);
    }
```

- [ ] **Step 3c: `executeOverFunction` 识别 XXXIF 路由到新方法**

在 `executeOverFunction`（`:681`）开头，funcName 属 XXXIF 族时走条件解析分支（`argsContent` = `cond, valueExpr`，用 `findTopLevelComma` 切；`cond` → `ConditionPredicateParser`；source 取自 cond/valueExpr 的首个 `[页签.字段]` 页签前缀，沿用现有 `resolveDriverPath`/`CardAggregateSource.rowsFor` 拿行集），其余 `*_OVER` 走原路径。

```java
        if (funcName.endsWith("IF")) {   // SUMIF/COUNTIF/... 族
            int comma = findTopLevelComma(argsContent);
            String condText = comma >= 0 ? argsContent.substring(0, comma).trim() : argsContent.trim();
            String valueExpr = comma >= 0 ? argsContent.substring(comma + 1).trim() : null;
            var pred = new com.cpq.formula.predicate.ConditionPredicateParser().parse(condText);
            // source：取 cond/valueExpr 中首个 [页签.字段] 的页签前缀（复用现有 source 解析；与 *_OVER 同源）
            String source = extractFirstTabRef(condText, valueExpr);   // 见 Step 3d
            List<Map<String, Object>> rows = com.cpq.template.service.CardAggregateSource.rowsFor(source);
            if (rows == null) {
                String driverPath = resolveDriverPath(source);
                rows = (driverPath == null || driverPath.isBlank()) ? List.of()
                     : dataLoader.loadByPath(driverPath, null, partNo, customerId).get();
            }
            if (rows == null) rows = List.of();
            return aggregateWithPredicate(funcName, rows, pred, valueExpr);
        }
```

- [ ] **Step 3d: 加 `extractFirstTabRef` 工具**

```java
    /** 从 cond + valueExpr 文本里取第一个 [页签.字段] 的页签前缀，作为聚合 source。 */
    private String extractFirstTabRef(String cond, String valueExpr) {
        for (String s : new String[]{cond, valueExpr}) {
            if (s == null) continue;
            int lb = s.indexOf('['); int dot = lb >= 0 ? s.indexOf('.', lb) : -1; int rb = lb >= 0 ? s.indexOf(']', lb) : -1;
            if (lb >= 0 && dot > lb && rb > dot) return s.substring(lb + 1, dot).trim();
            if (lb >= 0 && rb > lb) return s.substring(lb + 1, rb).trim();
        }
        return "";
    }
```

- [ ] **Step 4: 运行确认通过**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest=TemplateFormulaSumifTest`
Expected: 全部 PASS。

- [ ] **Step 5: EXCEL 线回归**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest='*TemplateFormula*'`
Expected: 0 failures（既有 `SUM_OVER` 测试不受影响）。

- [ ] **Step 6: 重启 + endpoint 自检（CLAUDE.md 强制）**

Run:
```bash
touch cpq-backend/src/main/java/com/cpq/template/service/TemplateFormulaService.java
sleep 7
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health
```
Expected: 200。

- [ ] **Step 7: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/template/service/TemplateFormulaService.java \
        cpq-backend/src/test/java/com/cpq/template/service/TemplateFormulaSumifTest.java
git commit -m "feat(excel-formula): SUMIF family text -> predicate row filter + aggregate"
```

---

## Phase 5 — 录入 UX（抽屉点击生成 SUMIF token）

> 录入模型 = 与现有 `cross_tab_ref` 一致：在 `TabJoinFormulaDrawer` 抽屉里可视化配条件 → 点击生成一个带 `predicate` 字段的 `cross_tab_ref` token 插入表达式框；表达式框渲染只读文本 `SUMIF(...)`。**不实现把手打文本反解析成 token**（与现有用法一致，编辑=重开抽屉）。

### Task 9: 抽屉条件构造器 + 生成带 predicate 的 token

**Files:**
- Modify: `cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx`
- Test: `cpq-frontend/src/pages/template/__tests__/sumifTokenBuild.test.ts`（纯函数：UI 输入 → token JSON）

- [ ] **Step 1: 抽纯函数 `buildSumifToken` 并写失败测试**

```ts
import { describe, it, expect } from 'vitest';
import { buildSumifToken } from '../TabJoinFormulaDrawer';

describe('buildSumifToken', () => {
  it('builds cross_tab_ref token with predicate + agg + targetExpr', () => {
    const token = buildSumifToken({
      func: 'SUMIF',
      source: 'compA',
      sourceLabel: '页签A',
      predicate: { op: '=', lhs: { kind: 'sourceField', field: '类型' }, rhs: { kind: 'literal', value: '管理费' } },
      valueExprTokens: [{ type: 'field', value: '金额', source: 'compA' }],
    });
    expect(token.type).toBe('cross_tab_ref');
    expect(token.agg).toBe('SUM');
    expect(token.predicate).toBeTruthy();
    expect(token.match).toEqual([]);
    expect(token.targetExpr?.length).toBe(1);
  });

  it('COUNTIF maps to agg COUNT and no targetExpr needed', () => {
    const token = buildSumifToken({ func: 'COUNTIF', source: 'compA', sourceLabel: '页签A',
      predicate: { op: '=', lhs: { kind: 'sourceField', field: '类型' }, rhs: { kind: 'literal', value: '管理费' } },
      valueExprTokens: [] });
    expect(token.agg).toBe('COUNT');
  });
});
```

- [ ] **Step 2: 运行确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/template/__tests__/sumifTokenBuild.test.ts`
Expected: FAIL（`buildSumifToken` 未导出）。

- [ ] **Step 3: 实现 `buildSumifToken`（导出纯函数）**

```ts
import type { ExpressionToken, ConditionPredicate } from '../../utils/formulaEngine';

const FUNC_TO_AGG: Record<string, ExpressionToken['agg']> = {
  SUMIF: 'SUM', COUNTIF: 'COUNT', AVGIF: 'AVG', MINIF: 'MIN', MAXIF: 'MAX',
};

export function buildSumifToken(input: {
  func: 'SUMIF' | 'COUNTIF' | 'AVGIF' | 'MINIF' | 'MAXIF';
  source: string;
  sourceLabel?: string;
  predicate: ConditionPredicate | null;
  valueExprTokens: ExpressionToken[];
}): ExpressionToken {
  return {
    type: 'cross_tab_ref',
    source: input.source,
    sourceLabel: input.sourceLabel,
    agg: FUNC_TO_AGG[input.func],
    match: [],                          // SUMIF 族用 predicate 过滤；match 留空（全量 + predicate）
    predicate: input.predicate,
    targetExpr: input.valueExprTokens.length > 0 ? input.valueExprTokens : undefined,
  };
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/template/__tests__/sumifTokenBuild.test.ts`
Expected: PASS。

- [ ] **Step 5: 抽屉 UI 接线（条件构造器 + 函数选择 + 插入按钮）**

在 `TabJoinFormulaDrawer.tsx` 增一个 SUMIF 配置区（Ant Design 表单：函数下拉 / source 页签选择 / 条件行编辑器〔字段 + 运算符 + 值（字面量或宿主字段）+ AND/OR 增删行〕/ 值表达式 token 选择），「插入」按钮调用 `buildSumifToken` 把结果 token 追加进表达式框 token 数组。沿用本抽屉既有的 token 插入回调（与 cross_tab_ref 插入同路径）。

- [ ] **Step 6: TS + Vite 自检**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/template/TabJoinFormulaDrawer.tsx
```
Expected: tsc 0 错误；curl 200。

- [ ] **Step 7: Commit**

```bash
git add cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx \
        cpq-frontend/src/pages/template/__tests__/sumifTokenBuild.test.ts
git commit -m "feat(ui): SUMIF condition builder drawer -> emit cross_tab_ref token with predicate"
```

---

## Phase 6 — E2E + 三入口渲染证据

### Task 10: 双 E2E spec 覆盖三入口

**Files:**
- Modify: `cpq-frontend/e2e/quotation-flow.spec.ts`（SIMPLE）
- Modify: `cpq-frontend/e2e/composite-product-flow.spec.ts`（COMPOSITE）

- [ ] **Step 1: 在测试模板里配 SUMIF 公式（三入口各一处）**

按 `docs/E2E测试方法.md` 与记忆 `cpq-e2e-quotation-flow-test-data`（苏州西门子 + 报价模板0608 + 料号 10110002）：
- 组件字段公式入口：给某组件字段配 SUMIF（抽屉生成 predicate token），预期分行对齐宿主行键。
- EXCEL 列 / 小计入口：配 `SUMIF([X.类型]='管理费', [X.金额])`，预期单值。

- [ ] **Step 2: 写断言**

```ts
// 组件线：SUMIF 列每个宿主行键各自出值（分行），且无 '加载中'
const loading = await page.locator («text=加载中»).count();
expect(loading).toBe(0);
// 断言某宿主行 SUMIF 单元格 = 期望值（按测试数据算）
// EXCEL/小计：断言对应单元格为单值
```
（实际选择器/期望值按测试模板填，参考既有 spec 中 qf-* 截图断言写法。）

- [ ] **Step 3: 跑双 spec**

Run:
```bash
cd cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
npx playwright test --config=e2e/playwright.config.ts e2e/composite-product-flow.spec.ts --reporter=list
```
Expected: 全部 test `passed`；`'加载中' final count = 0`；8 Tab `'加载中'=0`。

- [ ] **Step 4: 留存渲染证据**

留 qf-* 截图（组件分行 / EXCEL 单值 / 小计单值各一），作为 PR 渲染证据（AP-44 强制）。

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/e2e/quotation-flow.spec.ts cpq-frontend/e2e/composite-product-flow.spec.ts
git commit -m "test(e2e): SUMIF family across 3 entry points (component/excel/subtotal)"
```

---

## 收尾

- [ ] 全量后端测试：`cd cpq-backend && ./mvnw -q -o test`（0 failures）。
- [ ] 更新 `docs/RECORD.md`：追加 `[2026-06-15] 公式 - 新增 SUMIF 族条件聚合 | 涉及 predicate 包 + evalCrossTab + TemplateFormulaService + formulaEngine.ts + TabJoinFormulaDrawer | 取向A: cross_tab_ref 加可选 predicate`。
- [ ] 更新 spec 状态为「已实现」。
- [ ] 走 `superpowers:finishing-a-development-branch`：合并 master → 跑测试 → 删 worktree + 分支（用户确认达标后，按 CLAUDE.md 自动收尾）。

---

## Self-Review（计划自检）

- **Spec 覆盖**：函数族五个（Task 2 reduce + Task 8 映射）✅；三入口（组件 Task 5/7、EXCEL+小计 Task 8）✅；条件 `= != <> > < >= <=` + AND/OR + 括号（Task 2/4/7）✅；跨页签按宿主行键（复用 evalCrossTab match + Task 5 predicate）✅；笛卡尔放大（沿用 evalCrossTab 多命中累加）✅；类型/NULL valEquals 口径（Task 2/7 parity）✅；COUNTIF 单参（Task 8）✅；缺省 predicate 零回归（Task 5 Step 2/Step 5 回归）✅；AP-44 校验点（Task 6 两处）✅；前后端 parity（Task 7 共享用例）✅；双 E2E（Task 10）✅。
- **占位扫描**：无 TBD；E2E Task 10 的选择器/期望值标注「按测试模板填」属 E2E 数据相关，须在 worktree 内对真实测试模板落实，非逻辑占位。
- **类型一致**：`ConditionPredicate`/`Comparison`/`Bool`/`Operand`（Java）与 `ConditionPredicate`/`PredicateOperand`（TS）字段名一致（`bool/op/lhs/rhs/kind/field/value`）；`evalCrossTab`/`aggregateRows`/`aggregateWithPredicate`/`buildSumifToken` 命名贯穿一致 ✅。
