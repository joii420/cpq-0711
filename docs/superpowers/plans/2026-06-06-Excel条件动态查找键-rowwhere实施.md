# Excel 卡片公式 WHERE 动态查找键（第一期·ROW_WHERE）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Excel `CARD_FORMULA` 列的「字段·按条件取行 (ROW_WHERE)」条件右侧"值"能引用本产品行可见的键（字面量 / 本行产品字段 / 本行其它 CARD_FORMULA 列），实现动态 VLOOKUP。

**Architecture:** 在 `CardRef` 增结构化 `condRows`（后端优先用之，旧 `cond` 字符串走兼容路径）。后端 `CardFormulaEvaluator` 求 ROW_WHERE 时，按当前产品行上下文（`productRow` = `componentRowData` + `partNo`）+ 已算列（`cached`）把 `condRows` 解析成带标量字面量的 JEXL 谓词，复用 `firstMatchIndex` 扫行，不造新引擎。列拓扑排序把 `rhs.type=column` 也算作列依赖边（保证求值顺序 + 环检测）。前端 `CardFormulaDrawer` 条件构建器每行加"值来源选择器"（字面量/产品字段/本行列），生成 `condRows`；编辑旧 `cond` 时反解析回填。

**Tech Stack:** Java 17 + Quarkus（JUnit5 / `@QuarkusTest`）、JEXL3（既有 `evalRowExpression`）、React + Ant Design + TypeScript、Vitest。

**Scope（已与用户确认）：**
- 本计划**只做 ROW_WHERE 动态 RHS**。**聚合 WHERE 动态 RHS 不在本计划内**（谓词内嵌公式文本，机制更绕，另起小计划；数据模型 `condRows` 复用，不返工）。
- RHS = `literal | product(组件字段 + 料号 __partNo__) | column(本行其它 CARD_FORMULA 列)`，**单值引用**，不支持 RHS 写四则/函数。
- 「产品字段」候选 = 模板各页签 `fields` 并集 + `料号(__partNo__)`，**纯前端拼**，不新增后端"候选字段"接口；不含 lineItem 组件外的产品属性。
- 旧 ref 编辑：**反解析** `cond` JEXL → `condRows`（rhs=literal）回填；后端旧 `cond` 路径保持兼容。
- WHERE 引用列的依赖**纳入拓扑图 + 环检测**（后端权威）。

**关键不变量（来自 spec §4 / §10）：**
- RHS 仅能引用"求值时已就绪"的来源——**产品字段（`productRow` / `partNo`）+ 已算 CARD_FORMULA 列（`cached`）**。VARIABLE / 普通 FORMULA 列不可作 RHS（它们在 CARD_FORMULA 批量之后才算）。
- 中文标识符：`condRows.left` / `rhs(product)` 字段是中文，走 `cols` 别名 + `productRow` Map 直取，**不直接当 JEXL 标识符**（[[cpq-chinese-identifiers-need-ascii-alias]]）。
- RHS 解析为空 / 字段不存在 → 该条件按"取不到键"处理（永假 `1==2` → 不匹配 → ROW_WHERE 返回 `—`），**不抛异常**。

---

## 文件结构

**后端（改）**
- `cpq-backend/src/main/java/com/cpq/quotation/service/card/CardRef.java` — 加 `condRows` + 嵌套类型 `CondRow`/`Rhs`/`RhsType` + `fromMap` 解析 + `hasCondRows()`。
- `cpq-backend/src/main/java/com/cpq/quotation/service/CardFormulaEvaluator.java` — `evaluateColumns` 加 `productRow` 入参（新 6 参重载）；`resolveCardScalars`/`pickFieldValue` 线接 `productRow`+`cached`+`partNo`；新增 `buildDynamicCond`/`resolveRhs`/`toJexlLiteral`/`opToJexl`；`topoOrder` 加 refs 重载收集 `rhs.type=column` 依赖边 + `condRowColumnDeps`。
- `cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java` — 两处 `evaluateColumns` 调用传 `componentRowData` 作 `productRow`。

**后端（测）**
- `cpq-backend/src/test/java/com/cpq/quotation/card/CardRefTest.java` — 加 `condRows` 解析断言。
- `cpq-backend/src/test/java/com/cpq/quotation/card/CardFormulaTopoTest.java` — 加 "WHERE 引用列纳入拓扑/环检测"。
- `cpq-backend/src/test/java/com/cpq/quotation/card/CardRowWhereDynamicTest.java`（新建）— `@QuarkusTest`：product / column / literal 回归 / 多条件 AND·OR / RHS 取空 → `—`。

**前端（改）**
- `cpq-frontend/src/pages/template/cardFormula.ts` — 加 `CondRowSpec`/`CondRhsType` 类型 + `CardRefSpec.condRows` + 纯函数 `buildCondRows` / `parseCondToRows`。
- `cpq-frontend/src/pages/template/CardFormulaDrawer.tsx` — `CondRow` 加 `rhsType`；条件行加"值来源选择器" + 产品字段/本行列下拉；新 prop `colSourceTypes`；`buildInsertResult` 生成 `condRows`；ref 标签点击 → 回填编辑（反解析旧 `cond`）。
- `cpq-frontend/src/pages/template/ExcelViewConfigTab.tsx` — 传 `colSourceTypes` prop。

**前端（测）**
- `cpq-frontend/src/pages/template/cardFormula.test.ts` — 加 `buildCondRows` / `parseCondToRows` 断言。

---

## Task 1: 后端数据模型 — `CardRef.condRows`

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/card/CardRef.java`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/card/CardRefTest.java`

- [ ] **Step 1: 写失败测试** — 在 `CardRefTest.java` 末尾 `}` 前加两个方法：

```java
    @Test void parses_condRows_with_product_rhs() {
        CardRef r = CardRef.fromMap(java.util.Map.of(
            "tab", "TC1", "field", "加工费", "mode", "ROW_WHERE",
            "cols", java.util.Map.of("c0", "关联号"),
            "condRows", java.util.List.of(java.util.Map.of(
                "left", "关联号", "op", "eq", "logic", "and",
                "rhs", java.util.Map.of("type", "product", "value", "__partNo__")))));
        assertTrue(r.hasCondRows());
        assertEquals(1, r.condRows.size());
        CardRef.CondRow cr = r.condRows.get(0);
        assertEquals("关联号", cr.left);
        assertEquals("eq", cr.op);
        assertEquals(CardRef.RhsType.PRODUCT, cr.rhs.type);
        assertEquals("__partNo__", cr.rhs.value);
    }

    @Test void legacy_ref_without_condRows_hasCondRows_false() {
        CardRef r = CardRef.fromMap(java.util.Map.of(
            "tab", "TC1", "field", "加工费", "mode", "ROW_WHERE", "cond", "c0=='电镀'",
            "cols", java.util.Map.of("c0", "工序")));
        assertFalse(r.hasCondRows());
        assertEquals("c0=='电镀'", r.cond);
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -q -Dtest=CardRefTest test`
Expected: 编译失败 / `cannot find symbol: hasCondRows / condRows / CondRow / RhsType`。

- [ ] **Step 3: 改 `CardRef.java`** — 整文件替换为：

```java
package com.cpq.quotation.service.card;

import java.util.*;

/** Excel 列公式里的卡片引用：指向某页签实例的小计 / 字段(首行或按条件取行) / 或作为聚合源(无 field)。 */
public final class CardRef {
    public enum Mode { FIRST_ROW, ROW_WHERE }
    public enum RhsType { LITERAL, PRODUCT, COLUMN }
    public static final String SUBTOTAL = "__subtotal__";

    /** ROW_WHERE 动态条件右侧"值"引用。 */
    public static final class Rhs {
        public final RhsType type;
        public final String value; // literal:原值 / product:字段名(或 __partNo__) / column:col_key
        public Rhs(RhsType type, String value) { this.type = type; this.value = value; }
    }

    /** 一条结构化条件：left(中文字段) op rhs，logic 与下一条连接。 */
    public static final class CondRow {
        public final String left;
        public final String op;     // eq|ne|gt|gte|lt|lte|in
        public final String logic;  // and|or（末条不用）
        public final Rhs rhs;
        public CondRow(String left, String op, String logic, Rhs rhs) {
            this.left = left; this.op = op; this.logic = logic; this.rhs = rhs;
        }
    }

    public final String tab;      // 页签实例标识（compId:sortOrder）
    public final String field;    // 中文字段名 / __subtotal__ / null(聚合源)
    public final Mode mode;
    public final String cond;     // 旧式 ROW_WHERE 行筛选条件（用别名）；condRows 非空时忽略
    public final Map<String, String> cols; // 别名→中文字段名
    public final List<CondRow> condRows;   // 结构化动态条件（优先于 cond）；空 = 走旧 cond 路径

    private CardRef(String tab, String field, Mode mode, String cond,
                    Map<String, String> cols, List<CondRow> condRows) {
        this.tab = tab; this.field = field; this.mode = mode; this.cond = cond;
        this.cols = cols != null ? cols : Map.of();
        this.condRows = condRows != null ? condRows : List.of();
    }

    public boolean isSubtotal() { return SUBTOTAL.equals(field); }
    public boolean isAggregateSource() { return field == null || field.isBlank(); }
    public boolean hasCondRows() { return condRows != null && !condRows.isEmpty(); }

    @SuppressWarnings("unchecked")
    public static CardRef fromMap(Map<String, Object> m) {
        if (m == null) return null;
        String tab = str(m.get("tab"));
        String field = str(m.get("field"));
        String modeStr = str(m.get("mode"));
        Mode mode = "ROW_WHERE".equalsIgnoreCase(modeStr) ? Mode.ROW_WHERE : Mode.FIRST_ROW;
        String cond = str(m.get("cond"));
        Map<String, String> cols = new HashMap<>();
        Object colsObj = m.get("cols");
        if (colsObj instanceof Map<?, ?> cm)
            for (Map.Entry<?, ?> e : cm.entrySet()) cols.put(e.getKey().toString(), e.getValue().toString());
        return new CardRef(tab, field, mode, cond, cols, parseCondRows(m.get("condRows")));
    }

    private static List<CondRow> parseCondRows(Object o) {
        List<CondRow> out = new ArrayList<>();
        if (!(o instanceof List<?> list)) return out;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> rm)) continue;
            String left = str(rm.get("left"));
            String op = str(rm.get("op"));
            String logic = str(rm.get("logic"));
            out.add(new CondRow(left,
                    op == null || op.isBlank() ? "eq" : op,
                    logic == null || logic.isBlank() ? "and" : logic,
                    parseRhs(rm.get("rhs"))));
        }
        return out;
    }

    private static Rhs parseRhs(Object o) {
        if (!(o instanceof Map<?, ?> rm)) return new Rhs(RhsType.LITERAL, "");
        String t = str(rm.get("type"));
        RhsType type = switch (t == null ? "literal" : t.toLowerCase()) {
            case "product" -> RhsType.PRODUCT;
            case "column" -> RhsType.COLUMN;
            default -> RhsType.LITERAL;
        };
        return new Rhs(type, str(rm.get("value")));
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -q -Dtest=CardRefTest test`
Expected: `BUILD SUCCESS`，全部 CardRefTest 方法 PASS（旧 4 个 + 新 2 个）。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/card/CardRef.java \
        cpq-backend/src/test/java/com/cpq/quotation/card/CardRefTest.java
git commit -m "feat(card): CardRef 增结构化 condRows + RhsType 解析(动态查找键数据模型)"
```

---

## Task 2: 后端拓扑 — `rhs.type=column` 纳入列依赖图

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/CardFormulaEvaluator.java:29-66`（`columnDeps` / `topoOrder` 区段）
- Test: `cpq-backend/src/test/java/com/cpq/quotation/card/CardFormulaTopoTest.java`

- [ ] **Step 1: 写失败测试** — 在 `CardFormulaTopoTest.java` 末尾 `}` 前加：

```java
    @Test void column_rhs_in_where_is_a_column_dep() {
        // B 的 WHERE 条件引用列 A（公式文本里没有 [A]）→ A 必须排在 B 前
        Map<String,String> f = new LinkedHashMap<>();
        f.put("B", "=[投料.关联(条件)]");
        f.put("A", "=[投料.小计]");
        Map<String, Map<String,Object>> refs = new LinkedHashMap<>();
        refs.put("B", Map.of("投料.关联(条件)", Map.of(
            "tab", "t:0", "field", "关联", "mode", "ROW_WHERE",
            "cols", Map.of("c0", "子件号"),
            "condRows", List.of(Map.of("left", "子件号", "op", "eq", "logic", "and",
                "rhs", Map.of("type", "column", "value", "A"))))));
        refs.put("A", Map.of());
        List<String> order = CardFormulaEvaluator.topoOrder(f, refs);
        assertTrue(order.indexOf("A") < order.indexOf("B"));
    }

    @Test void column_rhs_cycle_is_detected() {
        Map<String,String> f = new LinkedHashMap<>();
        f.put("A", "=[投料.字段(条件)]");
        f.put("B", "=[加工.字段(条件)]");
        Map<String, Map<String,Object>> refs = new LinkedHashMap<>();
        refs.put("A", Map.of("投料.字段(条件)", Map.of("tab","t:0","field","x","mode","ROW_WHERE",
            "cols", Map.of("c0","k"),
            "condRows", List.of(Map.of("left","k","op","eq","logic","and",
                "rhs", Map.of("type","column","value","B"))))));
        refs.put("B", Map.of("加工.字段(条件)", Map.of("tab","t:1","field","y","mode","ROW_WHERE",
            "cols", Map.of("c0","k"),
            "condRows", List.of(Map.of("left","k","op","eq","logic","and",
                "rhs", Map.of("type","column","value","A"))))));
        assertThrows(BusinessException.class, () -> CardFormulaEvaluator.topoOrder(f, refs));
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -q -Dtest=CardFormulaTopoTest test`
Expected: 编译失败 `method topoOrder(Map<String,String>, Map<...>) not found`。

- [ ] **Step 3: 改 `CardFormulaEvaluator.java`** — 用下面整段替换 `columnDeps`(29-38) 到 `topoOrder`(41-66) 之间的内容（即把 `columnDeps` 方法保留、把单参 `topoOrder` 改为委托新双参重载，并新增 `condRowColumnDeps`）：

```java
    /** 提取公式里"裸 col_key"（不含 . 的占位）作为列间依赖。 */
    static Set<String> columnDeps(String formula, Set<String> allCols) {
        Set<String> deps = new LinkedHashSet<>();
        if (formula == null) return deps;
        Matcher m = BRACKET.matcher(formula);
        while (m.find()) {
            String ref = m.group(1).trim();
            if (!ref.contains(".") && allCols.contains(ref)) deps.add(ref);
        }
        return deps;
    }

    /** 决策A：从某列 refs.condRows 收集 rhs.type=column 的列依赖边（WHERE 里引用的列）。 */
    static Set<String> condRowColumnDeps(Map<String, Object> refs, Set<String> allCols) {
        Set<String> out = new LinkedHashSet<>();
        if (refs == null) return out;
        for (Object refObj : refs.values()) {
            CardRef r = CardRef.fromMap(asRefMap(refObj));
            if (r == null || !r.hasCondRows()) continue;
            for (CardRef.CondRow cr : r.condRows) {
                if (cr.rhs != null && cr.rhs.type == CardRef.RhsType.COLUMN
                        && cr.rhs.value != null && allCols.contains(cr.rhs.value)) {
                    out.add(cr.rhs.value);
                }
            }
        }
        return out;
    }

    /** 兼容旧签名（仅按公式文本建依赖）。 */
    public static List<String> topoOrder(Map<String, String> formulas) {
        return topoOrder(formulas, Map.of());
    }

    /** Kahn 拓扑排序；存在环抛 BusinessException。依赖边 = 公式 [col] + condRows 的 rhs(column)。 */
    public static List<String> topoOrder(Map<String, String> formulas,
                                         Map<String, Map<String, Object>> refsByCol) {
        Set<String> cols = formulas.keySet();
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        Map<String, Integer> indeg = new LinkedHashMap<>();
        for (String c : cols) {
            Set<String> d = columnDeps(formulas.get(c), cols);
            d.addAll(condRowColumnDeps(refsByCol == null ? null : refsByCol.get(c), cols));
            deps.put(c, d);
        }
        for (String c : cols) indeg.put(c, deps.get(c).size()); // 入度 = 本列依赖数
        Deque<String> q = new ArrayDeque<>();
        for (String c : cols) if (indeg.get(c) == 0) q.add(c);
        List<String> order = new ArrayList<>();
        while (!q.isEmpty()) {
            String c = q.poll();
            order.add(c);
            for (String other : cols) {
                if (deps.get(other).contains(c)) {
                    indeg.put(other, indeg.get(other) - 1);
                    if (indeg.get(other) == 0) q.add(other);
                }
            }
        }
        if (order.size() != cols.size()) {
            Set<String> cyc = new LinkedHashSet<>(cols);
            cyc.removeAll(order);
            throw new BusinessException(400, "Excel 列公式存在循环引用: " + cyc);
        }
        return order;
    }
```

> 注：`asRefMap` 已是本类静态私有方法（line ~135），`condRowColumnDeps` 可直接调用。

- [ ] **Step 4: 改 `evaluateColumnsInternal` 调用拓扑处** — 把 `CardFormulaEvaluator.java` 内 `List<String> order = topoOrder(formulaByCol);`（line ~96）改为：

```java
        List<String> order = topoOrder(formulaByCol, refsByCol);
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -q -Dtest=CardFormulaTopoTest test`
Expected: `BUILD SUCCESS`，旧 3 个 + 新 2 个全 PASS。

- [ ] **Step 6: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/CardFormulaEvaluator.java \
        cpq-backend/src/test/java/com/cpq/quotation/card/CardFormulaTopoTest.java
git commit -m "feat(card): 列拓扑/环检测纳入 condRows 的 rhs(column) 依赖边(决策A)"
```

---

## Task 3: 后端求值 — ROW_WHERE 动态 RHS 解析

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/CardFormulaEvaluator.java`（`evaluateColumns` 重载 / `evaluateColumnsInternal` / `resolveCardScalars` / `pickFieldValue` + 新增辅助方法）
- Test: `cpq-backend/src/test/java/com/cpq/quotation/card/CardRowWhereDynamicTest.java`（新建）

- [ ] **Step 1: 写失败测试** — 新建 `CardRowWhereDynamicTest.java`：

```java
package com.cpq.quotation.card;

import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.service.CardFormulaEvaluator;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CardRowWhereDynamicTest {
    @Inject CardFormulaEvaluator evaluator;

    private QuotationLineComponentData tab(String comp, String rowDataJson) {
        var d = new QuotationLineComponentData();
        d.componentId = UUID.fromString(comp); d.sortOrder = 0; d.subtotal = BigDecimal.ZERO;
        d.rowData = rowDataJson;
        return d;
    }

    /** RHS=product(__partNo__)：取关联号==本行料号的那行加工费。 */
    @Test void dynamic_rhs_product_partNo() {
        var comp = "55555555-5555-5555-5555-555555555555";
        var d = tab(comp, "[{\"关联号\":\"P1\",\"加工费\":2},{\"关联号\":\"P9\",\"加工费\":9}]");
        var refs = Map.<String,Object>of("加工.加工费(条件)", Map.of(
            "tab", comp+":0", "field", "加工费", "mode", "ROW_WHERE",
            "cols", Map.of("c0", "关联号"),
            "condRows", List.of(Map.of("left","关联号","op","eq","logic","and",
                "rhs", Map.of("type","product","value","__partNo__")))));
        var col = new LinkedHashMap<String,Object>();
        col.put("col_key","A"); col.put("source_type","CARD_FORMULA");
        col.put("formula","=[加工.加工费(条件)]"); col.put("refs", refs);
        var out = evaluator.evaluateColumns(List.of(col), List.of(d), null, "P9", null, Map.of());
        assertEquals(0, new BigDecimal("9").compareTo(new BigDecimal(out.get("A").toString())));
    }

    /** RHS=product(本行产品字段)：关联号==本行 productRow 的"母件号"。 */
    @Test void dynamic_rhs_product_field() {
        var comp = "55555555-5555-5555-5555-555555555555";
        var d = tab(comp, "[{\"关联号\":\"M1\",\"加工费\":3},{\"关联号\":\"M2\",\"加工费\":7}]");
        var refs = Map.<String,Object>of("加工.加工费(条件)", Map.of(
            "tab", comp+":0", "field", "加工费", "mode", "ROW_WHERE",
            "cols", Map.of("c0", "关联号"),
            "condRows", List.of(Map.of("left","关联号","op","eq","logic","and",
                "rhs", Map.of("type","product","value","母件号")))));
        var col = new LinkedHashMap<String,Object>();
        col.put("col_key","A"); col.put("source_type","CARD_FORMULA");
        col.put("formula","=[加工.加工费(条件)]"); col.put("refs", refs);
        Map<String,Object> productRow = Map.of("母件号", "M2");
        var out = evaluator.evaluateColumns(List.of(col), List.of(d), null, "PX", null, productRow);
        assertEquals(0, new BigDecimal("7").compareTo(new BigDecimal(out.get("A").toString())));
    }

    /** RHS=column(本行其它 CARD_FORMULA 列)：A 先算出键，B 的 WHERE 用 [A]。 */
    @Test void dynamic_rhs_column() {
        var comp = "55555555-5555-5555-5555-555555555555";
        var d = tab(comp, "[{\"关联号\":\"K1\",\"加工费\":4},{\"关联号\":\"K2\",\"加工费\":8}]");
        var refsB = Map.<String,Object>of("加工.加工费(条件)", Map.of(
            "tab", comp+":0", "field", "加工费", "mode", "ROW_WHERE",
            "cols", Map.of("c0", "关联号"),
            "condRows", List.of(Map.of("left","关联号","op","eq","logic","and",
                "rhs", Map.of("type","column","value","A")))));
        var colA = new LinkedHashMap<String,Object>();
        colA.put("col_key","A"); colA.put("source_type","CARD_FORMULA");
        colA.put("formula","='K2'"); colA.put("refs", Map.of());
        var colB = new LinkedHashMap<String,Object>();
        colB.put("col_key","B"); colB.put("source_type","CARD_FORMULA");
        colB.put("formula","=[加工.加工费(条件)]"); colB.put("refs", refsB);
        var out = evaluator.evaluateColumns(List.of(colB, colA), List.of(d), null, "PX", null, Map.of());
        assertEquals(0, new BigDecimal("8").compareTo(new BigDecimal(out.get("B").toString())));
    }

    /** 多条件 AND：关联号==本行料号 且 类型=='电镀'。 */
    @Test void dynamic_rhs_multi_and() {
        var comp = "55555555-5555-5555-5555-555555555555";
        var d = tab(comp, "[{\"关联号\":\"P1\",\"类型\":\"酸洗\",\"加工费\":2}," +
                          "{\"关联号\":\"P1\",\"类型\":\"电镀\",\"加工费\":9}]");
        var refs = Map.<String,Object>of("加工.加工费(条件)", Map.of(
            "tab", comp+":0", "field", "加工费", "mode", "ROW_WHERE",
            "cols", Map.of("c0", "关联号", "c1", "类型"),
            "condRows", List.of(
                Map.of("left","关联号","op","eq","logic","and",
                    "rhs", Map.of("type","product","value","__partNo__")),
                Map.of("left","类型","op","eq","logic","and",
                    "rhs", Map.of("type","literal","value","电镀")))));
        var col = new LinkedHashMap<String,Object>();
        col.put("col_key","A"); col.put("source_type","CARD_FORMULA");
        col.put("formula","=[加工.加工费(条件)]"); col.put("refs", refs);
        var out = evaluator.evaluateColumns(List.of(col), List.of(d), null, "P1", null, Map.of());
        assertEquals(0, new BigDecimal("9").compareTo(new BigDecimal(out.get("A").toString())));
    }

    /** RHS 取空（产品字段不存在）→ 不匹配 → DASH。 */
    @Test void dynamic_rhs_missing_key_returns_dash() {
        var comp = "55555555-5555-5555-5555-555555555555";
        var d = tab(comp, "[{\"关联号\":\"P1\",\"加工费\":2}]");
        var refs = Map.<String,Object>of("加工.加工费(条件)", Map.of(
            "tab", comp+":0", "field", "加工费", "mode", "ROW_WHERE",
            "cols", Map.of("c0", "关联号"),
            "condRows", List.of(Map.of("left","关联号","op","eq","logic","and",
                "rhs", Map.of("type","product","value","不存在字段")))));
        var col = new LinkedHashMap<String,Object>();
        col.put("col_key","A"); col.put("source_type","CARD_FORMULA");
        col.put("formula","=[加工.加工费(条件)]"); col.put("refs", refs);
        var out = evaluator.evaluateColumns(List.of(col), List.of(d), null, "P1", null, Map.of());
        assertEquals(CardFormulaEvaluator.DASH, out.get("A"));
    }

    /** 旧 cond 字面量条件无 condRows → 兼容路径仍工作。 */
    @Test void legacy_cond_still_works() {
        var comp = "55555555-5555-5555-5555-555555555555";
        var d = tab(comp, "[{\"工序\":\"酸洗\",\"加工费\":2},{\"工序\":\"电镀\",\"加工费\":9}]");
        var refs = Map.<String,Object>of("加工.加工费(工序=电镀)", Map.of(
            "tab", comp+":0", "field", "加工费", "mode", "ROW_WHERE",
            "cond", "c0=='电镀'", "cols", Map.of("c0","工序")));
        var col = new LinkedHashMap<String,Object>();
        col.put("col_key","A"); col.put("source_type","CARD_FORMULA");
        col.put("formula","=[加工.加工费(工序=电镀)]"); col.put("refs", refs);
        var out = evaluator.evaluateColumns(List.of(col), List.of(d), null, "P1", null, Map.of());
        assertEquals(0, new BigDecimal("9").compareTo(new BigDecimal(out.get("A").toString())));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -q -Dtest=CardRowWhereDynamicTest test`
Expected: 编译失败 `method evaluateColumns(... , Map) not found`。

- [ ] **Step 3: 加 6 参 `evaluateColumns` 重载 + 线接 `productRow`** — 改 `CardFormulaEvaluator.java`：

3a. 把现有两个 5 参 `evaluateColumns`（line 69-82）替换为「5 参委托 + 6 参实体」：

```java
    /** 旧 5 参：productRow 缺省为空 Map（动态 RHS 取不到 product 键 → 不匹配）。 */
    public Map<String, Object> evaluateColumns(
            List<Map<String, Object>> columns,
            List<QuotationLineComponentData> tabs,
            UUID customerId, String partNo, UUID quotationId) {
        return evaluateColumns(columns, tabs, customerId, partNo, quotationId, Map.of());
    }

    public Map<String, Object> evaluateColumns(
            List<Map<String, Object>> columns,
            CardDataProvider provider,
            UUID customerId, String partNo, UUID quotationId) {
        return evaluateColumns(columns, provider, customerId, partNo, quotationId, Map.of());
    }

    /** 6 参：带本产品行 productRow（= componentRowData），供 ROW_WHERE 动态 RHS=product 解析。 */
    public Map<String, Object> evaluateColumns(
            List<Map<String, Object>> columns,
            List<QuotationLineComponentData> tabs,
            UUID customerId, String partNo, UUID quotationId, Map<String, Object> productRow) {
        return evaluateColumnsInternal(columns, new CardDataProvider(tabs), customerId, partNo, quotationId, productRow);
    }

    public Map<String, Object> evaluateColumns(
            List<Map<String, Object>> columns,
            CardDataProvider provider,
            UUID customerId, String partNo, UUID quotationId, Map<String, Object> productRow) {
        return evaluateColumnsInternal(columns, provider, customerId, partNo, quotationId, productRow);
    }
```

3b. `evaluateColumnsInternal` 签名加 `productRow`（line 84-86）：

```java
    private Map<String, Object> evaluateColumnsInternal(
            List<Map<String, Object>> columns, CardDataProvider provider,
            UUID customerId, String partNo, UUID quotationId, Map<String, Object> productRow) {
```

3c. `evaluateColumnsInternal` 里调 `resolveCardScalars` 处（line 107）传 `productRow` + `cached` + `partNo`：

```java
            String resolved = resolveCardScalars(formula, refs, provider, anyNonEmpty, anyRef, aggBindings,
                    productRow, cached, partNo);
```

- [ ] **Step 4: `resolveCardScalars` + `pickFieldValue` 线接 + 新增辅助方法** — 改 `CardFormulaEvaluator.java`：

4a. `resolveCardScalars` 签名（line 148-151）加三参，并把内部 `pickFieldValue(provider, ref)` 调用（line 177）改为带新参：

```java
    private String resolveCardScalars(String formula, Map<String, Object> refs,
            CardDataProvider provider,
            boolean[] anyNonEmpty, boolean[] anyRef,
            Map<String, CardAggregateSource.Binding> aggBindings,
            Map<String, Object> productRow, Map<String, Object> cached, String partNo) {
```

（line 177 那行）：

```java
                Object v = pickFieldValue(provider, ref, productRow, cached, partNo);
```

4b. 整体替换 `pickFieldValue`（line 187-203）并在其后新增辅助方法：

```java
    private Object pickFieldValue(CardDataProvider provider, CardRef ref,
                                  Map<String, Object> productRow, Map<String, Object> cached, String partNo) {
        List<Map<String, Object>> rows = provider.rowsOf(ref.tab);
        if (rows.isEmpty()) return null;
        if (ref.mode == CardRef.Mode.ROW_WHERE) {
            String cond = ref.hasCondRows()
                    ? buildDynamicCond(ref, productRow, cached, partNo)   // 动态 RHS
                    : ref.cond;                                           // 旧式字面量条件
            if (cond != null && !cond.isBlank()) {
                // 用 ref.cols 把每行重映射成别名行供条件求值
                List<Map<String, Object>> aliased = new ArrayList<>(rows.size());
                for (var row : rows) {
                    Map<String, Object> a = new HashMap<>();
                    for (var ce : ref.cols.entrySet()) a.put(ce.getKey(), row.get(ce.getValue()));
                    aliased.add(a);
                }
                int idx = templateFormulaService.firstMatchIndex(aliased, cond);
                return idx < 0 ? null : rows.get(idx).get(ref.field); // 原始行的中文字段值
            }
        }
        return rows.get(0).get(ref.field); // FIRST_ROW（或 ROW_WHERE 无条件）
    }

    /**
     * 把 ref.condRows 在本产品行上下文解析成带标量字面量的 JEXL 谓词（左值用别名）。
     * RHS 取空 → 该条件用永假 1==2（取不到键 → 不匹配），AND/OR 语义自然成立。
     */
    private String buildDynamicCond(CardRef ref, Map<String, Object> productRow,
                                    Map<String, Object> cached, String partNo) {
        // 反查别名：字段 → 别名（cols 是 别名→字段）
        Map<String, String> fieldToAlias = new HashMap<>();
        for (var e : ref.cols.entrySet()) fieldToAlias.put(e.getValue(), e.getKey());
        StringBuilder sb = new StringBuilder();
        List<CardRef.CondRow> rows = ref.condRows;
        for (int i = 0; i < rows.size(); i++) {
            CardRef.CondRow c = rows.get(i);
            String alias = fieldToAlias.getOrDefault(c.left, c.left);
            Object scalar = resolveRhs(c.rhs, productRow, cached, partNo);
            String expr;
            if (scalar == null || scalar.toString().isEmpty()) {
                expr = "1==2"; // 取不到键 → 不匹配
            } else if ("in".equalsIgnoreCase(c.op) && c.rhs.type == CardRef.RhsType.LITERAL) {
                // IN 仅字面量场景（逗号分隔 → (alias=='v1' || alias=='v2')）
                List<String> vals = new ArrayList<>();
                for (String v : scalar.toString().split(",")) { v = v.trim(); if (!v.isEmpty()) vals.add(v); }
                if (vals.isEmpty()) { expr = "1==2"; }
                else {
                    StringBuilder in = new StringBuilder("(");
                    for (int k = 0; k < vals.size(); k++) {
                        if (k > 0) in.append(" || ");
                        in.append(alias).append("=='").append(vals.get(k)).append("'");
                    }
                    expr = in.append(")").toString();
                }
            } else {
                // 标量比较（动态 product/column 或字面量非 IN）；IN+非字面量退化为 eq
                String op = "in".equalsIgnoreCase(c.op) ? "==" : opToJexl(c.op);
                expr = alias + op + toJexlLiteral(scalar);
            }
            sb.append(expr);
            if (i < rows.size() - 1) sb.append("or".equalsIgnoreCase(c.logic) ? " || " : " && ");
        }
        return sb.toString();
    }

    /** 解析 RHS 为标量；只允许 productRow / partNo / 已算列（cached）。 */
    private Object resolveRhs(CardRef.Rhs rhs, Map<String, Object> productRow,
                             Map<String, Object> cached, String partNo) {
        if (rhs == null) return null;
        return switch (rhs.type) {
            case LITERAL -> rhs.value;
            case PRODUCT -> "__partNo__".equals(rhs.value) ? partNo
                            : (productRow == null ? null : productRow.get(rhs.value));
            case COLUMN -> cached == null ? null : cached.get(rhs.value);
        };
    }

    /** 标量 → JEXL 字面量：可解析为数字则裸写，否则单引号字符串（沿用 buildCondJexl 口径）。 */
    private static String toJexlLiteral(Object v) {
        String s = v.toString();
        try { new BigDecimal(s); return s; }
        catch (Exception e) { return "'" + s.replace("'", "") + "'"; }
    }

    private static String opToJexl(String op) {
        return switch (op == null ? "eq" : op) {
            case "ne" -> "!="; case "gt" -> ">"; case "gte" -> ">=";
            case "lt" -> "<"; case "lte" -> "<="; default -> "==";
        };
    }
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -q -Dtest=CardRowWhereDynamicTest,CardRowWhereTest test`
Expected: `BUILD SUCCESS`，`CardRowWhereDynamicTest` 7 方法 + `CardRowWhereTest` 1 方法全 PASS。

- [ ] **Step 6: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/CardFormulaEvaluator.java \
        cpq-backend/src/test/java/com/cpq/quotation/card/CardRowWhereDynamicTest.java
git commit -m "feat(card): ROW_WHERE 动态 RHS(product/column/literal) 后端求值 + 空键DASH兜底"
```

---

## Task 4: 后端接线 — `ExcelViewService` 传 `componentRowData` 作 `productRow`

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java:321-331`

- [ ] **Step 1: 改两处 `evaluateColumns` 调用** — 把 `buildRowData` 内 CARD_FORMULA 求值块（line 321-331）替换为：

```java
        if (!cardCols.isEmpty()) {
            if (effectiveRows != null) {
                com.cpq.quotation.service.card.CardDataProvider provider =
                    com.cpq.quotation.service.card.CardDataProvider.fromEffectiveRows(effectiveRows);
                cardFormulaValues = cardFormulaEvaluator.evaluateColumns(
                    cardCols, provider, customerId, partNo, null, componentRowData);
            } else {
                cardFormulaValues = cardFormulaEvaluator.evaluateColumns(
                    cardCols, componentDataList, customerId, partNo, null, componentRowData);
            }
        }
```

> `componentRowData`（line 303-309 已构造的"各组件首行拍平 flat map"）就是 spec §2 的 `productRow`，键为中文字段名，与 `condRows.rhs(product).value` 对齐。`partNo`（line 312）已就绪传第 4 参。

- [ ] **Step 2: 触发 Quarkus 重启 + 编译验证**

Run:
```bash
cd cpq-backend && ./mvnw -q -Dtest=CardRowWhereDynamicTest,CardRefTest,CardFormulaTopoTest,CardRowWhereTest,CardFormulaEvaluatorTest,CardFormulaEvaluatorEffectiveRowsTest test
```
Expected: `BUILD SUCCESS`；既有 IT/UT 不回归（`ExcelViewCardFormulaIT` 走 `effectiveRows` 路径，行为不变）。

- [ ] **Step 3: 接口活性自检** — 确保后端能起：

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health
```
Expected: `200`（dev 已在跑）；若未跑则 `./mvnw quarkus:dev` 起后再验。

- [ ] **Step 4: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java
git commit -m "feat(card): ExcelView 把 componentRowData 作 productRow 传入卡片公式求值(动态VLOOKUP接线)"
```

---

## Task 5: 前端纯逻辑 — `cardFormula.ts` 类型 + `buildCondRows` / `parseCondToRows`

**Files:**
- Modify: `cpq-frontend/src/pages/template/cardFormula.ts`
- Test: `cpq-frontend/src/pages/template/cardFormula.test.ts`

- [ ] **Step 1: 写失败测试** — 在 `cardFormula.test.ts` 的 `describe` 块内末尾加：

```ts
  it('buildCondRows 过滤空字段 + 透传 rhs', () => {
    const rows = buildCondRows([
      { field: '关联号', op: 'eq', value: '__partNo__', logic: 'and', rhsType: 'product' },
      { field: '', op: 'eq', value: 'x', logic: 'and', rhsType: 'literal' },
      { field: '类型', op: 'eq', value: '电镀', logic: 'and', rhsType: 'literal' },
    ]);
    expect(rows).toEqual([
      { left: '关联号', op: 'eq', logic: 'and', rhs: { type: 'product', value: '__partNo__' } },
      { left: '类型', op: 'eq', logic: 'and', rhs: { type: 'literal', value: '电镀' } },
    ]);
  });

  it('parseCondToRows 反解析字面量 cond（含 && 与 IN）', () => {
    const cols = { c0: '工序', c1: '数量' };
    const rows = parseCondToRows("(c0=='镀铜' || c0=='镀镍') && c1>0", cols);
    expect(rows).toEqual([
      { left: '工序', op: 'in', logic: 'and', rhs: { type: 'literal', value: '镀铜,镀镍' } },
      { left: '数量', op: 'gt', logic: 'and', rhs: { type: 'literal', value: '0' } },
    ]);
  });

  it('parseCondToRows 空串 → []', () => {
    expect(parseCondToRows('', {})).toEqual([]);
  });
```

并把顶部 import 改为：

```ts
import { genAlias, expandIn, extractColKeyDeps, validateCardFormula, buildCondRows, parseCondToRows } from './cardFormula';
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/template/cardFormula.test.ts`
Expected: FAIL — `buildCondRows is not a function` / `parseCondToRows is not a function`。

- [ ] **Step 3: 改 `cardFormula.ts`** — 把第 1 行 `CardRefSpec` 定义替换，并在文件末尾追加新类型与函数：

3a. 替换第 1 行：

```ts
export type CondRhsType = 'literal' | 'product' | 'column';
export type CondOp = 'eq' | 'ne' | 'gt' | 'gte' | 'lt' | 'lte' | 'in';

export interface CondRowSpec {
  left: string;
  op: CondOp;
  logic: 'and' | 'or';
  rhs: { type: CondRhsType; value: string };
}

export interface CardRefSpec {
  tab: string;
  field?: string;
  mode?: 'FIRST_ROW' | 'ROW_WHERE';
  cond?: string;
  cols?: Record<string, string>;
  condRows?: CondRowSpec[];
}
```

3b. 文件末尾追加：

```ts
const JEXL_TO_OP: Record<string, CondOp> = {
  '==': 'eq', '!=': 'ne', '>=': 'gte', '<=': 'lte', '>': 'gt', '<': 'lt',
};

/** 把条件构建器行（含 rhsType）转成结构化 condRows，过滤空字段行。 */
export function buildCondRows(
  conds: { field: string; op: CondOp; value: string; logic: 'and' | 'or'; rhsType: CondRhsType }[],
): CondRowSpec[] {
  return conds
    .filter(c => c.field)
    .map(c => ({ left: c.field, op: c.op, logic: c.logic, rhs: { type: c.rhsType, value: c.value } }));
}

/**
 * 反解析旧式字面量 cond（由 buildCondJexl 生成）→ condRows（rhs 全为 literal）。
 * 支持：`alias op literal` 用 ` && ` / ` || ` 连接；IN 形如 `(alias=='v1' || alias=='v2')`。
 * cols：别名→中文字段名。解析失败的段跳过；空串 → []。
 */
export function parseCondToRows(cond: string, cols: Record<string, string>): CondRowSpec[] {
  const s = (cond || '').trim();
  if (!s) return [];
  // 1. 按顶层 && / || 切段，记录每段后面的连接符
  const segs: { text: string; logicAfter: 'and' | 'or' }[] = [];
  let depth = 0, buf = '';
  for (let i = 0; i < s.length; i++) {
    const ch = s[i];
    if (ch === '(' || ch === '[') depth++;
    else if (ch === ')' || ch === ']') depth--;
    if (depth === 0 && (s.startsWith(' && ', i) || s.startsWith(' || ', i))) {
      segs.push({ text: buf.trim(), logicAfter: s.startsWith(' && ', i) ? 'and' : 'or' });
      buf = ''; i += 3; continue;
    }
    buf += ch;
  }
  if (buf.trim()) segs.push({ text: buf.trim(), logicAfter: 'and' });

  const out: CondRowSpec[] = [];
  for (const seg of segs) {
    const t = seg.text.trim();
    const inMatch = t.startsWith('(') && t.endsWith(')');
    if (inMatch) {
      // IN 组：(alias=='v1' || alias=='v2')
      const inner = t.slice(1, -1);
      const parts = inner.split('||').map(p => p.trim());
      const eqRe = /^([A-Za-z_]\w*)\s*==\s*'(.*)'$/;
      let alias = ''; const vals: string[] = [];
      let ok = true;
      for (const p of parts) {
        const mm = p.match(eqRe);
        if (!mm) { ok = false; break; }
        alias = mm[1]; vals.push(mm[2]);
      }
      if (ok && alias) {
        out.push({ left: cols[alias] ?? alias, op: 'in', logic: seg.logicAfter,
                   rhs: { type: 'literal', value: vals.join(',') } });
        continue;
      }
    }
    // 标量段：alias op literal
    const m = t.match(/^([A-Za-z_]\w*)\s*(==|!=|>=|<=|>|<)\s*(.+)$/);
    if (!m) continue;
    const alias = m[1]; const op = JEXL_TO_OP[m[2]] ?? 'eq';
    let lit = m[3].trim();
    if (lit.startsWith("'") && lit.endsWith("'")) lit = lit.slice(1, -1);
    out.push({ left: cols[alias] ?? alias, op, logic: seg.logicAfter,
               rhs: { type: 'literal', value: lit } });
  }
  // 末段 logicAfter 无意义，但保持 'and' 即可（构建时末条不用 logic）
  return out;
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/template/cardFormula.test.ts`
Expected: PASS — 旧 6 用例 + 新 3 用例全绿。

- [ ] **Step 5: 提交**

```bash
git add cpq-frontend/src/pages/template/cardFormula.ts cpq-frontend/src/pages/template/cardFormula.test.ts
git commit -m "feat(card-fe): cardFormula 增 condRows 类型 + buildCondRows/parseCondToRows 纯逻辑"
```

---

## Task 6: 前端 UI — `CardFormulaDrawer` 条件行 RHS 来源选择器 + 生成 condRows

**Files:**
- Modify: `cpq-frontend/src/pages/template/CardFormulaDrawer.tsx`

- [ ] **Step 1: 扩 `CondRow` 类型 + 默认值** — 改 line 65-70 与 line 249：

line 65-70：

```tsx
interface CondRow {
  field: string;
  op: CondOperator;
  value: string;      // 文本，IN 时是逗号分隔；product/column 时是字段名/列号
  logic: CondLogic;   // 与下一行的连接符（最后一行不用）
  rhsType: CondRhsType; // 值来源：字面量 / 本行产品字段 / 本行卡片公式列
}
```

line 249：

```tsx
const DEFAULT_COND_ROW: CondRow = { field: '', op: 'eq', value: '', logic: 'and', rhsType: 'literal' };
```

- [ ] **Step 2: 引入类型 + 扩 props** — 改 line 22-24 的 import，并改 `CardFormulaDrawerProps`（line 39-52）：

import（line 22）：

```tsx
import { genAlias, expandIn, validateCardFormula, buildCondRows, parseCondToRows } from './cardFormula';
import type { CardRefSpec, CondRhsType, CondRowSpec } from './cardFormula';
```

`CardFormulaDrawerProps` 加一个可选 prop（在 `dryRunQuotationId?` 后）：

```tsx
  dryRunQuotationId?: string;
  /** col_key → source_type，用于「本行卡片公式列」RHS 下拉只列 CARD_FORMULA 列。 */
  colSourceTypes?: Record<string, string>;
}
```

并在组件解构（line 251-260）加 `colSourceTypes`：

```tsx
  onClose,
  dryRunQuotationId,
  colSourceTypes,
}) => {
```

- [ ] **Step 3: `buildInsertResult` 的 row_where 分支生成 condRows** — 替换 line 188-203 的 `if (refType === 'row_where')` 块：

```tsx
  if (refType === 'row_where') {
    if (!field) return null;
    const usedFields = conds.filter(c => c.field).map(c => c.field);
    const aliasMap = buildAliasMap(usedFields);
    const cols: Record<string, string> = {};
    for (const [f, a] of Object.entries(aliasMap)) cols[a] = f;
    const condRows = buildCondRows(conds);
    // 仅当全部 RHS=字面量时才生成旧式 cond（向后兼容 + 占位展示）；含动态 RHS 时 cond 留空，后端用 condRows
    const allLiteral = condRows.every(c => c.rhs.type === 'literal');
    const cond = allLiteral ? buildCondJexl(conds, aliasMap) : '';
    const hasDynamic = condRows.some(c => c.rhs.type !== 'literal');
    const condSummary = condRows.length ? (hasDynamic ? '动态条件' : '条件') : '无条件';
    const placeholder = `${tabName}.${field}(${condSummary})`;
    return {
      placeholder,
      refKey: placeholder,
      ref: { tab: tab.tabKey, field, mode: 'ROW_WHERE', cond, cols, condRows },
    };
  }
```

- [ ] **Step 4: 产品字段 / 本行列候选 + 条件行 UI 加来源选择器** — 改两处：

4a. 在 `fieldOptions`（line 452）后加候选列表：

```tsx
  const fieldOptions = (selTab?.fields || []).map(f => ({ label: f, value: f }));

  // RHS=产品字段 候选：所有页签 fields 并集 + 料号(__partNo__)（决策A：纯前端拼，不含组件外属性）
  const productFieldOptions = (() => {
    const seen = new Set<string>();
    const opts: { label: string; value: string }[] = [{ label: '料号(__partNo__)', value: '__partNo__' }];
    for (const t of tabs) for (const f of t.fields) {
      if (f && !seen.has(f)) { seen.add(f); opts.push({ label: f, value: f }); }
    }
    return opts;
  })();

  // RHS=本行卡片公式列 候选：其它 CARD_FORMULA 列（按 colSourceTypes 判定）
  const cardColumnOptions = allColKeys
    .filter(k => k !== value.col_key && (colSourceTypes ? colSourceTypes[k] === 'CARD_FORMULA' : true))
    .map(k => ({ label: `[${k}]`, value: k }));
```

4b. 替换条件行里的"值 Input"块（line 743-750）为「来源选择器 + 按来源渲染值控件」：

```tsx
                        {/* 值来源 */}
                        <Select
                          size="small"
                          style={{ width: 104 }}
                          value={c.rhsType}
                          onChange={v => updateCondRow(i, { rhsType: v as CondRhsType, value: '' })}
                          options={[
                            { label: '字面量', value: 'literal' },
                            { label: '产品字段', value: 'product' },
                            { label: '本行列', value: 'column' },
                          ]}
                        />

                        {/* 值（按来源渲染） */}
                        {c.rhsType === 'literal' && (
                          <Input
                            size="small"
                            style={{ width: 160 }}
                            placeholder={c.op === 'in' ? '值1,值2,值3' : '值'}
                            value={c.value}
                            onChange={e => updateCondRow(i, { value: e.target.value })}
                          />
                        )}
                        {c.rhsType === 'product' && (
                          <Select
                            size="small"
                            style={{ width: 200 }}
                            placeholder="选产品字段"
                            value={c.value || undefined}
                            onChange={v => updateCondRow(i, { value: v })}
                            options={productFieldOptions}
                            showSearch
                            optionFilterProp="label"
                          />
                        )}
                        {c.rhsType === 'column' && (
                          <Select
                            size="small"
                            style={{ width: 160 }}
                            placeholder="选本行列"
                            value={c.value || undefined}
                            onChange={v => updateCondRow(i, { value: v })}
                            options={cardColumnOptions}
                            notFoundContent="无其它卡片公式列"
                          />
                        )}
```

- [ ] **Step 5: 插入时非空校验（spec §6）** — 在 `handleInsertRef`（line ~374）里 `buildInsertResult` 调用前，对 row_where 加守卫：

把 `handleInsertRef` 内的：

```tsx
    const result = buildInsertResult(refType, selTab, selField, conds, aggFunc, aggExpr);
    if (!result) {
      message.warning('请补全引用信息（字段不能为空）');
      return;
    }
```

替换为：

```tsx
    if (refType === 'row_where') {
      // spec §6：rhs.type=product 字段非空；op=in 值非空（column 已由下拉约束为 CARD_FORMULA 列）
      const bad = conds.filter(c => c.field).find(c =>
        (c.rhsType === 'product' && !c.value) ||
        (c.rhsType === 'column' && !c.value) ||
        (c.rhsType === 'literal' && c.op === 'in' && !c.value.trim()));
      if (bad) { message.warning(`条件「${bad.field}」的值未填完整`); return; }
    }
    const result = buildInsertResult(refType, selTab, selField, conds, aggFunc, aggExpr);
    if (!result) {
      message.warning('请补全引用信息（字段不能为空）');
      return;
    }
```

- [ ] **Step 6: 自检编译 + Vite 200**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/template/CardFormulaDrawer.tsx
```
Expected: tsc `0 错误`；curl `200`。

- [ ] **Step 7: 提交**

```bash
git add cpq-frontend/src/pages/template/CardFormulaDrawer.tsx
git commit -m "feat(card-fe): 条件行加 RHS 来源选择器(字面量/产品字段/本行列) + 生成 condRows + 非空校验"
```

---

## Task 7: 前端接线 — `ExcelViewConfigTab` 传 `colSourceTypes`

**Files:**
- Modify: `cpq-frontend/src/pages/template/ExcelViewConfigTab.tsx:558-573`

- [ ] **Step 1: 传 prop** — 在 `<CardFormulaDrawer ...>` 的 `allFormulas={...}` 之后加一行：

```tsx
          allFormulas={Object.fromEntries(
            columns
              .filter(c => c.source_type === 'CARD_FORMULA')
              .map(c => [c.col_key, c.formula || '']),
          )}
          colSourceTypes={Object.fromEntries(columns.map(c => [c.col_key, c.source_type]))}
          value={columns[cardDrawerColIdx]}
```

- [ ] **Step 2: 自检编译 + Vite 200**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/template/ExcelViewConfigTab.tsx
```
Expected: tsc `0 错误`；curl `200`。

- [ ] **Step 3: 提交**

```bash
git add cpq-frontend/src/pages/template/ExcelViewConfigTab.tsx
git commit -m "feat(card-fe): ExcelViewConfigTab 传 colSourceTypes 给卡片公式抽屉"
```

---

## Task 8: 前端 — ref 标签点击回填编辑（含反解析旧 cond）

> 决策 A：编辑旧列时把已有 ROW_WHERE ref 的条件反解析回填到构建器，可继续编辑后重新插入（同 refKey 覆盖）。当前抽屉只把 refs 显示为可删标签、无回填入口，本任务补这个入口，使 `parseCondToRows` 有落点。

**Files:**
- Modify: `cpq-frontend/src/pages/template/CardFormulaDrawer.tsx`

- [ ] **Step 1: 加"回填"函数** — 在 `handleInsertRef`（line 374）之前加：

```tsx
  // 把已有 ROW_WHERE ref 回填到构建器（编辑）。condRows 优先；缺则反解析旧 cond。
  const loadRefIntoBuilder = (refKey: string, ref: CardRefSpec) => {
    if (ref.mode !== 'ROW_WHERE') {
      message.info('仅「字段·按条件取行」引用可回填编辑；其它类型请删除后重建');
      return;
    }
    setSelTabKey(ref.tab);
    setRefType('row_where');
    setSelField(ref.field || '');
    const cols = ref.cols || {};
    const rows: CondRowSpec[] = (ref.condRows && ref.condRows.length)
      ? ref.condRows
      : parseCondToRows(ref.cond || '', cols);
    setConds(
      rows.length
        ? rows.map(r => ({
            field: r.left, op: r.op as CondOperator, value: r.rhs.value,
            logic: r.logic as CondLogic, rhsType: r.rhs.type,
          }))
        : [{ ...DEFAULT_COND_ROW }],
    );
    message.success(`已载入引用「${refKey}」到下方构建器，可编辑后重新插入（同名覆盖）`);
  };
```

- [ ] **Step 2: ref 标签可点回填** — 改 refs 预览的 `<Tag>`（line 612-618），给标签体加 `onClick`（保留 `closable` 删除）：

```tsx
                  <Tag
                    closable
                    color="blue"
                    style={{ cursor: 'pointer' }}
                    onClick={() => loadRefIntoBuilder(key, ref)}
                    onClose={() => setRefs(prev => { const n = { ...prev }; delete n[key]; return n; })}
                  >
                    {key}
                  </Tag>
```

并在该区块标题补一行提示（line 597 附近 `已定义引用 refs` 之后）：

```tsx
            <Text strong style={{ marginBottom: 8, display: 'block' }}>
              已定义引用 refs
              <Text type="secondary" style={{ fontSize: 12, fontWeight: 400, marginLeft: 8 }}>
                （点标签可回填到下方构建器编辑；× 删除）
              </Text>
            </Text>
```

- [ ] **Step 3: 自检编译 + Vite 200**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/template/CardFormulaDrawer.tsx
```
Expected: tsc `0 错误`；curl `200`。

- [ ] **Step 4: 提交**

```bash
git add cpq-frontend/src/pages/template/CardFormulaDrawer.tsx
git commit -m "feat(card-fe): ref 标签点击回填构建器编辑(旧 cond 反解析为 condRows)"
```

---

## Task 9: 集成验证 — 试算路径 + 全量自检

**Files:** 无新增（验证 + 文档自检声明）

- [ ] **Step 1: 后端全卡片相关单测一把过**

Run:
```bash
cd cpq-backend && ./mvnw -q -Dtest='Card*Test,*CardFormula*' test
```
Expected: `BUILD SUCCESS`，无回归。

- [ ] **Step 2: 前端单测 + 全量 tsc**

Run:
```bash
cd cpq-frontend && npx vitest run src/pages/template/cardFormula.test.ts && npx tsc --noEmit -p tsconfig.json
```
Expected: vitest 全绿；tsc `0 错误`。

- [ ] **Step 3: 试算端到端手验（动态 VLOOKUP）** — 在 ExcelView 配置页对一个 CARD_FORMULA 列：
  1. 加 ROW_WHERE 引用，条件 `关联号 等于 [产品字段:料号(__partNo__)]`；插入公式 `=[页签.字段(动态条件)]`。
  2. 点「试算」（需在报价单内编辑入口，有 `dryRunQuotationId`）。
  3. 断言：试算结果各行取到的是"关联号==本行料号"那一行的字段值；构造一个无匹配料号的行 → 显示 `—`。

记录：试算请求 `POST /api/cpq/quotations/{id}/excel-view/dry-run`，F12 Network 看响应 `rows[].{colKey}` 为期望单值（非 `—（共N项）`）。

- [ ] **Step 4: 写自检声明 + 收尾提交**

在 `docs/RECORD.md` 追加一行（格式遵循 CLAUDE.md §开发记录）：

```
[2026-06-06] Excel卡片公式 - ROW_WHERE 动态查找键(第一期) | CardRef.condRows / CardFormulaEvaluator.buildDynamicCond / topoOrder(refs) / ExcelViewService 传 productRow / CardFormulaDrawer RHS来源选择器+反解析回填 | 决策: 仅做ROW_WHERE(聚合WHERE另立计划); RHS=literal/product/column单值; 列依赖纳入condRows(column); RHS空键→1==2→DASH; 向后兼容旧cond
```

自检声明示例（"完成"宣告必带）：
> 后端 `Card*Test` 全 PASS ✅；前端 `cardFormula.test.ts` 全绿 + `tsc --noEmit` 0 错误 ✅；`CardFormulaDrawer.tsx` / `ExcelViewConfigTab.tsx` → Vite 200 ✅；`/q/health` → 200 ✅；试算动态 VLOOKUP 取单值、无匹配→`—` ✅

```bash
git add docs/RECORD.md
git commit -m "docs(record): ROW_WHERE 动态查找键(第一期)开发记录 + 自检声明"
```

---

## 后续（不在本计划）

- **聚合 WHERE 动态 RHS**（SUM_OVER 内嵌谓词）：另起小 spec/计划，复用 `condRows` 数据模型；需在公式文本里按 `[页签名]` 定位 SUM_OVER 段并改写谓词 + 处理同页签多 SUM_OVER 歧义。
- **第二期**：RHS = 嵌套另一页签 card-ref（递归 ref 构建器 UI + 后端递归解析）。
