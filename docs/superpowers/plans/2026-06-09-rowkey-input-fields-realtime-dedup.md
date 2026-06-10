# 行键放开输入字段 + 录入实时判重 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 INPUT_TEXT / INPUT_NUMBER 字段也能在组件管理勾为行键，报价录入时同组件组合键重复的行实时标红，提交时硬拦重复（含输入字段键 / driver+输入混合键）。

**Architecture:** 方案 A（异构 `string[]` rowKeyFields，零迁移）。**不改**现有 `computeRowKey`（服务 editRows 对齐），**并行新增** input-inclusive 的 `computeDedupKey(rowKeyFields, driverRow, rowValues)` 专用判重；前端实时判重 `findDuplicateRowKeys` + 后端 `RowKeyUniquenessService` 改两路位置化取数（`snapshot_rows` 驱动列 + `row_data` 输入值/手动行）。

**Tech Stack:** Java 17 / Quarkus / Jackson / JUnit 5；React + Ant Design / TypeScript / Vitest / Playwright。

**关联 spec：** `docs/superpowers/specs/2026-06-09-rowkey-input-fields-realtime-dedup-design.md`（已评审定案）。

**已核实事实（勿重新发明）：**
- `ComponentDriverService.resolveRowKeyCandidates`（`:1036`）：现按 `basic_data_path` 叶子列是否命中 driver 列集合判 `eligible`；入参 `fields: List<Map<String,Object>>` 含 `field_type` / `name` / `basic_data_path` 键。
- `RowKeyCandidatesResponse.Candidate`：`fieldName / displayName / resolvedColumn / eligible / reason`。
- `FormulaCalculator.computeRowKey(JsonNode rowKeyFields, JsonNode driverRow)`（`:446`，public）：`||` 拼接；空/`__seq_no__` → null。**本计划不改它**。
- `RowKeyUniquenessService.collectConflicts(String structureJson, List<LineItemRows> items)`（现读 `quoteCardValues.baseRows[].driverRow`）；`LineItemRows(label, valuesJson)`。
- `QuotationService.submit`（`:651`）：`:680` 从 `quotation_view_structure` 取 `QUOTE_CARD` 份 `structure` 作 `quoteCardStructureJson`；`:685-694` 装配 `LineItemRows` 调 `collectConflicts`。
- `QuotationLineComponentData`（实体）：`lineItemId` / `componentId` / `snapshotRows`(列 `snapshot_rows`, JSONB) / `rowData`(列 `row_data`, JSONB, 默认 `"[]"`)。`snapshot_rows` = `List<ExpandDriverResponse.Row>{driverRow,basicDataValues}` 序列化；`row_data` = 行对象数组，手动行含 `"_origin":"manual"`。
- `CardSnapshotService.buildCardValues`（`:465`）：`baseRows` 只来自 `snapshot_rows`（driver 展开），**不含手动行/输入值**。
- 前端 `useCardSnapshots.ts#computeRowKey`（`:52`，导出）：`rowKeyFields` 从 `driverRow` 取值。**本计划不改它**。
- 前端 `QuotationStep2.tsx`：行渲染循环在 `:1899~1911`，`rawRow` = `comp.rows[i]`（含手填 INPUT 值）；写路径下标按 AP-54 用 `realRowIndex`；行数按 AP-51 driver 权威 + 手动行。
- 前端 `FieldConfigTable.tsx`（`:428~450`）：行键勾选列用 `candidatesByField[record.name]` 的 `eligible` / `resolvedColumn`；`onToggleRowKey(col, checked)` 写 `rowKeyFields`。
- 前端 `RowKeyCandidate`（`types.ts:251`）：`fieldName/displayName/resolvedColumn/eligible/reason`。
- `ComponentManagement.tsx#refreshRowKeyCandidates`（`:368`）：POST `/components/{id}/row-key-candidates`，`map[c.fieldName]=c`。

---

## File Structure

- Modify: `cpq-backend/.../quotation/service/FormulaCalculator.java` — 新增 `public static String computeDedupKey(...)`（不动 `computeRowKey`）。
- Modify: `cpq-backend/.../component/service/ComponentDriverService.java` — `resolveRowKeyCandidates` 放开 INPUT 字段 + 撞名排除 + 写 `source`。
- Modify: `cpq-backend/.../component/dto/RowKeyCandidatesResponse.java` — `Candidate` 加 `String source`。
- Modify: `cpq-backend/.../quotation/service/rowkey/RowKeyUniquenessService.java` — 重构两路位置化取数 + 新 record 契约。
- Modify: `cpq-backend/.../quotation/service/QuotationService.java` — `submit` 装配新契约（加载 componentData）。
- Test: `cpq-backend/.../quotation/service/FormulaCalculatorComputeDedupKeyTest.java`（新，纯 JUnit）。
- Test: `cpq-backend/.../component/service/ResolveRowKeyCandidatesTest.java`（新或扩展，纯 JUnit）。
- Test: `cpq-backend/.../quotation/service/rowkey/RowKeyUniquenessServiceTest.java`（改：新契约 + 输入/混合/撞名用例）。
- Create: `cpq-frontend/src/pages/quotation/rowDedup.ts` — `computeDedupKey` + `findDuplicateRowKeys`。
- Test: `cpq-frontend/src/pages/quotation/rowDedup.test.ts`（新，vitest）。
- Modify: `cpq-frontend/src/pages/component/types.ts` — `RowKeyCandidate` 加 `source?`。
- Modify: `cpq-frontend/src/pages/component/FieldConfigTable.tsx` — tooltip 按 source 区分。
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx` — 实时标红接线。
- Test: `cpq-frontend/e2e/rowkey-input-dedup.spec.ts`（新，专项 E2E）。

---

## Task 1: 后端 `computeDedupKey`（TDD，纯静态）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/FormulaCalculatorComputeDedupKeyTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FormulaCalculatorComputeDedupKeyTest {

    private static final ObjectMapper M = new ObjectMapper();
    private JsonNode j(String s) { try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); } }

    @Test
    void driverColumnFromDriverRow() {
        JsonNode rkf = j("[\"child_no\",\"elem\"]");
        String k = FormulaCalculator.computeDedupKey(rkf, j("{\"child_no\":\"P1\",\"elem\":\"Cu\"}"), j("{}"));
        assertEquals("P1||Cu", k);
    }

    @Test
    void inputFieldFallsBackToRowValues() {
        // driver 行没有该字段 → 从 rowValues 取（手填输入字段）
        JsonNode rkf = j("[\"child_no\",\"material\"]");
        String k = FormulaCalculator.computeDedupKey(rkf, j("{\"child_no\":\"P1\"}"), j("{\"material\":\"SUS304\"}"));
        assertEquals("P1||SUS304", k);
    }

    @Test
    void driverNonEmptyWinsOverRowValues() {
        JsonNode rkf = j("[\"elem\"]");
        String k = FormulaCalculator.computeDedupKey(rkf, j("{\"elem\":\"Cu\"}"), j("{\"elem\":\"Ni\"}"));
        assertEquals("Cu", k);
    }

    @Test
    void allBlankReturnsNull() {
        JsonNode rkf = j("[\"a\",\"b\"]");
        assertNull(FormulaCalculator.computeDedupKey(rkf, j("{}"), j("{}")));
    }

    @Test
    void emptyRowKeyFieldsReturnsNull() {
        assertNull(FormulaCalculator.computeDedupKey(j("[]"), j("{\"x\":1}"), j("{}")));
    }

    @Test
    void seqNoSentinelReturnsNull() {
        assertNull(FormulaCalculator.computeDedupKey(j("[\"__seq_no__\"]"), j("{}"), j("{}")));
    }

    @Test
    void partialKeyKept() {
        // 部分填（一列有值一列空）算真实键，参与判重
        JsonNode rkf = j("[\"a\",\"b\"]");
        assertEquals("P1||", FormulaCalculator.computeDedupKey(rkf, j("{\"a\":\"P1\"}"), j("{}")));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest=FormulaCalculatorComputeDedupKeyTest 2>&1 | tail -15`
Expected: 编译失败 `cannot find symbol ... computeDedupKey`。

- [ ] **Step 3: 写实现**

在 `FormulaCalculator.java` 的 `computeRowKey`（`:446`）方法**之后**插入：

```java
    /**
     * 判重专用组合键（input-inclusive）：逐字段 driverRow 非空优先，否则取 rowValues。
     * 与 computeRowKey 区别：额外读 rowValues（手填输入字段值），仅用于行键唯一性判重，
     * 不接入 editRows / formula 路径（避开鸡生蛋）。全字段为空 → null（不参与判重）。
     */
    public static String computeDedupKey(JsonNode rowKeyFields, JsonNode driverRow, JsonNode rowValues) {
        if (rowKeyFields == null || !rowKeyFields.isArray() || rowKeyFields.size() == 0) return null;
        if (rowKeyFields.size() == 1 && "__seq_no__".equals(rowKeyFields.get(0).asText(""))) return null;
        java.util.List<String> parts = new java.util.ArrayList<>();
        boolean any = false;
        for (JsonNode k : rowKeyFields) {
            String field = k.asText("");
            String v = pickNonEmpty(driverRow, field);
            if (v == null) v = pickNonEmpty(rowValues, field);
            if (v != null) any = true;
            parts.add(v == null ? "" : v);
        }
        if (!any) return null;
        return String.join("||", parts);
    }

    /** 取 node[field] 文本，缺失/null/空串 → null。 */
    private static String pickNonEmpty(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.path(field);
        if (v == null || v.isMissingNode() || v.isNull()) return null;
        String s = v.asText("");
        return s.isEmpty() ? null : s;
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest=FormulaCalculatorComputeDedupKeyTest 2>&1 | tail -15`
Expected: `BUILD SUCCESS`，7 个测试全过。

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/FormulaCalculatorComputeDedupKeyTest.java
git commit -m "feat(rowkey): FormulaCalculator.computeDedupKey 判重专用 input-inclusive 组合键 + 单测"
```

---

## Task 2: 后端候选端点放开 INPUT 字段 + 撞名排除 + source（TDD）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/component/dto/RowKeyCandidatesResponse.java`
- Modify: `cpq-backend/src/main/java/com/cpq/component/service/ComponentDriverService.java`（`resolveRowKeyCandidates` `:1036`）
- Test: `cpq-backend/src/test/java/com/cpq/component/service/ResolveRowKeyCandidatesTest.java`

- [ ] **Step 1: DTO 加 `source` 字段**

在 `RowKeyCandidatesResponse.Candidate` 的 `reason` 字段后加：

```java
        /** 行键来源："driver"（取自 driver 列） / "input"（取自手填输入字段）；eligible=false 时为 null。 */
        public String source;
```

- [ ] **Step 2: 写失败测试**

```java
package com.cpq.component.service;

import com.cpq.component.dto.RowKeyCandidatesResponse.Candidate;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ResolveRowKeyCandidatesTest {

    private Map<String,Object> f(String name, String type, String basicPath) {
        return Map.of("name", name, "field_type", type,
            "basic_data_path", basicPath == null ? "" : basicPath);
    }
    private Candidate byName(List<Candidate> cs, String n) {
        return cs.stream().filter(c -> n.equals(c.fieldName)).findFirst().orElseThrow();
    }

    @Test
    void inputTextEligibleWhenNoCollision() {
        List<Candidate> cs = ComponentDriverService.resolveRowKeyCandidates(
            "$v_demo",
            List.of(f("material", "INPUT_TEXT", null)),
            Set.of("child_no", "elem"));   // driver 列不含 material
        Candidate c = byName(cs, "material");
        assertTrue(c.eligible);
        assertEquals("material", c.resolvedColumn);
        assertEquals("input", c.source);
    }

    @Test
    void inputNumberEligible() {
        List<Candidate> cs = ComponentDriverService.resolveRowKeyCandidates(
            "$v_demo", List.of(f("seq", "INPUT_NUMBER", null)), Set.of("child_no"));
        assertTrue(byName(cs, "seq").eligible);
        assertEquals("input", byName(cs, "seq").source);
    }

    @Test
    void inputCollidingWithDriverColumnRejected() {
        List<Candidate> cs = ComponentDriverService.resolveRowKeyCandidates(
            "$v_demo", List.of(f("elem", "INPUT_TEXT", null)), Set.of("child_no", "elem"));
        Candidate c = byName(cs, "elem");
        assertFalse(c.eligible);
        assertTrue(c.reason != null && c.reason.contains("撞名"));
    }

    @Test
    void formulaFieldStillIneligible() {
        List<Candidate> cs = ComponentDriverService.resolveRowKeyCandidates(
            "$v_demo", List.of(f("amount", "FORMULA", null)), Set.of("child_no"));
        assertFalse(byName(cs, "amount").eligible);
    }

    @Test
    void driverBackedBasicDataStillEligibleWithDriverSource() {
        List<Candidate> cs = ComponentDriverService.resolveRowKeyCandidates(
            "$v_demo",
            List.of(f("childNo", "BASIC_DATA", "$v_demo.child_no")),
            Set.of("child_no", "elem"));
        Candidate c = byName(cs, "childNo");
        assertTrue(c.eligible);
        assertEquals("child_no", c.resolvedColumn);
        assertEquals("driver", c.source);
    }
}
```

> 注：`extractLeafField("$v_demo.child_no")` 须返回 `child_no`；若该 helper 口径不同，按实际调整测试里的 basic_data_path 写法（先 grep `extractLeafField` 确认）。

- [ ] **Step 3: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest=ResolveRowKeyCandidatesTest 2>&1 | tail -20`
Expected: 断言失败（INPUT 字段当前 `eligible=false`）/ `source` 为 null。

- [ ] **Step 4: 改 `resolveRowKeyCandidates`**

定位 `:1043` 的 `for (Map<String, Object> f : fields)` 循环。在取 `c.fieldName` 后、判 `basicPath` 为空的分支处改造：当 `basic_data_path` 为空时，不再一律判 false，先看 `field_type`。完整替换循环体为：

```java
        for (Map<String, Object> f : fields) {
            var c = new com.cpq.component.dto.RowKeyCandidatesResponse.Candidate();
            c.fieldName = f.get("name") == null ? null : String.valueOf(f.get("name"));
            c.displayName = c.fieldName;
            String fieldType = f.get("field_type") == null ? "" : String.valueOf(f.get("field_type"));

            Object pathObj = f.get("basic_data_path");
            String basicPath = pathObj == null ? null : String.valueOf(pathObj);
            boolean hasBasicPath = basicPath != null && !basicPath.isBlank();

            // 输入字段分支（无 driver 列也可作行键，取手填值）
            if (!hasBasicPath && ("INPUT_TEXT".equals(fieldType) || "INPUT_NUMBER".equals(fieldType))) {
                if (haveColumns && c.fieldName != null && driverColumns.contains(c.fieldName)) {
                    c.eligible = false;
                    c.reason = "字段名与 driver 列撞名，不能作行键";
                } else {
                    c.eligible = true;
                    c.resolvedColumn = c.fieldName;
                    c.source = "input";
                    c.reason = null;
                }
                out.add(c);
                continue;
            }

            if (!hasBasicPath) {
                c.eligible = false;
                c.reason = "该字段无 driver 列，不能作行键";
                out.add(c);
                continue;
            }
            String leaf = extractLeafField(basicPath);
            if (leaf == null) {
                c.eligible = false;
                c.reason = "该字段无 driver 列，不能作行键";
                out.add(c);
                continue;
            }
            c.resolvedColumn = leaf;
            if (!haveColumns) {
                c.eligible = false;
                c.reason = "该 driver 无列信息，请先将 driver 配为 SQL 视图";
            } else if (driverColumns.contains(leaf)) {
                c.eligible = true;
                c.source = "driver";
                c.reason = null;
            } else {
                c.eligible = false;
                c.reason = "该字段不取自 driver 行，不能作行键";
            }
            out.add(c);
        }
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest=ResolveRowKeyCandidatesTest 2>&1 | tail -20`
Expected: `BUILD SUCCESS`，5 个测试全过。

- [ ] **Step 6: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/component/dto/RowKeyCandidatesResponse.java \
        cpq-backend/src/main/java/com/cpq/component/service/ComponentDriverService.java \
        cpq-backend/src/test/java/com/cpq/component/service/ResolveRowKeyCandidatesTest.java
git commit -m "feat(rowkey): 候选端点放开 INPUT_TEXT/INPUT_NUMBER + 撞名排除 + source 标记"
```

---

## Task 3: 重构 `RowKeyUniquenessService` 两路位置化取数（TDD）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/rowkey/RowKeyUniquenessService.java`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/rowkey/RowKeyUniquenessServiceTest.java`（改写）

**新契约：**
- `record CompRows(String componentId, String snapshotRowsJson, String rowDataJson)`
- `record LineItemComps(String lineItemLabel, List<CompRows> comps)`
- `List<RowKeyConflict> collectConflicts(String structureJson, List<LineItemComps> items)`

**取数规则：**
- 结构 `structureJson`：`{tabs:[{componentId,componentName,rowKeyFields:[...]}]}`，按 componentId 索引（沿用现 `parseStructure`）。
- 对每组件：`snapshotRows` = 解析 `snapshotRowsJson`（数组，每项 `{driverRow,...}`）；`rowData` = 解析 `rowDataJson`（数组，行对象，手动行含 `_origin:"manual"`）。
  - `driverDataRows` = rowData 中 `_origin != "manual"` 的子序列；`manualRows` = `_origin == "manual"` 的子序列。
  - driver 行 `i ∈ [0, snapshotRows.size())`：`driverRow = snapshotRows[i].driverRow`；`overlay = i < driverDataRows.size() ? driverDataRows[i] : {}`；`key = computeDedupKey(rkf, driverRow, overlay)`。
  - 手动行：`key = computeDedupKey(rkf, {}, manualRow)`。
- 全部 key 交 `RowKeyConflictDetector.detect(label · componentName, keys)`。

- [ ] **Step 1: 改写测试**

整体替换 `RowKeyUniquenessServiceTest.java` 为：

```java
package com.cpq.quotation.service.rowkey;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RowKeyUniquenessServiceTest {

    @Inject RowKeyUniquenessService svc;

    private static final String STRUCT = """
        { "tabs": [
          { "componentId": "c1", "componentName": "投料", "rowKeyFields": ["child_no", "material"] },
          { "componentId": "c2", "componentName": "无键组件", "rowKeyFields": [] }
        ] }""";

    private RowKeyUniquenessService.LineItemComps item(String label, RowKeyUniquenessService.CompRows... comps) {
        return new RowKeyUniquenessService.LineItemComps(label, List.of(comps));
    }

    @Test
    void driverColumnDuplicate_detected() {
        // material 为手填输入字段(rowData)，child_no 为 driver 列(snapshot_rows)
        String snap = """
          [ { "driverRow": { "child_no": "P1" } },
            { "driverRow": { "child_no": "P1" } },
            { "driverRow": { "child_no": "P2" } } ]""";
        String rd = """
          [ { "material": "Cu" }, { "material": "Cu" }, { "material": "Cu" } ]""";
        List<RowKeyConflict> r = svc.collectConflicts(STRUCT,
            List.of(item("产品A", new RowKeyUniquenessService.CompRows("c1", snap, rd))));
        assertEquals(1, r.size());
        assertEquals("P1||Cu", r.get(0).rowKey());
        assertEquals(List.of(0, 1), r.get(0).rowIndices());
    }

    @Test
    void uniqueMixedKeys_noConflict() {
        String snap = """
          [ { "driverRow": { "child_no": "P1" } }, { "driverRow": { "child_no": "P1" } } ]""";
        String rd = """[ { "material": "Cu" }, { "material": "Ni" } ]""";
        List<RowKeyConflict> r = svc.collectConflicts(STRUCT,
            List.of(item("产品A", new RowKeyUniquenessService.CompRows("c1", snap, rd))));
        assertTrue(r.isEmpty());
    }

    @Test
    void manualRowsDuplicate_detected() {
        // 无 driver 展开(snapshot 空)，两条手动行 child_no+material 全同
        String snap = "[]";
        String rd = """
          [ { "_origin": "manual", "child_no": "M1", "material": "X" },
            { "_origin": "manual", "child_no": "M1", "material": "X" } ]""";
        List<RowKeyConflict> r = svc.collectConflicts(STRUCT,
            List.of(item("产品A", new RowKeyUniquenessService.CompRows("c1", snap, rd))));
        assertEquals(1, r.size());
        assertEquals("M1||X", r.get(0).rowKey());
    }

    @Test
    void manualRowDuplicatesDriverRow_detected() {
        // driver 行 P1/Cu + 手动行 P1/Cu → 跨来源撞键
        String snap = """[ { "driverRow": { "child_no": "P1" } } ]""";
        String rd = """
          [ { "child_no": "P1", "material": "Cu" },
            { "_origin": "manual", "child_no": "P1", "material": "Cu" } ]""";
        // driverDataRows[0] = 第一条(非manual)，overlay 提供 material=Cu；手动行单独算
        List<RowKeyConflict> r = svc.collectConflicts(STRUCT,
            List.of(item("产品A", new RowKeyUniquenessService.CompRows("c1", snap, rd))));
        assertEquals(1, r.size());
        assertEquals("P1||Cu", r.get(0).rowKey());
    }

    @Test
    void componentWithoutRowKeyFields_skipped() {
        String rd = """[ { "x": "1" }, { "x": "1" } ]""";
        List<RowKeyConflict> r = svc.collectConflicts(STRUCT,
            List.of(item("产品A", new RowKeyUniquenessService.CompRows("c2", "[]", rd))));
        assertTrue(r.isEmpty());
    }

    @Test
    void allBlankKeys_notFlagged() {
        String snap = "[]";
        String rd = """[ { "_origin": "manual" }, { "_origin": "manual" } ]""";
        List<RowKeyConflict> r = svc.collectConflicts(STRUCT,
            List.of(item("产品A", new RowKeyUniquenessService.CompRows("c1", snap, rd))));
        assertTrue(r.isEmpty());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest=RowKeyUniquenessServiceTest 2>&1 | tail -20`
Expected: 编译失败（`CompRows` / `LineItemComps` 不存在、`collectConflicts` 签名不匹配）。

- [ ] **Step 3: 重写实现**

整体替换 `RowKeyUniquenessService.java` 为：

```java
package com.cpq.quotation.service.rowkey;

import com.cpq.quotation.service.FormulaCalculator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 提交时行键唯一性装配（两路位置化取数）：
 *   - 驱动列 ← snapshot_rows（按行下标 driverRow）
 *   - 输入值 / 手动行 ← row_data（按行下标；_origin='manual' 追加末尾）
 * 用 {@link FormulaCalculator#computeDedupKey} 算 input-inclusive 组合键，交 {@link RowKeyConflictDetector} 判重。
 * 解析失败按"跳过该单元"降级，不阻断提交。
 */
@ApplicationScoped
public class RowKeyUniquenessService {

    private static final Logger LOG = Logger.getLogger(RowKeyUniquenessService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单组件两路原始 JSON。 */
    public record CompRows(String componentId, String snapshotRowsJson, String rowDataJson) {}
    /** 单明细的全部组件行。 */
    public record LineItemComps(String lineItemLabel, List<CompRows> comps) {}

    private record TabKeyCfg(String componentName, JsonNode rowKeyFields) {}

    public List<RowKeyConflict> collectConflicts(String structureJson, List<LineItemComps> items) {
        List<RowKeyConflict> out = new ArrayList<>();
        Map<String, TabKeyCfg> cfgByComp = parseStructure(structureJson);
        if (cfgByComp.isEmpty() || items == null) return out;

        for (LineItemComps item : items) {
            if (item == null || item.comps() == null) continue;
            for (CompRows comp : item.comps()) {
                TabKeyCfg cfg = cfgByComp.get(comp.componentId());
                if (cfg == null || !cfg.rowKeyFields().isArray() || cfg.rowKeyFields().isEmpty()) continue;

                ArrayNode snapshotRows = parseArray(comp.snapshotRowsJson());
                ArrayNode rowData = parseArray(comp.rowDataJson());

                List<JsonNode> driverDataRows = new ArrayList<>();
                List<JsonNode> manualRows = new ArrayList<>();
                for (JsonNode r : rowData) {
                    if ("manual".equals(r.path("_origin").asText(""))) manualRows.add(r);
                    else driverDataRows.add(r);
                }

                List<String> keys = new ArrayList<>();
                // driver 行：snapshot 权威行数，overlay 取 driverDataRows[i]（输入字段值）
                for (int i = 0; i < snapshotRows.size(); i++) {
                    JsonNode driverRow = snapshotRows.get(i).path("driverRow");
                    JsonNode overlay = i < driverDataRows.size() ? driverDataRows.get(i) : MAPPER.createObjectNode();
                    keys.add(FormulaCalculator.computeDedupKey(cfg.rowKeyFields(), driverRow, overlay));
                }
                // 手动行：driverRow 空，全部从 row_data 取
                ObjectNode emptyDriver = MAPPER.createObjectNode();
                for (JsonNode mr : manualRows) {
                    keys.add(FormulaCalculator.computeDedupKey(cfg.rowKeyFields(), emptyDriver, mr));
                }

                String label = (item.lineItemLabel() == null ? "" : item.lineItemLabel() + " · ") + cfg.componentName();
                out.addAll(RowKeyConflictDetector.detect(label, keys));
            }
        }
        return out;
    }

    private Map<String, TabKeyCfg> parseStructure(String structureJson) {
        Map<String, TabKeyCfg> map = new HashMap<>();
        if (structureJson == null || structureJson.isBlank()) return map;
        try {
            for (JsonNode tab : MAPPER.readTree(structureJson).path("tabs")) {
                String cid = tab.path("componentId").asText("");
                if (cid.isBlank()) continue;
                String name = tab.path("componentName").asText(cid);
                map.put(cid, new TabKeyCfg(name, tab.path("rowKeyFields")));
            }
        } catch (Exception e) {
            LOG.warnf("[rowkey] parseStructure failed: %s", e.getMessage());
        }
        return map;
    }

    private ArrayNode parseArray(String json) {
        if (json == null || json.isBlank()) return MAPPER.createArrayNode();
        try {
            JsonNode n = MAPPER.readTree(json);
            return n.isArray() ? (ArrayNode) n : MAPPER.createArrayNode();
        } catch (Exception e) {
            LOG.warnf("[rowkey] parseArray failed: %s", e.getMessage());
            return MAPPER.createArrayNode();
        }
    }
}
```

> 注意：`computeDedupKey` 可能返回 null（全空键）；`RowKeyConflictDetector.detect` 已对 `null`/blank 跳过（见 `:160`），故 `keys` 含 null 安全。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest=RowKeyUniquenessServiceTest 2>&1 | tail -20`
Expected: `BUILD SUCCESS`，6 个测试全过。

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/rowkey/RowKeyUniquenessService.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/rowkey/RowKeyUniquenessServiceTest.java
git commit -m "feat(rowkey): RowKeyUniquenessService 两路位置化取数(snapshot_rows+row_data) 支持输入/混合/手动行键"
```

---

## Task 4: 接入 `QuotationService.submit`（装配新契约）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java`（`submit` `:685-694`）

- [ ] **Step 1: 替换装配段**

定位 `submit` 内 `:685-694`：

```java
        java.util.List<com.cpq.quotation.service.rowkey.RowKeyUniquenessService.LineItemRows> rowsForCheck =
            new java.util.ArrayList<>();
        for (QuotationLineItem li : lineItems) {
            String label = li.productNameSnapshot != null ? li.productNameSnapshot
                         : (li.productPartNoSnapshot != null ? li.productPartNoSnapshot : "明细");
            rowsForCheck.add(new com.cpq.quotation.service.rowkey.RowKeyUniquenessService.LineItemRows(
                label, li.quoteCardValues));
        }
        java.util.List<com.cpq.quotation.service.rowkey.RowKeyConflict> conflicts =
            rowKeyUniquenessService.collectConflicts(quoteCardStructureJson, rowsForCheck);
```

整体替换为（按组件加载 snapshot_rows + row_data）：

```java
        java.util.List<com.cpq.quotation.service.rowkey.RowKeyUniquenessService.LineItemComps> rowsForCheck =
            new java.util.ArrayList<>();
        for (QuotationLineItem li : lineItems) {
            String label = li.productNameSnapshot != null ? li.productNameSnapshot
                         : (li.productPartNoSnapshot != null ? li.productPartNoSnapshot : "明细");
            java.util.List<com.cpq.quotation.service.rowkey.RowKeyUniquenessService.CompRows> comps =
                new java.util.ArrayList<>();
            java.util.List<com.cpq.quotation.entity.QuotationLineComponentData> cds =
                com.cpq.quotation.entity.QuotationLineComponentData.list("lineItemId", li.id);
            for (com.cpq.quotation.entity.QuotationLineComponentData cd : cds) {
                if (cd.componentId == null) continue;
                comps.add(new com.cpq.quotation.service.rowkey.RowKeyUniquenessService.CompRows(
                    cd.componentId.toString(), cd.snapshotRows, cd.rowData));
            }
            rowsForCheck.add(new com.cpq.quotation.service.rowkey.RowKeyUniquenessService.LineItemComps(label, comps));
        }
        java.util.List<com.cpq.quotation.service.rowkey.RowKeyConflict> conflicts =
            rowKeyUniquenessService.collectConflicts(quoteCardStructureJson, rowsForCheck);
```

> 确认 `QuotationLineComponentData` 的字段名：`snapshotRows`（列 `snapshot_rows`）、`rowData`（列 `row_data`）、`componentId`。若实体属性名不同，grep `class QuotationLineComponentData` 后按实际调整。

- [ ] **Step 2: 触发后端重启并验证编译**

Run: `cd cpq-backend && touch src/main/java/com/cpq/quotation/service/QuotationService.java && sleep 7 && curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health`
Expected: `200`。若 500/启动失败，看 dev 日志修编译错（常见：实体属性名不符）。

- [ ] **Step 3: 全量回归 rowkey 后端测试**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest='RowKey*,FormulaCalculatorComputeDedupKeyTest,ResolveRowKeyCandidatesTest' 2>&1 | tail -20`
Expected: `BUILD SUCCESS`。

- [ ] **Step 4: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java
git commit -m "feat(rowkey): submit 装配两路取数(每组件 snapshot_rows+row_data)接入唯一性校验"
```

---

## Task 5: 前端 `computeDedupKey` + `findDuplicateRowKeys`（TDD vitest）

**Files:**
- Create: `cpq-frontend/src/pages/quotation/rowDedup.ts`
- Test: `cpq-frontend/src/pages/quotation/rowDedup.test.ts`

- [ ] **Step 1: 写失败测试**

```ts
import { describe, it, expect } from 'vitest';
import { computeDedupKey, findDuplicateRowKeys } from './rowDedup';

describe('computeDedupKey', () => {
  it('driver 列取自 driverRow', () => {
    expect(computeDedupKey(['child_no', 'elem'], { child_no: 'P1', elem: 'Cu' }, {})).toBe('P1||Cu');
  });
  it('输入字段从 rowValues 回退取', () => {
    expect(computeDedupKey(['child_no', 'material'], { child_no: 'P1' }, { material: 'SUS304' })).toBe('P1||SUS304');
  });
  it('driver 非空优先于 rowValues', () => {
    expect(computeDedupKey(['elem'], { elem: 'Cu' }, { elem: 'Ni' })).toBe('Cu');
  });
  it('全空 → null', () => {
    expect(computeDedupKey(['a', 'b'], {}, {})).toBeNull();
  });
  it('空 rowKeyFields → null', () => {
    expect(computeDedupKey([], { x: 1 }, {})).toBeNull();
  });
  it('__seq_no__ 哨兵 → null', () => {
    expect(computeDedupKey(['__seq_no__'], {}, {})).toBeNull();
  });
});

describe('findDuplicateRowKeys', () => {
  it('无重复 → 空集', () => {
    const rows = [
      { driverRow: { child_no: 'P1' }, rowValues: { material: 'Cu' } },
      { driverRow: { child_no: 'P1' }, rowValues: { material: 'Ni' } },
    ];
    expect(findDuplicateRowKeys(rows, ['child_no', 'material']).size).toBe(0);
  });
  it('一组重复 → 两个下标', () => {
    const rows = [
      { driverRow: { child_no: 'P1' }, rowValues: { material: 'Cu' } },
      { driverRow: { child_no: 'P1' }, rowValues: { material: 'Cu' } },
      { driverRow: { child_no: 'P2' }, rowValues: { material: 'Cu' } },
    ];
    expect([...findDuplicateRowKeys(rows, ['child_no', 'material'])].sort()).toEqual([0, 1]);
  });
  it('手动行 vs driver 行跨来源撞键', () => {
    const rows = [
      { driverRow: { child_no: 'P1' }, rowValues: { material: 'Cu' } },
      { driverRow: {}, rowValues: { child_no: 'P1', material: 'Cu' } }, // 手动行
    ];
    expect(findDuplicateRowKeys(rows, ['child_no', 'material']).size).toBe(2);
  });
  it('全空键不判重', () => {
    const rows = [{ driverRow: {}, rowValues: {} }, { driverRow: {}, rowValues: {} }];
    expect(findDuplicateRowKeys(rows, ['a', 'b']).size).toBe(0);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/rowDedup.test.ts 2>&1 | tail -15`
Expected: FAIL（模块/导出不存在）。

- [ ] **Step 3: 写实现**

```ts
// cpq-frontend/src/pages/quotation/rowDedup.ts
/**
 * 判重专用组合键（input-inclusive）：逐字段 driverRow 非空优先，否则取 rowValues。
 * 与 useCardSnapshots#computeRowKey 区别：额外读 rowValues（手填输入字段值），仅用于行键唯一性判重，
 * 不接入 editRows / formula 路径。镜像后端 FormulaCalculator.computeDedupKey。
 */
export function computeDedupKey(
  rowKeyFields: string[] | undefined | null,
  driverRow: Record<string, any> | undefined,
  rowValues: Record<string, any> | undefined,
): string | null {
  if (!rowKeyFields || rowKeyFields.length === 0) return null;
  if (rowKeyFields.length === 1 && rowKeyFields[0] === '__seq_no__') return null;
  let any = false;
  const parts = rowKeyFields.map((f) => {
    let v = pickNonEmpty(driverRow, f);
    if (v == null) v = pickNonEmpty(rowValues, f);
    if (v != null) any = true;
    return v == null ? '' : v;
  });
  if (!any) return null;
  return parts.join('||');
}

function pickNonEmpty(obj: Record<string, any> | undefined, field: string): string | null {
  if (!obj) return null;
  const v = obj[field];
  if (v == null) return null;
  const s = String(v);
  return s.length === 0 ? null : s;
}

/** 返回组合键重复（≥2 次且非空）的行下标集合。 */
export function findDuplicateRowKeys(
  rows: Array<{ driverRow?: Record<string, any>; rowValues?: Record<string, any> }>,
  rowKeyFields: string[] | undefined | null,
): Set<number> {
  const dup = new Set<number>();
  if (!rowKeyFields || rowKeyFields.length === 0) return dup;
  const byKey = new Map<string, number[]>();
  rows.forEach((r, i) => {
    const k = computeDedupKey(rowKeyFields, r.driverRow, r.rowValues);
    if (k == null) return;
    const arr = byKey.get(k) ?? [];
    arr.push(i);
    byKey.set(k, arr);
  });
  for (const idxs of byKey.values()) {
    if (idxs.length >= 2) idxs.forEach((i) => dup.add(i));
  }
  return dup;
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/rowDedup.test.ts 2>&1 | tail -15`
Expected: 全部 PASS（10 用例）。

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/quotation/rowDedup.ts cpq-frontend/src/pages/quotation/rowDedup.test.ts
git commit -m "feat(rowkey): 前端 computeDedupKey + findDuplicateRowKeys 判重纯函数 + vitest"
```

---

## Task 6: `RowKeyCandidate.source` 类型 + FieldConfigTable tooltip

**Files:**
- Modify: `cpq-frontend/src/pages/component/types.ts`（`:251`）
- Modify: `cpq-frontend/src/pages/component/FieldConfigTable.tsx`（`:437-439`）

- [ ] **Step 1: 类型加 `source`**

在 `RowKeyCandidate` 接口的 `reason` 后加：

```ts
  /** 行键来源："driver" | "input"；eligible=false 时可能为 undefined。 */
  source?: 'driver' | 'input' | null;
```

- [ ] **Step 2: tooltip 按 source 区分**

定位 `FieldConfigTable.tsx:437-439`：

```tsx
        const tip = eligible
          ? `行键列：${col}`
          : (cand?.reason ?? '该字段无 driver 列，不能作行键');
```

替换为：

```tsx
        const tip = eligible
          ? (cand?.source === 'input' ? `行键列（手填）：${col}` : `行键列（driver）：${col}`)
          : (cand?.reason ?? '该字段无 driver 列，不能作行键');
```

- [ ] **Step 3: TS 校验 + Vite transform**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json 2>&1 | tail -5`
Expected: 0 错误。
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/component/FieldConfigTable.tsx`
Expected: `200`。

- [ ] **Step 4: Commit**

```bash
git add cpq-frontend/src/pages/component/types.ts cpq-frontend/src/pages/component/FieldConfigTable.tsx
git commit -m "feat(rowkey): RowKeyCandidate.source + FieldConfigTable tooltip 区分 driver/手填"
```

---

## Task 7: QuotationStep2 录入实时标红接线

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`

**目标：** active 组件渲染时，对组合键重复的行（按渲染下标 `i`）在行键单元格加红框 + Tooltip 提示。不回滚、不阻断 autosave。

- [ ] **Step 1: 导入判重函数**

在文件顶部 import 区加：

```tsx
import { findDuplicateRowKeys } from './rowDedup';
```

- [ ] **Step 2: 计算重复行下标集合（useMemo）**

在渲染 active 组件行的循环（`:1899` 附近）**之前**，定位到已能拿到 `activeRowKeyFields`、`activeDriverExpansion`、`activeComponent`（或 `splitRows`/`comp.rows`）的作用域，加入：

```tsx
                    // 行键实时判重：组合键重复的渲染行下标集合（driver 列取 driverRow、输入字段取 rawRow）
                    const dedupRows = (activeComponent?.rows ?? []).map((rawRow: any, i: number) => {
                      const ra = /* 与渲染循环一致的行映射 */ undefined as any;
                      const driverRow = (activeDriverExpansion && activeDriverExpansion.rows[i]?.driverRow)
                        ?? activeSnap?.driverRows?.[i]
                        ?? {};
                      return { driverRow, rowValues: rawRow ?? {} };
                    });
                    const duplicateRowIdx = findDuplicateRowKeys(dedupRows, activeRowKeyFields);
```

> **关键对齐**：`dedupRows` 的下标必须与渲染循环的 `i` 一致（同一份 `activeComponent.rows` / `splitRows` 顺序）。若渲染循环用的是 `splitRows`（driver 行 + 末尾手动行），则 `dedupRows` 也按 `splitRows` 构造，driver 行取 `activeDriverExpansion.rows[i].driverRow`、手动行 `driverRow={}`。实现时**以渲染循环实际用的行数组为准**，保证 `i` 同源（AP-54 纪律）。

- [ ] **Step 3: 渲染层标红**

在行键字段单元格渲染处（或整行 `<tr>`/行容器），当 `duplicateRowIdx.has(i)` 时套红框 + Tooltip。最小改法——在行键所属单元格外包：

```tsx
{duplicateRowIdx.has(i) ? (
  <Tooltip title="行键重复：与同组件其他行组合键冲突，提交前需修正">
    <span style={{ display: 'block', border: '1px solid #ff4d4f', borderRadius: 4, padding: 1 }}>
      {/* 原单元格内容 */}
    </span>
  </Tooltip>
) : (
  <>{/* 原单元格内容 */}</>
)}
```

> 若难以精确包裹单个行键单元格，可退而给该行容器加 `style={{ outline: duplicateRowIdx.has(i) ? '1px solid #ff4d4f' : undefined }}` + 一个行级 Tooltip/图标。保持"标红 + 悬浮提示"语义即可。确保 `Tooltip` 已从 `antd` 导入。

- [ ] **Step 4: TS 校验 + Vite transform**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json 2>&1 | tail -5`
Expected: 0 错误。
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/QuotationStep2.tsx`
Expected: `200`。

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx
git commit -m "feat(rowkey): QuotationStep2 录入实时标红重复组合键(软提示不阻断)"
```

---

## Task 8: 专项 E2E + 协议级双 spec 回归

**Files:**
- Create: `cpq-frontend/e2e/rowkey-input-dedup.spec.ts`

> 协议级改动（`QuotationStep2.tsx`）必须先跑既有双 spec 回归，再跑专项。选择器约定见 `docs/E2E测试方法.md`。

- [ ] **Step 1: 既有双 spec 回归**

Run:
```bash
cd cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts e2e/composite-product-flow.spec.ts --reporter=list 2>&1 | tail -30
```
Expected: 全部 `passed`，`'加载中' final count = 0`，8 Tab `'加载中'=0`。

- [ ] **Step 2: 写专项 E2E**

新建 `cpq-frontend/e2e/rowkey-input-dedup.spec.ts`，覆盖（按 `docs/E2E测试方法.md` 的登录/选择器/UTF-8 约定）：

```ts
import { test, expect } from '@playwright/test';

// 用例 1：组件管理放开 —— INPUT 字段「行键」勾选框可勾、FORMULA 仍 disabled
test('组件管理: INPUT 字段可勾行键, FORMULA 仍禁用', async ({ page }) => {
  // 1. 登录 + 进组件管理 + 打开一个含 INPUT_TEXT 与 FORMULA 字段的组件
  // 2. 断言 INPUT_TEXT 行的「行键」Checkbox 未 disabled（可勾）
  // 3. 断言 FORMULA 行的「行键」Checkbox disabled
  // （选择器：FieldConfigTable 行键列 Checkbox；按 docs/E2E测试方法.md 的 data-testid / 文案定位）
});

// 用例 2：报价录入 —— 同组件两条手填行键相同 → 实时标红
test('报价录入: 重复组合键实时标红', async ({ page }) => {
  // 1. 新建/打开报价单进 Step2，选含输入字段行键的组件
  // 2. 添加两行并填入相同的行键字段值
  // 3. 断言两行出现红框（border #ff4d4f）或重复提示 Tooltip 文案「行键重复」
});

// 用例 3：提交硬拦 —— 重复键 submit 返回 422；唯一键 submit 成功
test('报价提交: 重复键 422, 唯一键成功', async ({ page, request }) => {
  // 1. 构造重复键报价单(DRAFT) → submit → 期望 422 + message 含「行键重复」
  // 2. 改成唯一键 → submit → 期望成功(SUBMITTED)
});
```

> 三个用例的具体选择器、登录步骤、testid 按 `docs/E2E测试方法.md` 标杆补全（参考 `quotation-flow.spec.ts` 现有写法）。截图存 `e2e/screenshots/`。

- [ ] **Step 3: 跑专项 E2E**

Run:
```bash
cd cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/rowkey-input-dedup.spec.ts --reporter=list 2>&1 | tail -30
```
Expected: 3 用例 `passed`。

- [ ] **Step 4: Commit**

```bash
git add cpq-frontend/e2e/rowkey-input-dedup.spec.ts
git commit -m "test(rowkey): 专项 E2E — INPUT 可勾行键 / 实时标红 / 提交 422"
```

---

## Task 9: 集成自检 + RECORD 记录（CLAUDE.md 强制）

**Files:**
- Modify: `docs/RECORD.md`

- [ ] **Step 1: 后端健康 + 全量单测**

Run:
```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health
cd cpq-backend && ./mvnw -q -o test -Dtest='RowKey*,FormulaCalculatorComputeDedupKeyTest,ResolveRowKeyCandidatesTest' 2>&1 | tail -20
```
Expected: health `200`；测试 `BUILD SUCCESS`。

- [ ] **Step 2: 前端 TS + vitest + 改动文件 Vite 200**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json 2>&1 | tail -5
npx vitest run src/pages/quotation/rowDedup.test.ts 2>&1 | tail -10
for f in src/pages/component/FieldConfigTable.tsx src/pages/quotation/QuotationStep2.tsx; do
  echo -n "$f "; curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:5174/$f"; done
```
Expected: TS 0 错误；vitest 全过；两文件 `200`。

- [ ] **Step 3: 手动验证提交拦截（curl）**

构造一份含重复组合键的 DRAFT（手动加一行与已有行行键全列相同），替换 `<DRAFT_ID>`：
Run: `curl -s -X POST http://localhost:8081/api/cpq/quotations/<DRAFT_ID>/submit -H "Content-Type: application/json" | head -c 400`
Expected: 非 200，`message` 含「行键重复，无法提交」+ 行号明细。
再对全唯一键 DRAFT 重复：Expected 提交成功（SUBMITTED）。

- [ ] **Step 4: 追加 RECORD.md**

```
[2026-06-09] 行键 - 放开 INPUT_TEXT/INPUT_NUMBER 可作行键 + 录入实时判重 + 提交硬拦支持输入/混合/手动行键 | FormulaCalculator(computeDedupKey) / ComponentDriverService(resolveRowKeyCandidates) / RowKeyUniquenessService(两路位置化取数) / QuotationService.submit / rowDedup.ts / FieldConfigTable.tsx / QuotationStep2.tsx | 方案A异构string[]零迁移; 不改computeRowKey改用并行computeDedupKey避AP-54; submit取数从quoteCardValues.baseRows改为componentData(snapshot_rows+row_data)两路位置化合并; 撞名走候选端点eligible=false排除
```

- [ ] **Step 5: 自检声明 + Commit**

在完成说明里写明（CLAUDE.md 强制）：
> 后端 `RowKey*` / `FormulaCalculatorComputeDedupKeyTest` / `ResolveRowKeyCandidatesTest` 全过 ✅；`/q/health`→200 ✅；前端 TS 0 错误 + `rowDedup.test.ts` 全过 ✅；`FieldConfigTable.tsx` / `QuotationStep2.tsx`→Vite 200 ✅；双 spec E2E `'加载中'=0` ✅；专项 E2E 3 用例 passed ✅；重复键 submit→422 + 明细 ✅；唯一键→SUBMITTED ✅。

```bash
git add docs/RECORD.md
git commit -m "docs(record): 行键放开输入字段 + 录入实时判重 开发记录"
```

---

## Self-Review（写后自检）

**Spec coverage（对照 spec 各节）：**
- §4.1 配置端放开 INPUT + 撞名排除 + source → Task 2 ✅
- §4.2 新增 computeDedupKey（不改 computeRowKey）双镜像 → Task 1（后端）+ Task 5（前端）✅
- §4.3 提交校验两路位置化取数 → Task 3（服务）+ Task 4（submit 装配）✅
- §4.4 录入实时判重 findDuplicateRowKeys + 标红 → Task 5（函数）+ Task 7（渲染）✅
- §5 测试矩阵（detector 不动 / Uniqueness 新用例 / computeDedupKey 单测 / findDuplicateRowKeys vitest / 双 spec + 3 专项 E2E）→ Task 1/2/3/5/8 ✅
- §6 YAGNI（不改 computeRowKey、不迁移、不动核价）→ 全程未触及 ✅
- §8 验收标准（勾选/标红/422/SUBMITTED/E2E）→ Task 8/9 ✅

**Placeholder scan：** 无 TBD/TODO。Task 7 Step 2 的 `dedupRows` 行映射明确要求"以渲染循环实际行数组为准、保证 i 同源"——这是 AP-54 纪律的执行约束，非占位；实施者读 `:1899~1911` 现有循环即可对齐。Task 4 的"确认实体属性名"、Task 2 的"确认 extractLeafField 口径"均给了 grep 确认手段。

**Type consistency：**
- 后端 `computeDedupKey(JsonNode, JsonNode, JsonNode)` static —— Task 1 定义、Task 3 调用一致。
- `CompRows(componentId, snapshotRowsJson, rowDataJson)` / `LineItemComps(lineItemLabel, comps)` —— Task 3 定义、Task 4 构造字段名一致。
- 前端 `computeDedupKey(rowKeyFields, driverRow, rowValues)` / `findDuplicateRowKeys(rows, rowKeyFields)` —— Task 5 定义、Task 7 调用一致；`rows` 元素 `{driverRow?, rowValues?}` 一致。
- `RowKeyCandidate.source` / `Candidate.source` —— 前后端命名一致（`"driver"|"input"`）。

**风险点回顾：** Task 4 实体属性名、Task 2 `extractLeafField` 口径、Task 7 渲染行下标同源——均在对应 Task 给了实测/grep 确认步骤。
