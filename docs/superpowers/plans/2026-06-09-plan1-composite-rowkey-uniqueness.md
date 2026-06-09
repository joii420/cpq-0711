# Plan 1 — 组合行键唯一性校验（后端权威） Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 报价单提交（submit）时，对每个组件把 driver 展开行 + 手动行的组合行键（`rowKeyFields`）全部计算，发现重复即抛错并列出冲突行，阻止提交。

**Architecture:** 纯函数冲突检测器（`RowKeyConflictDetector`，无 DB，单测覆盖）+ 报价服务侧装配方法（`RowKeyUniquenessService.collectConflicts`，解析 `quote_card_values` 的 `baseRows[].driverRow` 与结构快照 `tabs[].rowKeyFields`，复用已有 public `FormulaCalculator.computeRowKey`）+ 在 `QuotationService.submit(id, userId)` 创建 snapshot 前调用，冲突时抛 `BusinessException(422, ...)`。

**Tech Stack:** Java 17 / Quarkus / Jackson `ObjectMapper` / JUnit 5（纯单测，部分 `@QuarkusTest` 装配测试）。

**Scope（本 Plan 边界）：**
- 本 Plan 只做**后端权威校验**（硬保证：提交被拦截 + 冲突明细）。这已完整满足 spec §7「保存时校验、列出冲突拦截、含 driver 行」的硬性要求。
- **不含**前端提交前的 UX 预提示（定位到 Tab/行）。该项作为 Plan 1b 后续（需先读 `QuotationWizard` 提交处理器），不在本 Plan 内伪造代码。
- 关联 spec：`docs/superpowers/specs/2026-06-09-multi-subtotal-conditional-formula-design.md` §7 / 设计 E。

**复用的既有事实（已核对源码，勿重新发明）：**
- `FormulaCalculator.computeRowKey(JsonNode rowKeyFields, JsonNode driverRow)` 是 **public**，口径：`rowKeyFields` 空/null → 不可用；单元素 `__seq_no__` → 走行号；否则按字段取 `driverRow` 值用 `||` 拼接（见 `cpq-frontend/.../useCardSnapshots.ts#computeRowKey` 镜像）。`CardSnapshotService.java:824` 已调用。
- `quote_card_values` 结构：`{ tabs: [ { componentId, baseRows: [ { driverRow:{...}, basicDataValues:{...} }, ... ], editRows, formulaResults } ] }`（见 `CardSnapshotService#extractBaseRowsByComp`，`:1040`）。
- 结构快照 `quote_card_structure`：`{ tabs: [ { componentId, rowKeyFields:[...], fields:[...] } ] }`（`CardSnapshotService.java:233` 写 `tabNode.set("rowKeyFields", ...)`）。`Quotation.quoteCardStructure` 列存其 JSON。
- 提交入口：`QuotationService.submit(UUID id, UUID userId)`（`:648`），其中 `List<QuotationLineItem> lineItems = QuotationLineItem.list("quotationId = ?1", id);` 后、删除/创建 snapshot 处。
- 异常约定：`throw new BusinessException(int code, String message)`（如 `:651` `BusinessException(404, ...)`）。

---

## File Structure

- Create: `cpq-backend/src/main/java/com/cpq/quotation/service/rowkey/RowKeyConflictDetector.java`
  — 纯函数：给定每组件的 `List<String> rowKeys`（按行序），返回冲突列表。无 DB、无注入。
- Create: `cpq-backend/src/main/java/com/cpq/quotation/service/rowkey/RowKeyConflict.java`
  — 不可变记录：`(String componentName, String rowKey, List<Integer> rowIndices)`。
- Create: `cpq-backend/src/main/java/com/cpq/quotation/service/rowkey/RowKeyUniquenessService.java`
  — `@ApplicationScoped`，注入 `FormulaCalculator`；方法 `collectConflicts(String structureJson, List<LineItemRows> items)` 解析 JSON、算 key、调 detector。
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java`
  — `submit(UUID, UUID)` 内、`lineItems` 取出后、删 snapshot 前，调用校验，冲突抛 `BusinessException(422, ...)`。
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/rowkey/RowKeyConflictDetectorTest.java`（纯 JUnit）
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/rowkey/RowKeyUniquenessServiceTest.java`（`@QuarkusTest`，手搓 JSON）

---

## Task 1: 冲突记录类型 `RowKeyConflict`

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/quotation/service/rowkey/RowKeyConflict.java`

- [ ] **Step 1: 写记录类型**

```java
package com.cpq.quotation.service.rowkey;

import java.util.List;

/**
 * 单条行键冲突：某组件下，组合行键 rowKey 在多行重复。
 * rowIndices 为 0 基行序（baseRows 中的位置），用于定位与报错明细。
 */
public record RowKeyConflict(String componentName, String rowKey, List<Integer> rowIndices) {

    /** 人类可读的冲突描述，用于拼装提交报错信息。 */
    public String describe() {
        return "组件「" + componentName + "」行键 [" + rowKey + "] 在第 "
            + rowIndices.stream().map(i -> String.valueOf(i + 1)).reduce((a, b) -> a + "," + b).orElse("")
            + " 行重复";
    }
}
```

- [ ] **Step 2: 编译确认**

Run: `cd cpq-backend && ./mvnw -q -o compile 2>&1 | tail -5`
Expected: 无 `RowKeyConflict` 相关错误（其余既有警告忽略）。

- [ ] **Step 3: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/rowkey/RowKeyConflict.java
git commit -m "feat(rowkey): RowKeyConflict 记录类型"
```

---

## Task 2: 纯函数检测器 `RowKeyConflictDetector`（TDD）

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/quotation/service/rowkey/RowKeyConflictDetector.java`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/rowkey/RowKeyConflictDetectorTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.cpq.quotation.service.rowkey;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RowKeyConflictDetectorTest {

    @Test
    void noDuplicates_returnsEmpty() {
        List<RowKeyConflict> r = RowKeyConflictDetector.detect("投料", List.of("A||1", "A||2", "B||1"));
        assertTrue(r.isEmpty());
    }

    @Test
    void duplicate_reportsIndices() {
        List<RowKeyConflict> r = RowKeyConflictDetector.detect("投料", List.of("A||1", "A||2", "A||1"));
        assertEquals(1, r.size());
        assertEquals("A||1", r.get(0).rowKey());
        assertEquals(List.of(0, 2), r.get(0).rowIndices());
        assertEquals("投料", r.get(0).componentName());
    }

    @Test
    void tripleDuplicate_collectsAllIndices() {
        List<RowKeyConflict> r = RowKeyConflictDetector.detect("加工", List.of("X", "X", "X"));
        assertEquals(1, r.size());
        assertEquals(List.of(0, 1, 2), r.get(0).rowIndices());
    }

    @Test
    void multipleDistinctDuplicates_reportedSeparately() {
        List<RowKeyConflict> r = RowKeyConflictDetector.detect("回料", List.of("A", "B", "A", "B"));
        assertEquals(2, r.size());
    }

    @Test
    void blankKey_isSkipped_notTreatedAsDuplicate() {
        // 空 key（无法计算行键的行）不参与冲突判定，避免把"全空"误判为重复。
        List<RowKeyConflict> r = RowKeyConflictDetector.detect("投料", List.of("", "", "A"));
        assertTrue(r.isEmpty());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest=RowKeyConflictDetectorTest 2>&1 | tail -15`
Expected: 编译失败 `cannot find symbol ... RowKeyConflictDetector`。

- [ ] **Step 3: 写最小实现**

```java
package com.cpq.quotation.service.rowkey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯函数行键冲突检测：给定某组件按行序排列的 rowKey 列表，
 * 返回出现 ≥2 次的 key 及其全部行下标。空白 key 跳过（无法计算行键的行不参与判定）。
 */
public final class RowKeyConflictDetector {

    private RowKeyConflictDetector() {}

    public static List<RowKeyConflict> detect(String componentName, List<String> rowKeys) {
        Map<String, List<Integer>> byKey = new LinkedHashMap<>();
        for (int i = 0; i < rowKeys.size(); i++) {
            String k = rowKeys.get(i);
            if (k == null || k.isBlank()) continue;
            byKey.computeIfAbsent(k, x -> new ArrayList<>()).add(i);
        }
        List<RowKeyConflict> out = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : byKey.entrySet()) {
            if (e.getValue().size() >= 2) {
                out.add(new RowKeyConflict(componentName, e.getKey(), List.copyOf(e.getValue())));
            }
        }
        return out;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest=RowKeyConflictDetectorTest 2>&1 | tail -15`
Expected: `BUILD SUCCESS`，5 个测试全过。

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/rowkey/RowKeyConflictDetector.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/rowkey/RowKeyConflictDetectorTest.java
git commit -m "feat(rowkey): 纯函数行键冲突检测器 + 单测"
```

---

## Task 3: 装配服务 `RowKeyUniquenessService`（解析 JSON + 复用 computeRowKey，TDD）

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/quotation/service/rowkey/RowKeyUniquenessService.java`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/rowkey/RowKeyUniquenessServiceTest.java`

**契约：**
- 入参：结构快照 JSON 字符串 `structureJson`（`{tabs:[{componentId,rowKeyFields:[...]}]}`），以及每个明细的值快照 `List<LineItemRows>`（每项含 `lineItemLabel` + `valuesJson`，后者 `{tabs:[{componentId,baseRows:[{driverRow}]}]}`）。
- 逐明细 × 逐组件：用结构里的 `rowKeyFields`（按 componentId 匹配），对该组件 `baseRows[].driverRow` 调 `formulaCalculator.computeRowKey(rowKeyFields, driverRow)` 得行键列表 → 交给 `RowKeyConflictDetector.detect`。
- `rowKeyFields` 为空数组 → 该组件不参与校验（未声明行键的组件跳过）。
- 组件名取结构 tab 的 `componentName`（无则回退 componentId）；冲突描述前缀加明细 label。

- [ ] **Step 1: 写失败测试**

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
          { "componentId": "c1", "componentName": "投料", "rowKeyFields": ["child_no", "elem"] },
          { "componentId": "c2", "componentName": "无键组件", "rowKeyFields": [] }
        ] }""";

    @Test
    void duplicateCompositeKey_detected() {
        String values = """
          { "tabs": [ { "componentId": "c1", "baseRows": [
            { "driverRow": { "child_no": "P1", "elem": "Cu" } },
            { "driverRow": { "child_no": "P1", "elem": "Cu" } },
            { "driverRow": { "child_no": "P2", "elem": "Cu" } }
          ] } ] }""";
        List<RowKeyConflict> r = svc.collectConflicts(STRUCT,
            List.of(new RowKeyUniquenessService.LineItemRows("产品A", values)));
        assertEquals(1, r.size());
        assertEquals("P1||Cu", r.get(0).rowKey());
        assertEquals(List.of(0, 1), r.get(0).rowIndices());
    }

    @Test
    void uniqueCompositeKeys_noConflict() {
        String values = """
          { "tabs": [ { "componentId": "c1", "baseRows": [
            { "driverRow": { "child_no": "P1", "elem": "Cu" } },
            { "driverRow": { "child_no": "P1", "elem": "Ni" } }
          ] } ] }""";
        List<RowKeyConflict> r = svc.collectConflicts(STRUCT,
            List.of(new RowKeyUniquenessService.LineItemRows("产品A", values)));
        assertTrue(r.isEmpty());
    }

    @Test
    void componentWithoutRowKeyFields_skipped() {
        String values = """
          { "tabs": [ { "componentId": "c2", "baseRows": [
            { "driverRow": { "x": "1" } }, { "driverRow": { "x": "1" } }
          ] } ] }""";
        List<RowKeyConflict> r = svc.collectConflicts(STRUCT,
            List.of(new RowKeyUniquenessService.LineItemRows("产品A", values)));
        assertTrue(r.isEmpty());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest=RowKeyUniquenessServiceTest 2>&1 | tail -15`
Expected: 编译失败 `cannot find symbol ... RowKeyUniquenessService`。

- [ ] **Step 3: 写实现**

```java
package com.cpq.quotation.service.rowkey;

import com.cpq.quotation.service.FormulaCalculator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 提交时行键唯一性装配：解析结构快照（rowKeyFields）+ 各明细值快照（baseRows[].driverRow），
 * 复用 public {@link FormulaCalculator#computeRowKey} 算组合行键，交 {@link RowKeyConflictDetector} 判重。
 * 解析失败按"跳过该单元"降级，不阻断提交（不引入因脏 JSON 误拦截）。
 */
@ApplicationScoped
public class RowKeyUniquenessService {

    private static final Logger LOG = Logger.getLogger(RowKeyUniquenessService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject FormulaCalculator formulaCalculator;

    /** 单个明细的值快照载体。 */
    public record LineItemRows(String lineItemLabel, String valuesJson) {}

    /** 结构 tab 的行键配置缓存项。 */
    private record TabKeyCfg(String componentName, JsonNode rowKeyFields) {}

    public List<RowKeyConflict> collectConflicts(String structureJson, List<LineItemRows> items) {
        List<RowKeyConflict> out = new ArrayList<>();
        Map<String, TabKeyCfg> cfgByComp = parseStructure(structureJson);
        if (cfgByComp.isEmpty() || items == null) return out;

        for (LineItemRows item : items) {
            JsonNode tabs = readTabs(item.valuesJson());
            for (JsonNode tab : tabs) {
                String cid = tab.path("componentId").asText("");
                TabKeyCfg cfg = cfgByComp.get(cid);
                if (cfg == null || !cfg.rowKeyFields().isArray() || cfg.rowKeyFields().isEmpty()) continue;

                JsonNode baseRows = tab.path("baseRows");
                if (!baseRows.isArray() || baseRows.isEmpty()) continue;

                List<String> keys = new ArrayList<>();
                for (JsonNode br : baseRows) {
                    JsonNode driverRow = br.path("driverRow");
                    keys.add(formulaCalculator.computeRowKey(cfg.rowKeyFields(), driverRow));
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

    private JsonNode readTabs(String valuesJson) {
        if (valuesJson == null || valuesJson.isBlank()) return MissingNode.getInstance();
        try {
            return MAPPER.readTree(valuesJson).path("tabs");
        } catch (Exception e) {
            LOG.warnf("[rowkey] readTabs failed: %s", e.getMessage());
            return MissingNode.getInstance();
        }
    }
}
```

> 注：`MissingNode` 不是数组，`for (JsonNode tab : missing)` 迭代 0 次，安全。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest=RowKeyUniquenessServiceTest 2>&1 | tail -20`
Expected: `BUILD SUCCESS`，3 个测试全过。若 `computeRowKey` 签名/口径与预期不符（如返回带行号兜底），按实际行为调整断言并在此说明。

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/rowkey/RowKeyUniquenessService.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/rowkey/RowKeyUniquenessServiceTest.java
git commit -m "feat(rowkey): 提交期行键唯一性装配服务 + @QuarkusTest"
```

---

## Task 4: 接入 `QuotationService.submit`（提交期拦截）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java`（`submit(UUID id, UUID userId)`，`:648`）

- [ ] **Step 1: 注入服务字段**

在 `QuotationService` 既有 `@Inject` 区（如 `SnapshotCollectorService snapshotCollectorService;` 之后，`:67` 附近）加入：

```java
    @Inject
    com.cpq.quotation.service.rowkey.RowKeyUniquenessService rowKeyUniquenessService;
```

- [ ] **Step 2: 在 submit 内、创建 snapshot 前插入校验**

定位 `submit(UUID id, UUID userId)` 中：

```java
        // Create product snapshots for all line items
        List<QuotationLineItem> lineItems = QuotationLineItem.list("quotationId = ?1", id);
```

在这一行**之后**、其后的 `for (QuotationLineItem li : lineItems) {` 循环**之前**，插入：

```java
        // 行键唯一性校验（设计 E）：组合行键不可重复，含 driver 展开行。冲突即拒绝提交。
        java.util.List<com.cpq.quotation.service.rowkey.RowKeyUniquenessService.LineItemRows> rowsForCheck =
            new java.util.ArrayList<>();
        for (QuotationLineItem li : lineItems) {
            String label = li.productNameSnapshot != null ? li.productNameSnapshot
                         : (li.productPartNoSnapshot != null ? li.productPartNoSnapshot : "明细");
            rowsForCheck.add(new com.cpq.quotation.service.rowkey.RowKeyUniquenessService.LineItemRows(
                label, li.quoteCardValues));
        }
        java.util.List<com.cpq.quotation.service.rowkey.RowKeyConflict> conflicts =
            rowKeyUniquenessService.collectConflicts(q.quoteCardStructure, rowsForCheck);
        if (!conflicts.isEmpty()) {
            StringBuilder sb = new StringBuilder("行键重复，无法提交：");
            for (com.cpq.quotation.service.rowkey.RowKeyConflict c : conflicts) {
                sb.append("\n· ").append(c.describe());
            }
            throw new BusinessException(422, sb.toString());
        }
```

> `q.quoteCardStructure`：`Quotation` 实体上的结构快照列（与 `useCardSnapshots` 读的 `quoteCardStructure` 同源）。若该字段在 `Quotation` 实体上的属性名不同，按实体实际属性名调整（grep `quote_card_structure` 确认列→属性映射）。

- [ ] **Step 3: 触发后端重启并验证编译**

Run: `cd cpq-backend && touch src/main/java/com/cpq/quotation/service/QuotationService.java && sleep 7 && curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health`
Expected: `200`（编译通过、Quarkus 起来）。若 500/启动失败，看 dev 日志修编译错。

- [ ] **Step 4: 全量回归既有报价测试**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest='RowKey*,QuotationServiceTest' 2>&1 | tail -20`
Expected: `BUILD SUCCESS`。若无 `QuotationServiceTest`，仅跑 `RowKey*` 并在提交说明里记录"submit 接入由 Step 5 集成验证覆盖"。

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java
git commit -m "feat(rowkey): submit 期行键唯一性拦截（422 + 冲突明细）"
```

---

## Task 5: 集成自检（按 CLAUDE.md 强制自检）

- [ ] **Step 1: 健康检查**

Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health`
Expected: `200`。

- [ ] **Step 2: 构造重复行键报价单并提交（手动验证拦截）**

前置：在一份 DRAFT 报价单里，让某组件的 driver 展开 + 手动行产生重复组合键（最简：手动加一行与已有行 `rowKeyFields` 全列相同）。

Run（替换 `<DRAFT_ID>`）：
`curl -s -X POST http://localhost:8081/api/cpq/quotations/<DRAFT_ID>/submit -H "Content-Type: application/json" | head -c 400`
Expected: 返回非 200 业务错误，`message` 含「行键重复，无法提交」+ 冲突明细行号。

- [ ] **Step 3: 唯一行键报价单提交成功（回归）**

对一份所有组件行键唯一的 DRAFT 重复 Step 2 的 curl。
Expected: 提交成功（status → SUBMITTED），证明校验不误伤正常单。

- [ ] **Step 4: 自检声明**

在完成说明里写明（CLAUDE.md 强制）：
> `RowKeyConflictDetectorTest` / `RowKeyUniquenessServiceTest` 全过 ✅；`/q/health` → 200 ✅；重复行键单 submit → 422 + 冲突明细 ✅；唯一行键单 submit → SUBMITTED ✅。

---

## Self-Review（写后自检）

**Spec coverage（对照 spec §7 / 设计 E）：**
- 「保存时校验」→ Task 4 接入 submit ✅
- 「driver 展开行 + 手动行全算 computeRowKey」→ Task 3 遍历 `baseRows[].driverRow`（提交时 baseRows 已含 driver 展开 + 手动行）✅
- 「列出冲突行并拦截保存」→ Task 4 抛 422 + `RowKeyConflict.describe()` 行号明细 ✅
- 「driver 自身带重复也报冲突」→ detector 不区分行来源，driver 行同样判重 ✅
- 「组合键」→ `computeRowKey` 多列 `||` 拼接 ✅
- 「前端提交前提示」→ **明确不在本 Plan**（Plan 1b 后续，已在 Scope 标注）。

**Placeholder scan：** 无 TBD/TODO；唯二的"按实际调整"注记（computeRowKey 口径、quoteCardStructure 属性名）是源码核对点，非占位——已给出确认手段（跑测试 / grep 列名）。

**Type consistency：** `RowKeyConflict(componentName, rowKey, rowIndices)` 三处用法一致；`LineItemRows(lineItemLabel, valuesJson)` 在 Task 3 定义、Task 4 构造，字段名一致；`collectConflicts(String, List<LineItemRows>)` 签名 Task 3 定义、Task 4 调用一致。

**风险点：** `q.quoteCardStructure` 的实体属性名、`computeRowKey` 行号兜底口径——均在 Task 中给了实测确认步骤，执行时一跑即知。
