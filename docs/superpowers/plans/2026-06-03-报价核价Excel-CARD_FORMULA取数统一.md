# 报价/核价 Excel 视图 CARD_FORMULA 取数统一 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让报价/核价 Excel 视图的 CARD_FORMULA 列取数统一来自"同侧卡片快照的有效合并行"，使 Excel 与卡片所见即所得地同源；核价侧在加产品时算对（冻结不刷），报价侧随草稿重刷/编辑实时同步。

**Architecture:** 后端新增共享工具 `CardEffectiveRows`，把一份卡片值快照（`quote_card_values` / `costing_card_values`）解析成"每页签的有效合并行 + 小计"；`CardDataProvider` 增加 `fromEffectiveRows` 入口供 `CardFormulaEvaluator` 按 `componentId:sortOrder` 精确命中；`ExcelViewService.buildRowData` 计算 CARD_FORMULA 时数据源从「读 `quotation_line_component_data`」切换为「传入的同侧卡片有效行」；`CardSnapshotService` 在算 Excel 值快照时把同侧卡片快照透传进去。前端 Step2 两个 `LinkedExcelView` 改为渲染同侧 Excel 值快照（`quote_excel_values` / `costing_excel_values`），缺快照即显示空/「—」，不再实时回退 `getExcelView`；报价卡片编辑后就地回灌 `quoteExcelValues`。

**Tech Stack:** Java 17 / Quarkus / Hibernate Panache / Jackson / JUnit；React + TypeScript + Ant Design；Playwright E2E；PostgreSQL。

**确认过的需求边界（来自 brainstorming）：**
1. 报价 + 核价两侧都纳入统一逻辑。
2. 仅管页面渲染链路；导出 `exportExcelView` 维持现状不动。
3. 缺快照 → 显示空/「—」，**不降级**回实时 `getExcelView`。
4. 核价快照"加产品写、永久冻结只读"——核价修复落在加产品链路（`snapshotLineValues`），**不**做草稿重刷核价。
5. 存量已冻结的错误核价快照**不回填**，只对今后新加产品生效。
6. effectiveRows = 卡片那行该字段的最终显示值（按字段类型路由）；Excel 引用卡片公式列时取"已算好的数值结果"，不在 Excel 端重算卡片公式。
7. 范围仅 Step2 两个 Excel 视图（`LinkedExcelView` 报价 + 核价），详情/只读/对比/导出不动。
8. 报价卡片编辑单元格后，报价 Excel 视图当场实时刷新。

**关联规范（必读）：**
- 方案：`docs/superpowers/specs/2026-06-03-报价核价Excel-CARD_FORMULA取数统一-design.md`
- `docs/E2E测试方法.md`（ExcelViewService/CardSnapshotService/QuotationStep2 改动强制 E2E）
- `docs/反模式.md` AP-51（driver 行数纪律）、AP-50（渲染层 single-source）
- CLAUDE.md「修改后强制自检」——后端改 `ExcelViewService.java`/`CardSnapshotService.java` + 前端改 `QuotationStep2.tsx`/`LinkedExcelView.tsx` 均在强制 E2E 清单内。

---

## 数据形态速查（实现前必读，后续任务直接引用）

**卡片值快照**（`quote_card_values` / `costing_card_values`，由 `CardSnapshotService.assembleTabsWithFormulaResults` 产出）：

```json
{ "tabs": [
  { "componentId": "b3359f70-...",
    "tabName": "元素",
    "baseRows":  [ { "driverRow": {...}, "basicDataValues": { "类型": "非银点类", "含量": 0.5, ... } }, ... ],
    "editRows":  [ { "rowKey": "k1", "values": { "单价": 12.3, ... } }, ... ],
    "formulaResults": [ { "rowKey": "k1", "values": { "金额": 6.15, ... } }, ... ]
  }
] }
```
- `baseRows` 按位置 i 排列；`editRows`/`formulaResults` 按 `rowKey` 索引。
- 值快照 tab 节点**当前不带 `sortOrder`，也不带 `subtotal`**（本计划 Task 2 补 `subtotal`；`sortOrder` 从 `components_snapshot` 按 `componentId` 取）。

**CARD_FORMULA 列 ref**（`CardRef`）：`{ tab: "componentId:sortOrder", field: "中文字段名" | "__subtotal__" | null(聚合源), mode: FIRST_ROW|ROW_WHERE, cond, cols }`。

**有效合并行**（每行一个 flat map，后者覆盖前者）：
```
effectiveRow[i] = driverRow[i] ∪ basicDataValues[i] ∪ formulaResults(rowKey 匹配).values ∪ editRows(rowKey 匹配).values
```
其中 `rowKey` = `FormulaCalculator.computeRowKey(rowKeyFields, baseRows[i].driverRow)`，空则用位置下标 `String.valueOf(i)`（与 `CardSnapshotService` 既有口径一致）。

**components_snapshot**（`template.components_snapshot`，数组）：每项含 `componentId`、`sortOrder`、`tabName`、`componentCode`。用于把值快照的 `componentId` 映射到 `sortOrder` 以拼 `tabKey`。

---

## File Structure

**后端（新增）：**
- `cpq-backend/src/main/java/com/cpq/quotation/service/card/CardEffectiveRows.java` — 卡片值快照 → `Map<tabKey, TabRows>` 共享解析工具（报价/核价共用，中性命名）。
- `cpq-backend/src/test/java/com/cpq/quotation/service/card/CardEffectiveRowsTest.java` — 解析口径单测。
- `cpq-backend/src/test/java/com/cpq/quotation/service/card/CardDataProviderFromEffectiveRowsTest.java` — `fromEffectiveRows` 精确命中单测。
- `cpq-backend/src/test/java/com/cpq/quotation/service/CardFormulaEvaluatorEffectiveRowsTest.java` — 喂含"类型"行 → A/B/C 求值单测。

**后端（修改）：**
- `cpq-backend/src/main/java/com/cpq/quotation/service/card/CardDataProvider.java` — 加 `TabRows` 内部类 + `fromEffectiveRows(Map)` 静态工厂 + 让 provider 能持有"已解析行/小计"。
- `cpq-backend/src/main/java/com/cpq/quotation/service/CardFormulaEvaluator.java` — `evaluateColumns` 加接受 `CardDataProvider` 的重载。
- `cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java` — `buildRowData`/`buildLineRowData` 加 `effectiveRows` 入参，CARD_FORMULA 分支改用它。
- `cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java` — `assembleTabsWithFormulaResults` 写 per-tab `subtotal`；`buildExcelValues` 加 `cardValuesJson` 入参并透传；`snapshotLineValues`/`refreshQuoteCardValues`/`editCardValue` 三处调用点对齐。

**前端（修改）：**
- `cpq-frontend/src/services/quotationService.ts` — `LineItemResponse` 已有 `quoteExcelValues`/`costingExcelValues`（确认）。
- `cpq-frontend/src/pages/quotation/QuotationStep2.tsx` — `LineItem` 类型加 `quoteExcelValues`/`costingExcelValues`；两个 `LinkedExcelView` 传 `side`；编辑回灌补 `quoteExcelValues`。
- `cpq-frontend/src/pages/quotation/QuotationWizard.tsx` — 映射 `li.quoteExcelValues`/`li.costingExcelValues` 进 LineItem。
- `cpq-frontend/src/pages/quotation/useExcelSnapshotRows.ts` — **新增** hook：解析同侧 Excel 值快照 → `LinkedExcelRow[]`（缺快照→空）。
- `cpq-frontend/src/pages/quotation/LinkedExcelView.tsx` — 新模型 rows 改用 `useExcelSnapshotRows`，加 `side` 入参。

---

## Task 1: 后端共享工具 CardEffectiveRows（卡片值快照 → 有效合并行）

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/quotation/service/card/CardEffectiveRows.java`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/card/CardEffectiveRowsTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.cpq.quotation.service.card;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CardEffectiveRowsTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static final String COMPONENTS_SNAPSHOT = """
        [ { "componentId": "comp-A", "sortOrder": 2, "tabName": "元素" } ]
        """;

    // 一行: driver 给"类型/含量", editRows 给"单价", formulaResults 给"金额"
    private static final String CARD_VALUES = """
        { "tabs": [ {
            "componentId": "comp-A",
            "tabName": "元素",
            "baseRows": [ { "driverRow": {"hf_part_no":"P1"},
                            "basicDataValues": {"类型":"非银点类","含量":0.5} } ],
            "editRows": [ { "rowKey": "0", "values": {"单价":12.3} } ],
            "formulaResults": [ { "rowKey": "0", "values": {"金额":6.15} } ]
        } ] }
        """;

    @Test
    void parsesEffectiveRowsKeyedByComponentIdAndSortOrder() throws Exception {
        JsonNode cardValues = M.readTree(CARD_VALUES);
        JsonNode componentsSnapshot = M.readTree(COMPONENTS_SNAPSHOT);

        Map<String, CardEffectiveRows.TabRows> result =
            CardEffectiveRows.parse(cardValues, componentsSnapshot, (cid) -> null);

        // tabKey = componentId:sortOrder
        CardEffectiveRows.TabRows tab = result.get("comp-A:2");
        assertNotNull(tab, "应按 componentId:sortOrder 建键");
        assertEquals(1, tab.rows.size());

        Map<String, Object> row0 = tab.rows.get(0);
        // 字段路由: 基础字段来自 basicDataValues
        assertEquals("非银点类", row0.get("类型"));
        assertEquals(0.5, ((Number) row0.get("含量")).doubleValue(), 1e-9);
        // 输入字段来自 editRows
        assertEquals(12.3, ((Number) row0.get("单价")).doubleValue(), 1e-9);
        // 公式字段来自 formulaResults（已算好的值，不重算）
        assertEquals(6.15, ((Number) row0.get("金额")).doubleValue(), 1e-9);
        // driver 原始列也并入
        assertEquals("P1", row0.get("hf_part_no"));
    }

    @Test
    void editRowsOverrideBasicAndFormula() throws Exception {
        // editRows 同名字段优先级最高（用户手改盖一切）
        String cv = CARD_VALUES.replace("\"单价\":12.3", "\"单价\":12.3,\"含量\":9.9");
        JsonNode cardValues = M.readTree(cv);
        JsonNode cs = M.readTree(COMPONENTS_SNAPSHOT);

        Map<String, CardEffectiveRows.TabRows> result =
            CardEffectiveRows.parse(cardValues, cs, (cid) -> null);
        Map<String, Object> row0 = result.get("comp-A:2").rows.get(0);
        assertEquals(9.9, ((Number) row0.get("含量")).doubleValue(), 1e-9,
            "editRows 同名字段应覆盖 basicDataValues");
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd cpq-backend && ./mvnw test -Dtest=CardEffectiveRowsTest -q`
Expected: 编译失败 / `CardEffectiveRows` 不存在。

- [ ] **Step 3: 实现 CardEffectiveRows**

```java
package com.cpq.quotation.service.card;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

/**
 * 卡片值快照 → 每页签"有效合并行 + 小计"。报价/核价共用（中性命名，勿加 costing 前缀）。
 *
 * <p>有效行合成口径（与前端 ProductCard / QuotationStep2 从快照构造的有效行逐字段一致）：
 * effectiveRow[i] = driverRow[i] ∪ basicDataValues[i] ∪ formulaResults(rowKey).values ∪ editRows(rowKey).values
 * （后者覆盖前者；editRows = 用户手改，优先级最高；formulaResults = 卡片已算好的公式值，不重算）。
 *
 * <p>tabKey = componentId:sortOrder，sortOrder 从 components_snapshot 按 componentId 取（值快照不含 sortOrder）。
 */
public final class CardEffectiveRows {

    public static final class TabRows {
        public final List<Map<String, Object>> rows;
        public final BigDecimal subtotal; // 值快照 tab.subtotal；缺失为 null
        public TabRows(List<Map<String, Object>> rows, BigDecimal subtotal) {
            this.rows = rows; this.subtotal = subtotal;
        }
    }

    /**
     * @param cardValues        卡片值快照根（{tabs:[...]}）
     * @param componentsSnapshot 模板 components_snapshot 数组（用于 componentId→sortOrder）
     * @param rowKeyFieldsOf    componentId → rowKeyFields JsonNode（用于 rowKey 计算）；可返回 null
     */
    public static Map<String, TabRows> parse(
            JsonNode cardValues, JsonNode componentsSnapshot,
            Function<String, JsonNode> rowKeyFieldsOf) {
        Map<String, TabRows> out = new LinkedHashMap<>();
        if (cardValues == null) return out;

        Map<String, Integer> sortByComp = new HashMap<>();
        if (componentsSnapshot != null && componentsSnapshot.isArray()) {
            for (JsonNode c : componentsSnapshot) {
                String cid = c.path("componentId").asText("");
                if (!cid.isBlank()) sortByComp.put(cid, c.path("sortOrder").asInt(0));
            }
        }

        JsonNode tabs = cardValues.path("tabs");
        if (!tabs.isArray()) return out;

        for (JsonNode tab : tabs) {
            String cid = tab.path("componentId").asText("");
            if (cid.isBlank()) continue;
            int sortOrder = sortByComp.getOrDefault(cid, tab.path("sortOrder").asInt(0));
            String tabKey = cid + ":" + sortOrder;

            JsonNode baseRows = tab.path("baseRows");
            JsonNode rkf = rowKeyFieldsOf != null ? rowKeyFieldsOf.apply(cid) : null;

            // editRows / formulaResults 按 rowKey 建索引
            Map<String, JsonNode> editByKey = indexByRowKey(tab.path("editRows"));
            Map<String, JsonNode> formulaByKey = indexByRowKey(tab.path("formulaResults"));

            List<Map<String, Object>> rows = new ArrayList<>();
            int i = 0;
            if (baseRows.isArray()) {
                for (JsonNode br : baseRows) {
                    JsonNode driverRow = br.path("driverRow");
                    String rowKey = computeRowKey(rkf, driverRow, i);

                    Map<String, Object> row = new LinkedHashMap<>();
                    putAll(row, driverRow);                       // 1. driver 原始列
                    putAll(row, br.path("basicDataValues"));      // 2. 基础/DATA_SOURCE 值（含"类型"）
                    putAll(row, valuesOf(formulaByKey.get(rowKey)));// 3. 卡片已算好的公式值
                    putAll(row, valuesOf(editByKey.get(rowKey)));  // 4. 用户手改（最高优先级）
                    rows.add(row);
                    i++;
                }
            }

            BigDecimal subtotal = tab.has("subtotal") && !tab.path("subtotal").isNull()
                ? tab.path("subtotal").decimalValue() : null;
            out.put(tabKey, new TabRows(rows, subtotal));
        }
        return out;
    }

    private static Map<String, JsonNode> indexByRowKey(JsonNode arr) {
        Map<String, JsonNode> m = new HashMap<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) m.put(n.path("rowKey").asText(""), n);
        }
        return m;
    }

    private static JsonNode valuesOf(JsonNode rowNode) {
        return rowNode == null ? null : rowNode.path("values");
    }

    private static void putAll(Map<String, Object> target, JsonNode obj) {
        if (obj == null || !obj.isObject()) return;
        obj.fields().forEachRemaining(e -> target.put(e.getKey(), jsonToJava(e.getValue())));
    }

    private static Object jsonToJava(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) return null;
        if (v.isNumber()) return v.numberValue();
        if (v.isBoolean()) return v.booleanValue();
        return v.asText();
    }

    /** rowKey: 优先按 rowKeyFields 从 driverRow 拼；无 rkf → 位置下标。与 CardSnapshotService 口径一致。 */
    private static String computeRowKey(JsonNode rkf, JsonNode driverRow, int idx) {
        if (rkf != null && rkf.isArray() && rkf.size() > 0 && driverRow != null && driverRow.isObject()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode f : rkf) {
                String fn = f.asText("");
                if (!fn.isEmpty()) sb.append(driverRow.path(fn).asText("")).append("|");
            }
            String k = sb.toString();
            if (!k.isBlank() && !k.replace("|", "").isBlank()) return k;
        }
        return String.valueOf(idx);
    }

    private CardEffectiveRows() {}
}
```

> 注：测试用例里 `rowKeyFieldsOf=(cid)->null` 走位置下标分支（editRows/formulaResults rowKey="0" ↔ 第 0 行）。真实调用方会注入 `componentId → component.row_key_fields`。

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd cpq-backend && ./mvnw test -Dtest=CardEffectiveRowsTest -q`
Expected: PASS（2 个用例）。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/card/CardEffectiveRows.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/card/CardEffectiveRowsTest.java
git commit -m "feat(excel): 加 CardEffectiveRows 共享工具(卡片快照→有效合并行)"
```

---

## Task 2: 值快照写入 per-tab subtotal（让 CARD_FORMULA 小计引用可取）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java`（`assembleTabsWithFormulaResults`，约 line 576-630）
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/CardSnapshotSubtotalTest.java`

**背景**：`assembleTabsWithFormulaResults` PASS 1 已算出每 NORMAL tab 的 `sub`（存入 `componentSubtotals`），但 PASS 2 组装 tab 节点时**没把 subtotal 写进值快照**。Task 1 的 `CardEffectiveRows.parse` 读 `tab.subtotal`，需要这里补写。

- [ ] **Step 1: 写失败测试**

```java
package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CardSnapshotSubtotalTest {

    @Inject CardSnapshotService svc;
    private static final ObjectMapper M = new ObjectMapper();

    // 最小 components_snapshot：1 个 NORMAL tab，含一个 is_subtotal 字段或可算小计的公式
    private static final String SNAPSHOT = """
        [ { "componentId":"c1", "componentCode":"C1", "tabName":"投料",
            "componentType":"NORMAL", "sortOrder":1,
            "fields":[ {"name":"金额","field_type":"INPUT_NUMBER","is_subtotal":true,"sort_order":1} ],
            "formulas":[], "formula_assignments":[] } ]
        """;

    @Test
    void assembledTabCarriesSubtotal() throws Exception {
        JsonNode snapshot = M.readTree(SNAPSHOT);
        var baseRowsByComp = new java.util.LinkedHashMap<String, com.fasterxml.jackson.databind.node.ArrayNode>();
        var baseRows = M.createArrayNode();
        var r = M.createObjectNode();
        r.set("driverRow", M.createObjectNode());
        var bdv = M.createObjectNode(); bdv.put("金额", 7); r.set("basicDataValues", bdv);
        baseRows.add(r);
        baseRowsByComp.put("c1", baseRows);

        // 反射/包级调用 assembleTabsWithFormulaResults（同包测试可直接访问 package-private；若 private 则改测 buildCostingCardValues 落库后的 JSON）
        JsonNode root = M.readTree(
            svc.assembleTabsWithFormulaResultsForTest(snapshot, baseRowsByComp, null));
        JsonNode tab0 = root.path("tabs").get(0);
        assertTrue(tab0.has("subtotal"), "值快照 tab 应带 subtotal");
    }
}
```

> 实现注：`assembleTabsWithFormulaResults` 现为 `private`。为可测，新增 package-private 薄包装 `String assembleTabsWithFormulaResultsForTest(...)`（返回 `MAPPER.writeValueAsString(root)`）或直接把该方法可见性放宽到 package-private 并让测试同包。二选一，测试与实现同包 `com.cpq.quotation.service`。

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd cpq-backend && ./mvnw test -Dtest=CardSnapshotSubtotalTest -q`
Expected: FAIL（`subtotal` 字段不存在 / 测试包装方法不存在）。

- [ ] **Step 3: 在 PASS 2 写 subtotal + 加测试包装**

在 `assembleTabsWithFormulaResults` 的 PASS 2 循环里（设置 `baseRows`/`editRows`/`formulaResults` 之后）加：

```java
            // 值快照带上本 tab 小计（供 Excel CARD_FORMULA 的 __subtotal__ 引用，见 CardEffectiveRows）
            String code = tab.path("componentCode").asText(null);
            Double sub = componentSubtotals.get(cid);
            if (sub == null && code != null) sub = componentSubtotals.get(code);
            if (sub == null) sub = componentSubtotals.get(tab.path("tabName").asText(""));
            if (sub != null) tabNode.put("subtotal", sub);
```

在类内加测试包装（package-private）：

```java
    /** 仅供单测：暴露 assembleTabsWithFormulaResults 的 JSON 结果。 */
    String assembleTabsWithFormulaResultsForTest(JsonNode snapshot,
            java.util.Map<String, com.fasterxml.jackson.databind.node.ArrayNode> baseRowsByComp,
            java.util.Map<String, com.fasterxml.jackson.databind.node.ArrayNode> editRowsByComp) throws Exception {
        return MAPPER.writeValueAsString(
            assembleTabsWithFormulaResults(snapshot, baseRowsByComp, editRowsByComp));
    }
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd cpq-backend && ./mvnw test -Dtest=CardSnapshotSubtotalTest -q`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/CardSnapshotSubtotalTest.java
git commit -m "feat(snapshot): 卡片值快照 tab 写入 subtotal(供 Excel CARD_FORMULA 小计引用)"
```

---

## Task 3: CardDataProvider.fromEffectiveRows + CardFormulaEvaluator 重载

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/card/CardDataProvider.java`
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/CardFormulaEvaluator.java`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/card/CardDataProviderFromEffectiveRowsTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.cpq.quotation.service.card;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CardDataProviderFromEffectiveRowsTest {

    @Test
    void exactTabKeyHitNoSortFallback() {
        Map<String, CardEffectiveRows.TabRows> eff = new HashMap<>();
        eff.put("b3359f70:2", new CardEffectiveRows.TabRows(
            List.of(Map.of("类型", "非银点类", "含量", 0.5)),
            new BigDecimal("0.5")));

        CardDataProvider p = CardDataProvider.fromEffectiveRows(eff);

        assertTrue(p.hasTab("b3359f70:2"));
        assertEquals("非银点类", p.rowsOf("b3359f70:2").get(0).get("类型"));
        assertEquals(0, new BigDecimal("0.5").compareTo(p.subtotalOf("b3359f70:2")));
        // 精确命中，不做 sortOrder 回退：错 componentId 同 sortOrder 不应命中
        assertFalse(p.hasTab("WRONG:2"));
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd cpq-backend && ./mvnw test -Dtest=CardDataProviderFromEffectiveRowsTest -q`
Expected: FAIL（`fromEffectiveRows` 不存在）。

- [ ] **Step 3: 改 CardDataProvider 支持"已解析行"模式**

在 `CardDataProvider` 加一条并行存储 + 工厂（不破坏既有 `QuotationLineComponentData` 构造）：

```java
    // 已解析有效行模式（fromEffectiveRows）：直接持有 rows/subtotal，旁路 rowData 反序列化。
    private Map<String, List<Map<String, Object>>> effRows;
    private Map<String, BigDecimal> effSubtotal;

    private CardDataProvider() {} // 供 fromEffectiveRows 用

    /** 从 CardEffectiveRows 解析结果构造：精确 tabKey 命中，不做 sortOrder 回退。 */
    public static CardDataProvider fromEffectiveRows(
            Map<String, com.cpq.quotation.service.card.CardEffectiveRows.TabRows> eff) {
        CardDataProvider p = new CardDataProvider();
        p.effRows = new HashMap<>();
        p.effSubtotal = new HashMap<>();
        if (eff != null) {
            for (var e : eff.entrySet()) {
                p.effRows.put(e.getKey(), e.getValue().rows != null ? e.getValue().rows : List.of());
                p.effSubtotal.put(e.getKey(), e.getValue().subtotal);
            }
        }
        return p;
    }
```

并改 `rowsOf` / `subtotalOf` / `hasTab`：effRows 模式下精确命中、不回退：

```java
    public List<Map<String, Object>> rowsOf(String tabKey) {
        if (effRows != null) return effRows.getOrDefault(tabKey, List.of());
        QuotationLineComponentData d = resolve(tabKey);
        if (d == null || d.rowData == null || d.rowData.isBlank()) return List.of();
        try {
            List<Map<String, Object>> rows =
                MAPPER.readValue(d.rowData, new TypeReference<List<Map<String, Object>>>() {});
            return rows != null ? rows : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    public BigDecimal subtotalOf(String tabKey) {
        if (effSubtotal != null) return effSubtotal.get(tabKey);
        QuotationLineComponentData d = resolve(tabKey);
        return d == null ? null : d.subtotal;
    }

    public boolean hasTab(String tabKey) {
        if (effRows != null) return effRows.containsKey(tabKey);
        return resolve(tabKey) != null;
    }
```

（保留既有 `CardDataProvider(List<QuotationLineComponentData>)` 构造 + `byTab`/`bySort`/`resolve` 不动。）

- [ ] **Step 4: CardFormulaEvaluator 加接受 provider 的重载**

在 `CardFormulaEvaluator` 把现有 `evaluateColumns(columns, tabs, ...)` 抽出 provider 后的核心逻辑，新增重载：

```java
    /** 重载：直接吃已构造好的 provider（来自 CardEffectiveRows，精确命中）。 */
    public Map<String, Object> evaluateColumns(
            List<Map<String, Object>> columns,
            CardDataProvider provider,
            UUID customerId, String partNo, UUID quotationId) {
        return evaluateColumnsInternal(columns, provider, customerId, partNo, quotationId);
    }

    // 既有 public 方法改为构造 provider 后委托：
    public Map<String, Object> evaluateColumns(
            List<Map<String, Object>> columns,
            List<QuotationLineComponentData> tabs,
            UUID customerId, String partNo, UUID quotationId) {
        return evaluateColumnsInternal(columns, new CardDataProvider(tabs), customerId, partNo, quotationId);
    }
```

把原 `evaluateColumns(... tabs ...)` 方法体（从 `Map<String,String> formulaByCol ...` 开始）整体移进 `private Map<String,Object> evaluateColumnsInternal(List<Map<String,Object>> columns, CardDataProvider provider, UUID customerId, String partNo, UUID quotationId)`，删掉其内部 `CardDataProvider provider = new CardDataProvider(tabs);` 那一行（改由参数传入）。

- [ ] **Step 5: 运行测试，确认通过**

Run: `cd cpq-backend && ./mvnw test -Dtest=CardDataProviderFromEffectiveRowsTest -q`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/card/CardDataProvider.java \
        cpq-backend/src/main/java/com/cpq/quotation/service/CardFormulaEvaluator.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/card/CardDataProviderFromEffectiveRowsTest.java
git commit -m "feat(excel): CardDataProvider.fromEffectiveRows + Evaluator provider 重载(精确命中)"
```

---

## Task 4: ExcelViewService 取数源切换（CARD_FORMULA 用传入的有效行）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/CardFormulaEvaluatorEffectiveRowsTest.java`

**目标**：`buildRowData`/`buildLineRowData` 接受可选 `Map<tabKey, TabRows> effectiveRows`；非空时 CARD_FORMULA 走 `CardDataProvider.fromEffectiveRows`，空时维持旧路径（读 `quotation_line_component_data`，兼容导出/旧调用）。

- [ ] **Step 1: 写端到端求值单测（喂含"类型"行 → A/B/C）**

```java
package com.cpq.quotation.service;

import com.cpq.quotation.service.card.CardDataProvider;
import com.cpq.quotation.service.card.CardEffectiveRows;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** A=含量小计、B=条件聚合(类型=='非银点类' 的 含量+单价)、C=A+B，验证取数源=有效行时全部出值。 */
@QuarkusTest
class CardFormulaEvaluatorEffectiveRowsTest {

    @Inject CardFormulaEvaluator evaluator;

    @Test
    void cardFormulaHitsTypeColumnFromEffectiveRows() {
        // 两行：一行"非银点类"，一行"银点类"
        Map<String, CardEffectiveRows.TabRows> eff = new HashMap<>();
        eff.put("compE:2", new CardEffectiveRows.TabRows(List.of(
            new LinkedHashMap<>(Map.of("类型", "非银点类", "含量", 200, "单价", 75)),
            new LinkedHashMap<>(Map.of("类型", "银点类",   "含量", 10,  "单价", 5))
        ), null));
        CardDataProvider provider = CardDataProvider.fromEffectiveRows(eff);

        // B 列：SUM_OVER([元素] WHERE 类型=='非银点类', 含量+单价)
        Map<String, Object> bRefs = Map.of("元素", Map.of(
            "tab", "compE:2", "isAggregateSource", true,
            "cols", Map.of("c0", "类型", "c1", "含量", "c2", "单价")));
        Map<String, Object> colB = new LinkedHashMap<>();
        colB.put("col_key", "B");
        colB.put("source_type", "CARD_FORMULA");
        colB.put("formula", "SUM_OVER([元素] WHERE c0=='非银点类', c1+c2)");
        colB.put("refs", bRefs);

        Map<String, Object> out = evaluator.evaluateColumns(
            List.of(colB), provider, null, "P1", null);

        // 仅"非银点类"行计入：200+75 = 275
        assertEquals(0, new java.math.BigDecimal("275").compareTo(
            new java.math.BigDecimal(out.get("B").toString())));
    }
}
```

> 注：B 列 refs/公式按现网 CARD_FORMULA 语法填写（参照模板「核价模板0603」实际配置）。若现网聚合 refs 形态与此处不同，以 `CardRef`/`CardAggregateSource` 实际解析为准微调本测试，但断言值 275 不变。

- [ ] **Step 2: 运行测试，确认失败或通过**

Run: `cd cpq-backend && ./mvnw test -Dtest=CardFormulaEvaluatorEffectiveRowsTest -q`
Expected: 若 Task 3 已就绪，本测试应直接 PASS（它只验证 evaluator+provider，不依赖 ExcelViewService）。**先跑，确认 evaluator 链路正确**，再做下面 ExcelViewService 接线。

- [ ] **Step 3: ExcelViewService.buildRowData 加 effectiveRows 入参**

改 `buildRowData` 签名，新增末位参数 `Map<String, CardEffectiveRows.TabRows> effectiveRows`：

```java
    private Map<String, Object> buildRowData(QuotationLineItem li,
                                              List<Map<String, Object>> columns,
                                              UUID templateId,
                                              Map<String, TemplateFormulaDTO> formulaByName,
                                              UUID quotationCustomerId,
                                              Map<String, com.cpq.quotation.service.card.CardEffectiveRows.TabRows> effectiveRows) {
```

CARD_FORMULA 分支（原 line 219-227）改为：

```java
        Map<String, Object> cardFormulaValues = java.util.Collections.emptyMap();
        List<Map<String, Object>> cardCols = new ArrayList<>();
        for (Map<String, Object> col : columns)
            if ("CARD_FORMULA".equals(col.get("source_type"))) cardCols.add(col);
        if (!cardCols.isEmpty()) {
            if (effectiveRows != null) {
                // 统一取数源：同侧卡片快照有效行（精确 componentId:sortOrder 命中）
                com.cpq.quotation.service.card.CardDataProvider provider =
                    com.cpq.quotation.service.card.CardDataProvider.fromEffectiveRows(effectiveRows);
                cardFormulaValues = cardFormulaEvaluator.evaluateColumns(
                    cardCols, provider, customerId, partNo, null);
            } else {
                // 兼容降级：旧路径读 quotation_line_component_data（导出/旧调用/快照缺失）
                cardFormulaValues = cardFormulaEvaluator.evaluateColumns(
                    cardCols, componentDataList, customerId, partNo, null);
            }
        }
```

把所有现有 `buildRowData(...)` 调用点补传 `effectiveRows`：
- `getExcelView`（line 125）→ 传 `null`（实时路径维持旧行为；本计划前端已不再调它，但保留兼容）。
- `dryRun`（line 158）→ 传 `null`。
- `regenerateAllSnapshots`（line 317）→ 传 `null`。
- 旧兼容重载 `buildRowData(li, columns)`（line 280-282）→ 调用时传 `null`。
- `buildLineRowData`（line 182）→ 见 Step 4 透传。

- [ ] **Step 4: buildLineRowData 加 cardValues 重载**

```java
    /** 既有签名保留（cardValues 缺省→旧路径）。 */
    public Map<String, Object> buildLineRowData(QuotationLineItem li, UUID templateId, UUID customerId) {
        return buildLineRowData(li, templateId, customerId, (String) null);
    }

    /**
     * 新重载：传同侧卡片值快照 JSON → 解析有效行 → CARD_FORMULA 用它取数。
     * cardValuesJson 为空/解析失败 → effectiveRows=null → 降级旧路径。
     */
    public Map<String, Object> buildLineRowData(QuotationLineItem li, UUID templateId,
                                                UUID customerId, String cardValuesJson) {
        if (li == null || templateId == null) return new LinkedHashMap<>();
        try {
            Template template = Template.findById(templateId);
            if (template == null || template.excelViewConfig == null || template.excelViewConfig.isBlank())
                return new LinkedHashMap<>();
            List<Map<String, Object>> columns = parseJsonArray(template.excelViewConfig);
            if (columns.isEmpty()) return new LinkedHashMap<>();
            List<TemplateFormulaDTO> templateFormulas = templateFormulaService.listByTemplate(templateId);
            Map<String, TemplateFormulaDTO> formulaByName = new LinkedHashMap<>();
            for (TemplateFormulaDTO f : templateFormulas) formulaByName.put(f.name, f);

            Map<String, com.cpq.quotation.service.card.CardEffectiveRows.TabRows> eff =
                parseEffectiveRows(cardValuesJson, templateId);
            return buildRowData(li, columns, templateId, formulaByName, customerId, eff);
        } catch (Exception e) {
            LOG.warnf("[ExcelView] buildLineRowData failed li=%s tmpl=%s: %s", li.id, templateId, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /** 解析卡片值快照 → 有效行 Map；空/异常 → null（降级旧路径）。 */
    private Map<String, com.cpq.quotation.service.card.CardEffectiveRows.TabRows>
            parseEffectiveRows(String cardValuesJson, UUID templateId) {
        if (cardValuesJson == null || cardValuesJson.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode cardValues = MAPPER.readTree(cardValuesJson);
            // components_snapshot（componentId→sortOrder）
            @SuppressWarnings("unchecked")
            var rows = (List<Object>) (List<?>) jakarta.persistence.Persistence.class != null
                ? null : null; // 占位删除，见下
            return null;
        } catch (Exception e) {
            return null;
        }
    }
```

> ⚠️ 上面 `parseEffectiveRows` 的 components_snapshot 取数有占位，**必须**改为真实实现：`ExcelViewService` 无 `EntityManager` 注入，改为直接 `Template.findById(templateId)` 读 `components_snapshot` 字段（`Template` 实体上的 `componentsSnapshot` 列），再调 `CardEffectiveRows.parse`。rowKeyFields 用 `(cid)->null`（Excel 取数对位置下标足够；如需精确 rowKey 对齐，可后续注入 component 查询）。落地实现：

```java
    private Map<String, com.cpq.quotation.service.card.CardEffectiveRows.TabRows>
            parseEffectiveRows(String cardValuesJson, UUID templateId) {
        if (cardValuesJson == null || cardValuesJson.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode cardValues = MAPPER.readTree(cardValuesJson);
            Template t = Template.findById(templateId);
            com.fasterxml.jackson.databind.JsonNode componentsSnapshot =
                (t != null && t.componentsSnapshot != null)
                    ? MAPPER.readTree(t.componentsSnapshot) : null;
            return com.cpq.quotation.service.card.CardEffectiveRows.parse(
                cardValues, componentsSnapshot, (cid) -> null);
        } catch (Exception e) {
            LOG.debugf("[ExcelView] parseEffectiveRows failed tmpl=%s: %s", templateId, e.getMessage());
            return null;
        }
    }
```

> 确认 `Template` 实体有 `componentsSnapshot` 字段（snake `components_snapshot`）。若字段名不同，grep `components_snapshot` on `Template.java` 校正。

- [ ] **Step 5: 编译 + 跑相关单测**

Run: `cd cpq-backend && ./mvnw -q -DskipTests compile && ./mvnw test -Dtest=CardFormulaEvaluatorEffectiveRowsTest,CardEffectiveRowsTest,CardDataProviderFromEffectiveRowsTest -q`
Expected: 编译 0 错误；3 个测试类全 PASS。

- [ ] **Step 6: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/CardFormulaEvaluatorEffectiveRowsTest.java
git commit -m "feat(excel): buildRowData CARD_FORMULA 取数改用同侧卡片有效行(降级保旧路径)"
```

---

## Task 5: CardSnapshotService 透传同侧卡片快照给 Excel 值

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java`（`buildExcelValues` line 505、`snapshotLineValues` line 376/385、`refreshQuoteCardValues` line 810、`editCardValue` line 913）

- [ ] **Step 1: buildExcelValues 加 cardValuesJson 入参**

```java
    /** 既有签名保留（无卡片快照→旧路径）。 */
    String buildExcelValues(QuotationLineItem li, UUID templateId, UUID customerId) {
        return buildExcelValues(li, templateId, customerId, null);
    }

    /** 新重载：把同侧卡片值快照透传给 ExcelViewService，CARD_FORMULA 用同侧有效行取数。 */
    String buildExcelValues(QuotationLineItem li, UUID templateId, UUID customerId, String cardValuesJson) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode rowsNode = root.putArray("rows");
            if (li == null || templateId == null) return MAPPER.writeValueAsString(root);

            Map<String, Object> rowData = excelViewService.buildLineRowData(li, templateId, customerId, cardValuesJson);
            if (rowData != null && !rowData.isEmpty()) {
                rowsNode.add(MAPPER.valueToTree(rowData));
            }
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] buildExcelValues failed li=%s tmpl=%s: %s",
                li != null ? li.id : "null", templateId, e.getMessage());
            try {
                ObjectNode root = MAPPER.createObjectNode();
                root.putArray("rows");
                return MAPPER.writeValueAsString(root);
            } catch (Exception ex) { return null; }
        }
    }
```

- [ ] **Step 2: snapshotLineValues 两侧对齐（line 372-386）**

```java
            // 报价侧：先算卡片值，再用它喂报价 Excel
            managed.quoteCardValues = safeCall(() ->
                buildCardValues(managed, q.customerTemplateId));
            managed.quoteExcelValues = safeCall(() ->
                buildExcelValues(managed, q.customerTemplateId, q.customerId, managed.quoteCardValues));

            // 核价侧：先算核价卡片值（含"类型"），再用它喂核价 Excel —— 核价 Bug 在此处修对（加产品冻结）
            if (q.costingCardTemplateId != null) {
                managed.costingCardValues = safeCall(() ->
                    buildCostingCardValues(managed, q.costingCardTemplateId, q.customerId, q.id));
                managed.costingExcelValues = safeCall(() ->
                    buildExcelValues(managed, q.costingCardTemplateId, q.customerId, managed.costingCardValues));
            }
```

> 顺序保证：`quoteCardValues`/`costingCardValues` 在各自 Excel 计算前已赋值（`safeCall` 同步返回）。

- [ ] **Step 3: refreshQuoteCardValues 报价 Excel 重算用新卡片值（line 809-811）**

```java
            // 4. 重算报价 Excel（用刚算好的 quoteCardValues 有效行；核价不动）
            String excel = safeCall(() ->
                buildExcelValues(managed, q.customerTemplateId, q.customerId, managed.quoteCardValues));
            if (excel != null) managed.quoteExcelValues = excel;
```

- [ ] **Step 4: editCardValue 报价 Excel 重算用新卡片值（line 912-914）**

```java
            // 重算报价 Excel（用本次编辑后的 quoteCardValues；核价不动）
            String excel = safeCall(() ->
                buildExcelValues(li, q.customerTemplateId, q.customerId, li.quoteCardValues));
            if (excel != null) li.quoteExcelValues = excel;
```

> `li.quoteCardValues` 已在本方法上方（line 910）赋为新值，故此处取到的是编辑后的卡片快照。

- [ ] **Step 5: 编译 + 重启 Quarkus + 健康检查**

Run:
```bash
cd cpq-backend && ./mvnw -q -DskipTests compile
# touch 触发 dev mode 重启
touch src/main/java/com/cpq/quotation/service/CardSnapshotService.java
sleep 7
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health
```
Expected: 编译 0 错误；health = 200。

- [ ] **Step 6: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java
git commit -m "feat(snapshot): Excel 值快照透传同侧卡片快照(核价加产品时取数取对/报价编辑同源)"
```

---

## Task 6: 前端 LineItem 携带 Excel 值快照 + 新 hook 读快照

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（`LineItem` 类型，约 line 161-162 附近）
- Modify: `cpq-frontend/src/pages/quotation/QuotationWizard.tsx`（line 293-294 映射处）
- Create: `cpq-frontend/src/pages/quotation/useExcelSnapshotRows.ts`

- [ ] **Step 1: LineItem 类型加 Excel 快照字段**

`QuotationStep2.tsx` 的 `LineItem` interface（`quoteCardValues`/`costingCardValues` 旁）加：

```ts
  quoteCardValues?: string;
  costingCardValues?: string;
  quoteExcelValues?: string;
  costingExcelValues?: string;
```

- [ ] **Step 2: QuotationWizard 映射 Excel 快照**

`QuotationWizard.tsx`（line 293-294 处，`quoteCardValues`/`costingCardValues` 旁）加：

```ts
        quoteCardValues: li.quoteCardValues ?? undefined,
        costingCardValues: li.costingCardValues ?? undefined,
        quoteExcelValues: li.quoteExcelValues ?? undefined,
        costingExcelValues: li.costingExcelValues ?? undefined,
```

- [ ] **Step 3: 新建 useExcelSnapshotRows hook**

```ts
/**
 * useExcelSnapshotRows —— 从同侧 Excel 值快照(quoteExcelValues/costingExcelValues)解析渲染行。
 *
 * 与卡片视图读 quote/costing card values 同构：后端已在加产品/草稿重刷/编辑时算好 Excel 值快照，
 * 前端只渲染快照。缺快照 → 该行无值(显示"—")，不回退实时 getExcelView（设计：渲染纯走快照）。
 */
import { useMemo } from 'react';
import type { CostingTemplateColumn } from '../../services/costingTemplateService';
import type { LinkedExcelRow } from './useLinkedExcelRows';
import type { LineItem } from './QuotationStep2';

export interface UseExcelSnapshotRowsParams {
  lineItems: LineItem[];
  side: 'QUOTE' | 'COSTING';
  parsedColumns: CostingTemplateColumn[];
}

function normalize(v: any): any {
  if (v === null || v === undefined || v === '' || v === '—') return null;
  return v;
}

export function useExcelSnapshotRows(params: UseExcelSnapshotRowsParams): { rows: LinkedExcelRow[] } {
  const { lineItems, side, parsedColumns } = params;

  const rows = useMemo<LinkedExcelRow[]>(() => {
    return (lineItems || []).map((li, i) => {
      const json = side === 'QUOTE' ? li.quoteExcelValues : li.costingExcelValues;
      let cellMap: Record<string, any> = {};
      if (json) {
        try {
          const parsed = JSON.parse(json);
          const arr = Array.isArray(parsed?.rows) ? parsed.rows : [];
          // 每 lineItem 一行（buildExcelValues 产出单行 rows[0]）
          if (arr.length > 0 && arr[0] && typeof arr[0] === 'object') cellMap = arr[0];
        } catch { /* 缺快照/解析失败 → 空 → 显示"—" */ }
      }
      const hfPartNo = li.productPartNo;
      const cellValues: Record<string, any> = {};
      for (const col of parsedColumns) cellValues[col.col_key] = normalize(cellMap[col.col_key]);
      return {
        __key: li.id ? `snap-${side}-${li.id}` : `snap-${side}-row-${i}`,
        __label: hfPartNo ?? `产品 ${i + 1}`,
        __hfPartNo: hfPartNo,
        __noData: !json,
        ...cellValues,
      };
    });
  }, [lineItems, side, parsedColumns]);

  return { rows };
}
```

- [ ] **Step 4: TS 编译 + Vite 200 自检**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/useExcelSnapshotRows.ts
```
Expected: TS 0 错误；Vite 200。

- [ ] **Step 5: 提交**

```bash
git add cpq-frontend/src/pages/quotation/useExcelSnapshotRows.ts \
        cpq-frontend/src/pages/quotation/QuotationStep2.tsx \
        cpq-frontend/src/pages/quotation/QuotationWizard.tsx
git commit -m "feat(excel-fe): LineItem 携带 Excel 值快照 + useExcelSnapshotRows(纯快照渲染)"
```

---

## Task 7: LinkedExcelView 新模型改读快照 + Step2 传 side

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/LinkedExcelView.tsx`
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（两个 `LinkedExcelView` 调用点 line 2251 报价 / line 2311 核价）

- [ ] **Step 1: LinkedExcelView 加 side 入参 + 新模型 rows 用快照 hook**

`Props` 加：

```ts
  /** 本视图侧：QUOTE=报价 Excel、COSTING=核价 Excel；新模型下据此读对应 Excel 值快照 */
  side?: 'QUOTE' | 'COSTING';
```

import 替换 `useBackendExcelRows` → `useExcelSnapshotRows`：

```ts
import { useExcelSnapshotRows } from './useExcelSnapshotRows';
```

把 `backendResult`（line 83-88）替换为：

```ts
  const snapshotResult = useExcelSnapshotRows({
    lineItems,
    side: side ?? 'QUOTE',
    parsedColumns: legacyColumns,
  });
```

新模型分支（line 96-102）改为：

```ts
  } = useBackend
    ? {
        rows: snapshotResult.rows,
        parsedColumns: legacyColumns,
        loading: legacyResult.loading,   // 列结构仍由旧 hook 加载；rows 来自快照(同步)
        error: legacyResult.error,
      }
    : {
```

> 删除原 `backendResult` 相关代码与 `useBackendExcelRows` import（`useBackendExcelRows.ts` 文件保留不删，仍被其它路径/未来兼容引用；仅本视图不再用）。grep 确认无其它引用后再决定是否删文件——本计划**不删** `useBackendExcelRows.ts`。

- [ ] **Step 2: Step2 两个调用点传 side**

报价 Excel 视图（line 2251 附近，`viewLabel` 为报价/客户 Excel 的那个）加 `side="QUOTE"`；核价 Excel 视图（line 2311 附近，`templateId` 为核价模板的那个）加 `side="COSTING"`。

```tsx
        <LinkedExcelView
          side="QUOTE"
          ...其余既有 props 不变
        />
```
```tsx
        <LinkedExcelView
          side="COSTING"
          ...其余既有 props 不变
        />
```

> 核对：用 grep `LinkedExcelView` 看两处的 `templateId`/`viewLabel`，报价侧 side=QUOTE、核价侧 side=COSTING，勿混（AP 命名纪律）。

- [ ] **Step 3: TS 编译 + Vite 200**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/LinkedExcelView.tsx
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/QuotationStep2.tsx
```
Expected: TS 0 错误；两个文件 Vite 200。

- [ ] **Step 4: 提交**

```bash
git add cpq-frontend/src/pages/quotation/LinkedExcelView.tsx \
        cpq-frontend/src/pages/quotation/QuotationStep2.tsx
git commit -m "feat(excel-fe): Step2 报价/核价 Excel 视图改读同侧值快照(side 区分)"
```

---

## Task 8: 报价卡片编辑后就地回灌 quoteExcelValues（实时同步）

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（编辑回灌处 line 1110-1111）

**背景**：`editCardValue` 端点响应已含 `quoteExcelValues`（后端 Task 5 已用新卡片值重算）。现前端只回灌 `quoteCardValues`，需补 `quoteExcelValues`，使切到报价 Excel 视图即见新值。

- [ ] **Step 1: 回灌补 quoteExcelValues**

把 line 1108-1111 的回灌改为：

```ts
      const qcv = res?.data?.quoteCardValues;
      const qev = res?.data?.quoteExcelValues;
      if (qcv || qev) onUpdate(() => {
        const patch: Partial<LineItem> = {};
        if (qcv) patch.quoteCardValues = qcv;
        if (qev) patch.quoteExcelValues = qev;
        return patch;
      });
```

- [ ] **Step 2: TS 编译 + Vite 200**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/QuotationStep2.tsx
```
Expected: TS 0 错误；Vite 200。

- [ ] **Step 3: 提交**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx
git commit -m "feat(excel-fe): 报价卡片编辑后就地回灌 quoteExcelValues(Excel 视图实时同步)"
```

---

## Task 9: E2E 验证（核价出值 + 报价回归 + 编辑同步）

**Files:**
- 复用/扩展：`cpq-frontend/e2e/quotation-flow.spec.ts`
- 参考：`docs/E2E测试方法.md`

**强制项**（CLAUDE.md）：本次改了 `ExcelViewService.java` / `CardSnapshotService.java` / `QuotationStep2.tsx` / `LinkedExcelView.tsx` —— 全在强制 E2E 清单内。

- [ ] **Step 1: 准备/确认数据**

确认存在配了 A/B/C CARD_FORMULA 的核价模板（如「核价模板0603」），以及一张能复现的报价单；**新加一次产品**（触发 `snapshotLineValues` 走新逻辑生成 `costing_excel_values`）。E2E 用注入-还原范式或对新加产品的草稿单只读校验。

- [ ] **Step 2: 跑 E2E**

Run（参照 `docs/E2E测试方法.md`）:
```bash
cd cpq-frontend
rm -f e2e/screenshots/qf-*.png 2>/dev/null
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```
Expected:
- 全部 test `passed`；`'加载中' final count = 0`；8 Tab `'加载中'=0`。
- **报价单 Excel 回归**：原断言值（如 QT-1497 链路相关数值）不退化。

- [ ] **Step 3: 核价 Excel 出值 + 同源核对（手动 / E2E 断言）**

校验点：
1. 核价单 Excel 视图 A/B/C **非「—」**，且 B 列按 `类型=='非银点类'` 命中（取核价 ys_view 的"类型"列）。
2. 核价 Excel 某列值 **== 核价卡片该页签按同公式手算值**（同源）。
3. 报价卡片改一个输入单元格 → 切报价 Excel 视图 → 对应列**当场变新值**。
4. 缺快照场景（老单未重新加产品）→ Excel 视图显示「—」而非报错/实时拉数。

- [ ] **Step 4: DB 抽查新快照**

Run（按 CLAUDE.md 的 psql 范式，连接参数用本机 dev 配置）:
```bash
PGPASSWORD=<pwd> psql -h <host> -U <user> -d <db> -c \
  "SELECT id, (costing_excel_values::jsonb->'rows'->0) FROM quotation_line_item WHERE id = '<新加产品行id>';"
```
Expected: `costing_excel_values.rows[0]` 含 A/B/C 且为数值（非 null/「—」）。

- [ ] **Step 5: 提交 E2E 证据**

```bash
git add cpq-frontend/e2e/quotation-flow.spec.ts cpq-frontend/e2e/screenshots/qf-*.png
git commit -m "test(excel): 核价 Excel CARD_FORMULA 出值 + 报价回归 + 编辑同步 E2E"
```

---

## Task 10: 文档回写

**Files:**
- Modify: `docs/RECORD.md`（追加开发记录）
- Modify: `docs/Excel模板配置指南.md` 或 `docs/报价单核价单功能总结.md`（如涉及取数口径描述）

- [ ] **Step 1: RECORD.md 追加**

格式：`[2026-06-03] 报价/核价Excel - CARD_FORMULA 取数统一为"同侧卡片快照有效行" | ExcelViewService/CardSnapshotService/CardEffectiveRows/LinkedExcelView/useExcelSnapshotRows | 核价加产品时取对(冻结不刷)、报价随重刷/编辑同源；缺快照显示—不降级；存量核价错值不回填`

- [ ] **Step 2: 提交**

```bash
git add docs/RECORD.md docs/Excel模板配置指南.md
git commit -m "docs(excel): 回写 CARD_FORMULA 取数统一开发记录"
```

---

## 自检声明清单（每个后端/前端任务结束必带一行）

- 后端：`mvnw compile 0 错误 ✅；<相关测试> PASS ✅；touch 重启后 /q/health=200 ✅`
- 前端：`tsc 0 错误 ✅；<改动 .tsx/.ts> → Vite 200 ✅`
- 收尾：`E2E quotation-flow 1 passed ✅；'加载中' final=0 ✅；核价 Excel A/B/C 出值 ✅；报价回归不退化 ✅`

没有这行声明 = 未完成（CLAUDE.md 强制）。

---

## Self-Review（写计划后自查）

**Spec 覆盖：**
- §0 命名纠正 → Task 1（CardEffectiveRows 中性命名）、Task 6（useExcelSnapshotRows）、Task 7（side 区分）。✅
- §1 真因（取数取错源 + 前端实时绕回）→ Task 4（后端取数源切换）、Task 7（前端改读快照）。✅
- §2.1 有效行合成 → Task 1。✅
- §2.2 fromEffectiveRows 精确命中 → Task 3。✅
- §2.3 ExcelViewService 取数源切换 → Task 4。✅
- §2.4 buildExcelValues 透传 + 三调用点对齐 → Task 5。✅
- §2.5 前端 Excel 视图读快照 → Task 6/7。✅
- 需求 3（缺快照不降级）→ Task 6 hook（无 getExcelView 回退）。✅
- 需求 4/5（核价冻结、只新加产品生效、不回填）→ Task 5 Step 2（修在 snapshotLineValues；refreshQuoteCardValues 不碰核价）。✅
- 需求 6（公式取已算值）→ Task 1（formulaResults 并入有效行）。✅
- 需求 8（编辑实时同步）→ Task 8。✅
- subtotal 引用 → Task 2。✅

**占位扫描：** Task 4 Step 4 第一版 `parseEffectiveRows` 含占位，已在同 Step 给出"落地实现"覆盖版并标注 ⚠️ 必须替换；执行者以落地版为准。其余无 TODO/TBD。

**类型一致性：** `CardEffectiveRows.TabRows{rows, subtotal}`、`CardDataProvider.fromEffectiveRows(Map<String,TabRows>)`、`evaluateColumns(columns, CardDataProvider, ...)`、`buildRowData(..., Map<String,TabRows>)`、`buildLineRowData(..., String cardValuesJson)`、`buildExcelValues(..., String cardValuesJson)`、`useExcelSnapshotRows({lineItems, side, parsedColumns})` 跨任务一致。✅

**待执行期确认的事实（执行者第一步先 grep 校正，不阻塞计划）：**
1. `Template` 实体 `componentsSnapshot` 字段名（Task 4）。
2. 现网 CARD_FORMULA 聚合 refs 实际形态（Task 4 单测断言对齐，值 275 不变）。
3. Step2 两个 `LinkedExcelView` 哪个是报价/核价（按 templateId/viewLabel 判定，Task 7）。
