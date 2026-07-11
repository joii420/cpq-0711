# 报价单 Excel 视图 TAB_JOIN 取值/小计修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让报价单 Excel 视图（v2 配置走后端 `getExcelView`）的 `TAB_JOIN_FORMULA` 列正确出值——明细列求和（A=0.0774）、SUBTOTAL 页签总计引用（C 产品小计=0.22），不再全 0。

**Architecture:** 不改保存链路、不改前端、不改 `CardDataProvider`/`TabJoinPlanEvaluator`。新增一个**读时计算器**，把每行（line item）持久化的 `quotation_line_component_data.row_data`（新鲜真相源）转换成系统已有的「有效行」抽象 `Map<tabKey, CardEffectiveRows.TabRows>{rows, subtotal, subtotalByColumn}`：明细行原样透传、列小计 = 列求和、SUBTOTAL 组件总计 = 复用既有 `FormulaCalculator.evaluateExpression` 求其 `component_subtotal` 公式。该 map 以**裸 componentId**（Excel 列 `tabKey` 约定）与 `componentId:sortOrder`（CardRef 约定）**双键**登记，从而绕过两个根因：①裸 componentId 在持久化 `CardDataProvider.resolve()` 无法解析；②小计从不落库（全库 0）。最后用现成的 `CardDataProvider.fromEffectiveRows(...)` 喂给 `getExcelView` 的 TAB_JOIN 求值分支。

**Tech Stack:** Java 17 + Quarkus 3.23 + Hibernate Panache；Jackson；JUnit 5 / Quarkus Test；后端单测 `./mvnw test`；前端 Playwright E2E `quotation-flow.spec.ts`。

---

## 背景与根因（必读，勿重新调研）

实测报价单 `QT-20260618-1772`（`quotation_id 0a737ed5-33f5-48ab-8ca8-9c8284342e13`，钉模板 v1.4 `17a128e8-7cb6-4f5c-942e-fb0440858a18`，1 个产品行）Excel 视图三列全 0。已查实数据与代码：

- 列定义在 EXCEL 组件副本 `COMP-0035__imp1`（`a8d4198c-5f3b-4a21-85a3-876a3fe2be7d`）的 `excel_columns`：
  - A `材料成本` = `[来料.材料成本]`，`tabs[0].tabKey = "e31bbdd1-1c27-4fd4-a1a5-581b3461ae8b"`（**裸 componentId**）
  - B `损耗成本` = `[来料.材料损耗成本]`
  - C `产品小计` = `[报价小计(总计)]`，`tabs[0].tabKey = "8ccd4a88-69ea-421e-b6a5-999248d5609c"`
- `来料`（`e31bbdd1`，SUBTOTAL? 否，明细组件）的 `row_data` **有真实值**：`材料成本` = 0.0433 / 0.0341（应和 = **0.0774**），`材料损耗成本` = 0 / 0。
- `报价小计`（`8ccd4a88`，`component_type=SUBTOTAL`，`code=COMP-0034__imp1`）`row_data=[]`、`subtotal=0`，其 `formulas[0]`（name=`总成本`）= 4 个 `component_subtotal` 之和：
  `来料.材料成本(COMP-0028__imp1) + 来料.材料损耗成本(COMP-0028__imp1) + 组装加工费.费用(COMP-0038__imp1) + 其他费用.费用(COMP-0031__imp1)`
  = 0.0774 + 0 + 0.08 + 0.067074 ≈ **0.22**（与产品卡 ¥0.22 一致）。

**根因①（tabKey 错配）**：组件级页签由 `ComponentTabDefService.java:66-67` 发**裸 componentId** 作 `tabKey`；运行时 `getExcelView` 走持久化 `CardDataProvider`，key 为 `componentId:sortOrder`，`resolve()` 只认精确 `cid:sort` 或冒号后缀 sortOrder → 裸 UUID 无冒号 → 返回 null → `rowsOf` 空 → 明细列 0。
> 反向修（让 producer 发 `cid:sortOrder`）**错误**：全局组件按目录 code 排序（来料=index 0），但报价单里来料 `sortOrder=1`，两者不一致。稳定键只能是 componentId，故修在 consumer 侧。

**根因②（派生小计从不落库）**：全库 731 条 `quotation_line_component_data`，非零 `subtotal` = 0 条。`subtotalOf()` 读 `d.subtotal` 永远 0。`报价小计` 的值是前端渲染时算的，DB 无；`quote_card_values` 快照虽存在但本草稿为陈旧空值（`来料.subtotalByColumn` 全 0、rows 空）。唯一新鲜真相源 = `row_data`。

**为什么本方案能同时修两者**：把 `row_data` 现算成「有效行 + 列小计 + SUBTOTAL 公式结果」，以**裸 componentId 键**登记进 `fromEffectiveRows`（精确命中，无需 `resolve` 兜底）→ ①解决；SUBTOTAL 总计现算 → ②解决。

---

## File Structure

- **Create** `cpq-backend/src/main/java/com/cpq/quotation/service/card/ComponentDataEffectiveRows.java`
  纯静态计算器（DB-free，便于单测）：`compute(cdList, metaById, fc) -> Map<tabKey, CardEffectiveRows.TabRows>`。职责单一：持久化 componentData → 带小计的有效行，双键登记。
- **Create** `cpq-backend/src/test/java/com/cpq/quotation/card/ComponentDataEffectiveRowsTest.java`
  纯单测：列求和、`code#col`/`name#col` componentSubtotals、SUBTOTAL 公式求值、双键、空/脏数据兜底。
- **Modify** `cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java`
  注入 `FormulaCalculator`；新增私有 `buildTabJoinEffectiveRows(componentDataList)`（加载 `Component` 元数据 + 调 `compute`）；把 `buildRowData` 中 `effectiveRows==null` 分支的 `tabJoinProvider` 从 `new CardDataProvider(componentDataList)` 换成 `fromEffectiveRows(buildTabJoinEffectiveRows(...))`。

> **不改**：`CardDataProvider.java` / `TabJoinPlanEvaluator.java` / `ComponentTabDefService.java` / 任何前端文件 / 任何 8-文件交接清单内的文件。CARD_FORMULA 分支与 `effectiveRows!=null`（预览/试算）分支保持原行为，blast radius 限定在「报价单渲染的 TAB_JOIN 列」。

---

## 执行前置（必须）

- [ ] **P0：起隔离 worktree**。用 `superpowers:using-git-worktrees` 建分支（建议名 `excel-view-tabjoin-subtotal`）。后端编译/测试在该 worktree 的 `cpq-backend/` 内用其 `mvnw` 跑（参见历史记忆 `cpq-worktree-maven-test-tree`：`mvnw` 在 `cpq-backend/` 不在仓库根）。dev server（8081/5174）是共享主树，自检用主树实例。
- [ ] **P0.5：核对实体字段**。`Read` `cpq-backend/src/main/java/com/cpq/component/entity/Component.java`，确认 `formulas`/`code`/`name`/`componentType` 字段名与类型（预期 `formulas` 为 `String`/jsonb、`componentType` 为 `String`）。`Read` `QuotationLineComponentData.java` 确认 `componentId`(UUID)/`sortOrder`(Integer)/`rowData`(String)/`subtotal`(BigDecimal)。若字段名不符，按实际名修正下方代码。

---

## Task 1: 读时计算器 `ComponentDataEffectiveRows`（纯函数，TDD）

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/quotation/service/card/ComponentDataEffectiveRows.java`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/card/ComponentDataEffectiveRowsTest.java`

- [ ] **Step 1: 写失败测试**

`cpq-backend/src/test/java/com/cpq/quotation/card/ComponentDataEffectiveRowsTest.java`：

```java
package com.cpq.quotation.card;

import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.service.FormulaCalculator;
import com.cpq.quotation.service.card.CardEffectiveRows;
import com.cpq.quotation.service.card.ComponentDataEffectiveRows;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ComponentDataEffectiveRowsTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static QuotationLineComponentData cd(String id, int sort, String rowJson) {
        QuotationLineComponentData d = new QuotationLineComponentData();
        d.componentId = UUID.fromString(id);
        d.sortOrder = sort;
        d.rowData = rowJson;
        d.subtotal = BigDecimal.ZERO;
        return d;
    }

    private static com.fasterxml.jackson.databind.JsonNode formulas(String json) {
        try { return M.readTree(json); } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** 明细页签：列求和进 subtotalByColumn，且双键（裸 cid + cid:sort）都命中同一 TabRows。 */
    @Test
    void detailColumnSumsAndDualKey() {
        String LL = "11111111-1111-1111-1111-111111111111";
        var cdList = List.of(cd(LL, 1,
            "[{\"材料成本\":0.0433,\"材料损耗成本\":0},{\"材料成本\":0.0341,\"材料损耗成本\":0}]"));
        Map<UUID, ComponentDataEffectiveRows.Meta> meta = Map.of(
            UUID.fromString(LL), new ComponentDataEffectiveRows.Meta("COMP-0028__imp1", "来料", "DETAIL", null));

        Map<String, CardEffectiveRows.TabRows> out =
            ComponentDataEffectiveRows.compute(cdList, meta, new FormulaCalculator());

        CardEffectiveRows.TabRows byBare = out.get(LL);
        CardEffectiveRows.TabRows bySort = out.get(LL + ":1");
        assertNotNull(byBare, "裸 componentId 必须命中");
        assertSame(byBare, bySort, "双键指向同一 TabRows");
        assertEquals(2, byBare.rows.size());
        assertEquals(0, new BigDecimal("0.0774").compareTo(byBare.subtotalByColumn.get("材料成本")));
        assertEquals(0, BigDecimal.ZERO.compareTo(byBare.subtotalByColumn.get("材料损耗成本")));
    }

    /** SUBTOTAL 组件总计 = 其 component_subtotal 公式求值（跨 tab，code#col 键，含同名列 费用 不串值）。 */
    @Test
    void subtotalComponentFormulaEvaluated() {
        String LL = "11111111-1111-1111-1111-111111111111"; // 来料 COMP-0028
        String ZZ = "22222222-2222-2222-2222-222222222222"; // 组装加工费 COMP-0038
        String QT = "33333333-3333-3333-3333-333333333333"; // 其他费用 COMP-0031
        String ST = "44444444-4444-4444-4444-444444444444"; // 报价小计 COMP-0034 SUBTOTAL

        var cdList = List.of(
            cd(LL, 1, "[{\"材料成本\":0.0433,\"材料损耗成本\":0},{\"材料成本\":0.0341,\"材料损耗成本\":0}]"),
            cd(ZZ, 2, "[{\"费用\":0.08}]"),
            cd(QT, 3, "[{\"费用\":0.067074}]"),
            cd(ST, 8, "[]"));

        String subtotalFormulas = "[{\"name\":\"总成本\",\"expression\":["
            + "{\"type\":\"component_subtotal\",\"value\":\"材料成本\",\"tab_name\":\"材料成本\",\"component_code\":\"COMP-0028__imp1\"},"
            + "{\"type\":\"operator\",\"value\":\"+\"},"
            + "{\"type\":\"component_subtotal\",\"value\":\"材料损耗成本\",\"tab_name\":\"材料损耗成本\",\"component_code\":\"COMP-0028__imp1\"},"
            + "{\"type\":\"operator\",\"value\":\"+\"},"
            + "{\"type\":\"component_subtotal\",\"value\":\"费用\",\"tab_name\":\"费用\",\"component_code\":\"COMP-0038__imp1\"},"
            + "{\"type\":\"operator\",\"value\":\"+\"},"
            + "{\"type\":\"component_subtotal\",\"value\":\"费用\",\"tab_name\":\"费用\",\"component_code\":\"COMP-0031__imp1\"}"
            + "]}]";

        Map<UUID, ComponentDataEffectiveRows.Meta> meta = new HashMap<>();
        meta.put(UUID.fromString(LL), new ComponentDataEffectiveRows.Meta("COMP-0028__imp1", "来料", "DETAIL", null));
        meta.put(UUID.fromString(ZZ), new ComponentDataEffectiveRows.Meta("COMP-0038__imp1", "组装加工费", "DETAIL", null));
        meta.put(UUID.fromString(QT), new ComponentDataEffectiveRows.Meta("COMP-0031__imp1", "其他费用", "DETAIL", null));
        meta.put(UUID.fromString(ST), new ComponentDataEffectiveRows.Meta("COMP-0034__imp1", "报价小计", "SUBTOTAL", formulas(subtotalFormulas)));

        Map<String, CardEffectiveRows.TabRows> out =
            ComponentDataEffectiveRows.compute(cdList, meta, new FormulaCalculator());

        BigDecimal productSubtotal = out.get(ST).subtotal;
        assertNotNull(productSubtotal);
        // 0.0774 + 0 + 0.08 + 0.067074 = 0.224474（FormulaCalculator 四舍五入到 4 位 → 0.2245）
        assertEquals(0, new BigDecimal("0.2245").compareTo(productSubtotal),
            "实际=" + productSubtotal);
    }

    /** 脏/空数据兜底：null rowData、非数值列、缺 meta 都不抛异常。 */
    @Test
    void nullAndDirtyDataSafe() {
        String X = "55555555-5555-5555-5555-555555555555";
        var cdList = new ArrayList<QuotationLineComponentData>();
        cdList.add(cd(X, 0, null));
        cdList.add(cd(X.replace('5','6'), 1, "[{\"料件\":\"H65带\",\"项次\":1,\"加工费\":0.04}]"));
        Map<String, CardEffectiveRows.TabRows> out =
            ComponentDataEffectiveRows.compute(cdList, Map.of(), new FormulaCalculator());
        assertEquals(0, out.get(X).rows.size());
        assertFalse(out.get(X.replace('5','6')).subtotalByColumn.containsKey("料件"));
        assertEquals(0, new BigDecimal("0.04").compareTo(
            out.get(X.replace('5','6')).subtotalByColumn.get("加工费")));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run（worktree 内）：`cd cpq-backend && ./mvnw -q -o test -Dtest=ComponentDataEffectiveRowsTest`
Expected: 编译失败（`ComponentDataEffectiveRows` 不存在）。

- [ ] **Step 3: 实现 `ComponentDataEffectiveRows`**

`cpq-backend/src/main/java/com/cpq/quotation/service/card/ComponentDataEffectiveRows.java`：

```java
package com.cpq.quotation.service.card;

import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.service.FormulaCalculator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.*;

/**
 * 读时计算器：持久化 {@link QuotationLineComponentData}（row_data 为新鲜真相源）→
 * 系统既有「有效行」抽象 {@link CardEffectiveRows.TabRows}{rows, subtotal, subtotalByColumn}。
 *
 * <p>解决两个根因：①Excel 列 tabKey 是裸 componentId，持久化 CardDataProvider.resolve()
 * 解析不到 → 这里以裸 componentId 直接作 key（fromEffectiveRows 精确命中，不依赖 resolve 兜底）；
 * ②小计从不落库 → 这里现算（列求和 + SUBTOTAL 组件 component_subtotal 公式求值）。
 *
 * <p>双键登记：每个页签同时以「裸 componentId」（Excel 列 tabKey 约定）与
 * 「componentId:sortOrder」（CardRef 约定）登记同一 TabRows，兼容两类消费方。
 *
 * <p>DB-free 纯函数（meta 与 FormulaCalculator 由调用方注入），便于单测。
 */
public final class ComponentDataEffectiveRows {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ComponentDataEffectiveRows() {}

    /** 组件元数据（由调用方从 Component 实体加载，保持本类 DB-free）。 */
    public static final class Meta {
        public final String code;
        public final String name;
        public final String componentType;
        /** 组件 formulas JSON 数组（SUBTOTAL 组件求总计用），可为 null。 */
        public final JsonNode formulas;
        public Meta(String code, String name, String componentType, JsonNode formulas) {
            this.code = code;
            this.name = name;
            this.componentType = componentType;
            this.formulas = formulas;
        }
    }

    public static Map<String, CardEffectiveRows.TabRows> compute(
            List<QuotationLineComponentData> cdList,
            Map<UUID, Meta> metaById,
            FormulaCalculator fc) {
        Map<String, CardEffectiveRows.TabRows> out = new LinkedHashMap<>();
        if (cdList == null || cdList.isEmpty()) return out;
        Map<UUID, Meta> metas = metaById != null ? metaById : Map.of();

        // Pass 1：解析行 + 列求和；构建全局 componentSubtotals（code#col 与 name#col，避免同名列 费用 串值）
        List<TabAcc> accs = new ArrayList<>();
        Map<String, Double> componentSubtotals = new HashMap<>();
        for (QuotationLineComponentData cd : cdList) {
            if (cd == null) continue;
            List<Map<String, Object>> rows = parseRows(cd.rowData);
            Map<String, BigDecimal> colSums = columnSums(rows);
            Meta meta = cd.componentId != null ? metas.get(cd.componentId) : null;
            accs.add(new TabAcc(cd, rows, colSums, meta));
            if (meta != null) {
                for (Map.Entry<String, BigDecimal> e : colSums.entrySet()) {
                    double v = e.getValue().doubleValue();
                    if (meta.code != null) componentSubtotals.put(meta.code + "#" + e.getKey(), v);
                    if (meta.name != null) componentSubtotals.put(meta.name + "#" + e.getKey(), v);
                }
            }
        }

        // Pass 2：算 subtotal（SUBTOTAL → 公式求值；其余 → 沿用持久化值）+ 双键装配
        for (TabAcc a : accs) {
            BigDecimal subtotal = a.cd.subtotal;
            if (a.meta != null && "SUBTOTAL".equals(a.meta.componentType)
                    && a.meta.formulas != null && a.meta.formulas.isArray() && a.meta.formulas.size() > 0) {
                JsonNode expr = a.meta.formulas.get(0).path("expression");
                if (expr.isArray() && expr.size() > 0) {
                    FormulaCalculator.RowContext ctx = new FormulaCalculator.RowContext();
                    ctx.componentSubtotals = componentSubtotals;
                    subtotal = fc.evaluateExpression(expr, ctx);
                }
            }
            CardEffectiveRows.TabRows tr =
                new CardEffectiveRows.TabRows(a.rows, subtotal, a.colSums);
            if (a.cd.componentId != null) {
                String cid = a.cd.componentId.toString();
                int sort = a.cd.sortOrder == null ? 0 : a.cd.sortOrder;
                out.put(cid, tr);                       // 裸 componentId（Excel 列 tabKey 约定）
                out.putIfAbsent(cid + ":" + sort, tr);  // componentId:sortOrder（CardRef 约定）
            }
        }
        return out;
    }

    private static List<Map<String, Object>> parseRows(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> r =
                MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            return r != null ? r : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 对每个数值列求和；非数值/空白列跳过（不进结果）。 */
    static Map<String, BigDecimal> columnSums(List<Map<String, Object>> rows) {
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            if (row == null) continue;
            for (Map.Entry<String, Object> e : row.entrySet()) {
                BigDecimal v = toBig(e.getValue());
                if (v != null) sums.merge(e.getKey(), v, BigDecimal::add);
            }
        }
        return sums;
    }

    private static BigDecimal toBig(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return null;
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }

    private static final class TabAcc {
        final QuotationLineComponentData cd;
        final List<Map<String, Object>> rows;
        final Map<String, BigDecimal> colSums;
        final Meta meta;
        TabAcc(QuotationLineComponentData cd, List<Map<String, Object>> rows,
               Map<String, BigDecimal> colSums, Meta meta) {
            this.cd = cd; this.rows = rows; this.colSums = colSums; this.meta = meta;
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run：`cd cpq-backend && ./mvnw -q -o test -Dtest=ComponentDataEffectiveRowsTest`
Expected: 3 个测试 PASS。
> 若 `subtotalComponentFormulaEvaluated` 期望值不符：先 `Read` `FormulaCalculator.java` 的 `case "component_subtotal":`（约 130-165 行）确认 token key 优先级与四舍五入位数，按实际把断言从 `0.2245` 调整为引擎实际产出（数量级必须 ≈0.22；若仍是 0 说明 componentSubtotals 键没对上，是真 bug，需修键格式而非改断言）。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/card/ComponentDataEffectiveRows.java \
        cpq-backend/src/test/java/com/cpq/quotation/card/ComponentDataEffectiveRowsTest.java
git commit -m "feat(excel-view): add read-time effective-rows builder with computed subtotals"
```

---

## Task 2: 接线进 `ExcelViewService.buildRowData`

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java`（注入 + 新私有方法 + 改 356-360 行 tabJoinProvider 分支）

- [ ] **Step 1: 注入 `FormulaCalculator` + 确保 `MAPPER`/`JsonNode` 可用**

在 `ExcelViewService` 字段区（其它 `@Inject` 附近）添加：

```java
    @jakarta.inject.Inject
    com.cpq.quotation.service.FormulaCalculator formulaCalculator;
```

确认类内已有 `ObjectMapper MAPPER`（多数服务有）；若没有，添加：

```java
    private static final com.fasterxml.jackson.databind.ObjectMapper EXVIEW_MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();
```

（下方代码统一用 `EXVIEW_MAPPER`；若已有现成 `MAPPER` 字段，直接复用并把下方 `EXVIEW_MAPPER` 替换为它。）

- [ ] **Step 2: 新增私有方法 `buildTabJoinEffectiveRows`**

放在 `buildRowData(...)` 五参重载后面（约 415 行 `}` 之后）：

```java
    /**
     * 读时：从持久化 componentData 现算 TAB_JOIN 用的有效行（含列小计 + SUBTOTAL 公式总计）。
     * 加载各页签 Component 元数据（code/name/type/formulas）后委托纯计算器。
     */
    private Map<String, com.cpq.quotation.service.card.CardEffectiveRows.TabRows>
            buildTabJoinEffectiveRows(java.util.List<com.cpq.quotation.entity.QuotationLineComponentData> cdList) {
        java.util.Map<java.util.UUID, com.cpq.quotation.service.card.ComponentDataEffectiveRows.Meta> metaById =
            new java.util.HashMap<>();
        for (com.cpq.quotation.entity.QuotationLineComponentData cd : cdList) {
            if (cd.componentId == null || metaById.containsKey(cd.componentId)) continue;
            com.cpq.component.entity.Component c =
                com.cpq.component.entity.Component.findById(cd.componentId);
            if (c == null) continue;
            com.fasterxml.jackson.databind.JsonNode formulas = null;
            try {
                if (c.formulas != null && !c.formulas.isBlank()) {
                    formulas = EXVIEW_MAPPER.readTree(c.formulas);
                }
            } catch (Exception ignore) { /* 公式坏 → 当无公式，subtotal 退回持久化值 */ }
            metaById.put(cd.componentId,
                new com.cpq.quotation.service.card.ComponentDataEffectiveRows.Meta(
                    c.code, c.name, c.componentType, formulas));
        }
        return com.cpq.quotation.service.card.ComponentDataEffectiveRows.compute(
            cdList, metaById, formulaCalculator);
    }
```

> P0.5 已确认：`Component.formulas` 为 `String`、`componentType` 为 `String`。若实体字段名不同，按实际改（如 `c.getFormulas()`）。

- [ ] **Step 3: 改 `tabJoinProvider` 构造分支（当前 356-360 行）**

原：

```java
        // TAB_JOIN_FORMULA：构造 provider（有效行优先，缺省降级持久化 componentData）
        com.cpq.quotation.service.card.CardDataProvider tabJoinProvider =
            (effectiveRows != null)
                ? com.cpq.quotation.service.card.CardDataProvider.fromEffectiveRows(effectiveRows)
                : new com.cpq.quotation.service.card.CardDataProvider(componentDataList);
```

改为：

```java
        // TAB_JOIN_FORMULA：构造 provider。
        // effectiveRows!=null（预览/试算）→ 用传入有效行（原行为）。
        // effectiveRows==null（报价单渲染）→ 从持久化 componentData 现算有效行（含列小计 + SUBTOTAL 公式），
        //   以裸 componentId 键命中 Excel 列 tabKey；修复明细列取数 0 与小计引用 0 两个根因。
        com.cpq.quotation.service.card.CardDataProvider tabJoinProvider =
            (effectiveRows != null)
                ? com.cpq.quotation.service.card.CardDataProvider.fromEffectiveRows(effectiveRows)
                : com.cpq.quotation.service.card.CardDataProvider.fromEffectiveRows(
                      buildTabJoinEffectiveRows(componentDataList));
```

> 仅动 TAB_JOIN 的 provider；CARD_FORMULA 分支（340-354 行）保持不变，限定影响面。

- [ ] **Step 4: 编译**

Run：`cd cpq-backend && ./mvnw -q -o compile`
Expected: exit 0。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java
git commit -m "feat(excel-view): compute TAB_JOIN effective-rows from row_data at render time"
```

---

## Task 3: 集成验证（API + 真实数据 + E2E）

**Files:** 无代码改动；验证 + 出证据。

- [ ] **Step 1: 触发后端热重载**

Run：`touch cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java`（主树或让 dev mode 拾取）；等 5-7 秒。
> dev server 跑共享主树。worktree 改动须先 `cp`/`git apply` 那 2 个改动文件到主树工作区，dev server 才看得到（参见交接文档环境坑）。

- [ ] **Step 2: 后端存活**

Run：`curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/api/cpq/components`
Expected: `401`（鉴权正常，非 500）。

- [ ] **Step 3: 直接验证目标报价单 Excel 视图取值**

用真实 SQL 复核期望值（基线）：

```bash
export PGPASSWORD=joii5231
psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db -A -t -c \
"SELECT '来料材料成本和=' || COALESCE(sum((r->>'材料成本')::numeric),0)
 FROM quotation_line_component_data cd
 JOIN quotation_line_item li ON li.id=cd.line_item_id, jsonb_array_elements(cd.row_data) r
 WHERE li.quotation_id='0a737ed5-33f5-48ab-8ca8-9c8284342e13'
   AND cd.component_id='e31bbdd1-1c27-4fd4-a1a5-581b3461ae8b';"
```
Expected: `来料材料成本和=0.0774`。

再调 Excel 视图端点（需带登录后的 Cookie/Token；若仅能 UI 验证，跳到 Step 4）：
`GET /api/cpq/quotations/0a737ed5-33f5-48ab-8ca8-9c8284342e13/excel-view`
Expected JSON `rows[0]`：A(`材料成本`)≈`0.0774`、B(`损耗成本`)=`0`、C(`产品小计`)≈`0.22`（非全 0）。

- [ ] **Step 4: UI 复测 + 截图**

打开报价单 `QT-20260618-1772` → 添加产品 → Excel 视图。Expected：三列 A=0.0774、B=0、C=0.22。截图存档（修复前全 0 vs 修复后）。

- [ ] **Step 5: 协议级 E2E（动了报价渲染链 `ExcelViewService`，CLAUDE.md 强制）**

Run（主树 `cpq-frontend`）：
```bash
cd cpq-frontend
Remove-Item e2e\screenshots\qf-*.png -ErrorAction SilentlyContinue
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```
Expected: `1 passed`、`'加载中' final count = 0`、全 8 Tab `'加载中'=0`。
> 回归重点：确认既有正常渲染的报价单未因「persistent→effectiveRows」切换而退化（E2E 测试数据见记忆 `cpq-e2e-quotation-flow-test-data`：苏州西门子 + 报价模板0608）。

- [ ] **Step 6: 后端全量单测**

Run：`cd cpq-backend && ./mvnw -q -o test -Dtest='ExcelView*,ComponentDataEffectiveRows*,CardEffectiveRows*'`
Expected: BUILD SUCCESS，无回归。

---

## 收尾（验证全绿后）

- [ ] 追加一条 `docs/RECORD.md`：`[2026-06-19] 报价Excel视图 - TAB_JOIN列全0根因(裸tabKey不可解析+小计不落库)+读时effective-rows修复 | ComponentDataEffectiveRows.java/ExcelViewService.java/+test | 复用fromEffectiveRows双键绕过resolve;SUBTOTAL用FormulaCalculator现算`。
- [ ] 考虑沉淀反模式（AP-xx）：「Excel/TAB_JOIN 读后端时，派生小计必须读时公式驱动，不能读未落库的 subtotal 字段」+「全局组件 tabKey=裸componentId，消费侧须按 componentId 命中」。
- [ ] **提交范围纪律**：本任务**只**提交 3 个文件（`ComponentDataEffectiveRows.java` + 其 test + `ExcelViewService.java`），逐个 `git add`，**严禁 `git add -A`**（主树混有交接文档 8 文件、单位换算、他人 WIP）。本任务与交接文档那 8 个文件**分开提交**。
- [ ] 走 `superpowers:finishing-a-development-branch`：合并回 master → 跑测试 → `git worktree remove` + 删分支 + 删 node_modules 软链。

---

## Self-Review 备注（作者已核）

- **Spec 覆盖**：根因①（裸 tabKey）由「裸 componentId 直接作 key + fromEffectiveRows 精确命中」覆盖（Task1 双键 + Task2 接线）；根因②（小计不落库）由 colSum + SUBTOTAL 公式现算覆盖（Task1）。A/B/C 三列均有对应。
- **同名列串值**：`费用` 同时在 COMP-0038/COMP-0031 → componentSubtotals 用 `code#col` 键消歧（Task1 实现 + 测试 `subtotalComponentFormulaEvaluated` 专测）。
- **类型一致**：`ComponentDataEffectiveRows.Meta`、`compute(...)`、`buildTabJoinEffectiveRows(...)` 签名在 Task1/Task2 一致；`TabRows(rows, subtotal, subtotalByColumn)` 用既有三参构造器。
- **待执行者确认点**：`Component.formulas`/`componentType` 字段名与类型（P0.5）；`FormulaCalculator.evaluateExpression` 的四舍五入位数与 component_subtotal key 优先级（Task1 Step4 备注）。
