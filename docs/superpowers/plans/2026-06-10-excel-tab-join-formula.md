# Excel 页签连表公式构建器 Implementation Plan（v2）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement task-by-task. Steps use checkbox (`- [ ]`).

**Goal:** Excel 模板视图列新增来源 `TAB_JOIN_FORMULA`：单卡片内多页签**按 rowKeyFields 行键自动对齐(全外连·缺补0)**，单表达式**按加减项分段、裸明细默认自动求和**、可显式聚合，算出一个单值写入 Excel 单元格；可视化构建器（全页签字段矩阵 + 置灰锁定）+ 样本卡片试算。

**Architecture:** 内存对齐 + 复用 JEXL/`SafeArithmetic`。后端 `TabJoinPlanEvaluator` v2 重写：`alignByRowKey`(替代 v1 INNER join) + `evalExpression`(加减项分段自动求和，复用 v1 聚合归约机制) + `evaluateColumn`(去 groups/where/final)。前端新 `TabJoinFormulaDrawer`（单表达式 + 字段矩阵 + 置灰）。

**Tech Stack:** Java17+Quarkus+JEXL3；JUnit5+QuarkusTest；React+AntD5；Playwright E2E。
**设计来源：** spec `docs/superpowers/specs/2026-06-10-excel-tab-join-formula-design.md`（v2）；原型 `docs/html/excel-tab-join-formula-builder-v2.html`。

---

## 已完成基线（v1，保留 / 复用）
- **T1 `SafeArithmetic`**（`b3d1c43`，缺值0/除数1）— ✅ 原样保留，v2 照用。
- v1 的 `TabJoinPlanEvaluator` 现有：`Join`/`buildWideRows`(INNER 笛卡尔)、`Cond`/`applyWhere`(WHERE)、`evalGroupExpression`(两层求值含 reduceAgg/matchParen/findAggCall/substituteRowTokens/numLit/toBig)、`evaluateColumn`(groups/final/组N)。
- **v2 处置**：删 `Join/buildWideRows/applyWhere/Cond`；改 `evalGroupExpression`→`evalExpression`；重写 `evaluateColumn`；**保留聚合归约机制**(reduceAgg/matchParen/findAggCall/substituteRowTokens/numLit/toBig/TOKEN/AGG_CALL/jexl 字段)。

## 列配置 JSON 契约（v2，前后端共用）
```json
{ "col_key":"L","source_type":"TAB_JOIN_FORMULA",
  "expression":"[投料.金额] * [加工.工时] + [回料(总计)]",
  "tabs":[ {"alias":"投料","tabKey":"<cid>:0","rowKeyFields":["物料编码"]},
           {"alias":"加工","tabKey":"<cid>:1","rowKeyFields":["物料编码"]},
           {"alias":"回料","tabKey":"<cid>:2","rowKeyFields":["物料编码","工序"]} ] }
```
令牌：明细 `[投料.金额]`；小计列总计 `[投料.金额(总计)]`；页签总计 `[投料(总计)]`。

---

## Phase 1 — 后端求值器 v2 重写（TDD）

### Task 2(v2): 删 v1 JOIN/WHERE + 新增 alignByRowKey 行键对齐

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluator.java`
- Delete: `cpq-backend/src/test/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluatorJoinTest.java`、`TabJoinPlanEvaluatorWhereTest.java`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluatorAlignTest.java`

- [ ] **Step 1: 删除 v1 测试 + v1 方法**
  - `git rm cpq-backend/src/test/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluatorJoinTest.java cpq-backend/src/test/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluatorWhereTest.java`
  - 从 `TabJoinPlanEvaluator.java` 删除：`record Join`、`buildWideRows`、`joinIn`、`keysEqual`、`record Cond`、`applyWhere`、`evalCond`。**保留** `prefix`、`str`（对齐会用）、以及 `evalGroupExpression` 及其私有辅助（Task 4 再改）、`evaluateColumn`（Task 5 再改）暂时保留可编译。
  - 注意 `evaluateColumn`/`evalOneGroup` 当前调用了 `buildWideRows`/`applyWhere`/`parseJoins`/`parseWhere` —— 为保持本步可编译，临时把 `evalOneGroup` 体改成 `return java.math.BigDecimal.ZERO;` 并删除 `parseJoins`/`parseWhere`（Task 5 整体重写）。本步只要**编译通过**即可，evaluateColumn 行为不保证（无测试依赖它到 Task 5）。

- [ ] **Step 2: 写失败测试 `TabJoinPlanEvaluatorAlignTest.java`**

```java
package com.cpq.quotation.service.tabjoin;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TabJoinPlanEvaluatorAlignTest {

    private final TabJoinPlanEvaluator ev = new TabJoinPlanEvaluator();
    private Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void single_tab_prefixed() {
        var tabs = Map.of("投料", List.of(row("物料编码","M1","金额",100)));
        var wide = ev.alignByRowKey(List.of("物料编码"), tabs);
        assertEquals(1, wide.size());
        assertEquals(100, wide.get(0).get("投料.金额"));
    }

    @Test
    void full_outer_union_missing_absent() {
        // 投料 M1,M2 ; 加工 M1,M3 ; 行键 物料编码 → 并集 M1,M2,M3 三行
        var tabs = new LinkedHashMap<String, List<Map<String,Object>>>();
        tabs.put("投料", List.of(row("物料编码","M1","金额",100), row("物料编码","M2","金额",60)));
        tabs.put("加工", List.of(row("物料编码","M1","工时",4), row("物料编码","M3","工时",5)));
        var wide = ev.alignByRowKey(List.of("物料编码"), tabs);
        assertEquals(3, wide.size(), "并集 M1/M2/M3");
        // 找到 M1 行：投料.金额=100 且 加工.工时=4
        var m1 = wide.stream().filter(r -> "M1".equals(str(r.get("投料.物料编码")))||"M1".equals(str(r.get("加工.物料编码")))).findFirst().orElseThrow();
        assertEquals(100, m1.get("投料.金额"));
        assertEquals(4, m1.get("加工.工时"));
        // M2 行：只有投料；加工字段缺失(key 不存在 → get 返 null)
        var m2 = wide.stream().filter(r -> "M2".equals(str(r.get("投料.物料编码")))).findFirst().orElseThrow();
        assertEquals(60, m2.get("投料.金额"));
        assertNull(m2.get("加工.工时"), "M2 加工缺失→字段不存在");
        // M3 行：只有加工
        var m3 = wide.stream().filter(r -> "M3".equals(str(r.get("加工.物料编码")))).findFirst().orElseThrow();
        assertNull(m3.get("投料.金额"));
        assertEquals(5, m3.get("加工.工时"));
    }

    @Test
    void composite_rowkey() {
        var tabs = new LinkedHashMap<String, List<Map<String,Object>>>();
        tabs.put("回料", List.of(row("物料编码","M1","工序","电镀","回料金额",30),
                                 row("物料编码","M1","工序","酸洗","回料金额",9)));
        var wide = ev.alignByRowKey(List.of("物料编码","工序"), tabs);
        assertEquals(2, wide.size(), "复合行键两行不合并");
    }

    private static String str(Object o){ return o==null?null:o.toString(); }
}
```

- [ ] **Step 3: 跑确认失败** `cd cpq-backend && ./mvnw -q test -Dtest=TabJoinPlanEvaluatorAlignTest` → 编译失败（alignByRowKey 不存在）。

- [ ] **Step 4: 实现 alignByRowKey**（加进 `TabJoinPlanEvaluator`，复用已留的 `prefix`/`str`）

```java
    /**
     * 行键对齐（全外连）：把同一行键类的若干页签按 rowKeyFields 值对齐。
     * 行键值的并集，每个行键组合出一行；某页签该行键缺行 → 其字段不并入(取值→null→0)。
     * 行键唯一 → 无笛卡尔放大。宽行键 = "别名.字段"。
     * @param tabRows 仅含"被表达式明细引用到"的页签 alias→rows
     */
    public List<Map<String, Object>> alignByRowKey(
            List<String> rowKeyFields, Map<String, List<Map<String, Object>>> tabRows) {
        // keyStr -> 合并行
        LinkedHashMap<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (var e : tabRows.entrySet()) {
            String alias = e.getKey();
            for (Map<String, Object> r : e.getValue()) {
                String keyStr = keyOf(rowKeyFields, r);
                Map<String, Object> merged = byKey.computeIfAbsent(keyStr, k -> new LinkedHashMap<>());
                for (var fe : r.entrySet()) merged.put(alias + "." + fe.getKey(), fe.getValue());
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private String keyOf(List<String> rowKeyFields, Map<String, Object> row) {
        StringBuilder sb = new StringBuilder();
        for (String k : rowKeyFields) sb.append(str(row.get(k))).append("");
        return sb.toString();
    }
```

- [ ] **Step 5: 跑确认通过** → `Tests run: 3, Failures: 0`。
- [ ] **Step 6: 提交**（add 修改的 evaluator + 新测试 + 删除的两个旧测试）
```bash
git add -u cpq-backend/src/test/java/com/cpq/quotation/service/tabjoin/
git add cpq-backend/src/main/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluator.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluatorAlignTest.java
git commit -m "refactor(tabjoin)!: v2 删INNER-JOIN/WHERE,改行键全外连对齐 alignByRowKey

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```
> 注意 `git add -u .../tabjoin/` 仅暂存该目录下已跟踪文件的改删（含 git rm 的两个旧测试），不会误带其它目录。**不要 git add -A、不要碰 RECORD.md。**

---

### Task 4(v2): evalExpression — 加减项分段 + 裸明细默认求和

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluator.java`
- Delete: `cpq-backend/src/test/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluatorExprTest.java`（v1 两层求值测试，语义已变）
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluatorEvalTest.java`

把 v1 `evalGroupExpression(aggExpr, rows, subtotals)` 改写为 `evalExpression(expression, alignedRows, scalars)`：顶层 `+ -` 拆项；含裸明细的项逐行求和，否则算一次；总计令牌用 scalars。复用 reduceAgg/matchParen/findAggCall/substituteRowTokens/numLit/toBig。

- [ ] **Step 1: 删 v1 Expr 测试** `git rm .../TabJoinPlanEvaluatorExprTest.java`

- [ ] **Step 2: 写失败测试 `TabJoinPlanEvaluatorEvalTest.java`**

```java
package com.cpq.quotation.service.tabjoin;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TabJoinPlanEvaluatorEvalTest {

    private final TabJoinPlanEvaluator ev = new TabJoinPlanEvaluator();
    private Map<String,Object> w(Object... kv){ Map<String,Object> m=new LinkedHashMap<>();
        for(int i=0;i<kv.length;i+=2) m.put((String)kv[i],kv[i+1]); return m; }
    private void bd(String e, BigDecimal a){ assertEquals(0,new BigDecimal(e).compareTo(a),"got "+a); }

    @Test void bare_detail_term_autosum() {
        var rows = List.of(w("投料.金额",100,"加工.工时",4), w("投料.金额",60,"加工.工时",0), w("投料.金额",0,"加工.工时",5));
        bd("400", ev.evalExpression("[投料.金额] * [加工.工时]", rows, Map.of())); // 100*4+60*0+0*5
    }
    @Test void explicit_avg_once() {
        var rows = List.of(w("投料.工时",4), w("投料.工时",5));
        bd("4.5", ev.evalExpression("AVG([投料.工时])", rows, Map.of()));
    }
    @Test void avg_times_bare_detail() {
        var rows = List.of(w("投料.工时",4,"投料.数量",2), w("投料.工时",5,"投料.数量",3));
        // AVG=4.5 ; Σ(4.5*数量)=4.5*(2+3)=22.5
        bd("22.5", ev.evalExpression("AVG([投料.工时]) * [投料.数量]", rows, Map.of()));
    }
    @Test void agg_plus_scalar_total_once() {
        var rows = List.of(w("投料.金额",100), w("投料.金额",60));
        var scalars = Map.of("回料(总计)", new BigDecimal("39"));
        bd("139", ev.evalExpression("MAX([投料.金额]) + [回料(总计)]", rows, scalars)); // 100+39
    }
    @Test void mixed_sum_term_plus_scalar() {
        var rows = List.of(w("投料.金额",100,"加工.工时",4), w("投料.金额",60,"加工.工时",0), w("投料.金额",0,"加工.工时",5));
        var scalars = Map.of("回料(总计)", new BigDecimal("39"));
        bd("439", ev.evalExpression("[投料.金额] * [加工.工时] + [回料(总计)]", rows, scalars)); // 400+39
    }
    @Test void column_total_token() {
        var scalars = Map.of("投料.金额(总计)", new BigDecimal("160"));
        bd("160", ev.evalExpression("[投料.金额(总计)]", List.of(), scalars));
    }
    @Test void missing_detail_zero_div_one() {
        var rows = List.of(w("a.x",10));
        bd("10", ev.evalExpression("SUM([a.x] / [a.y])", rows, Map.of())); // a.y 缺→0→除数按1→10
    }
}
```

- [ ] **Step 3: 跑确认失败** → 编译失败（evalExpression 不存在）。

- [ ] **Step 4: 实现 evalExpression（替换 v1 evalGroupExpression）**

删除 `public BigDecimal evalGroupExpression(...)` 与其私有 `substituteScalarTokens`（小计写法变了）。**保留** `replaceAggregates`/`findAggCall`/`matchParen`/`reduceAgg`/`substituteRowTokens`/`numLit`/`toBig`/常量/`jexl` 字段。新增：

```java
    private static final java.util.regex.Pattern AGG_MARK = AGG_CALL; // 复用聚合识别

    /**
     * v2 求值：按顶层 +/- 拆加减项；含"裸明细"(未被聚合函数圈住)的项→对齐行逐行算再求和；
     * 否则(明细全在聚合内/纯标量)→算一次。total 令牌(.总计结尾)从 scalars 取，detail 令牌逐行取。
     * @param alignedRows 行键对齐后的宽行
     * @param scalars     总计令牌(raw,如 "回料(总计)"/"投料.金额(总计)")→值
     */
    public java.math.BigDecimal evalExpression(
            String expression, List<Map<String, Object>> alignedRows,
            Map<String, java.math.BigDecimal> scalars) {
        if (expression == null || expression.isBlank()) return java.math.BigDecimal.ZERO;
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        for (Term t : splitTerms(expression)) {
            String aggResolved = replaceAggregatesS(t.text, alignedRows, scalars);
            java.math.BigDecimal termVal;
            if (hasBareDetail(t.text)) {
                java.math.BigDecimal s = java.math.BigDecimal.ZERO;
                for (Map<String, Object> r : alignedRows) s = s.add(evalRow(aggResolved, r, scalars));
                termVal = s;
            } else {
                termVal = evalRow(aggResolved, java.util.Map.of(), scalars);
            }
            total = t.sign >= 0 ? total.add(termVal) : total.subtract(termVal);
        }
        return total;
    }

    private record Term(int sign, String text) {}

    /** 顶层 +/- 拆项（尊重括号），首项 sign=+1。 */
    private List<Term> splitTerms(String expr) {
        List<Term> out = new ArrayList<>();
        int depth = 0, sign = 1; StringBuilder cur = new StringBuilder();
        for (int i = 0; i < expr.length(); i++) {
            char ch = expr.charAt(i);
            if (ch == '(') depth++; else if (ch == ')') depth--;
            if (depth == 0 && (ch == '+' || ch == '-') && cur.toString().trim().length() > 0) {
                out.add(new Term(sign, cur.toString())); cur.setLength(0); sign = ch == '+' ? 1 : -1;
            } else cur.append(ch);
        }
        if (cur.toString().trim().length() > 0) out.add(new Term(sign, cur.toString()));
        return out;
    }

    /** 项内去掉聚合函数后仍有 detail 令牌(非 .总计 结尾)？ */
    private boolean hasBareDetail(String term) {
        String stripped = blankOutAggregates(term);
        java.util.regex.Matcher m = TOKEN.matcher(stripped);
        while (m.find()) { if (!m.group(1).trim().endsWith("(总计)")) return true; }
        return false;
    }
    /** 把 FN(...) 整体换成 "0"（用于判断裸明细），不递归。 */
    private String blankOutAggregates(String expr) {
        StringBuilder out = new StringBuilder(); int i = 0;
        while (i < expr.length()) {
            int fnStart = findAggCall(expr, i);
            if (fnStart < 0) { out.append(expr.substring(i)); break; }
            int open = expr.indexOf('(', fnStart), close = matchParen(expr, open);
            out.append(expr, i, fnStart).append('0'); i = close + 1;
        }
        return out.toString();
    }

    /** 同 v1 replaceAggregates，但内层逐行求值用 evalRow(带 scalars)。 */
    private String replaceAggregatesS(String expr, List<Map<String, Object>> rows,
                                      Map<String, java.math.BigDecimal> scalars) {
        StringBuilder out = new StringBuilder(); int i = 0;
        while (i < expr.length()) {
            int fnStart = findAggCall(expr, i);
            if (fnStart < 0) { out.append(expr.substring(i)); break; }
            int open = expr.indexOf('(', fnStart);
            String fn = expr.substring(fnStart, open).trim().toUpperCase();
            int close = matchParen(expr, open);
            String inner = expr.substring(open + 1, close);
            out.append(expr, i, fnStart);
            out.append(reduceAggS(fn, inner, rows, scalars).toPlainString());
            i = close + 1;
        }
        return out.toString();
    }
    private java.math.BigDecimal reduceAggS(String fn, String inner, List<Map<String, Object>> rows,
                                            Map<String, java.math.BigDecimal> scalars) {
        List<java.math.BigDecimal> vals = new ArrayList<>();
        for (Map<String, Object> r : rows) vals.add(evalRow(inner, r, scalars));
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

    /** 单行求值：detail 令牌→该行值(缺0)；total 令牌(.总计结尾)→scalars(缺0)；JEXL(SafeArithmetic)。 */
    private java.math.BigDecimal evalRow(String expr, Map<String, Object> row,
                                         Map<String, java.math.BigDecimal> scalars) {
        java.util.regex.Matcher m = TOKEN.matcher(expr);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String tok = m.group(1).trim(); String lit;
            if (tok.endsWith("(总计)")) {
                java.math.BigDecimal s = scalars.get(tok); lit = s != null ? s.toPlainString() : "0";
            } else lit = numLit(row.get(tok));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(lit));
        }
        m.appendTail(sb);
        Object r = jexl.createExpression(sb.toString()).evaluate(new org.apache.commons.jexl3.MapContext());
        return toBig(r);
    }
```

> 若 v1 仍有 `substituteRowTokens` 未被引用导致告警，可保留或删除（不影响）。`reduceAgg`(v1) 若不再被调用可删，但本任务用 `reduceAggS`/`evalRow` 取代。确保编译。

- [ ] **Step 5: 跑确认通过** `./mvnw -q test -Dtest=TabJoinPlanEvaluatorEvalTest` → 7 tests PASS。
- [ ] **Step 6: 提交**
```bash
git add -u cpq-backend/src/test/java/com/cpq/quotation/service/tabjoin/
git add cpq-backend/src/main/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluator.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluatorEvalTest.java
git commit -m "feat(tabjoin): v2 evalExpression 加减项分段+裸明细自动求和

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5(v2): evaluateColumn — 解析tabs→行键对齐→单表达式求值

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluator.java`
- Delete: `cpq-backend/src/test/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluatorColumnTest.java`（v1 groups 版）
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluatorColumnV2Test.java`

- [ ] **Step 1: 删 v1 Column 测试** `git rm .../TabJoinPlanEvaluatorColumnTest.java`

- [ ] **Step 2: 写失败测试 `TabJoinPlanEvaluatorColumnV2Test.java`**

```java
package com.cpq.quotation.service.tabjoin;

import com.cpq.quotation.service.card.CardDataProvider;
import com.cpq.quotation.service.card.CardEffectiveRows;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TabJoinPlanEvaluatorColumnV2Test {

    private final TabJoinPlanEvaluator ev = new TabJoinPlanEvaluator();

    private CardDataProvider provider() {
        Map<String, CardEffectiveRows.TabRows> eff = new LinkedHashMap<>();
        // 投料 行键 物料编码: M1金额100, M2金额60 ; 列小计 金额=160
        eff.put("T投:0", new CardEffectiveRows.TabRows(
            List.of(Map.of("物料编码","M1","金额",new BigDecimal("100")),
                    Map.of("物料编码","M2","金额",new BigDecimal("60"))),
            new BigDecimal("160"), Map.of("金额", new BigDecimal("160"))));
        // 加工 行键 物料编码: M1工时4, M3工时5
        eff.put("T加:1", new CardEffectiveRows.TabRows(
            List.of(Map.of("物料编码","M1","工时",new BigDecimal("4")),
                    Map.of("物料编码","M3","工时",new BigDecimal("5"))), null));
        // 回料 行键 物料编码+工序: 回料金额 30+9, 页签小计 39
        eff.put("T回:2", new CardEffectiveRows.TabRows(
            List.of(Map.of("物料编码","M1","工序","电镀","回料金额",new BigDecimal("30")),
                    Map.of("物料编码","M1","工序","酸洗","回料金额",new BigDecimal("9"))),
            new BigDecimal("39")));
        return CardDataProvider.fromEffectiveRows(eff);
    }

    private Map<String,Object> col(String expr) {
        Map<String,Object> c = new LinkedHashMap<>();
        c.put("expression", expr);
        c.put("tabs", List.of(
            Map.of("alias","投料","tabKey","T投:0","rowKeyFields",List.of("物料编码")),
            Map.of("alias","加工","tabKey","T加:1","rowKeyFields",List.of("物料编码")),
            Map.of("alias","回料","tabKey","T回:2","rowKeyFields",List.of("物料编码","工序"))));
        return c;
    }

    @Test void detail_term_plus_tab_total() {
        // Σ(投料.金额×加工.工时) over M1,M2,M3 = 100*4+60*0+0*5=400 ; +回料(总计)39 = 439
        BigDecimal v = ev.evaluateColumn(col("[投料.金额] * [加工.工时] + [回料(总计)]"), provider());
        assertEquals(0, new BigDecimal("439").compareTo(v), "got "+v);
    }
    @Test void column_total() {
        BigDecimal v = ev.evaluateColumn(col("[投料.金额(总计)]"), provider());
        assertEquals(0, new BigDecimal("160").compareTo(v));
    }
}
```

- [ ] **Step 3: 跑确认失败** → 编译失败（evaluateColumn 签名变化）。

- [ ] **Step 4: 重写 evaluateColumn（删 v1 的 groups/evalOneGroup/evalFinal/GROUP_REF）**

```java
    /**
     * v2 整列求值：解析 expression 引用的页签 → 取明细页签行按 rowKeyFields 全外连对齐 →
     * 收集总计令牌的标量 → evalExpression。返回单值。
     */
    @SuppressWarnings("unchecked")
    public java.math.BigDecimal evaluateColumn(Map<String, Object> col,
                                               com.cpq.quotation.service.card.CardDataProvider provider) {
        String expr = (String) col.getOrDefault("expression", "");
        if (expr.isBlank()) return java.math.BigDecimal.ZERO;
        List<Map<String, Object>> tabs = (List<Map<String, Object>>) col.getOrDefault("tabs", List.of());
        // alias → {tabKey, rowKeyFields}
        Map<String, String> tabKeyOf = new LinkedHashMap<>();
        Map<String, List<String>> rkfOf = new LinkedHashMap<>();
        for (Map<String, Object> t : tabs) {
            tabKeyOf.put((String) t.get("alias"), (String) t.get("tabKey"));
            rkfOf.put((String) t.get("alias"), (List<String>) t.getOrDefault("rowKeyFields", List.of()));
        }
        // 解析令牌
        java.util.regex.Matcher m = TOKEN.matcher(expr);
        java.util.LinkedHashSet<String> detailAliases = new java.util.LinkedHashSet<>();
        Map<String, java.math.BigDecimal> scalars = new LinkedHashMap<>();
        while (m.find()) {
            String tok = m.group(1).trim();
            if (tok.endsWith("(总计)")) {
                String body = tok.substring(0, tok.length() - "(总计)".length());
                if (body.contains(".")) { // 列总计
                    String alias = body.substring(0, body.indexOf('.'));
                    String colName = body.substring(body.indexOf('.') + 1);
                    java.math.BigDecimal s = provider.subtotalOfColumn(tabKeyOf.get(alias), colName);
                    scalars.put(tok, s != null ? s : java.math.BigDecimal.ZERO);
                } else { // 页签总计
                    java.math.BigDecimal s = provider.subtotalOf(tabKeyOf.get(body));
                    scalars.put(tok, s != null ? s : java.math.BigDecimal.ZERO);
                }
            } else {
                detailAliases.add(tok.contains(".") ? tok.substring(0, tok.indexOf('.')) : tok);
            }
        }
        // 明细页签按行键对齐（同类，取第一个明细页签的 rowKeyFields）
        List<Map<String, Object>> aligned = List.of();
        if (!detailAliases.isEmpty()) {
            String first = detailAliases.iterator().next();
            List<String> rkf = rkfOf.getOrDefault(first, List.of());
            Map<String, List<Map<String, Object>>> tabRows = new LinkedHashMap<>();
            for (String alias : detailAliases) tabRows.put(alias, provider.rowsOf(tabKeyOf.get(alias)));
            aligned = alignByRowKey(rkf, tabRows);
        }
        return evalExpression(expr, aligned, scalars);
    }
```

删除残留的 v1 `evalOneGroup`/`parseJoins`/`parseWhere`/`evalFinal`/`record Result`/`GROUP_REF`（若 Task 2 已临时清掉部分，这里彻底删干净）。

- [ ] **Step 5: 跑确认通过 + 同包回归** `./mvnw -q test -Dtest='*TabJoin*,SafeArithmeticTest'` → 全 PASS（Align3 + Eval7 + ColumnV2 2 + SafeArithmetic4）。
- [ ] **Step 6: 提交**
```bash
git add -u cpq-backend/src/test/java/com/cpq/quotation/service/tabjoin/
git add cpq-backend/src/main/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluator.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/tabjoin/TabJoinPlanEvaluatorColumnV2Test.java
git commit -m "feat(tabjoin): v2 evaluateColumn 行键对齐+单表达式(去groups/where/final)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Phase 2 — 接入 ExcelViewService + 试算端点

### Task 6: buildRowData 接入 TAB_JOIN_FORMULA（v2）
**Files:** Modify `cpq-backend/.../quotation/service/ExcelViewService.java`

- [ ] **Step 1: 注入** 在字段区加 `@Inject com.cpq.quotation.service.tabjoin.TabJoinPlanEvaluator tabJoinPlanEvaluator;`
- [ ] **Step 2: provider 构造**（buildRowData 五参重载内，CARD_FORMULA 块后）：
```java
        com.cpq.quotation.service.card.CardDataProvider tabJoinProvider =
            (effectiveRows != null)
                ? com.cpq.quotation.service.card.CardDataProvider.fromEffectiveRows(effectiveRows)
                : new com.cpq.quotation.service.card.CardDataProvider(componentDataList);
```
- [ ] **Step 3: switch 加 case**（CARD_FORMULA 后）：
```java
                case "TAB_JOIN_FORMULA" -> {
                    try { yield tabJoinPlanEvaluator.evaluateColumn(col, tabJoinProvider); }
                    catch (Exception e) {
                        LOG.warnf("[ExcelView] TAB_JOIN_FORMULA col '%s' eval failed: %s", colKey, e.getMessage());
                        yield null;
                    }
                }
```
- [ ] **Step 4: 自检** `./mvnw -q compile` 成功；`touch` ExcelViewService.java 重启后 `curl .../q/health` 200。
- [ ] **Step 5: 提交** 只 add ExcelViewService.java。

### Task 7: saveExcelViewConfig 校验（v2）
**Files:** Modify `ExcelViewService.java`；Test `cpq-backend/.../tabjoin/TabJoinConfigValidationTest.java`

校验：`TAB_JOIN_FORMULA` 列 `expression` 非空；表达式 `[别名...]` 引用的 alias 必须在 `tabs` 声明；裸明细引用的页签 rowKeyFields 必须同一类（不同→报错）。
- [ ] **Step 1: 写失败测试**（断言 static `validateTabJoinConfig` 抛错：未声明 alias / 明细跨行键类）
```java
package com.cpq.quotation.service.tabjoin;
import com.cpq.common.exception.BusinessException;
import com.cpq.quotation.service.ExcelViewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TabJoinConfigValidationTest {
    private List<Map<String,Object>> parse(String j) throws Exception {
        return new ObjectMapper().readValue(j, new TypeReference<>(){});
    }
    @Test void undeclared_alias_rejected() throws Exception {
        var cols = parse("""
          [{"col_key":"A","source_type":"TAB_JOIN_FORMULA","expression":"[未知.金额]","tabs":[]}]""");
        var ex = assertThrows(BusinessException.class, () -> ExcelViewService.validateTabJoinConfig(cols));
        assertTrue(ex.getMessage().contains("未知"));
    }
    @Test void detail_cross_rowkey_class_rejected() throws Exception {
        var cols = parse("""
          [{"col_key":"A","source_type":"TAB_JOIN_FORMULA","expression":"[投料.金额]+[回料.回料金额]",
            "tabs":[{"alias":"投料","tabKey":"x:0","rowKeyFields":["物料编码"]},
                    {"alias":"回料","tabKey":"y:1","rowKeyFields":["物料编码","工序"]}]}]""");
        assertThrows(BusinessException.class, () -> ExcelViewService.validateTabJoinConfig(cols));
    }
    @Test void valid_passes() throws Exception {
        var cols = parse("""
          [{"col_key":"A","source_type":"TAB_JOIN_FORMULA","expression":"[投料.金额]+[回料(总计)]",
            "tabs":[{"alias":"投料","tabKey":"x:0","rowKeyFields":["物料编码"]},
                    {"alias":"回料","tabKey":"y:1","rowKeyFields":["物料编码","工序"]}]}]""");
        assertDoesNotThrow(() -> ExcelViewService.validateTabJoinConfig(cols));
    }
}
```
- [ ] **Step 2: 跑确认失败**
- [ ] **Step 3: 实现 static validateTabJoinConfig + saveExcelViewConfig 调用**
```java
    @SuppressWarnings("unchecked")
    public static void validateTabJoinConfig(List<Map<String, Object>> columns) {
        java.util.regex.Pattern TOK = java.util.regex.Pattern.compile("\\[([^\\[\\]]+)]");
        for (Map<String, Object> col : columns) {
            if (!"TAB_JOIN_FORMULA".equals(col.get("source_type"))) continue;
            String expr = (String) col.getOrDefault("expression", "");
            if (expr.isBlank()) throw new BusinessException(400, "页签连表公式列 " + col.get("col_key") + " 表达式不能为空");
            List<Map<String, Object>> tabs = (List<Map<String, Object>>) col.getOrDefault("tabs", List.of());
            Map<String, List<String>> rkfOf = new HashMap<>();
            for (Map<String, Object> t : tabs) rkfOf.put((String) t.get("alias"),
                (List<String>) t.getOrDefault("rowKeyFields", List.of()));
            String detailClass = null;
            java.util.regex.Matcher m = TOK.matcher(expr);
            while (m.find()) {
                String tok = m.group(1).trim();
                String alias = tok.endsWith("(总计)")
                    ? null
                    : (tok.contains(".") ? tok.substring(0, tok.indexOf('.')) : tok);
                // 校验声明
                String declAlias = tok.endsWith("(总计)")
                    ? aliasOfTotal(tok) : alias;
                if (declAlias != null && !rkfOf.containsKey(declAlias))
                    throw new BusinessException(400, "页签连表公式列 " + col.get("col_key")
                        + " 引用了未声明的页签: " + declAlias);
                // 裸明细跨行键类校验
                if (alias != null) {
                    String sig = String.join("+", rkfOf.getOrDefault(alias, List.of()));
                    if (detailClass == null) detailClass = sig;
                    else if (!detailClass.equals(sig))
                        throw new BusinessException(400, "页签连表公式列 " + col.get("col_key")
                            + " 的明细字段跨了不同行键类(只能同行键页签的明细一起运算): " + detailClass + " ≠ " + sig);
                }
            }
        }
    }
    private static String aliasOfTotal(String tok) {
        String body = tok.substring(0, tok.length() - "(总计)".length());
        return body.contains(".") ? body.substring(0, body.indexOf('.')) : body;
    }
```
在 `saveExcelViewConfig` 的 EXCEL_FORMULA 校验后加 `validateTabJoinConfig(columns);`
- [ ] **Step 4: 跑确认通过**（3 tests）
- [ ] **Step 5: 提交**

### Task 8: 试算端点 + tab-defs / sample-cards 只读端点
**Files:** Modify `ExcelViewService.java`、`TemplateExcelViewResource.java`；可能新增取数 helper

- [ ] **Step 1: ExcelViewService.dryRunTabFormula**
```java
    public Map<String, Object> dryRunTabFormula(UUID lineItemId, Map<String, Object> column, String cardValuesJson) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            QuotationLineItem li = QuotationLineItem.findById(lineItemId);
            if (li == null) { out.put("errors", List.of("样本卡片不存在")); out.put("value", null); return out; }
            Map<String, com.cpq.quotation.service.card.CardEffectiveRows.TabRows> eff =
                parseEffectiveRows(cardValuesJson, li.templateId);
            com.cpq.quotation.service.card.CardDataProvider provider = (eff != null)
                ? com.cpq.quotation.service.card.CardDataProvider.fromEffectiveRows(eff)
                : new com.cpq.quotation.service.card.CardDataProvider(
                    QuotationLineComponentData.list("lineItemId = ?1 ORDER BY sortOrder ASC", lineItemId));
            java.math.BigDecimal v = tabJoinPlanEvaluator.evaluateColumn(column, provider);
            out.put("value", v); out.put("errors", List.of());
        } catch (Exception e) {
            out.put("value", null); out.put("errors", List.of(e.getMessage() == null ? "求值异常" : e.getMessage()));
        }
        return out;
    }
```
- [ ] **Step 2: TemplateExcelViewResource 三个端点**
  - `POST /dry-run-tab-formula` body `{lineItemId, column, cardValuesJson?}` → `excelViewService.dryRunTabFormula(...)`
  - `GET /tab-defs` → 返回 `[{alias, tabKey, rowKeyFields:[...], detailFields:[...], subtotalCols:[...]}]`，由 `template.componentsSnapshot` 解析（componentId:sortOrder → alias=组件名/tab_name；rowKeyFields 从组件 rowKeyFields；detailFields=字段名列表；subtotalCols=is_subtotal 列）。实现一个 `excelViewService.tabDefsOfTemplate(templateId)`。
  - `GET /sample-cards` → 查引用该 templateId 的 quotation_line_item（join quotation 取单号 + 卡片名），返回 `[{quotationId, quotationNo, lineItemId, cardName}]`。实现 `excelViewService.sampleCardsOfTemplate(templateId)`。
  > tab-defs/sample-cards 的取数细节执行时按 `Template.componentsSnapshot` 实际结构 + `QuotationLineItem`/`Quotation` 字段补全；若 componentsSnapshot 无 rowKeyFields，需从 component 配置补查。**执行 Task 8 时先读 `Template` 实体 + componentsSnapshot 样例确定字段名再写。**
- [ ] **Step 3: 编译+重启+端点自检**（dry-run 缺参→400；tab-defs/sample-cards 用真实 templateId→200 或空数组）
- [ ] **Step 4: 提交**

### Task 9: 端到端集成测试（v2）
**Files:** Test `cpq-backend/.../card/ExcelViewTabJoinFormulaIT.java`

仿 `ExcelViewCardFormulaIT` 造数（customer/user/quotation/template/line_item/component_data）。两个 component_data（投料 sort0 行键物料编码；加工 sort1 行键物料编码），excel_view_config 配 `TAB_JOIN_FORMULA` 列 `[投料.金额]*[加工.工时]`，断言 `buildLineRowData` 出对齐求和单值。
- [ ] **Step 1: 写测试**（参 v1 IT 模板；component rowKeyFields 通过 componentsSnapshot 或 alias 直配；明细对齐口径与单测一致）
- [ ] **Step 2: 跑通过**
- [ ] **Step 3: 提交** + 全量 `-Dtest='*TabJoin*,ExcelViewTabJoinFormulaIT'` 回归

---

## Phase 3 — 前端构建器 v2（E2E 验收，对照 v2 原型）

> 前端无单测，自检 = `npx tsc --noEmit` 0 错 + 改动 `.tsx` `curl :5174/src/<path>` 200。逐组件照 `docs/html/excel-tab-join-formula-builder-v2.html`。

### Task 10: 列来源类型 + Drawer 入口
**Files:** Modify `cpq-frontend/src/pages/template/ExcelViewConfigTab.tsx`
- [ ] SourceType 加 `'TAB_JOIN_FORMULA'`；`ExcelViewColumn` 加 `expression?:string; tabs?:any[]`。
- [ ] 列来源下拉加 `<Select.Option value="TAB_JOIN_FORMULA">页签连表公式</Select.Option>`；该列显示"配置公式"按钮 → `setTabJoinColIdx(idx)`。
- [ ] 渲染 `<TabJoinFormulaDrawer open templateId column={columns[i]} onClose onSave={patch=>updateColumn(i,patch)} />`（Task 11 建组件）。
- [ ] 自检 + 提交。

### Task 11: TabJoinFormulaDrawer 骨架 + service
**Files:** Create `cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx`、`cpq-frontend/src/services/tabJoinFormulaService.ts`
- [ ] service：`tabs(templateId)`→GET `/templates/{id}/excel-view-config/tab-defs`（返回 `{alias,tabKey,rowKeyFields,detailFields,subtotalCols}[]`）；`sampleCards(templateId)`→GET `/sample-cards`；`dryRun(templateId,lineItemId,column)`→POST `/dry-run-tab-formula`。
- [ ] Drawer：state `expression`、`tabs(已引用)`、`tabDefs`；**单表达式 TextArea** + 运算符工具条 + 函数工具条(SUM/AVG/MIN/MAX/COUNT，插 `FN()` 光标落括号内)；保存时从 expression 解析引用页签 alias → 组装 `tabs`(alias/tabKey/rowKeyFields 取自 tabDefs) → `onSave({source_type, expression, tabs})`。
- [ ] 求值规则提示块（照原型文案）。
- [ ] 自检 + 提交。
> 完整骨架代码照 v2 原型 `<textarea>` + 工具条 + `ins/insFn` 逻辑改写为受控 React state（`expression` 受控，插入用 `selectionStart` 拼接）。

### Task 12: TabFieldMatrix + 置灰锁定 + 字段插入
**Files:** Create `cpq-frontend/src/pages/template/tabjoin/TabFieldMatrix.tsx`；Modify Drawer
- [ ] 矩阵：每行一页签（左 alias + 行键徽标），右三组 chip：明细 / 小计列总计(`字段(总计)`) / 页签总计(`alias(总计)`)。
- [ ] **置灰**：从当前 expression 解析"明细令牌"得锁定行键类签名(`rowKeyFields.join('+')`，取第一个明细页签)；其它行键类页签明细 chip `disabled`(hover tooltip 说明)，总计 chip 始终可点；无明细令牌→全解锁。expression 变更实时重算。
- [ ] 点 chip 按三类插入对应令牌(`[a.f]` / `[a.f(总计)]` / `[a(总计)]`)到光标处。
- [ ] 锁定状态条 + 清空表达式按钮。
- [ ] 自检（`curl :5174/src/pages/template/tabjoin/TabFieldMatrix.tsx` 200）+ 提交。
> 解析/置灰逻辑直接移植 v2 原型 `parseTokens`/`activeRowKeySig`/`renderMatrix` 到 React。

### Task 13: 样本卡片选择 + 试算
**Files:** Create `cpq-frontend/src/pages/template/tabjoin/SampleCardPicker.tsx`；Modify Drawer
- [ ] SampleCardPicker：`sampleCards(templateId)` 下拉选 lineItem。
- [ ] 试算条：选样本 + 试算按钮 → `dryRun(templateId,lineItemId,{source_type,expression,tabs})` → 显示 `value`（errors 用 message.warning）。
- [ ] 自检 + 提交。

---

## Phase 4 — 验收

### Task 16: E2E + 全量自检 + 文档回写
**Files:** Create `cpq-frontend/e2e/tab-join-formula.spec.ts`；Modify `docs/RECORD.md`、`docs/Excel模板配置指南.md`
- [ ] E2E：进 DRAFT 模板 Excel 配置 → 列来源选页签连表公式 → 打开 Drawer → 点字段拼 `[投料.金额]*[加工.工时]` → 验置灰(回料明细灰) → 选样本 → 试算出值 → 保存。真实选择器，不留 `expect(true)`。
- [ ] 后端全量回归 `./mvnw -q test -Dtest='*TabJoin*,SafeArithmeticTest,ExcelViewTabJoinFormulaIT,TabJoinConfigValidationTest'` 全 PASS。
- [ ] 前端 `npx tsc --noEmit` 0 错 + `npx playwright test e2e/tab-join-formula.spec.ts` passed。
- [ ] RECORD.md 追加；`docs/Excel模板配置指南.md` 加"页签连表公式列(v2)"节（契约+行键对齐+加减项规则+示例表）。
- [ ] 提交 + 自检声明。

---

## Self-Review（对照 v2 spec）
- §4 数据模型(expression+tabs+rowKeyFields) → Task 5/10/11 契约一致 ✅
- §5 行键对齐 → Task 2 ✅；加减项分段自动求和 → Task 4（示例表逐条测）✅
- §6 前端(单表达式+矩阵+置灰) → Task 10-13 ✅
- §7 试算 → Task 8/13 ✅
- §8 错误(SafeArithmetic+校验+运行态兜底) → T1 保留 + Task 7 + Task 6 ✅
- §9 测试 → Task 2/4/5 单测 + Task 9 IT + Task 16 E2E ✅
- **依赖前置**：tab-defs/sample-cards 端点在 Task 8 实现，Task 11 前端依赖它们——执行顺序 Task 8 先于 Task 11。
- **v1 清理**：Task 2/4/5 各自 `git rm` 对应 v1 测试 + 删 v1 方法；完成后 evaluator 无 Join/Cond/buildWideRows/applyWhere/groups 残留。
