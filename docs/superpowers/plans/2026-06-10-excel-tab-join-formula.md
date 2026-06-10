# Excel 页签连表公式构建器 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 Excel 模板视图列新增一种来源类型 `TAB_JOIN_FORMULA`：单张产品卡片内，多页签 INNER JOIN + 组级 WHERE + 计算组聚合 → 一个单值写入 Excel 单元格，并提供可视化构建器与样本卡片试算。

**Architecture:** 方案 1（内存连表 + 复用 FormulaEngine）。后端新增纯逻辑 `TabJoinPlanEvaluator`（INNER JOIN → WHERE → 两层求值），复用现成 `CardDataProvider` 取页签行（tabKey=`componentId:sortOrder`）、`CardEffectiveRows` 物化行；接入 `ExcelViewService.buildRowData` 的列求值 switch；试算复用 `ExcelViewService.dryRun` 思路加一个返回 `{groupValues, finalValue}` 的端点。前端新增 `TabJoinFormulaDrawer`（仿现有 `CardFormulaDrawer`），在 `ExcelViewConfigTab` 列来源下拉加一项。

**Tech Stack:** Java 17 + Quarkus + Apache Commons JEXL3（自定义 `JexlArithmetic` 实现缺值=0/除数=1）；JUnit5 + QuarkusTest；React + Ant Design 5；Playwright E2E。

**设计来源：** `docs/superpowers/specs/2026-06-10-excel-tab-join-formula-design.md`，原型 `docs/html/excel-tab-join-formula-builder.html`。

---

## 列配置 JSON 契约（锁定，前后端共用）

```json
{
  "col_key": "L",
  "title": "单件总成本",
  "source_type": "TAB_JOIN_FORMULA",
  "final_expression": "组1 + 组2 - 100",
  "groups": [
    {
      "ref": "组1",
      "main_tab": "投料",
      "tabs": [
        { "alias": "投料", "tabKey": "11111111-1111-1111-1111-111111111111:0" },
        { "alias": "加工", "tabKey": "22222222-2222-2222-2222-222222222222:1" }
      ],
      "joins": [
        { "left_tab": "加工", "left_cols": ["物料编码"],
          "right_tab": "投料", "right_cols": ["物料编码"], "type": "INNER" }
      ],
      "where": [
        { "col": "投料.类型", "op": "=", "value": "主料", "logic": "AND" }
      ],
      "agg_expression": "SUM([投料.金额])"
    }
  ]
}
```

**令牌规则：** 表达式里 `[别名.字段]` 引用某页签某字段；`[别名.小计]` / `[别名.总计]` 为该页签级单值（广播到每行）。`final_expression` 里 `组N` 引用 `groups[i].ref`，只用 `+ - * / ( )` 和常数。
**求值语义（spec §5）：** 聚合函数 `SUM/AVG/MIN/MAX/COUNT` 内是逐行子表达式；聚合外裸 `[别名.字段]` 取过滤后第一行；缺值→0；除数 0/缺→按 1。

**v1 简化（写进 spec §11 已列）：** `[别名.小计]`/`[别名.总计]` 都解析为页签 `subtotalOf`（整页签小计，不区分列级）；join 图必须是连通树（无环、每条 join 引入一个新页签）。

---

## Phase 1 — 后端核心求值器（纯逻辑 + TDD）

### Task 1: SafeArithmetic（缺值=0 / 除数 0 或缺→按 1 的 JEXL 算术）

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/quotation/service/tabjoin/SafeArithmetic.java`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/tabjoin/SafeArithmeticTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.cpq.quotation.service.tabjoin;

import org.apache.commons.jexl3.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class SafeArithmeticTest {

    private Object eval(String expr) {
        JexlEngine jexl = new JexlBuilder().arithmetic(new SafeArithmetic()).strict(false).silent(true).create();
        return jexl.createExpression(expr).evaluate(new MapContext());
    }

    @Test
    void null_operand_treated_as_zero_in_add() {
        // a 未定义 → null → 当 0；strict(false) 下未定义变量为 null
        assertEquals(0, new BigDecimal("5").compareTo(new BigDecimal(eval("5 + a").toString())));
    }

    @Test
    void divide_by_zero_uses_one_as_divisor() {
        // 10 / 0 → 除数按 1 → 10
        assertEquals(0, new BigDecimal("10").compareTo(new BigDecimal(eval("10 / 0").toString())));
    }

    @Test
    void divide_by_null_uses_one_as_divisor() {
        assertEquals(0, new BigDecimal("10").compareTo(new BigDecimal(eval("10 / b").toString())));
    }

    @Test
    void normal_division_unchanged() {
        assertEquals(0, new BigDecimal("5").compareTo(new BigDecimal(eval("10 / 2").toString())));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -q test -Dtest=SafeArithmeticTest`
Expected: 编译失败（`SafeArithmetic` 不存在）。

- [ ] **Step 3: 实现 SafeArithmetic**

```java
package com.cpq.quotation.service.tabjoin;

import org.apache.commons.jexl3.JexlArithmetic;

/**
 * 页签连表公式专属算术：
 * - null 操作数按 0（加减乘）— 通过 strict=false + 父类对 null 的处理基础上，divide 显式兜底。
 * - 除数为 0 或 null → 按除数=1（spec：除数取不到默认 1，避免 DIV_ZERO 中断）。
 */
public class SafeArithmetic extends JexlArithmetic {

    public SafeArithmetic() {
        super(false); // 非严格：null 当 0 参与算术
    }

    @Override
    public Object divide(Object left, Object right) {
        if (right == null || isZero(right)) {
            // 除数取不到/为 0 → 按 1
            return super.divide(left == null ? 0 : left, 1);
        }
        return super.divide(left == null ? 0 : left, right);
    }

    private boolean isZero(Object v) {
        try {
            return toBigDecimal(v).signum() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -q test -Dtest=SafeArithmeticTest`
Expected: 4 tests PASS。若 `10/0` 不是除数=1 语义，检查 JEXL 版本 `divide` 签名（commons-jexl3 默认 `divide(Object,Object)`）。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/tabjoin/SafeArithmetic.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/tabjoin/SafeArithmeticTest.java
git commit -m "feat(tabjoin): SafeArithmetic — 缺值=0/除数0或缺→按1 的JEXL算术

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: TabJoinPlanEvaluator — INNER JOIN 宽表构建

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluator.java`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluatorJoinTest.java`

本任务只实现 `buildWideRows`：从 `Map<alias, List<Map<String,Object>>>`（每页签的行）+ joins 列表，按 INNER JOIN 产出宽表行（键 `别名.字段`）。

- [ ] **Step 1: 写失败测试**

```java
package com.cpq.quotation.service.tabjoin;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TabJoinPlanEvaluatorJoinTest {

    private final TabJoinPlanEvaluator ev = new TabJoinPlanEvaluator();

    private Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void single_tab_no_join_returns_prefixed_rows() {
        Map<String, List<Map<String, Object>>> tabs = Map.of(
            "投料", List.of(row("物料编码", "M1", "金额", 100)));
        List<Map<String, Object>> wide = ev.buildWideRows("投料", tabs, List.of());
        assertEquals(1, wide.size());
        assertEquals("M1", wide.get(0).get("投料.物料编码"));
        assertEquals(100, wide.get(0).get("投料.金额"));
    }

    @Test
    void inner_join_one_to_many_fans_out() {
        Map<String, List<Map<String, Object>>> tabs = Map.of(
            "投料", List.of(row("物料编码", "M1", "金额", 100)),
            "加工", List.of(row("物料编码", "M1", "工时", 2),
                            row("物料编码", "M1", "工时", 3),
                            row("物料编码", "M2", "工时", 9)));
        var join = new TabJoinPlanEvaluator.Join("加工", List.of("物料编码"), "投料", List.of("物料编码"));
        List<Map<String, Object>> wide = ev.buildWideRows("投料", tabs, List.of(join));
        assertEquals(2, wide.size(), "M1 投料1行 × 加工2行 = 2 行放大；M2 无投料匹配丢弃");
        assertEquals(100, wide.get(0).get("投料.金额"));
        assertEquals(2, wide.get(0).get("加工.工时"));
        assertEquals(3, wide.get(1).get("加工.工时"));
    }

    @Test
    void inner_join_no_match_drops_row() {
        Map<String, List<Map<String, Object>>> tabs = Map.of(
            "投料", List.of(row("物料编码", "M1")),
            "加工", List.of(row("物料编码", "MX")));
        var join = new TabJoinPlanEvaluator.Join("加工", List.of("物料编码"), "投料", List.of("物料编码"));
        assertTrue(ev.buildWideRows("投料", tabs, List.of(join)).isEmpty());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -q test -Dtest=TabJoinPlanEvaluatorJoinTest`
Expected: 编译失败（类/方法不存在）。

- [ ] **Step 3: 实现 buildWideRows + Join 记录类**

```java
package com.cpq.quotation.service.tabjoin;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;

/** 页签连表公式求值器：INNER JOIN 宽表 → WHERE → 两层聚合求值。纯逻辑、可单测、无 DB。 */
@ApplicationScoped
public class TabJoinPlanEvaluator {

    /** 一条 INNER JOIN 边：new 侧(leftTab/leftCols) 与已包含侧(rightTab/rightCols) 等值。 */
    public record Join(String leftTab, List<String> leftCols, String rightTab, List<String> rightCols) {}

    /**
     * 从各页签行 + joins 构建 INNER JOIN 宽表。宽表行键 = "别名.字段"。
     * 约定：join 图为连通树，每条 join 的一侧已在累积宽表中、另一侧是尚未加入的新页签。
     */
    public List<Map<String, Object>> buildWideRows(
            String mainTab,
            Map<String, List<Map<String, Object>>> tabRows,
            List<Join> joins) {

        // 1. 主页签行 → 前缀化
        List<Map<String, Object>> wide = new ArrayList<>();
        for (Map<String, Object> r : tabRows.getOrDefault(mainTab, List.of())) {
            wide.add(prefix(mainTab, r));
        }
        Set<String> included = new HashSet<>();
        included.add(mainTab);

        // 2. 逐条 join 引入新页签（顺序无关：每轮挑一条恰好一侧已包含的）
        List<Join> pending = new ArrayList<>(joins);
        while (!pending.isEmpty()) {
            Join picked = null;
            boolean newIsLeft = true;
            for (Join j : pending) {
                boolean lIn = included.contains(j.leftTab());
                boolean rIn = included.contains(j.rightTab());
                if (lIn ^ rIn) { picked = j; newIsLeft = !lIn; break; }
            }
            if (picked == null) break; // 剩余 join 无法连通（环/孤立）→ 忽略，按已连通部分算

            String newTab = newIsLeft ? picked.leftTab() : picked.rightTab();
            List<String> newCols = newIsLeft ? picked.leftCols() : picked.rightCols();
            String incTab = newIsLeft ? picked.rightTab() : picked.leftTab();
            List<String> incCols = newIsLeft ? picked.rightCols() : picked.leftCols();

            wide = joinIn(wide, incTab, incCols, newTab, newCols, tabRows.getOrDefault(newTab, List.of()));
            included.add(newTab);
            pending.remove(picked);
        }
        return wide;
    }

    private List<Map<String, Object>> joinIn(
            List<Map<String, Object>> wide, String incTab, List<String> incCols,
            String newTab, List<String> newCols, List<Map<String, Object>> newRows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> w : wide) {
            for (Map<String, Object> nr : newRows) {
                if (keysEqual(w, incTab, incCols, nr, newCols)) {
                    Map<String, Object> merged = new LinkedHashMap<>(w);
                    merged.putAll(prefix(newTab, nr));
                    out.add(merged);
                }
            }
        }
        return out;
    }

    private boolean keysEqual(Map<String, Object> wide, String incTab, List<String> incCols,
                              Map<String, Object> newRow, List<String> newCols) {
        if (incCols.size() != newCols.size()) return false;
        for (int i = 0; i < incCols.size(); i++) {
            Object a = wide.get(incTab + "." + incCols.get(i));
            Object b = newRow.get(newCols.get(i));
            if (!Objects.equals(str(a), str(b))) return false;
        }
        return true;
    }

    private static String str(Object o) { return o == null ? null : o.toString().trim(); }

    private Map<String, Object> prefix(String alias, Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) m.put(alias + "." + e.getKey(), e.getValue());
        return m;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -q test -Dtest=TabJoinPlanEvaluatorJoinTest`
Expected: 3 tests PASS。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluator.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluatorJoinTest.java
git commit -m "feat(tabjoin): INNER JOIN 宽表构建(连通树/复合键/一对多放大)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: TabJoinPlanEvaluator — WHERE 行过滤

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluator.java`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluatorWhereTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.cpq.quotation.service.tabjoin;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TabJoinPlanEvaluatorWhereTest {

    private final TabJoinPlanEvaluator ev = new TabJoinPlanEvaluator();

    private Map<String, Object> wrow(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
    private TabJoinPlanEvaluator.Cond c(String col, String op, String val, String logic) {
        return new TabJoinPlanEvaluator.Cond(col, op, val, logic);
    }

    @Test
    void eq_filter() {
        List<Map<String, Object>> rows = List.of(
            wrow("投料.类型", "主料", "投料.金额", 100),
            wrow("投料.类型", "辅料", "投料.金额", 5));
        var out = ev.applyWhere(rows, List.of(c("投料.类型", "=", "主料", "AND")));
        assertEquals(1, out.size());
        assertEquals(100, out.get(0).get("投料.金额"));
    }

    @Test
    void gt_lt_numeric() {
        List<Map<String, Object>> rows = List.of(
            wrow("投料.数量", 3), wrow("投料.数量", 10), wrow("投料.数量", 20));
        assertEquals(1, ev.applyWhere(rows, List.of(c("投料.数量", ">", "15", "AND"))).size());
        assertEquals(1, ev.applyWhere(rows, List.of(c("投料.数量", "<", "5", "AND"))).size());
    }

    @Test
    void contains_not_contains() {
        List<Map<String, Object>> rows = List.of(
            wrow("加工.工序", "电镀前处理"), wrow("加工.工序", "酸洗"));
        assertEquals(1, ev.applyWhere(rows, List.of(c("加工.工序", "包含", "电镀", "AND"))).size());
        assertEquals(1, ev.applyWhere(rows, List.of(c("加工.工序", "不包含", "电镀", "AND"))).size());
    }

    @Test
    void and_or_combination() {
        List<Map<String, Object>> rows = List.of(
            wrow("投料.类型", "主料", "投料.数量", 1),
            wrow("投料.类型", "辅料", "投料.数量", 99),
            wrow("投料.类型", "主料", "投料.数量", 99));
        // 类型=主料 AND 数量>50 → 仅第3行
        var out = ev.applyWhere(rows, List.of(
            c("投料.类型", "=", "主料", "AND"), c("投料.数量", ">", "50", "AND")));
        assertEquals(1, out.size());
        // 类型=辅料 OR 数量>50 → 第2、3行
        var out2 = ev.applyWhere(rows, List.of(
            c("投料.类型", "=", "辅料", "AND"), c("投料.数量", ">", "50", "OR")));
        assertEquals(2, out2.size());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -q test -Dtest=TabJoinPlanEvaluatorWhereTest`
Expected: 编译失败（`Cond` / `applyWhere` 不存在）。

- [ ] **Step 3: 实现 Cond + applyWhere**

在 `TabJoinPlanEvaluator` 内加：

```java
    /** 一条 WHERE 条件。op ∈ {=,>,<,包含,不包含}；logic ∈ {AND,OR}（用于与下一条的连接，首条 logic 忽略）。 */
    public record Cond(String col, String op, String value, String logic) {}

    /** 按 where 过滤宽表行。多条按 logic 左折叠（无优先级，从左到右）。空条件 → 原样返回。 */
    public List<Map<String, Object>> applyWhere(List<Map<String, Object>> rows, List<Cond> where) {
        if (where == null || where.isEmpty()) return rows;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            boolean acc = evalCond(r, where.get(0));
            for (int i = 1; i < where.size(); i++) {
                boolean cur = evalCond(r, where.get(i));
                acc = "OR".equalsIgnoreCase(where.get(i).logic()) ? (acc || cur) : (acc && cur);
            }
            if (acc) out.add(r);
        }
        return out;
    }

    private boolean evalCond(Map<String, Object> row, Cond c) {
        Object cell = row.get(c.col());
        String cv = cell == null ? "" : cell.toString().trim();
        String v = c.value() == null ? "" : c.value().trim();
        return switch (c.op()) {
            case "=" -> cv.equals(v);
            case "包含" -> cv.contains(v);
            case "不包含" -> !cv.contains(v);
            case ">", "<" -> {
                try {
                    int cmp = new java.math.BigDecimal(cv).compareTo(new java.math.BigDecimal(v));
                    yield ">".equals(c.op()) ? cmp > 0 : cmp < 0;
                } catch (Exception e) { yield false; }
            }
            default -> false;
        };
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -q test -Dtest=TabJoinPlanEvaluatorWhereTest`
Expected: 4 tests PASS。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluator.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluatorWhereTest.java
git commit -m "feat(tabjoin): WHERE 行过滤(=/>/</包含/不包含 + AND/OR 左折叠)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: TabJoinPlanEvaluator — 组内表达式两层求值

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluator.java`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluatorExprTest.java`

实现 `evalGroupExpression(aggExpr, wideRows, subtotals)`：聚合函数内逐行子表达式求值后 reduce，聚合外裸 `[别名.字段]` 取第一行；`[别名.小计]`/`[别名.总计]` 取 subtotals。

- [ ] **Step 1: 写失败测试**

```java
package com.cpq.quotation.service.tabjoin;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TabJoinPlanEvaluatorExprTest {

    private final TabJoinPlanEvaluator ev = new TabJoinPlanEvaluator();

    private Map<String, Object> w(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
    private void assertBD(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "got " + actual);
    }

    @Test
    void sum_of_single_column() {
        List<Map<String, Object>> rows = List.of(w("投料.金额", 100), w("投料.金额", 50));
        assertBD("150", ev.evalGroupExpression("SUM([投料.金额])", rows, Map.of()));
    }

    @Test
    void sum_of_row_level_product() {
        List<Map<String, Object>> rows = List.of(
            w("投料.单价", 10, "加工.工时", 2),
            w("投料.单价", 10, "加工.工时", 3));
        // 10*2 + 10*3 = 50
        assertBD("50", ev.evalGroupExpression("SUM([投料.单价] * [加工.工时])", rows, Map.of()));
    }

    @Test
    void two_aggregates_plus_bare_field_first_row() {
        List<Map<String, Object>> rows = List.of(
            w("投料.单价", 10, "投料.数量", 2, "投料.金额", 20),
            w("投料.单价", 5, "投料.数量", 4, "投料.金额", 20));
        // SUM(单价*数量)=10*2+5*4=40 ; SUM(金额)=40 ; [投料.金额]裸=第一行=20 → 40+40+20=100
        assertBD("100", ev.evalGroupExpression(
            "SUM([投料.单价]*[投料.数量]) + SUM([投料.金额]) + [投料.金额]", rows, Map.of()));
    }

    @Test
    void avg_min_max_count() {
        List<Map<String, Object>> rows = List.of(w("a.x", 2), w("a.x", 4), w("a.x", 6));
        assertBD("4", ev.evalGroupExpression("AVG([a.x])", rows, Map.of()));
        assertBD("2", ev.evalGroupExpression("MIN([a.x])", rows, Map.of()));
        assertBD("6", ev.evalGroupExpression("MAX([a.x])", rows, Map.of()));
        assertBD("3", ev.evalGroupExpression("COUNT([a.x])", rows, Map.of()));
    }

    @Test
    void subtotal_token_uses_subtotals_map() {
        List<Map<String, Object>> rows = List.of(w("投料.金额", 1));
        Map<String, BigDecimal> subs = Map.of("投料.小计", new BigDecimal("999"));
        assertBD("999", ev.evalGroupExpression("[投料.小计]", rows, subs));
    }

    @Test
    void missing_field_is_zero_and_div_by_zero_is_one() {
        List<Map<String, Object>> rows = List.of(w("a.x", 10));
        // a.y 缺 → 0 ; 10 / 0(=SUM 空?) 用裸字段: 10 / [a.y] → 10/0 → 除数按1 → 10
        assertBD("10", ev.evalGroupExpression("[a.x] / [a.y]", rows, Map.of()));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -q test -Dtest=TabJoinPlanEvaluatorExprTest`
Expected: 编译失败（`evalGroupExpression` 不存在）。

- [ ] **Step 3: 实现两层求值**

在 `TabJoinPlanEvaluator` 内加（含 JEXL 引擎复用 SafeArithmetic）：

```java
    private static final java.util.regex.Pattern TOKEN =
        java.util.regex.Pattern.compile("\\[([^\\[\\]]+)]");
    private static final java.util.Set<String> AGG_FNS =
        java.util.Set.of("SUM", "AVG", "MIN", "MAX", "COUNT");

    private final org.apache.commons.jexl3.JexlEngine jexl =
        new org.apache.commons.jexl3.JexlBuilder()
            .arithmetic(new SafeArithmetic()).strict(false).silent(true).create();

    /**
     * 组内表达式求值（两层作用域）：
     * 1. 先把每个聚合调用 FN(子表达式) 替换为标量字面量（子表达式逐行求值后 reduce）。
     * 2. 再把聚合外裸 [别名.字段] 替换为第一行值；[别名.小计]/[别名.总计] 用 subtotals。
     * 3. JEXL（SafeArithmetic）算最终标量。
     */
    public java.math.BigDecimal evalGroupExpression(
            String aggExpr, List<Map<String, Object>> rows, Map<String, java.math.BigDecimal> subtotals) {
        if (aggExpr == null || aggExpr.isBlank()) return java.math.BigDecimal.ZERO;
        String expr = replaceAggregates(aggExpr.trim(), rows);
        // 聚合外裸令牌 → 第一行 / subtotal
        Map<String, Object> firstRow = rows.isEmpty() ? Map.of() : rows.get(0);
        expr = substituteScalarTokens(expr, firstRow, subtotals);
        Object r = jexl.createExpression(expr).evaluate(new org.apache.commons.jexl3.MapContext());
        return toBig(r);
    }

    /** 扫描 FN( ... ) 聚合调用（括号配平），逐行算内部子表达式 → reduce → 替换为字面量。 */
    private String replaceAggregates(String expr, List<Map<String, Object>> rows) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < expr.length()) {
            int fnStart = findAggCall(expr, i);
            if (fnStart < 0) { out.append(expr.substring(i)); break; }
            int parenOpen = expr.indexOf('(', fnStart);
            String fn = expr.substring(fnStart, parenOpen).trim().toUpperCase();
            int parenClose = matchParen(expr, parenOpen);
            String inner = expr.substring(parenOpen + 1, parenClose);
            out.append(expr, i, fnStart);
            out.append(reduceAgg(fn, inner, rows).toPlainString());
            i = parenClose + 1;
        }
        return out.toString();
    }

    /** 返回下一个聚合函数名起始下标（其后紧跟 `(`）；无则 -1。 */
    private int findAggCall(String expr, int from) {
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("(?i)\\b(SUM|AVG|MIN|MAX|COUNT)\\s*\\(").matcher(expr);
        return m.find(from) ? m.start(1) : -1;
    }

    private int matchParen(String s, int open) {
        int depth = 0;
        for (int k = open; k < s.length(); k++) {
            if (s.charAt(k) == '(') depth++;
            else if (s.charAt(k) == ')') { depth--; if (depth == 0) return k; }
        }
        return s.length() - 1;
    }

    private java.math.BigDecimal reduceAgg(String fn, String inner, List<Map<String, Object>> rows) {
        List<java.math.BigDecimal> vals = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String rowExpr = substituteRowTokens(inner, row);
            Object v = jexl.createExpression(rowExpr).evaluate(new org.apache.commons.jexl3.MapContext());
            if (v != null) vals.add(toBig(v));
        }
        return switch (fn) {
            case "SUM" -> vals.stream().reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
            case "COUNT" -> java.math.BigDecimal.valueOf(vals.size());
            case "AVG" -> vals.isEmpty() ? java.math.BigDecimal.ZERO
                : vals.stream().reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
                      .divide(java.math.BigDecimal.valueOf(vals.size()), 10, java.math.RoundingMode.HALF_UP);
            case "MIN" -> vals.stream().min(java.math.BigDecimal::compareTo).orElse(java.math.BigDecimal.ZERO);
            case "MAX" -> vals.stream().max(java.math.BigDecimal::compareTo).orElse(java.math.BigDecimal.ZERO);
            default -> java.math.BigDecimal.ZERO;
        };
    }

    /** 行级：把 [别名.字段] 替换成该行数值字面量（缺→0）。 */
    private String substituteRowTokens(String expr, Map<String, Object> row) {
        java.util.regex.Matcher m = TOKEN.matcher(expr);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            Object v = row.get(m.group(1).trim());
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(numLit(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 标量级（聚合外）：[别名.小计]/[别名.总计] 用 subtotals；其它 [别名.字段] 用第一行（缺→0）。 */
    private String substituteScalarTokens(String expr, Map<String, Object> firstRow,
                                          Map<String, java.math.BigDecimal> subtotals) {
        java.util.regex.Matcher m = TOKEN.matcher(expr);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String tok = m.group(1).trim();
            String lit;
            if (tok.endsWith(".小计") || tok.endsWith(".总计")) {
                java.math.BigDecimal s = subtotals.get(tok);
                lit = s != null ? s.toPlainString() : "0";
            } else {
                lit = numLit(firstRow.get(tok));
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(lit));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String numLit(Object v) {
        if (v == null) return "0";
        try { return new java.math.BigDecimal(v.toString().trim()).toPlainString(); }
        catch (Exception e) { return "0"; }
    }

    java.math.BigDecimal toBig(Object v) {
        if (v == null) return java.math.BigDecimal.ZERO;
        if (v instanceof java.math.BigDecimal b) return b;
        try { return new java.math.BigDecimal(v.toString()); }
        catch (Exception e) { return java.math.BigDecimal.ZERO; }
    }
```

> 注意 `[别名.小计]`/`[别名.总计]` 仅在聚合外（标量层）解析为 subtotal；若被写进 `SUM(...)` 内，逐行替换走 `substituteRowTokens`（行内无该键 → 0），与 spec "subtotal 广播到每行" 略有出入 —— v1 接受此限制（小计/总计正常用法是裸引用，不套聚合）。已在 spec §11 标注。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -q test -Dtest=TabJoinPlanEvaluatorExprTest`
Expected: 6 tests PASS。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluator.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluatorExprTest.java
git commit -m "feat(tabjoin): 组内表达式两层求值(聚合内逐行/聚合外标量+小计)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: TabJoinPlanEvaluator — 整列求值入口 evaluateColumn

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluator.java`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluatorColumnTest.java`

把 group 列表 + final_expression 串起来：每组用 `CardDataProvider` 取页签行 → buildWideRows → applyWhere → evalGroupExpression → 绑 `组N`；再算 final。返回 `{groupValues, finalValue}`。

- [ ] **Step 1: 写失败测试**

```java
package com.cpq.quotation.service.tabjoin;

import com.cpq.quotation.service.card.CardDataProvider;
import com.cpq.quotation.service.card.CardEffectiveRows;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TabJoinPlanEvaluatorColumnTest {

    private final TabJoinPlanEvaluator ev = new TabJoinPlanEvaluator();

    private CardDataProvider provider() {
        Map<String, CardEffectiveRows.TabRows> eff = new LinkedHashMap<>();
        eff.put("T1:0", new CardEffectiveRows.TabRows(
            List.of(Map.of("物料编码", "M1", "金额", new BigDecimal("100"))), new BigDecimal("100")));
        eff.put("T2:1", new CardEffectiveRows.TabRows(
            List.of(Map.of("物料编码", "M1", "工时", new BigDecimal("4"))), null));
        eff.put("T3:2", new CardEffectiveRows.TabRows(
            List.of(Map.of("回料金额", new BigDecimal("96"))), null));
        return CardDataProvider.fromEffectiveRows(eff);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cfg() {
        // 组1: 投料(T1:0) ⋈ 加工(T2:1) on 物料编码, agg=SUM([投料.金额]) → 100
        // 组2: 回料(T3:2), agg=SUM([回料.回料金额]) → 96
        // final: 组1 + 组2 - 100 → 96
        Map<String, Object> g1 = new LinkedHashMap<>();
        g1.put("ref", "组1"); g1.put("main_tab", "投料");
        g1.put("tabs", List.of(Map.of("alias", "投料", "tabKey", "T1:0"),
                               Map.of("alias", "加工", "tabKey", "T2:1")));
        g1.put("joins", List.of(Map.of("left_tab", "加工", "left_cols", List.of("物料编码"),
                                       "right_tab", "投料", "right_cols", List.of("物料编码"), "type", "INNER")));
        g1.put("where", List.of());
        g1.put("agg_expression", "SUM([投料.金额])");

        Map<String, Object> g2 = new LinkedHashMap<>();
        g2.put("ref", "组2"); g2.put("main_tab", "回料");
        g2.put("tabs", List.of(Map.of("alias", "回料", "tabKey", "T3:2")));
        g2.put("joins", List.of()); g2.put("where", List.of());
        g2.put("agg_expression", "SUM([回料.回料金额])");

        Map<String, Object> col = new LinkedHashMap<>();
        col.put("final_expression", "组1 + 组2 - 100");
        col.put("groups", List.of(g1, g2));
        return col;
    }

    @Test
    void evaluates_groups_and_final() {
        TabJoinPlanEvaluator.Result res = ev.evaluateColumn(cfg(), provider());
        assertEquals(0, new BigDecimal("100").compareTo(res.groupValues().get("组1")));
        assertEquals(0, new BigDecimal("96").compareTo(res.groupValues().get("组2")));
        assertEquals(0, new BigDecimal("96").compareTo(res.finalValue()));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -q test -Dtest=TabJoinPlanEvaluatorColumnTest`
Expected: 编译失败（`evaluateColumn` / `Result` 不存在）。

- [ ] **Step 3: 实现 evaluateColumn + Result**

在 `TabJoinPlanEvaluator` 内加：

```java
    public record Result(Map<String, java.math.BigDecimal> groupValues, java.math.BigDecimal finalValue) {}

    /** 整列求值：每组算标量 → 绑 ref → 算 final_expression。provider 来自 CardEffectiveRows。 */
    @SuppressWarnings("unchecked")
    public Result evaluateColumn(Map<String, Object> col,
                                 com.cpq.quotation.service.card.CardDataProvider provider) {
        Map<String, java.math.BigDecimal> groupValues = new LinkedHashMap<>();
        List<Map<String, Object>> groups = (List<Map<String, Object>>) col.getOrDefault("groups", List.of());
        for (Map<String, Object> g : groups) {
            String ref = (String) g.get("ref");
            try {
                groupValues.put(ref, evalOneGroup(g, provider));
            } catch (Exception e) {
                groupValues.put(ref, java.math.BigDecimal.ZERO);
            }
        }
        // final：把 组N 当变量
        String finalExpr = (String) col.getOrDefault("final_expression", "");
        java.math.BigDecimal finalVal = evalFinal(finalExpr, groupValues);
        return new Result(groupValues, finalVal);
    }

    @SuppressWarnings("unchecked")
    private java.math.BigDecimal evalOneGroup(Map<String, Object> g,
                                              com.cpq.quotation.service.card.CardDataProvider provider) {
        String mainTab = (String) g.get("main_tab");
        List<Map<String, Object>> tabs = (List<Map<String, Object>>) g.getOrDefault("tabs", List.of());
        // alias → 行集；alias.小计/总计 → subtotal
        Map<String, List<Map<String, Object>>> tabRows = new LinkedHashMap<>();
        Map<String, java.math.BigDecimal> subtotals = new LinkedHashMap<>();
        for (Map<String, Object> t : tabs) {
            String alias = (String) t.get("alias");
            String tabKey = (String) t.get("tabKey");
            tabRows.put(alias, provider.rowsOf(tabKey));
            java.math.BigDecimal sub = provider.subtotalOf(tabKey);
            if (sub != null) { subtotals.put(alias + ".小计", sub); subtotals.put(alias + ".总计", sub); }
        }
        List<Join> joins = parseJoins((List<Map<String, Object>>) g.getOrDefault("joins", List.of()));
        List<Cond> where = parseWhere((List<Map<String, Object>>) g.getOrDefault("where", List.of()));

        List<Map<String, Object>> wide = buildWideRows(mainTab, tabRows, joins);
        wide = applyWhere(wide, where);
        return evalGroupExpression((String) g.get("agg_expression"), wide, subtotals);
    }

    @SuppressWarnings("unchecked")
    private List<Join> parseJoins(List<Map<String, Object>> raw) {
        List<Join> out = new ArrayList<>();
        for (Map<String, Object> j : raw) {
            out.add(new Join(
                (String) j.get("left_tab"), (List<String>) j.getOrDefault("left_cols", List.of()),
                (String) j.get("right_tab"), (List<String>) j.getOrDefault("right_cols", List.of())));
        }
        return out;
    }

    private List<Cond> parseWhere(List<Map<String, Object>> raw) {
        List<Cond> out = new ArrayList<>();
        for (Map<String, Object> c : raw) {
            out.add(new Cond((String) c.get("col"), (String) c.get("op"),
                    c.get("value") == null ? "" : c.get("value").toString(),
                    (String) c.getOrDefault("logic", "AND")));
        }
        return out;
    }

    /** final 层：组N 当变量，只四则+常数；SafeArithmetic 处理缺值/除零。 */
    private java.math.BigDecimal evalFinal(String expr, Map<String, java.math.BigDecimal> groupValues) {
        if (expr == null || expr.isBlank()) return java.math.BigDecimal.ZERO;
        org.apache.commons.jexl3.JexlContext ctx = new org.apache.commons.jexl3.MapContext();
        for (var e : groupValues.entrySet()) ctx.set(e.getKey(), e.getValue());
        try {
            Object r = jexl.createExpression(expr).evaluate(ctx);
            return toBig(r);
        } catch (Exception e) { return java.math.BigDecimal.ZERO; }
    }
```

> 注意 final 层把 `组1`/`组2` 作为 JEXL 变量名。JEXL 标识符允许中文 + 数字混合（如 `组1`）；若 JEXL 版本不支持中文变量名，回退方案：把 `组N` 在求值前替换为 `_g0/_g1` 并同步 ctx 键。Task 5 测试若因变量名失败，改用替换法。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -q test -Dtest=TabJoinPlanEvaluatorColumnTest`
Expected: 1 test PASS。若 `组1` 变量名报错 → 按 Step 3 注释改替换法，重跑。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluator.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluatorColumnTest.java
git commit -m "feat(tabjoin): evaluateColumn 整列入口(组求值+final四则)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Phase 2 — 接入 ExcelViewService + 试算端点

### Task 6: buildRowData 接入 TAB_JOIN_FORMULA 分支

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java:42`（注入）、`:336-374`（switch 分支）

- [ ] **Step 1: 注入 TabJoinPlanEvaluator**

在 `ExcelViewService` 字段区（`cardFormulaEvaluator` 注入下方，约 :42）加：

```java
    @Inject
    com.cpq.quotation.service.tabjoin.TabJoinPlanEvaluator tabJoinPlanEvaluator;
```

- [ ] **Step 2: 在 buildRowData 内构造一次 provider（供 TAB_JOIN_FORMULA 用）**

在 `buildRowData`（五参+effectiveRows 重载，:287）内，CARD_FORMULA 块之后、逐列 switch 之前（约 :332）加：

```java
        // TAB_JOIN_FORMULA：构造 provider（有效行优先，缺省降级持久化 componentData）
        com.cpq.quotation.service.card.CardDataProvider tabJoinProvider =
            (effectiveRows != null)
                ? com.cpq.quotation.service.card.CardDataProvider.fromEffectiveRows(effectiveRows)
                : new com.cpq.quotation.service.card.CardDataProvider(componentDataList);
```

- [ ] **Step 3: switch 加 case**

在 `buildRowData` 的 `switch (sourceType)`（:343）里，`case "CARD_FORMULA"` 之后加：

```java
                case "TAB_JOIN_FORMULA" -> {
                    try {
                        var res = tabJoinPlanEvaluator.evaluateColumn(col, tabJoinProvider);
                        yield res.finalValue();
                    } catch (Exception e) {
                        LOG.warnf("[ExcelView] TAB_JOIN_FORMULA col '%s' eval failed: %s", colKey, e.getMessage());
                        yield null;
                    }
                }
```

- [ ] **Step 4: 编译 + 重启自检**

Run: `cd cpq-backend && ./mvnw -q compile`
Expected: BUILD SUCCESS。
Run: `touch cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java && sleep 7 && curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health`
Expected: 200。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java
git commit -m "feat(tabjoin): ExcelViewService.buildRowData 接入 TAB_JOIN_FORMULA 列

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: saveExcelViewConfig 配置期校验

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java:73-84`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/tabjoin/TabJoinConfigValidationTest.java`

校验：TAB_JOIN_FORMULA 列 `final_expression` 引用的 `组N` 必须存在于 `groups[].ref`；`groups` 非空；每组 `agg_expression` 非空。

- [ ] **Step 1: 写失败测试**

```java
package com.cpq.quotation.service.tabjoin;

import com.cpq.common.exception.BusinessException;
import com.cpq.quotation.service.ExcelViewService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class TabJoinConfigValidationTest {

    @Inject ExcelViewService svc;

    @Test
    void final_expression_referencing_unknown_group_rejected() {
        String json = """
            [{"col_key":"A","source_type":"TAB_JOIN_FORMULA","final_expression":"组1 + 组9",
              "groups":[{"ref":"组1","agg_expression":"SUM([投料.金额])","tabs":[],"joins":[],"where":[]}]}]
            """;
        // validateTabJoinConfig 是 static 纯函数，直接断言抛错
        BusinessException ex = assertThrows(BusinessException.class,
            () -> ExcelViewService.validateTabJoinConfig(
                new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.Map<String,Object>>>(){})));
        assertTrue(ex.getMessage().contains("组9"));
    }

    @Test
    void valid_config_passes() throws Exception {
        String json = """
            [{"col_key":"A","source_type":"TAB_JOIN_FORMULA","final_expression":"组1",
              "groups":[{"ref":"组1","agg_expression":"SUM([投料.金额])","tabs":[],"joins":[],"where":[]}]}]
            """;
        assertDoesNotThrow(() -> ExcelViewService.validateTabJoinConfig(
            new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.Map<String,Object>>>(){})));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -q test -Dtest=TabJoinConfigValidationTest`
Expected: 编译失败（`validateTabJoinConfig` 不存在）。

- [ ] **Step 3: 实现 static validateTabJoinConfig + 在 saveExcelViewConfig 调用**

在 `ExcelViewService` 加 static 方法：

```java
    /** TAB_JOIN_FORMULA 列配置期校验：组非空、agg 非空、final 引用的 组N 必须存在。 */
    @SuppressWarnings("unchecked")
    public static void validateTabJoinConfig(List<Map<String, Object>> columns) {
        for (Map<String, Object> col : columns) {
            if (!"TAB_JOIN_FORMULA".equals(col.get("source_type"))) continue;
            List<Map<String, Object>> groups =
                (List<Map<String, Object>>) col.getOrDefault("groups", List.of());
            if (groups.isEmpty())
                throw new BusinessException(400, "页签连表公式列 " + col.get("col_key") + " 至少需要一个计算组");
            Set<String> refs = new HashSet<>();
            for (Map<String, Object> g : groups) {
                String ref = (String) g.get("ref");
                if (ref == null || ref.isBlank())
                    throw new BusinessException(400, "计算组缺少引用名(ref)");
                refs.add(ref);
                Object agg = g.get("agg_expression");
                if (agg == null || agg.toString().isBlank())
                    throw new BusinessException(400, "计算组 " + ref + " 的聚合表达式不能为空");
            }
            String fin = (String) col.getOrDefault("final_expression", "");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("组\\d+").matcher(fin);
            while (m.find()) {
                if (!refs.contains(m.group()))
                    throw new BusinessException(400,
                        "页签连表公式列 " + col.get("col_key") + " 的最终表达式引用了不存在的计算组: " + m.group());
            }
        }
    }
```

在 `saveExcelViewConfig`（:84，EXCEL_FORMULA 校验循环之后）加一行：

```java
        validateTabJoinConfig(columns);
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -q test -Dtest=TabJoinConfigValidationTest`
Expected: 2 tests PASS。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/tabjoin/TabJoinConfigValidationTest.java
git commit -m "feat(tabjoin): saveExcelViewConfig 校验组非空/agg非空/final引用合法

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: 试算端点 dry-run-tab-formula

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java`（加 `dryRunTabFormula` 方法）
- Modify: `cpq-backend/src/main/java/com/cpq/template/resource/TemplateExcelViewResource.java`（加 POST 端点）

返回 `{groupValues, finalValue}`。用样本 quotation+lineItem 的有效行（走 buildLineRowData 的 provider 构造路径）。

- [ ] **Step 1: ExcelViewService 加 dryRunTabFormula**

```java
    /**
     * TAB_JOIN_FORMULA 试算：对指定样本 lineItem（产品卡片）用传入的单列配置算 {groupValues, finalValue}。
     * cardValuesJson 非空走有效行；否则降级持久化 componentData。求值异常 → finalValue=null + errors。
     */
    public Map<String, Object> dryRunTabFormula(UUID lineItemId, Map<String, Object> tabFormulaCol, String cardValuesJson) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            QuotationLineItem li = QuotationLineItem.findById(lineItemId);
            if (li == null) { out.put("errors", List.of("样本卡片不存在")); return out; }
            Map<String, com.cpq.quotation.service.card.CardEffectiveRows.TabRows> eff =
                parseEffectiveRows(cardValuesJson, li.templateId);
            com.cpq.quotation.service.card.CardDataProvider provider = (eff != null)
                ? com.cpq.quotation.service.card.CardDataProvider.fromEffectiveRows(eff)
                : new com.cpq.quotation.service.card.CardDataProvider(
                    QuotationLineComponentData.list("lineItemId = ?1 ORDER BY sortOrder ASC", lineItemId));
            var res = tabJoinPlanEvaluator.evaluateColumn(tabFormulaCol, provider);
            out.put("groupValues", res.groupValues());
            out.put("finalValue", res.finalValue());
            out.put("errors", List.of());
        } catch (Exception e) {
            out.put("finalValue", null);
            out.put("errors", List.of(e.getMessage() == null ? "求值异常" : e.getMessage()));
        }
        return out;
    }
```

> `cardValuesJson` 来源：核价/报价卡片值快照。试算时由前端从样本卡片 GET 拿到（见 Task 15）；若前端暂传 null，则走持久化 componentData（仍可算）。

- [ ] **Step 2: TemplateExcelViewResource 加 POST 端点**

在 `TemplateExcelViewResource` 内加：

```java
    /**
     * POST /api/cpq/templates/{id}/excel-view-config/dry-run-tab-formula
     * Body: { "lineItemId": "...", "column": {...TAB_JOIN_FORMULA 列配置...}, "cardValuesJson": "..."(可选) }
     * Resp: { "groupValues": {...}, "finalValue": ..., "errors": [...] }
     */
    @POST
    @Path("/dry-run-tab-formula")
    public ApiResponse<Map<String, Object>> dryRunTabFormula(
            @PathParam("id") UUID templateId, Map<String, Object> body) {
        Object liId = body.get("lineItemId");
        if (liId == null) throw new BusinessException(400, "lineItemId is required");
        @SuppressWarnings("unchecked")
        Map<String, Object> column = (Map<String, Object>) body.get("column");
        if (column == null) throw new BusinessException(400, "column is required");
        String cardValuesJson = body.get("cardValuesJson") == null ? null : body.get("cardValuesJson").toString();
        return ApiResponse.success(
            excelViewService.dryRunTabFormula(UUID.fromString(liId.toString()), column, cardValuesJson));
    }
```

- [ ] **Step 3: 编译 + 重启 + 端点自检**

Run: `cd cpq-backend && ./mvnw -q compile`
Expected: BUILD SUCCESS。
Run: `touch cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java && sleep 7 && curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8081/api/cpq/templates/00000000-0000-0000-0000-000000000000/excel-view-config/dry-run-tab-formula -H "Content-Type: application/json" -d '{}'`
Expected: 400（lineItemId required，非 500）或 401（auth）。

- [ ] **Step 4: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java \
        cpq-backend/src/main/java/com/cpq/template/resource/TemplateExcelViewResource.java
git commit -m "feat(tabjoin): 试算端点 dry-run-tab-formula(返回 groupValues+finalValue)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: 端到端集成测试（仿 ExcelViewCardFormulaIT）

**Files:**
- Test: `cpq-backend/src/test/java/com/cpq/quotation/card/ExcelViewTabJoinFormulaIT.java`

复用 `ExcelViewCardFormulaIT` 的造数模板（customer/user/quotation/template/line_item/component_data），配一个 TAB_JOIN_FORMULA 列，断言 `buildLineRowData` 出正确单值。

- [ ] **Step 1: 写测试（基于 ExcelViewCardFormulaIT 改造）**

```java
package com.cpq.quotation.card;

import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.service.ExcelViewService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端：TAB_JOIN_FORMULA 列。单页签组 SUM 验证（JOIN 路径已在单测覆盖）。
 * 投料页签 rowData=[{金额:100},{金额:50}] → 组1=SUM([投料.金额])=150 → final 组1=150。
 */
@QuarkusTest
class ExcelViewTabJoinFormulaIT {

    @Inject ExcelViewService excelViewService;
    @Inject EntityManager em;

    private static final UUID COMP_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Test
    @TestTransaction
    void tab_join_formula_single_group_sum() {
        UUID customerId = UUID.randomUUID();
        String custCode = "ITT" + customerId.toString().replace("-", "").substring(0, 7);
        em.createNativeQuery("""
            INSERT INTO customer (id, name, code, level, status, accumulated_amount, version, created_at, updated_at)
            VALUES (?1, 'IT-Cust-TabJoin', ?2, 'STANDARD', 'ACTIVE', 0, 0, now(), now())
            """).setParameter(1, customerId).setParameter(2, custCode).executeUpdate();

        UUID userId = UUID.randomUUID();
        String uSuffix = userId.toString().replace("-", "").substring(0, 8);
        em.createNativeQuery("""
            INSERT INTO "user" (id, username, full_name, email, password_hash, role, status,
              is_first_login, failed_login_attempts, created_at, updated_at)
            VALUES (?1, ?2, 'IT User TJ', ?3, 'hash', 'SALES_REP', 'ACTIVE', true, 0, now(), now())
            """).setParameter(1, userId).setParameter(2, "it-tj-user-" + uSuffix)
            .setParameter(3, "ittj_" + uSuffix + "@test.com").executeUpdate();

        UUID quotationId = UUID.randomUUID();
        String qNum = "IT-TJ-" + quotationId.toString().replace("-", "").substring(0, 8);
        em.createNativeQuery("""
            INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id,
              status, total_amount, original_amount, system_discount_rate, final_discount_rate,
              is_manually_adjusted, tax_rate, tax_amount, bound_global_variables_snapshot, created_at, updated_at)
            VALUES (?1, ?2, ?3, 'IT TJ Quotation', ?4, 'DRAFT', 0, 0, 100, 100, false, 0, 0, '[]', now(), now())
            """).setParameter(1, quotationId).setParameter(2, qNum)
            .setParameter(3, customerId).setParameter(4, userId).executeUpdate();

        UUID templateId = UUID.randomUUID();
        String excelViewConfig = """
            [{"col_key":"A","source_type":"TAB_JOIN_FORMULA","final_expression":"组1",
              "groups":[{"ref":"组1","main_tab":"投料",
                "tabs":[{"alias":"投料","tabKey":"%s:0"}],
                "joins":[],"where":[],"agg_expression":"SUM([投料.金额])"}]}]
            """.formatted(COMP_ID).strip();
        em.createNativeQuery("""
            INSERT INTO template (id, template_series_id, name, status, formulas,
              template_sql_views_snapshot, excel_view_config, created_at, updated_at)
            VALUES (?1, ?2, 'IT-TabJoin-Tmpl', 'DRAFT', '[]', '{}', CAST(?3 AS jsonb), now(), now())
            """).setParameter(1, templateId).setParameter(2, UUID.randomUUID())
            .setParameter(3, excelViewConfig).executeUpdate();

        UUID lineItemId = UUID.randomUUID();
        em.createNativeQuery("""
            INSERT INTO quotation_line_item (id, quotation_id, product_id, template_id, product_attribute_values,
              subtotal, system_discount_rate, final_discount_rate, sort_order, part_version_locked, composite_type, created_at)
            VALUES (?1, ?2, NULL, NULL, '{}', 0, 100, 100, 0, 2000, 'SIMPLE', now())
            """).setParameter(1, lineItemId).setParameter(2, quotationId).executeUpdate();

        String rowData = "[{\"金额\":100},{\"金额\":50}]";
        em.createNativeQuery("""
            INSERT INTO quotation_line_component_data
              (id, line_item_id, component_id, tab_name, row_data, subtotal, sort_order, created_at)
            VALUES (?1, ?2, ?3, '投料', CAST(?4 AS jsonb), 150, 0, now())
            """).setParameter(1, UUID.randomUUID()).setParameter(2, lineItemId)
            .setParameter(3, COMP_ID).setParameter(4, rowData).executeUpdate();

        em.flush();
        QuotationLineItem li = QuotationLineItem.findById(lineItemId);
        assertNotNull(li);

        var out = excelViewService.buildLineRowData(li, templateId, null);
        Object a = out.get("A");
        assertNotNull(a, "TAB_JOIN_FORMULA 列 A 应有值");
        assertEquals(0, new BigDecimal("150").compareTo(new BigDecimal(a.toString())), "A 应=150, got " + a);
    }
}
```

> 注意 tabKey = `componentId:sortOrder`，本测 component_data 的 sort_order=0 → tabKey=`COMP_ID:0`，与配置一致。`buildLineRowData` 走 effectiveRows=null 降级路径 → `new CardDataProvider(componentDataList)` → `rowsOf` 用持久化 rowData（精确 byTab 或 sortOrder 回退命中）。

- [ ] **Step 2: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -q test -Dtest=ExcelViewTabJoinFormulaIT`
Expected: 1 test PASS（A=150）。若失败先确认 provider `rowsOf("COMP_ID:0")` 命中（持久化模式 keyOf=componentId:sortOrder）。

- [ ] **Step 3: 提交**

```bash
git add cpq-backend/src/test/java/com/cpq/quotation/card/ExcelViewTabJoinFormulaIT.java
git commit -m "test(tabjoin): 端到端 buildLineRowData 出 TAB_JOIN_FORMULA 单值=150

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 4: 跑全量 tabjoin 单测回归**

Run: `cd cpq-backend && ./mvnw -q test -Dtest='*TabJoin*,SafeArithmeticTest,ExcelViewTabJoinFormulaIT'`
Expected: 全 PASS。

---

## Phase 3 — 前端构建器（仿 CardFormulaDrawer；E2E 验收，非单测）

> 前端无单测惯例（仓库用 Playwright E2E）。每个 Task 收尾自检：`npx tsc --noEmit` 0 错误 + 改动 `.tsx` 走 `curl http://localhost:5174/src/<相对路径>` 200。原型见 `docs/html/excel-tab-join-formula-builder.html`，可对照布局。

### Task 10: 列来源类型 + 列表入口接线

**Files:**
- Modify: `cpq-frontend/src/pages/template/ExcelViewConfigTab.tsx`

- [ ] **Step 1: SourceType 加 TAB_JOIN_FORMULA**

`ExcelViewConfigTab.tsx:21` 改：

```typescript
type SourceType = 'VARIABLE' | 'FORMULA' | 'CARD_FORMULA' | 'TAB_JOIN_FORMULA' | 'PRODUCT_ATTRIBUTE' | 'COMPONENT_FIELD' | 'EXCEL_FORMULA' | 'FIXED_VALUE';
```

`ExcelViewColumn` interface（:23）加可选字段：

```typescript
  // TAB_JOIN_FORMULA 专属
  final_expression?: string;
  groups?: any[];
```

- [ ] **Step 2: 列来源下拉 + 抽屉状态**

在状态区（约 :104，CardFormulaDrawer 状态附近）加：

```typescript
  const [tabJoinColIdx, setTabJoinColIdx] = useState<number | null>(null);
```

在列来源 `Select`（搜索 `source_type` 的 Select Option 列表，与 CARD_FORMULA 同处）加一项：

```tsx
              <Select.Option value="TAB_JOIN_FORMULA">页签连表公式</Select.Option>
```

并在该列被设为 TAB_JOIN_FORMULA 时显示"配置公式"按钮（仿 CARD_FORMULA 的 `setCardDrawerColIdx` 入口），点击 `setTabJoinColIdx(idx)`。

- [ ] **Step 3: 渲染 Drawer（Task 11 创建组件后接入）**

在 return 末尾、CardFormulaDrawer 渲染处旁加（先占位，Task 11 实现组件）：

```tsx
      {tabJoinColIdx !== null && (
        <TabJoinFormulaDrawer
          open={tabJoinColIdx !== null}
          templateId={templateId}
          column={columns[tabJoinColIdx]}
          onClose={() => setTabJoinColIdx(null)}
          onSave={(patch) => { updateColumn(tabJoinColIdx, patch); setTabJoinColIdx(null); }}
        />
      )}
```

文件顶部 import（Task 11 创建后生效）：

```typescript
import TabJoinFormulaDrawer from './TabJoinFormulaDrawer';
```

- [ ] **Step 4: 自检（Task 11 完成前 tsc 会因缺组件报错，可暂留到 Task 11 一起验）**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json 2>&1 | grep -i TabJoinFormulaDrawer || echo "等待 Task 11"`

- [ ] **Step 5: 提交（与 Task 11 合并提交亦可）**

```bash
git add cpq-frontend/src/pages/template/ExcelViewConfigTab.tsx
git commit -m "feat(tabjoin-ui): 列来源新增页签连表公式 + Drawer 入口接线

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 11: TabJoinFormulaDrawer 骨架（最终表达式 + 计算组状态 + 保存）

**Files:**
- Create: `cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx`
- Create: `cpq-frontend/src/services/tabJoinFormulaService.ts`

- [ ] **Step 1: service（取模板页签定义 + 样本卡片 + 试算）**

```typescript
// cpq-frontend/src/services/tabJoinFormulaService.ts
import api from './api';

export interface TabDef { alias: string; tabKey: string; fields: string[]; aggFields: string[]; }

export const tabJoinFormulaService = {
  // 模板页签定义（alias/tabKey/字段/小计总计）。后端可用 componentsSnapshot 拼，或复用现有模板接口。
  tabs: (templateId: string) =>
    api.get(`/templates/${templateId}/tab-defs`) as Promise<TabDef[]>,
  // 样本：报价单/核价单 → 产品卡片(lineItem)
  sampleCards: (templateId: string) =>
    api.get(`/templates/${templateId}/sample-cards`) as Promise<Array<{ quotationId: string; quotationNo: string; lineItemId: string; cardName: string }>>,
  dryRun: (templateId: string, lineItemId: string, column: any, cardValuesJson?: string) =>
    api.post(`/templates/${templateId}/excel-view-config/dry-run-tab-formula`,
      { lineItemId, column, cardValuesJson }) as Promise<{ groupValues: Record<string, number>; finalValue: number | null; errors: string[] }>,
};
```

> **依赖说明**：本 service 假定两个 GET 端点 `/templates/{id}/tab-defs`、`/templates/{id}/sample-cards`。若后端尚无，需在 Task 11 附带补：`tab-defs` 从 `template.componentsSnapshot` 解析（componentId:sortOrder → alias=组件名 / fields=字段名列表 / aggFields=["小计","总计"]）；`sample-cards` 查该模板被引用的 quotation_line_item（join quotation 取单号）。**实现 Task 11 时若端点缺失，先在 TemplateExcelViewResource 补这两个只读 GET，再继续前端。**

- [ ] **Step 2: Drawer 骨架（state + 最终表达式框 + 保存）**

```tsx
// cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx
import React, { useState, useEffect, useRef } from 'react';
import { Drawer, Button, Input, Space, message, Typography } from 'antd';
import { tabJoinFormulaService, type TabDef } from '../../services/tabJoinFormulaService';

const { Text } = Typography;

interface Group {
  ref: string; main_tab: string;
  tabs: Array<{ alias: string; tabKey: string }>;
  joins: any[]; where: any[]; agg_expression: string;
}
interface Props {
  open: boolean; templateId: string; column: any;
  onClose: () => void; onSave: (patch: any) => void;
}

const TabJoinFormulaDrawer: React.FC<Props> = ({ open, templateId, column, onClose, onSave }) => {
  const [finalExpr, setFinalExpr] = useState<string>(column?.final_expression ?? '');
  const [groups, setGroups] = useState<Group[]>(column?.groups ?? []);
  const [tabDefs, setTabDefs] = useState<TabDef[]>([]);
  // 跟随焦点：'final' | `group:${i}`
  const activeTarget = useRef<string>('final');
  const finalRef = useRef<any>(null);

  useEffect(() => { if (open) tabJoinFormulaService.tabs(templateId).then(setTabDefs).catch(() => setTabDefs([])); }, [open, templateId]);

  const addGroup = () => setGroups(prev => [...prev,
    { ref: `组${prev.length + 1}`, main_tab: '', tabs: [], joins: [], where: [], agg_expression: '' }]);

  const insertToActive = (token: string) => {
    if (activeTarget.current === 'final') {
      setFinalExpr(v => v + token);
    } else {
      const gi = Number(activeTarget.current.split(':')[1]);
      setGroups(prev => prev.map((g, i) => i === gi ? { ...g, agg_expression: g.agg_expression + token } : g));
    }
  };

  const save = () => {
    if (groups.length === 0) { message.error('至少一个计算组'); return; }
    onSave({ source_type: 'TAB_JOIN_FORMULA', final_expression: finalExpr, groups });
  };

  return (
    <Drawer title="配置页签连表公式" width={1200} open={open} onClose={onClose}
      extra={<Space><Button onClick={onClose}>取消</Button><Button type="primary" onClick={save}>保存</Button></Space>}>
      <Text strong>① 最终表达式（组N + 四则 + 常数）</Text>
      <Input.TextArea ref={finalRef} rows={2} value={finalExpr}
        onFocus={() => { activeTarget.current = 'final'; }}
        onChange={e => setFinalExpr(e.target.value)} style={{ fontFamily: 'monospace', marginTop: 6 }} />
      <Space style={{ marginTop: 8 }}>
        {['+', '-', '*', '/', '(', ')'].map(op =>
          <Button key={op} size="small" onClick={() => setFinalExpr(v => v + op)}>{op}</Button>)}
      </Space>

      <div style={{ marginTop: 20, display: 'flex', justifyContent: 'space-between' }}>
        <Text strong>② 计算组</Text>
        <Button size="small" type="primary" onClick={addGroup}>+ 新增计算组</Button>
      </div>
      {/* Task 12-15 在此渲染 GroupCard 列表，传 groups/setGroups/tabDefs/insertToActive/activeTarget */}
      {groups.map((g, i) => (
        <div key={i} style={{ border: '1px solid #d6e4ff', borderRadius: 8, padding: 12, marginTop: 12 }}>
          <Text>{g.ref}</Text>
          <Input.TextArea rows={2} value={g.agg_expression}
            placeholder="组内聚合表达式，如 SUM([投料.金额])"
            onFocus={() => { activeTarget.current = `group:${i}`; }}
            onChange={e => setGroups(prev => prev.map((x, k) => k === i ? { ...x, agg_expression: e.target.value } : x))}
            style={{ fontFamily: 'monospace', marginTop: 6 }} />
        </div>
      ))}
    </Drawer>
  );
};

export default TabJoinFormulaDrawer;
```

- [ ] **Step 3: 自检**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误。
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/template/TabJoinFormulaDrawer.tsx`
Expected: 200。

- [ ] **Step 4: 提交**

```bash
git add cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx \
        cpq-frontend/src/services/tabJoinFormulaService.ts \
        cpq-frontend/src/pages/template/ExcelViewConfigTab.tsx
git commit -m "feat(tabjoin-ui): TabJoinFormulaDrawer 骨架(最终表达式+组状态+保存)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 12: GroupCard — 页签 pill + 添加页签默认同名关联键

**Files:**
- Create: `cpq-frontend/src/pages/template/tabjoin/GroupCard.tsx`
- Create: `cpq-frontend/src/pages/template/tabjoin/JoinKeyModal.tsx`
- Modify: `cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx`（用 GroupCard 替换 Step2 占位渲染）

- [ ] **Step 1: JoinKeyModal（选关联到哪个已加入页签 + 列对应；默认同名列预填）**

```tsx
// cpq-frontend/src/pages/template/tabjoin/JoinKeyModal.tsx
import React, { useState } from 'react';
import { Modal, Select, Button, Space } from 'antd';
import type { TabDef } from '../../../services/tabJoinFormulaService';

interface Props {
  open: boolean;
  newTab: TabDef;
  existingTabs: Array<{ alias: string; tabKey: string }>;
  tabDefs: TabDef[];
  onOk: (join: any) => void;
  onCancel: () => void;
}

const JoinKeyModal: React.FC<Props> = ({ open, newTab, existingTabs, tabDefs, onOk, onCancel }) => {
  const [targetAlias, setTargetAlias] = useState(existingTabs[0]?.alias ?? '');
  const targetDef = tabDefs.find(t => t.alias === targetAlias);
  // 默认同名列预填：新页签与目标页签字段名相同的列
  const defaultPairs = (newTab.fields || []).filter(f => (targetDef?.fields || []).includes(f))
    .map(f => ({ newCol: f, targetCol: f }));
  const [pairs, setPairs] = useState(defaultPairs.length ? defaultPairs : [{ newCol: '', targetCol: '' }]);

  return (
    <Modal title={`设置关联键 · 新页签「${newTab.alias}」`} open={open} onCancel={onCancel}
      onOk={() => onOk({
        left_tab: newTab.alias, left_cols: pairs.map(p => p.newCol),
        right_tab: targetAlias, right_cols: pairs.map(p => p.targetCol), type: 'INNER',
      })}>
      <Space direction="vertical" style={{ width: '100%' }}>
        <div>关联到已加入页签：
          <Select value={targetAlias} onChange={setTargetAlias} style={{ width: 200, marginLeft: 8 }}
            options={existingTabs.map(t => ({ value: t.alias, label: t.alias }))} />
        </div>
        {pairs.map((p, i) => (
          <Space key={i}>
            <Select value={p.newCol} style={{ width: 160 }} placeholder={`${newTab.alias}.列`}
              onChange={v => setPairs(ps => ps.map((x, k) => k === i ? { ...x, newCol: v } : x))}
              options={(newTab.fields || []).map(f => ({ value: f, label: `${newTab.alias}.${f}` }))} />
            <b>=</b>
            <Select value={p.targetCol} style={{ width: 160 }} placeholder={`${targetAlias}.列`}
              onChange={v => setPairs(ps => ps.map((x, k) => k === i ? { ...x, targetCol: v } : x))}
              options={(targetDef?.fields || []).map(f => ({ value: f, label: `${targetAlias}.${f}` }))} />
            <Button size="small" onClick={() => setPairs(ps => ps.filter((_, k) => k !== i))}>✕</Button>
          </Space>
        ))}
        <Button size="small" onClick={() => setPairs(ps => [...ps, { newCol: '', targetCol: '' }])}>+ 复合键</Button>
      </Space>
    </Modal>
  );
};

export default JoinKeyModal;
```

- [ ] **Step 2: GroupCard（页签 pills + 添加页签触发 JoinKeyModal + join 展示）**

```tsx
// cpq-frontend/src/pages/template/tabjoin/GroupCard.tsx
import React, { useState } from 'react';
import { Button, Space, Tag, Select } from 'antd';
import type { TabDef } from '../../../services/tabJoinFormulaService';
import JoinKeyModal from './JoinKeyModal';

interface Group {
  ref: string; main_tab: string;
  tabs: Array<{ alias: string; tabKey: string }>;
  joins: any[]; where: any[]; agg_expression: string;
}
interface Props {
  group: Group; index: number; tabDefs: TabDef[];
  onChange: (g: Group) => void;
}

const GroupCard: React.FC<Props> = ({ group, tabDefs, onChange }) => {
  const [joinModalTab, setJoinModalTab] = useState<TabDef | null>(null);
  const availableToAdd = tabDefs.filter(t => !group.tabs.some(gt => gt.alias === t.alias));

  const addTab = (def: TabDef) => {
    if (group.tabs.length === 0) {
      // 第一个 = 主页签，无需 join
      onChange({ ...group, main_tab: def.alias, tabs: [{ alias: def.alias, tabKey: def.tabKey }] });
    } else {
      setJoinModalTab(def); // 弹关联键
    }
  };

  return (
    <div>
      <Space wrap>
        {group.tabs.map(t => (
          <Tag key={t.alias} color={t.alias === group.main_tab ? 'purple' : 'default'} closable
            onClose={() => onChange({
              ...group, tabs: group.tabs.filter(x => x.alias !== t.alias),
              joins: group.joins.filter(j => j.left_tab !== t.alias && j.right_tab !== t.alias),
            })}>
            {t.alias}{t.alias === group.main_tab ? ' · 主' : ''}
          </Tag>
        ))}
        <Select size="small" style={{ width: 130 }} placeholder="+ 添加页签" value={null}
          onChange={(alias) => { const d = tabDefs.find(t => t.alias === alias); if (d) addTab(d); }}
          options={availableToAdd.map(t => ({ value: t.alias, label: t.alias }))} />
      </Space>
      {group.joins.map((j, i) => (
        <div key={i} style={{ fontSize: 12, color: '#555', marginTop: 6 }}>
          🔗 {j.left_tab}.{(j.left_cols || []).join(',')} = {j.right_tab}.{(j.right_cols || []).join(',')} (INNER)
        </div>
      ))}
      {joinModalTab && (
        <JoinKeyModal open={!!joinModalTab} newTab={joinModalTab}
          existingTabs={group.tabs} tabDefs={tabDefs}
          onCancel={() => setJoinModalTab(null)}
          onOk={(join) => {
            onChange({ ...group,
              tabs: [...group.tabs, { alias: joinModalTab.alias, tabKey: joinModalTab.tabKey }],
              joins: [...group.joins, join] });
            setJoinModalTab(null);
          }} />
      )}
    </div>
  );
};

export default GroupCard;
```

- [ ] **Step 3: TabJoinFormulaDrawer 用 GroupCard 替换占位**

把 Task 11 Step2 里 `{groups.map(...)}` 那段替换为：

```tsx
      {groups.map((g, i) => (
        <div key={i} style={{ border: '1px solid #d6e4ff', borderRadius: 8, padding: 12, marginTop: 12 }}>
          <GroupCard group={g} index={i} tabDefs={tabDefs}
            onChange={(ng) => setGroups(prev => prev.map((x, k) => k === i ? ng : x))} />
          <Input.TextArea rows={2} value={g.agg_expression}
            placeholder="组内聚合表达式，如 SUM([投料.金额])"
            onFocus={() => { activeTarget.current = `group:${i}`; }}
            onChange={e => setGroups(prev => prev.map((x, k) => k === i ? { ...x, agg_expression: e.target.value } : x))}
            style={{ fontFamily: 'monospace', marginTop: 8 }} />
        </div>
      ))}
```

并在 import 加 `import GroupCard from './tabjoin/GroupCard';`。

- [ ] **Step 4: 自检**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误。
Run: `for f in pages/template/tabjoin/GroupCard.tsx pages/template/tabjoin/JoinKeyModal.tsx pages/template/TabJoinFormulaDrawer.tsx; do curl -s -o /dev/null -w "$f %{http_code}\n" http://localhost:5174/src/$f; done`
Expected: 全 200。

- [ ] **Step 5: 提交**

```bash
git add cpq-frontend/src/pages/template/tabjoin/ cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx
git commit -m "feat(tabjoin-ui): GroupCard 页签pill + JoinKeyModal(默认同名关联键)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 13: VariableMatrix — 字段 chip + 跟随焦点插入 + 设置条件入口

**Files:**
- Create: `cpq-frontend/src/pages/template/tabjoin/VariableMatrix.tsx`
- Modify: `cpq-frontend/src/pages/template/tabjoin/GroupCard.tsx`、`TabJoinFormulaDrawer.tsx`

- [ ] **Step 1: VariableMatrix**

```tsx
// cpq-frontend/src/pages/template/tabjoin/VariableMatrix.tsx
import React from 'react';
import { Dropdown, Tag } from 'antd';
import type { TabDef } from '../../../services/tabJoinFormulaService';

interface Props {
  tabs: Array<{ alias: string; tabKey: string }>;
  tabDefs: TabDef[];
  onInsert: (token: string) => void;      // 跟随焦点插入 [别名.字段]
  onSetCond: (col: string) => void;        // 字段 → 设置条件（加到本组 where）
}

const VariableMatrix: React.FC<Props> = ({ tabs, tabDefs, onInsert, onSetCond }) => (
  <div style={{ border: '1px solid #e5e7eb', borderRadius: 6, marginTop: 8 }}>
    {tabs.map(t => {
      const def = tabDefs.find(d => d.alias === t.alias);
      const fields = [...(def?.fields || []), ...(def?.aggFields || [])];
      return (
        <div key={t.alias} style={{ display: 'flex', borderBottom: '1px solid #f0f0f0', padding: 6 }}>
          <div style={{ width: 110, fontWeight: 600 }}>{t.alias}</div>
          <div style={{ flex: 1, display: 'flex', gap: 6, flexWrap: 'wrap' }}>
            {fields.map(f => {
              const token = `[${t.alias}.${f}]`;
              const isAgg = (def?.aggFields || []).includes(f);
              return (
                <Dropdown key={f} trigger={['click']} menu={{ items: [
                  { key: 'ins', label: '插入到表达式', onClick: () => onInsert(token) },
                  { key: 'cond', label: '设置条件', onClick: () => onSetCond(`${t.alias}.${f}`) },
                ] }}>
                  <Tag style={{ cursor: 'pointer', borderStyle: isAgg ? 'dashed' : 'solid' }}>{f}</Tag>
                </Dropdown>
              );
            })}
          </div>
        </div>
      );
    })}
  </div>
);

export default VariableMatrix;
```

- [ ] **Step 2: GroupCard 接 VariableMatrix（透传 onInsert/onSetCond）**

GroupCard Props 加 `onInsert: (token: string) => void;`，在 join 展示下方渲染：

```tsx
      <VariableMatrix tabs={group.tabs} tabDefs={tabDefs}
        onInsert={onInsert}
        onSetCond={(col) => onChange({ ...group, where: [...group.where, { col, op: '=', value: '', logic: group.where.length ? 'AND' : '' }] })} />
```

import `VariableMatrix`。

- [ ] **Step 3: Drawer 把 insertToActive 传进对应组的 onInsert**

GroupCard 渲染处传：`onInsert={(token) => { activeTarget.current = \`group:${i}\`; insertToActive(token); }}`。
（点字段默认插到本组聚合框，符合"跟随焦点"；用户也可先聚焦最终框再点。）

- [ ] **Step 4: 自检**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误。
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/template/tabjoin/VariableMatrix.tsx`
Expected: 200。

- [ ] **Step 5: 提交**

```bash
git add cpq-frontend/src/pages/template/tabjoin/
git commit -m "feat(tabjoin-ui): VariableMatrix 字段chip+跟随焦点插入+设置条件入口

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 14: WherePanel + 函数工具条

**Files:**
- Create: `cpq-frontend/src/pages/template/tabjoin/WherePanel.tsx`
- Modify: `GroupCard.tsx`（渲染 WherePanel + 组聚合框上方函数工具条）

- [ ] **Step 1: WherePanel**

```tsx
// cpq-frontend/src/pages/template/tabjoin/WherePanel.tsx
import React from 'react';
import { Select, Input, Button, Space } from 'antd';

interface Cond { col: string; op: string; value: string; logic: string; }
interface Props { where: Cond[]; cols: string[]; onChange: (w: Cond[]) => void; }

const OPS = ['=', '>', '<', '包含', '不包含'];

const WherePanel: React.FC<Props> = ({ where, cols, onChange }) => (
  <div style={{ marginTop: 8 }}>
    {where.map((c, i) => (
      <Space key={i} style={{ marginBottom: 4 }}>
        {i > 0
          ? <Select value={c.logic} style={{ width: 70 }} options={[{ value: 'AND', label: 'AND' }, { value: 'OR', label: 'OR' }]}
              onChange={v => onChange(where.map((x, k) => k === i ? { ...x, logic: v } : x))} />
          : <span style={{ width: 70, display: 'inline-block' }} />}
        <Select value={c.col} style={{ width: 160 }} options={cols.map(col => ({ value: col, label: col }))}
          onChange={v => onChange(where.map((x, k) => k === i ? { ...x, col: v } : x))} />
        <Select value={c.op} style={{ width: 90 }} options={OPS.map(o => ({ value: o, label: o }))}
          onChange={v => onChange(where.map((x, k) => k === i ? { ...x, op: v } : x))} />
        <Input value={c.value} style={{ width: 120 }}
          onChange={e => onChange(where.map((x, k) => k === i ? { ...x, value: e.target.value } : x))} />
        <Button size="small" onClick={() => onChange(where.filter((_, k) => k !== i))}>✕</Button>
      </Space>
    ))}
    <Button size="small" onClick={() => onChange([...where, { col: cols[0] ?? '', op: '=', value: '', logic: where.length ? 'AND' : '' }])}>+ 增加条件</Button>
  </div>
);

export default WherePanel;
```

- [ ] **Step 2: GroupCard 渲染 WherePanel + 函数工具条**

GroupCard 内计算可选列（`别名.字段` 全集）：

```tsx
  const allCols = group.tabs.flatMap(t => {
    const d = tabDefs.find(x => x.alias === t.alias);
    return [...(d?.fields || []), ...(d?.aggFields || [])].map(f => `${t.alias}.${f}`);
  });
```

渲染：

```tsx
      <WherePanel where={group.where} cols={allCols}
        onChange={(w) => onChange({ ...group, where: w })} />
```

聚合框上方函数工具条（在 Drawer 的组聚合 TextArea 上方加，或 GroupCard 暴露 onInsert）：

```tsx
      <Space style={{ marginTop: 8 }}>
        {['+', '-', '*', '/', '(', ')'].map(op => <Button key={op} size="small" onClick={() => onInsert(op)}>{op}</Button>)}
        {['SUM', 'AVG', 'MIN', 'MAX', 'COUNT'].map(fn =>
          <Button key={fn} size="small" onClick={() => onInsert(`${fn}()`)} style={{ color: '#fa8c16' }}>{fn}</Button>)}
      </Space>
```

import `WherePanel`。

- [ ] **Step 3: 自检**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误。
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/template/tabjoin/WherePanel.tsx`
Expected: 200。

- [ ] **Step 4: 提交**

```bash
git add cpq-frontend/src/pages/template/tabjoin/
git commit -m "feat(tabjoin-ui): WherePanel 条件行(=/>/</包含/不包含+AND/OR) + 函数工具条

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 15: 样本卡片选择 + 试算

**Files:**
- Create: `cpq-frontend/src/pages/template/tabjoin/SampleCardPicker.tsx`
- Modify: `TabJoinFormulaDrawer.tsx`（顶部样本选择 + 整列试算 + 组结果回显）

- [ ] **Step 1: SampleCardPicker**

```tsx
// cpq-frontend/src/pages/template/tabjoin/SampleCardPicker.tsx
import React, { useEffect, useState } from 'react';
import { Select } from 'antd';
import { tabJoinFormulaService } from '../../../services/tabJoinFormulaService';

interface Props { templateId: string; value?: string; onChange: (lineItemId: string, label: string) => void; }

const SampleCardPicker: React.FC<Props> = ({ templateId, value, onChange }) => {
  const [opts, setOpts] = useState<Array<{ value: string; label: string }>>([]);
  useEffect(() => {
    tabJoinFormulaService.sampleCards(templateId)
      .then(list => setOpts(list.map(c => ({ value: c.lineItemId, label: `${c.quotationNo} / ${c.cardName}` }))))
      .catch(() => setOpts([]));
  }, [templateId]);
  return (
    <Select placeholder="选样本卡片试算" style={{ width: 280 }} value={value} options={opts}
      onChange={(v, o: any) => onChange(v, o.label)} />
  );
};

export default SampleCardPicker;
```

- [ ] **Step 2: Drawer 顶部加样本选择 + 试算按钮 + 回显**

在 Drawer state 加：

```tsx
  const [sampleLi, setSampleLi] = useState<string | undefined>(undefined);
  const [dryRunRes, setDryRunRes] = useState<{ groupValues: Record<string, number>; finalValue: number | null; errors: string[] } | null>(null);

  const runDryRun = async () => {
    if (!sampleLi) { message.warning('请先选样本卡片'); return; }
    try {
      const res = await tabJoinFormulaService.dryRun(templateId, sampleLi,
        { source_type: 'TAB_JOIN_FORMULA', final_expression: finalExpr, groups });
      setDryRunRes(res);
      if (res.errors?.length) message.warning(res.errors.join('; '));
    } catch (e: any) { message.error('试算失败: ' + (e?.message ?? e)); }
  };
```

在 Drawer `extra` 或顶部加：

```tsx
      <Space style={{ marginBottom: 12 }}>
        <SampleCardPicker templateId={templateId} value={sampleLi}
          onChange={(li) => setSampleLi(li)} />
        <Button onClick={runDryRun}>▶ 整列试算</Button>
        {dryRunRes && <Text strong style={{ color: '#1677ff' }}>结果：{dryRunRes.finalValue ?? '—'}</Text>}
      </Space>
```

组结果回显：在每个 GroupCard 下方加 `{dryRunRes?.groupValues?.[g.ref] != null && <Text type="success">本组结果：{dryRunRes.groupValues[g.ref]}</Text>}`。

import `SampleCardPicker`、`Button`、`message`、`Text`（按需）。

- [ ] **Step 3: 自检**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误。
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/template/tabjoin/SampleCardPicker.tsx`
Expected: 200。

- [ ] **Step 4: 提交**

```bash
git add cpq-frontend/src/pages/template/tabjoin/SampleCardPicker.tsx \
        cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx
git commit -m "feat(tabjoin-ui): 样本卡片选择 + 整列试算 + 组结果回显

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Phase 4 — 验收

### Task 16: E2E + 全量自检 + 文档回写

**Files:**
- Create/Modify: `cpq-frontend/e2e/tab-join-formula.spec.ts`（新建 E2E）
- Modify: `docs/RECORD.md`、`docs/Excel模板配置指南.md`（新列来源说明）

- [ ] **Step 1: 写 E2E（配一个 TAB_JOIN_FORMULA 列 → 试算出值 → 保存）**

参照 `docs/E2E测试方法.md` 与现有 `e2e/quotation-flow.spec.ts` 选择器约定。脚本要点：进模板 Excel 配置 → 列来源选「页签连表公式」→ 打开 Drawer → 新增组 → 选主页签 → 写 `SUM([投料.金额])` → 选样本卡片 → 点试算 → 断言结果非空 → 保存 → 断言列保存成功。

```typescript
// cpq-frontend/e2e/tab-join-formula.spec.ts
import { test, expect } from '@playwright/test';

test('页签连表公式：配置 → 试算 → 保存', async ({ page }) => {
  await page.goto('/');
  // … 登录 + 进 DRAFT 模板的 Excel 视图配置 Tab（按 quotation-flow.spec.ts 同款导航）
  // 新增列 → 列来源选 TAB_JOIN_FORMULA
  // 打开 TabJoinFormulaDrawer，新增计算组，选主页签，输入 SUM([投料.金额])
  // 选样本卡片，点「整列试算」，断言结果区出现数字
  // 点保存，断言无 error message
  expect(true).toBe(true); // 占位 — 实现时按真实选择器补全断言
});
```

> 实现时必须把上面注释替换为真实步骤与断言（参照 quotation-flow.spec.ts 的 data-testid / 文案选择器），不得留占位 `expect(true)`。

- [ ] **Step 2: 跑后端全量 tabjoin 回归**

Run: `cd cpq-backend && ./mvnw -q test -Dtest='*TabJoin*,SafeArithmeticTest,ExcelViewTabJoinFormulaIT,TabJoinConfigValidationTest'`
Expected: 全 PASS。

- [ ] **Step 3: 前端全量自检**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误。
Run: `npx playwright test --config=e2e/playwright.config.ts e2e/tab-join-formula.spec.ts --reporter=list`
Expected: passed。

- [ ] **Step 4: 文档回写**

- `docs/RECORD.md` 追加：`[2026-06-10] Excel页签连表公式 - 新增 TAB_JOIN_FORMULA 列来源(多页签INNER JOIN+计算组+两层求值+试算) | TabJoinPlanEvaluator/ExcelViewService/TabJoinFormulaDrawer | 关键:复用CardDataProvider取行,SafeArithmetic实现缺值0除数1`
- `docs/Excel模板配置指南.md` 加一节"页签连表公式列(TAB_JOIN_FORMULA)"：JSON 契约 + 计算组语义 + 两层求值 + 试算。

- [ ] **Step 5: 提交 + 自检声明**

```bash
git add cpq-frontend/e2e/tab-join-formula.spec.ts docs/RECORD.md docs/Excel模板配置指南.md
git commit -m "test(tabjoin): E2E 配置→试算→保存 + 文档回写

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

自检声明示例：
> 后端 `*TabJoin*` + IT 全 PASS ✅；TS 0 错误 ✅；TabJoinFormulaDrawer.tsx → Vite 200 ✅；dry-run-tab-formula → 400(缺参正常) ✅；E2E tab-join-formula passed ✅

---

## Self-Review（计划对照 spec）

- **spec §4 数据模型** → Task 1-5 契约一致（final_expression/groups/tabs/joins/where/agg_expression）✅
- **spec §5 求值流程（两层作用域）** → Task 4（逐行/标量）+ Task 5（组→final）✅
- **spec §6 前端构建器** → Task 10-15（Drawer/GroupCard/JoinKeyModal/VariableMatrix/WherePanel/SampleCardPicker）✅
- **spec §7 试算端点** → Task 8 ✅
- **spec §8 错误处理** → SafeArithmetic(缺值0/除数1, Task1) + 运行态 try/catch yield null(Task6) + 配置期 validate(Task7) ✅
- **spec §9 测试** → 单测 Task1-5 + IT Task9 + E2E Task16 ✅
- **缺口提示**：`tab-defs` / `sample-cards` 两个只读 GET 端点在 spec 未显列，Task 11 Step1 已标注为前置依赖（缺则先补后端）。执行 Task 11 时优先确认/补这两个端点。
- **类型一致性**：`Join`/`Cond`/`Result` record 跨 Task 2/3/5 一致；前端 `Group` 结构跨 Task 11-15 一致（ref/main_tab/tabs/joins/where/agg_expression）✅
