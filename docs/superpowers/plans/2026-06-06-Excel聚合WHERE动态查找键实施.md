# Excel 卡片公式 聚合 WHERE 动态查找键（第二期）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Excel `CARD_FORMULA` 列的聚合 `SUM_OVER([页签] WHERE 条件, 表达式)` 的 WHERE 过滤条件右侧"值"能引用本产品行可见的键（字面量 / 本行产品字段 / 本行其它 CARD_FORMULA 列），实现动态 SUMIF；并支持同一列公式里对同一页签写多个条件不同的聚合。

**Architecture:** 复用第一期 `condRows` 数据模型 + `buildDynamicCond`/`resolveRhs` + 拓扑 `condRowColumnDeps`。聚合 ref 携带 `condRows`；`CardAggregateSource.Binding` 加 `dynamicPredicate` 字段，`resolveCardScalars`（每产品行）复用 `buildDynamicCond` 算动态谓词存进 binding，`executeOverFunction` 优先用它而非公式文本谓词（**不改写公式文本**）。所有新建聚合 ref 用唯一 refKey `页签名#N` + 公式源 token `[页签名#N]`，支持同页签多聚合并顺带修复既有"同页签多静态聚合 cols 别名互覆盖"潜在缺陷。

**Tech Stack:** Java 17 + Quarkus（JUnit5 / `@QuarkusTest`）、JEXL3（`evalRowExpression`）、React + Ant Design + TypeScript、Vitest。

**前置：** 第一期 ROW_WHERE 动态查找键已实现（commit `b4ac322`→`998999b`）。本期复用其 `CardRef.condRows`（含 `RhsType`/`CondRow`/`Rhs`/`hasCondRows()`）、`CardFormulaEvaluator.buildDynamicCond`/`resolveRhs`/`condRowColumnDeps`、前端 `cardFormula.ts` 的 `CondRowSpec`/`buildCondRows` + `CardFormulaDrawer` 条件行 RHS 来源选择器（已对 aggregate 渲染）。设计文档 `docs/superpowers/specs/2026-06-06-Excel聚合WHERE动态查找键-design.md`。

**Scope（已确认）：**
- 只做聚合 **WHERE** 的动态 RHS；被聚合的 `aggExpr` 保持静态。
- RHS = `literal | product(组件字段 + __partNo__) | column(本行其它 CARD_FORMULA 列)`，单值引用。
- 同页签多聚合：本期必须支持（唯一 keying）。
- 聚合 ref **不做**点标签回填编辑（YAGNI，留后续）。

**关键不变量（沿用第一期）：** RHS 仅能引用 productRow / partNo + 已算 CARD_FORMULA 列（cached）；RHS 取空 → 该条件 `1==2` 永假 → 不匹配（聚合空集→0）；中文字段走 cols 别名 + Map 直取，不当 JEXL 标识符。

---

## 文件结构

**后端（改）**
- `cpq-backend/src/main/java/com/cpq/template/service/CardAggregateSource.java` — `Binding` 加 `dynamicPredicate` 字段 + 3 参构造（2 参委托 null）；新增静态 `predicateFor(sourceToken)`。
- `cpq-backend/src/main/java/com/cpq/quotation/service/CardFormulaEvaluator.java` — `resolveCardScalars` 登记聚合 binding 时，若 ref `hasCondRows()` 则 `buildDynamicCond` 算动态谓词传入 3 参 Binding。
- `cpq-backend/src/main/java/com/cpq/template/service/TemplateFormulaService.java` — `executeOverFunction` 取谓词改为 `predicateFor(source)` 优先于 `parsed.predicate`。

**后端（测）**
- `cpq-backend/src/test/java/com/cpq/quotation/card/CardAggregateSourcePredicateTest.java`（新建）— 普通 JUnit：`predicateFor` 取值 + null 兜底。
- `cpq-backend/src/test/java/com/cpq/quotation/card/CardAggregateDynamicTest.java`（新建）— `@QuarkusTest`：动态 SUM_OVER（product/column/多条件/空键→0）+ 同页签多动态聚合互不串 + 同页签多静态聚合 collision 修复 + 旧 token 兼容。

**前端（改）**
- `cpq-frontend/src/pages/template/cardFormula.ts` — `ALLOWED` 正则加 `#`；新增 `nextAggRefKey(tabName, existingKeys)`。
- `cpq-frontend/src/pages/template/CardFormulaDrawer.tsx` — `buildInsertResult` 聚合分支按 hasDynamic 生成 condRows/省 WHERE + 唯一 refKey；`handleInsertRef` 计算 `nextAggRefKey` 并把校验扩到 aggregate。

**前端（测）**
- `cpq-frontend/src/pages/template/cardFormula.test.ts` — `nextAggRefKey` 序号逻辑 + `validateCardFormula` 接受含 `#` 公式。

---

## Task 1: 后端 — `CardAggregateSource.Binding.dynamicPredicate` + `predicateFor`

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/template/service/CardAggregateSource.java`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/card/CardAggregateSourcePredicateTest.java`（新建）

- [ ] **Step 1: 写失败测试** — 新建 `CardAggregateSourcePredicateTest.java`：

```java
package com.cpq.quotation.card;

import com.cpq.template.service.CardAggregateSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CardAggregateSourcePredicateTest {
    @AfterEach void cleanup() { CardAggregateSource.clear(); }

    @Test void predicateFor_returns_binding_dynamic_predicate() {
        var binding = new CardAggregateSource.Binding("comp:0", Map.of("c0", "关联号"), "c0=='P9'");
        CardAggregateSource.set(new CardAggregateSource.Ctx(null,
                Map.of("加工#1", binding)));
        assertEquals("c0=='P9'", CardAggregateSource.predicateFor("加工#1"));
        assertEquals("c0=='P9'", CardAggregateSource.predicateFor(" 加工#1 ")); // trim
    }

    @Test void predicateFor_null_when_no_binding_or_no_predicate() {
        var staticBinding = new CardAggregateSource.Binding("comp:0", Map.of("c0", "工序")); // 2 参 → null 谓词
        CardAggregateSource.set(new CardAggregateSource.Ctx(null, Map.of("加工", staticBinding)));
        assertNull(CardAggregateSource.predicateFor("加工"));      // 有 binding 无动态谓词
        assertNull(CardAggregateSource.predicateFor("不存在"));     // 无 binding
        CardAggregateSource.clear();
        assertNull(CardAggregateSource.predicateFor("加工"));      // 无 Ctx
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -q -Dtest=CardAggregateSourcePredicateTest test`
Expected: 编译失败 — `Binding(String, Map, String)` 构造不存在 / `predicateFor` 不存在。

- [ ] **Step 3: 改 `CardAggregateSource.java`** — 整文件替换为：

```java
package com.cpq.template.service;

import com.cpq.quotation.service.card.CardDataProvider;
import java.util.*;

/** 求值期 ThreadLocal：当前料号卡片 provider + 聚合源token→{页签key + 别名映射 + (可空)动态谓词}。由 Excel 求值入口 set/clear。 */
public final class CardAggregateSource {
    public static final class Binding {
        public final String tabKey;
        public final Map<String, String> aliasToField; // c0 → 工序
        public final String dynamicPredicate;          // 按本产品行算好的 JEXL 谓词(别名左值); 可空 → 走公式文本谓词
        public Binding(String tabKey, Map<String, String> aliasToField) {
            this(tabKey, aliasToField, null);
        }
        public Binding(String tabKey, Map<String, String> aliasToField, String dynamicPredicate) {
            this.tabKey = tabKey;
            this.aliasToField = aliasToField != null ? aliasToField : Map.of();
            this.dynamicPredicate = dynamicPredicate;
        }
    }
    public static final class Ctx {
        public final CardDataProvider provider;
        public final Map<String, Binding> sourceToken;
        public Ctx(CardDataProvider p, Map<String, Binding> map) { this.provider = p; this.sourceToken = map; }
    }
    private static final ThreadLocal<Ctx> TL = new ThreadLocal<>();
    public static void set(Ctx c) { TL.set(c); }
    public static void clear() { TL.remove(); }
    public static Ctx get() { return TL.get(); }

    /** 命中卡片源 → 返回已按别名重映射 key 的行集(c0/c1...→值)，供 JEXL 行求值；否则 null(回退 dataLoader)。 */
    public static List<Map<String, Object>> rowsFor(String sourceToken) {
        Ctx c = TL.get();
        if (c == null || sourceToken == null) return null;
        Binding b = c.sourceToken.get(sourceToken.trim());
        if (b == null) return null;
        List<Map<String, Object>> raw = c.provider.rowsOf(b.tabKey);
        List<Map<String, Object>> out = new ArrayList<>(raw.size());
        for (Map<String, Object> row : raw) {
            Map<String, Object> aliased = new HashMap<>();
            for (Map.Entry<String, String> e : b.aliasToField.entrySet())
                aliased.put(e.getKey(), row.get(e.getValue()));
            out.add(aliased);
        }
        return out;
    }

    /** 该源 token 绑定的动态谓词(按本产品行算好); 无 Ctx / 无 binding / 无动态谓词 → null(走公式文本谓词)。 */
    public static String predicateFor(String sourceToken) {
        Ctx c = TL.get();
        if (c == null || sourceToken == null) return null;
        Binding b = c.sourceToken.get(sourceToken.trim());
        return b == null ? null : b.dynamicPredicate;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -q -Dtest=CardAggregateSourcePredicateTest test`
Expected: `BUILD SUCCESS`，2 方法 PASS。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/template/service/CardAggregateSource.java \
        cpq-backend/src/test/java/com/cpq/quotation/card/CardAggregateSourcePredicateTest.java
git commit -m "feat(card): CardAggregateSource.Binding 增 dynamicPredicate + predicateFor(聚合动态谓词载体)"
```

---

## Task 2: 后端 — 聚合 binding 注入动态谓词 + `executeOverFunction` 优先用之

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/CardFormulaEvaluator.java:198-205`（resolveCardScalars 聚合登记循环）
- Modify: `cpq-backend/src/main/java/com/cpq/template/service/TemplateFormulaService.java:717-726`（executeOverFunction 谓词取用）
- Test: `cpq-backend/src/test/java/com/cpq/quotation/card/CardAggregateDynamicTest.java`（新建）

- [ ] **Step 1: 写失败测试** — 新建 `CardAggregateDynamicTest.java`：

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
class CardAggregateDynamicTest {
    @Inject CardFormulaEvaluator evaluator;

    private QuotationLineComponentData tab(String comp, String rowDataJson) {
        var d = new QuotationLineComponentData();
        d.componentId = UUID.fromString(comp); d.sortOrder = 0; d.subtotal = BigDecimal.ZERO;
        d.rowData = rowDataJson;
        return d;
    }

    private BigDecimal num(Object v) { return new BigDecimal(v.toString()); }

    /** 动态 SUMIF: SUM_OVER([加工#1], c1) WHERE 关联号==本行料号 → 求和命中行的费。 */
    @Test void dynamic_aggregate_product_partNo() {
        var comp = "55555555-5555-5555-5555-555555555555";
        var d = tab(comp, "[{\"关联号\":\"P1\",\"费\":2}," +
                          "{\"关联号\":\"P9\",\"费\":9},{\"关联号\":\"P9\",\"费\":5}]");
        var refs = Map.<String,Object>of("加工#1", Map.of(
            "tab", comp+":0", "cols", Map.of("c0","关联号","c1","费"),
            "condRows", List.of(Map.of("left","关联号","op","eq","logic","and",
                "rhs", Map.of("type","product","value","__partNo__")))));
        var col = new LinkedHashMap<String,Object>();
        col.put("col_key","A"); col.put("source_type","CARD_FORMULA");
        col.put("formula","=SUM_OVER([加工#1], c1)"); col.put("refs", refs);
        var out = evaluator.evaluateColumns(List.of(col), List.of(d), null, "P9", null, Map.of());
        assertEquals(0, new BigDecimal("14").compareTo(num(out.get("A")))); // 9+5
    }

    /** 同一列对同页签两个条件不同的动态聚合互不串值(唯一 keying 核心)。 */
    @Test void two_dynamic_aggregates_same_tab_no_crosstalk() {
        var comp = "55555555-5555-5555-5555-555555555555";
        var d = tab(comp, "[{\"关联号\":\"P9\",\"类型\":\"酸洗\",\"费\":9}," +
                          "{\"关联号\":\"P1\",\"类型\":\"电镀\",\"费\":3}]");
        var refs = Map.<String,Object>of(
            "加工#1", Map.of("tab", comp+":0", "cols", Map.of("c0","关联号","c1","费"),
                "condRows", List.of(Map.of("left","关联号","op","eq","logic","and",
                    "rhs", Map.of("type","product","value","__partNo__")))),
            "加工#2", Map.of("tab", comp+":0", "cols", Map.of("c0","类型","c1","费"),
                "condRows", List.of(Map.of("left","类型","op","eq","logic","and",
                    "rhs", Map.of("type","literal","value","电镀")))));
        var col = new LinkedHashMap<String,Object>();
        col.put("col_key","A"); col.put("source_type","CARD_FORMULA");
        col.put("formula","=SUM_OVER([加工#1], c1) + SUM_OVER([加工#2], c1)"); col.put("refs", refs);
        var out = evaluator.evaluateColumns(List.of(col), List.of(d), null, "P9", null, Map.of());
        assertEquals(0, new BigDecimal("12").compareTo(num(out.get("A")))); // 9(关联P9) + 3(类型电镀)
    }

    /** 同页签两个静态聚合用不同字段不再 cols collision(潜在缺陷修复回归)。 */
    @Test void two_static_aggregates_same_tab_distinct_fields() {
        var comp = "55555555-5555-5555-5555-555555555555";
        var d = tab(comp, "[{\"工序\":\"酸洗\",\"区\":\"东\",\"费\":2}," +
                          "{\"工序\":\"电镀\",\"区\":\"西\",\"费\":7}]");
        var refs = Map.<String,Object>of(
            "加工#1", Map.of("tab", comp+":0", "cols", Map.of("c0","工序","c1","费")),
            "加工#2", Map.of("tab", comp+":0", "cols", Map.of("c0","区","c1","费")));
        var col = new LinkedHashMap<String,Object>();
        col.put("col_key","A"); col.put("source_type","CARD_FORMULA");
        col.put("formula","=SUM_OVER([加工#1] WHERE c0=='电镀', c1) + SUM_OVER([加工#2] WHERE c0=='东', c1)");
        col.put("refs", refs);
        var out = evaluator.evaluateColumns(List.of(col), List.of(d), null, "PX", null, Map.of());
        assertEquals(0, new BigDecimal("9").compareTo(num(out.get("A")))); // 7(工序电镀) + 2(区东)
    }

    /** RHS 取空(产品字段不存在) → 不匹配 → 空集 → 0。 */
    @Test void dynamic_aggregate_missing_key_is_zero() {
        var comp = "55555555-5555-5555-5555-555555555555";
        var d = tab(comp, "[{\"关联号\":\"P1\",\"费\":2}]");
        var refs = Map.<String,Object>of("加工#1", Map.of(
            "tab", comp+":0", "cols", Map.of("c0","关联号","c1","费"),
            "condRows", List.of(Map.of("left","关联号","op","eq","logic","and",
                "rhs", Map.of("type","product","value","不存在字段")))));
        var col = new LinkedHashMap<String,Object>();
        col.put("col_key","A"); col.put("source_type","CARD_FORMULA");
        col.put("formula","=SUM_OVER([加工#1], c1)"); col.put("refs", refs);
        var out = evaluator.evaluateColumns(List.of(col), List.of(d), null, "P1", null, Map.of());
        assertEquals(0, new BigDecimal("0").compareTo(num(out.get("A"))));
    }

    /** 旧 token(无#后缀、文本 WHERE、无 condRows)静态聚合兼容回归。 */
    @Test void legacy_static_aggregate_still_works() {
        var comp = "55555555-5555-5555-5555-555555555555";
        var d = tab(comp, "[{\"工序\":\"酸洗\",\"费\":2},{\"工序\":\"电镀\",\"费\":9}]");
        var refs = Map.<String,Object>of("加工", Map.of(
            "tab", comp+":0", "cols", Map.of("c0","工序","c1","费")));
        var col = new LinkedHashMap<String,Object>();
        col.put("col_key","A"); col.put("source_type","CARD_FORMULA");
        col.put("formula","=SUM_OVER([加工] WHERE c0=='电镀', c1)"); col.put("refs", refs);
        var out = evaluator.evaluateColumns(List.of(col), List.of(d), null, "PX", null, Map.of());
        assertEquals(0, new BigDecimal("9").compareTo(num(out.get("A"))));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -q -Dtest=CardAggregateDynamicTest test`
Expected: `dynamic_aggregate_product_partNo` / `two_dynamic_aggregates_same_tab_no_crosstalk` / `dynamic_aggregate_missing_key_is_zero` 失败（动态谓词未注入 → 聚合不过滤 → 求和全部行）。静态/legacy 用例此时可能已通过（唯一 token 本就工作）。

- [ ] **Step 3: 改 `CardFormulaEvaluator.resolveCardScalars` 登记聚合 binding** — 把 `resolveCardScalars` 顶部的聚合登记循环（line 198-205）：

```java
        Set<String> aggTokens = new HashSet<>();
        for (var e : refs.entrySet()) {
            CardRef r = CardRef.fromMap(asRefMap(e.getValue()));
            if (r != null && r.isAggregateSource()) {
                aggBindings.put(e.getKey(), new CardAggregateSource.Binding(r.tab, r.cols));
                aggTokens.add(e.getKey());
            }
        }
```

替换为（聚合 ref 有 condRows → 复用 buildDynamicCond 算本产品行动态谓词存进 binding）：

```java
        Set<String> aggTokens = new HashSet<>();
        for (var e : refs.entrySet()) {
            CardRef r = CardRef.fromMap(asRefMap(e.getValue()));
            if (r != null && r.isAggregateSource()) {
                String dynPred = r.hasCondRows()
                        ? buildDynamicCond(r, productRow, cached, partNo)   // 动态 WHERE 谓词(按本产品行)
                        : null;                                             // 静态 → 走公式文本谓词
                aggBindings.put(e.getKey(), new CardAggregateSource.Binding(r.tab, r.cols, dynPred));
                aggTokens.add(e.getKey());
            }
        }
```

- [ ] **Step 4: 改 `TemplateFormulaService.executeOverFunction` 优先用动态谓词** — 把 `executeOverFunction` 里逐行 WHERE 过滤段（line 714-726 附近，含 `for (Map<String, Object> row : rows)` 循环开头那段）改为：在 `for` 循环之前先取动态谓词，循环内用它。

定位 line 712-721 这段：

```java
            if (rows == null) rows = List.of();

            // 对每行执行行内表达式 + WHERE 过滤 + 聚合
            List<BigDecimal> values = new ArrayList<>();
            int rowIdx = 0;
            for (Map<String, Object> row : rows) {
                rowIdx++;
                // 应用 WHERE 谓词（可选）
                if (parsed.predicate != null && !parsed.predicate.isBlank()) {
                    Object pred = evalRowExpression(parsed.predicate, row);
```

替换为：

```java
            if (rows == null) rows = List.of();

            // 卡片动态谓词优先：binding 有按本产品行算好的谓词 → 用它；否则用公式文本切出的谓词。
            String dynPred = com.cpq.template.service.CardAggregateSource.predicateFor(parsed.source);
            String predicate = (dynPred != null) ? dynPred : parsed.predicate;

            // 对每行执行行内表达式 + WHERE 过滤 + 聚合
            List<BigDecimal> values = new ArrayList<>();
            int rowIdx = 0;
            for (Map<String, Object> row : rows) {
                rowIdx++;
                // 应用 WHERE 谓词（可选）
                if (predicate != null && !predicate.isBlank()) {
                    Object pred = evalRowExpression(predicate, row);
```

并把该循环内后续对 `parsed.predicate` 的**日志引用**（紧随其后的 `LOG.infof(... parsed.predicate ...)` 那行，约 line 722-724）改为引用 `predicate`：

把：
```java
                    LOG.infof("[Stage4] %s row#%d: predicate='%s' result=%s (truthy=%b) inputQty=%s",
                              funcName, rowIdx, parsed.predicate, pred, isTruthy(pred),
                              row.get("input_qty"));
```
改为（仅把 `parsed.predicate` 换成 `predicate`）：
```java
                    LOG.infof("[Stage4] %s row#%d: predicate='%s' result=%s (truthy=%b) inputQty=%s",
                              funcName, rowIdx, predicate, pred, isTruthy(pred),
                              row.get("input_qty"));
```
以及聚合后那行汇总日志（约 line 735-736）`'%s'` 对应的 `parsed.predicate` 也换成 `predicate`：
```java
            LOG.infof("[Stage4] %s: %d rows passed WHERE filter '%s', aggregating",
                      funcName, values.size(), predicate);
```

> 注：`parsed.predicate` 仅用于日志/原文本回显处可保留，但过滤判定与日志谓词文案统一用 `predicate`（动态优先）更准确、不误导排查。

- [ ] **Step 5: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -q -Dtest=CardAggregateDynamicTest,CardAggregateSourcePredicateTest test`
Expected: `BUILD SUCCESS`，`CardAggregateDynamicTest` 5 方法 + `CardAggregateSourcePredicateTest` 2 方法全 PASS。

- [ ] **Step 6: 回归既有卡片/聚合测试**

Run: `cd cpq-backend && ./mvnw -q -Dtest='Card*Test' test`
Expected: `BUILD SUCCESS`，全绿（含第一期 `CardRowWhereDynamicTest`/`CardFormulaTopoTest` + 既有 `CardAggregateTest`）。

- [ ] **Step 7: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/CardFormulaEvaluator.java \
        cpq-backend/src/main/java/com/cpq/template/service/TemplateFormulaService.java \
        cpq-backend/src/test/java/com/cpq/quotation/card/CardAggregateDynamicTest.java
git commit -m "feat(card): 聚合 SUM_OVER 动态 WHERE(复用buildDynamicCond) + executeOverFunction 优先用动态谓词"
```

---

## Task 3: 前端纯逻辑 — `ALLOWED` 加 `#` + `nextAggRefKey`

**Files:**
- Modify: `cpq-frontend/src/pages/template/cardFormula.ts`
- Test: `cpq-frontend/src/pages/template/cardFormula.test.ts`

- [ ] **Step 1: 写失败测试** — 在 `cardFormula.test.ts` 的 `describe` 块内末尾加：

```ts
  it('validateCardFormula 接受含 # 的聚合 token [页签#N]', () => {
    const errs = validateCardFormula(
      { col_key: 'A', formula: "=SUM_OVER([投料#1], c0)", refs: { '投料#1': { tab: 't:0', cols: { c0: '量' } } } } as any,
      ['A'], {});
    expect(errs.some(e => e.includes('非法字符'))).toBe(false);
  });

  it('nextAggRefKey 同页签递增、跨页签独立、含旧无后缀', () => {
    expect(nextAggRefKey('投料', [])).toBe('投料#1');
    expect(nextAggRefKey('投料', ['投料'])).toBe('投料#1');           // 旧无后缀 → 新从 #1
    expect(nextAggRefKey('投料', ['投料#1'])).toBe('投料#2');
    expect(nextAggRefKey('投料', ['投料#1', '投料#3'])).toBe('投料#4'); // max+1
    expect(nextAggRefKey('投料', ['加工#5'])).toBe('投料#1');          // 跨页签独立
    expect(nextAggRefKey('投料', ['投料.小计', '投料.量(条件)'])).toBe('投料#1'); // 非聚合 key 不计
  });
```

并把测试文件顶部 import 追加 `nextAggRefKey`：

```ts
import { genAlias, expandIn, extractColKeyDeps, validateCardFormula, buildCondRows, parseCondToRows, nextAggRefKey } from './cardFormula';
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/template/cardFormula.test.ts`
Expected: FAIL — `nextAggRefKey is not a function`；含 `#` 公式当前报"非法字符"（ALLOWED 未含 #）。

- [ ] **Step 3: 改 `cardFormula.ts`**

3a. `ALLOWED` 正则（约 line 33）加 `#`：

把：
```ts
const ALLOWED = /^[\sA-Za-z0-9_+\-*/().,%<>=!&|'一-龥\[\]]*$/; // 含中文(字符串字面量/占位内)
```
改为：
```ts
const ALLOWED = /^[\sA-Za-z0-9_+\-*/().,%<>=!&|'#一-龥\[\]]*$/; // 含中文 + #(聚合唯一 token [页签#N])
```

3b. 文件末尾追加 `nextAggRefKey`：

```ts
/**
 * 为某页签生成下一个唯一聚合 refKey `页签名#N`（N 从 1 起，取该页签已有 `页签名#数字` 的最大值 +1）。
 * 旧无后缀 `页签名` 视为占位但不计入序号（新建仍从 #1 起，与旧并存不冲突）。
 * 非聚合 key（如 `页签名.字段(条件)`）不计入。
 */
export function nextAggRefKey(tabName: string, existingKeys: string[]): string {
  const re = new RegExp(`^${tabName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}#(\\d+)$`);
  let max = 0;
  for (const k of existingKeys) {
    const m = k.match(re);
    if (m) max = Math.max(max, Number(m[1]));
  }
  return `${tabName}#${max + 1}`;
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/template/cardFormula.test.ts`
Expected: PASS — 旧用例 + 新 2 用例全绿。

- [ ] **Step 5: 全量 tsc**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误。

- [ ] **Step 6: 提交**

```bash
git add cpq-frontend/src/pages/template/cardFormula.ts cpq-frontend/src/pages/template/cardFormula.test.ts
git commit -m "feat(card-fe): cardFormula ALLOWED 加 # + nextAggRefKey(聚合唯一 token)"
```

---

## Task 4: 前端 UI — `CardFormulaDrawer` 聚合分支生成 condRows + 唯一 refKey

**Files:**
- Modify: `cpq-frontend/src/pages/template/CardFormulaDrawer.tsx`

- [ ] **Step 1: import `nextAggRefKey`** — 把顶部 `./cardFormula` 的值导入（约 line 22）追加 `nextAggRefKey`：

```tsx
import { genAlias, expandIn, validateCardFormula, buildCondRows, parseCondToRows, nextAggRefKey } from './cardFormula';
```

- [ ] **Step 2: `buildInsertResult` 聚合分支按 hasDynamic 分流 + 唯一 refKey** — 先给 `buildInsertResult` 增一个入参 `aggRefKey`，再改聚合分支。

2a. 改函数签名（约 line 159-166），在末尾加 `aggRefKey: string`：

```tsx
function buildInsertResult(
  refType: RefType,
  tab: TabInfo,
  field: string,
  conds: CondRow[],
  aggFunc: AggFunc,
  aggExpr: string,          // 用户填的行内别名表达式
  aggRefKey: string,        // 聚合唯一 refKey（页签名#N，由 handleInsertRef 算好传入）
): InsertResult | null {
```

2b. 替换 `if (refType === 'aggregate')` 整块（约 line 211-230）为：

```tsx
  if (refType === 'aggregate') {
    // 聚合：收集 conds 里的字段 + aggExpr 里的字段（用于别名映射）
    const usedFieldsInConds = conds.filter(c => c.field).map(c => c.field);
    const usedFieldsInExpr = tab.fields.filter(f => aggExpr.includes(f));
    const allUsedFields = [...usedFieldsInConds, ...usedFieldsInExpr].filter(Boolean);
    const aliasMap = buildAliasMap(allUsedFields);
    const aliasExpr = replaceFieldsWithAlias(aggExpr, aliasMap);
    const cols: Record<string, string> = {};
    for (const [f, a] of Object.entries(aliasMap)) cols[a] = f;

    const refKey = aggRefKey || tab.tabName; // 唯一 key（页签名#N）
    const condRows = buildCondRows(conds);
    const hasDynamic = condRows.length > 0 && condRows.some(c => c.rhs.type !== 'literal');
    if (hasDynamic) {
      // 动态：省略公式 WHERE（条件存 condRows，后端按本产品行算谓词）
      const placeholder = `${aggFunc}_OVER([${refKey}], ${aliasExpr || '1'})`;
      return { placeholder, refKey, ref: { tab: tab.tabKey, cols, condRows } };
    }
    // 全字面量：保持 WHERE 烤入公式（行为不变）
    const condJexl = buildCondJexl(conds, aliasMap);
    const condPart = condJexl ? ` WHERE ${condJexl}` : '';
    const placeholder = `${aggFunc}_OVER([${refKey}]${condPart}, ${aliasExpr || '1'})`;
    return { placeholder, refKey, ref: { tab: tab.tabKey, cols } };
  }
```

- [ ] **Step 3: `handleInsertRef` 算唯一 refKey 并传入 + 校验扩到 aggregate** — 改 `handleInsertRef`（约 line 414-426）。

把校验段的 `if (refType === 'row_where')` 扩到也覆盖 aggregate，并在 `buildInsertResult` 调用处算出 aggRefKey 传入：

把（约 line 414-426）：
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
```

替换为：
```tsx
    if (refType === 'row_where' || refType === 'aggregate') {
      // spec §6：rhs.type=product 字段非空；op=in 值非空（column 已由下拉约束为 CARD_FORMULA 列）
      const bad = conds.filter(c => c.field).find(c =>
        (c.rhsType === 'product' && !c.value) ||
        (c.rhsType === 'column' && !c.value) ||
        (c.rhsType === 'literal' && c.op === 'in' && !c.value.trim()));
      if (bad) { message.warning(`条件「${bad.field}」的值未填完整`); return; }
    }
    // 聚合用唯一 refKey 页签名#N（同页签多聚合不冲突；行内插入故 editingRefKey 恒为 null）
    const aggRefKey = refType === 'aggregate'
      ? nextAggRefKey(selTab.tabName, Object.keys(refs))
      : '';
    const result = buildInsertResult(refType, selTab, selField, conds, aggFunc, aggExpr, aggRefKey);
```

- [ ] **Step 4: 自检编译 + Vite 200**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/template/CardFormulaDrawer.tsx
```
Expected: tsc `0 错误`；curl `200`（Vite dev 未跑则注明，以 tsc 0 为准）。

- [ ] **Step 5: 提交**

```bash
git add cpq-frontend/src/pages/template/CardFormulaDrawer.tsx
git commit -m "feat(card-fe): 聚合分支生成 condRows(动态省WHERE) + 唯一 refKey 页签名#N + 校验扩到聚合"
```

---

## Task 5: 集成验证 + 自检

**Files:** 无新增（验证 + 文档）

- [ ] **Step 1: 后端全卡片相关单测一把过**

Run:
```bash
cd cpq-backend && ./mvnw -q -Dtest='Card*Test,*CardFormula*' test
```
Expected: `BUILD SUCCESS`，无回归（含第一期 + 本期 `CardAggregateDynamicTest`/`CardAggregateSourcePredicateTest`）。

- [ ] **Step 2: 前端单测 + 全量 tsc**

Run:
```bash
cd cpq-frontend && npx vitest run src/pages/template/cardFormula.test.ts && npx tsc --noEmit -p tsconfig.json
```
Expected: vitest 全绿；tsc `0 错误`。

- [ ] **Step 3: 试算端到端手验（动态 SUMIF + 同页签多聚合）** — 在 ExcelView 配置页对一个 CARD_FORMULA 列：
  1. 「聚合」类型，页签选一个有"关联号/料号"列的页签，条件 `关联号 等于 [产品字段:料号(__partNo__)]`，行内表达式填某数值字段 → 插入 → 公式出现 `SUM_OVER([页签#1], …)`（无 WHERE）。
  2. 再插入第二个同页签聚合、条件不同（如 `类型 等于 电镀`）→ 公式出现 `SUM_OVER([页签#2], …)`，两个 refKey 不同。
  3. 点「试算」（报价单内编辑入口，有 `dryRunQuotationId`）。
  4. 断言：第一个聚合只汇总"关联号==本行料号"的行；第二个只汇总"类型==电镀"的行；两者互不串值；构造无匹配料号行 → 该聚合为 0。

记录：`POST /api/cpq/quotations/{id}/excel-view/dry-run`，F12 Network 看响应 `rows[].{colKey}` 为期望聚合值。

- [ ] **Step 4: 写 RECORD.md + 自检声明** — 在 `docs/RECORD.md` 的第一期条目（`### [2026-06-06] Excel卡片公式 - WHERE 动态查找键(第一期·ROW_WHERE)`）之后、归档 footer（`---\n\n> 📦`）之前，追加：

```markdown
### [2026-06-06] Excel卡片公式 - 聚合 WHERE 动态查找键(第二期) | CardAggregateSource.dynamicPredicate+predicateFor / CardFormulaEvaluator.resolveCardScalars(复用buildDynamicCond) / TemplateFormulaService.executeOverFunction / cardFormula.ts(ALLOWED加# + nextAggRefKey) / CardFormulaDrawer 聚合分支

把第一期 ROW_WHERE 的动态查找键扩到聚合 `SUM_OVER([页签] WHERE 条件, 表达式)` 的 WHERE，实现动态 SUMIF，并支持同列同页签多个条件不同的聚合。

- **机制(方案①Binding注入)**: 聚合 ref 带 `condRows`；`CardAggregateSource.Binding` 加 `dynamicPredicate` + `predicateFor`；`resolveCardScalars` 登记聚合 binding 时若 hasCondRows 复用 `buildDynamicCond`(按本产品行)算谓词存进 binding；`executeOverFunction` 取谓词 `predicateFor(source) ?? parsed.predicate`(动态优先)。**不改写公式文本**。
- **唯一keying**: 新建聚合 refKey/token = `页签名#N`(`nextAggRefKey` 同页签递增)，支持同页签多聚合；顺带修复既有"同页签多静态聚合 cols 别名互覆盖"潜在缺陷。旧 `[页签]` token 兼容不迁移。
- **动态时省略公式 WHERE**(与 ROW_WHERE cond='' 对称)，全字面量聚合仍 WHERE 烤入不变。
- **零新增拓扑**: 第一期 `condRowColumnDeps` 已扫所有 refs condRows，聚合 ref 带 condRows 后其 column 依赖自动纳入。
- **校验**: `cardFormula.ts` ALLOWED 加 `#`(否则 `[页签#N]` 报非法字符)；插入非空校验扩到 aggregate。
- **范围**: 只做 WHERE 动态(aggExpr 不变)；RHS 复用 literal/product/column；聚合 ref 不做回填编辑(YAGNI)。
- **验证(已自检)**: 后端 `Card*Test` 全绿(新增 `CardAggregateDynamicTest` 5 含 product/同页签多动态聚合互不串/静态collision修复/空键→0/旧token兼容 + `CardAggregateSourcePredicateTest` 2)；前端 `cardFormula.test.ts` vitest 全绿(+nextAggRefKey/#公式)；tsc 0；`CardFormulaDrawer.tsx` Vite 200。
- **设计/计划**: `docs/superpowers/specs/2026-06-06-Excel聚合WHERE动态查找键-design.md` + `docs/superpowers/plans/2026-06-06-Excel聚合WHERE动态查找键实施.md`。
```

自检声明示例（"完成"宣告必带）：
> 后端 `Card*Test` 全 PASS（`CardAggregateDynamicTest` 5 + `CardAggregateSourcePredicateTest` 2）✅；前端 `cardFormula.test.ts` 全绿 + `tsc --noEmit` 0 错误 ✅；`CardFormulaDrawer.tsx` → Vite 200 ✅；试算动态 SUMIF 同页签两聚合互不串、无匹配→0 ✅

```bash
git add docs/RECORD.md
git commit -m "docs(record): 聚合 WHERE 动态查找键(第二期)开发记录 + 自检声明"
```

---

## 后续（不在本计划）

- **第三期**：ROW_WHERE / 聚合 RHS = 嵌套另一页签 card-ref（递归 ref 构建器 UI + 后端递归解析）。
- aggExpr（被聚合的行内表达式）引用本产品行键。
- 聚合 ref 点标签回填编辑。
