# 报价单复制 — 模板选择 + 跨模板用户输入迁移 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让报价单「复制」先弹模板选择抽屉（默认源模板），换模板后只迁移用户输入值（按字段名 + 仅 INPUT 类型），driver/公式由新模板重算，并修正现有 copy 丢父子链 + 不重建 4 份值快照的缺陷。

**Architecture:** 前端「复制」动作改为打开模板选择抽屉（仅列 PUBLISHED + QUOTATION 模板），用户确认后调 `POST /quotations/{id}/copy` 带 `{templateId}`。后端 `QuotationService.copy(id, templateId)` 复制单据头（`customerTemplateId`=新模板）+ 行项目（含 `compositeType`/`parentLineItemId` 重映射）+ 每个新模板页签的 `QuotationLineComponentData`（`row_data` 仅迁移 INPUT 类型字段、按字段名映射、snapshot_rows 留空），最后逐行调既有 `CardSnapshotService.refreshQuoteCardValues` 重建报价侧 4 份快照（driver 重展开 + 合并迁移来的 row_data 输入 + 重算公式），有核价模板再调 `refreshCostingCardValues`。

**Tech Stack:** Java 17 + Quarkus + Hibernate Panache（后端）；React + Ant Design Drawer（前端）；JUnit5/Mockito（后端单测）；Playwright（E2E）。

**核心设计依据（spec `docs/superpowers/specs/2026-06-16-quotation-copy-with-template-selection-design.md` + 代码核实）：**
- 报价模板挂单据头 `Quotation.customerTemplateId`（非行级）。
- 渲染权威源 = 行级 4 份值快照 `quote_card_values` 等；`row_data`=用户输入层（`[{字段名:值, row_index}]`），`snapshot_rows`=driver 基础层。
- 字段类型仅 `INPUT`/`INPUT_TEXT`/`INPUT_NUMBER` 为用户输入需迁移；`FIXED_VALUE`/`DATA_SOURCE`/`BASIC_DATA`/`FORMULA`/`LIST_FORMULA` 由新模板重算，不迁移。
- 同模板内 componentId 唯一、tabName 唯一 → 页签配对一对一（先 componentId 后 tabName）。
- `refreshQuoteCardValues(li)` 已实现「重展开新模板 driver + 按 rowKey 保编辑 + 合并 row_data 输入 + 重算公式」，迁移复用它。

---

## File Structure

**后端（worktree 内 `cpq-backend/`）：**
- 修改 `src/main/java/com/cpq/quotation/service/QuotationService.java`：新增 `copy(UUID id, UUID templateId)` 重载 + 私有 helper `mapInputRowData(...)` / `loadTemplateTabFields(...)` / `migrateAndCreateComponentData(...)`；保留旧 `copy(UUID id)` 改为委托 `copy(id, null)`。
- 修改 `src/main/java/com/cpq/quotation/resource/QuotationResource.java`：copy 端点接收可选 body `{templateId}`。
- 复用 `CardSnapshotService.refreshQuoteCardValues(li)` / `refreshCostingCardValues(quotationId)`（不改）。
- 新增 `src/test/java/com/cpq/quotation/service/QuotationCopyMappingTest.java`：纯逻辑单测（字段名+INPUT 类型映射）。

**前端（worktree 内 `cpq-frontend/`）：**
- 修改 `src/services/quotationService.ts`：`copy(id, templateId?)` 带 body。
- 新增 `src/pages/quotation/CopyQuotationDrawer.tsx`：模板选择抽屉。
- 修改 `src/pages/quotation/QuotationList.tsx`：copy 动作打开抽屉。

---

## Task 1: 后端 — 纯映射 helper `mapInputRowData`（TDD 核心）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/QuotationCopyMappingTest.java`

行为：给定源页签 `row_data`（JSON 数组字符串，行形如 `{"字段名":值,...,"row_index":N}`）与目标页签的「输入型字段名集合」`targetInputFieldNames`，输出新 `row_data` JSON——逐行保留 `row_index`，只搬 `targetInputFieldNames` 内且源行存在的字段值；其余字段不写（留空，由新模板重算）。源 `row_data` 为 null/`[]` → 返回 `"[]"`。

- [ ] **Step 1: 写失败测试**

```java
package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class QuotationCopyMappingTest {
    private final ObjectMapper M = new ObjectMapper();

    @Test
    void migratesOnlyTargetInputFieldsByName() throws Exception {
        String src = "[{\"row_index\":0,\"材料管理费\":\"12\",\"外购件管理费\":\"33\",\"利润\":99,\"汇率\":7.12}]";
        // 目标页签输入字段：材料管理费 + 外购件管理费（利润=FORMULA 已排除；汇率=BASIC_DATA 已排除）
        Set<String> targetInputs = Set.of("材料管理费", "外购件管理费");
        String out = QuotationService.mapInputRowData(src, targetInputs, M);
        var rows = M.readTree(out);
        assertEquals(1, rows.size());
        assertEquals(0, rows.get(0).get("row_index").asInt());
        assertEquals("12", rows.get(0).get("材料管理费").asText());
        assertEquals("33", rows.get(0).get("外购件管理费").asText());
        assertFalse(rows.get(0).has("利润"), "FORMULA 字段不应迁移");
        assertFalse(rows.get(0).has("汇率"), "BASIC_DATA 字段不应迁移");
    }

    @Test
    void unmatchedTargetFieldLeftEmpty() throws Exception {
        String src = "[{\"row_index\":0,\"材料管理费\":\"12\"}]";
        Set<String> targetInputs = Set.of("材料管理费", "新字段");  // 新字段源无值
        String out = QuotationService.mapInputRowData(src, targetInputs, M);
        var row = M.readTree(out).get(0);
        assertEquals("12", row.get("材料管理费").asText());
        assertFalse(row.has("新字段"), "源无值的目标字段留空(不写键)");
    }

    @Test
    void nullOrEmptySourceReturnsEmptyArray() throws Exception {
        assertEquals("[]", QuotationService.mapInputRowData(null, Set.of("x"), M));
        assertEquals("[]", QuotationService.mapInputRowData("[]", Set.of("x"), M));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -o test -Dtest=QuotationCopyMappingTest`
Expected: 编译失败 `cannot find symbol: method mapInputRowData`。

- [ ] **Step 3: 写最小实现**

在 `QuotationService` 类内新增（package-private static，便于单测；放在 `copy` 方法附近）：

```java
/**
 * 跨模板复制：从源页签 row_data 只迁移「目标页签输入型字段」的值（按字段名匹配）。
 * 非输入字段(FORMULA/BASIC_DATA/DATA_SOURCE/FIXED_VALUE/LIST_FORMULA)不迁移，由新模板重算。
 * @param sourceRowDataJson 源 quotation_line_component_data.row_data（JSON 数组字符串）
 * @param targetInputFieldNames 目标页签中 field_type ∈ {INPUT,INPUT_TEXT,INPUT_NUMBER} 的字段名集合
 * @return 新 row_data JSON（逐行保留 row_index + 命中的输入字段值）
 */
static String mapInputRowData(String sourceRowDataJson, java.util.Set<String> targetInputFieldNames,
                              com.fasterxml.jackson.databind.ObjectMapper mapper) {
    if (sourceRowDataJson == null || sourceRowDataJson.isBlank()) return "[]";
    try {
        com.fasterxml.jackson.databind.JsonNode rows = mapper.readTree(sourceRowDataJson);
        if (!rows.isArray() || rows.isEmpty()) return "[]";
        com.fasterxml.jackson.databind.node.ArrayNode out = mapper.createArrayNode();
        for (com.fasterxml.jackson.databind.JsonNode row : rows) {
            com.fasterxml.jackson.databind.node.ObjectNode newRow = mapper.createObjectNode();
            if (row.has("row_index")) newRow.set("row_index", row.get("row_index"));
            for (String fieldName : targetInputFieldNames) {
                if (row.has(fieldName)) newRow.set(fieldName, row.get(fieldName));
            }
            out.add(newRow);
        }
        return mapper.writeValueAsString(out);
    } catch (Exception e) {
        return "[]";
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -o test -Dtest=QuotationCopyMappingTest`
Expected: `BUILD SUCCESS`，3 tests passed。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/QuotationCopyMappingTest.java
git commit -m "feat(quotation-copy): row_data 输入字段名映射 helper + 单测"
```

---

## Task 2: 后端 — 读新模板页签的「输入型字段名」`loadTemplateTabFields`

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/QuotationCopyMappingTest.java`

行为：解析模板 `components_snapshot`（JSON 数组，每项 `{componentId, tabName, fields:[{name, field_type}]}`），返回 `List<TabFields>`，每项含 `componentId / tabName / inputFieldNames(Set<String>)`。`inputFieldNames` = `fields` 中 `field_type ∈ {INPUT, INPUT_TEXT, INPUT_NUMBER}` 的 `name`。

- [ ] **Step 1: 写失败测试**（追加到 `QuotationCopyMappingTest`）

```java
    @Test
    void parsesInputFieldNamesFromSnapshot() throws Exception {
        String snap = "[{\"componentId\":\"c1\",\"tabName\":\"产品\",\"fields\":["
            + "{\"name\":\"材料管理费\",\"field_type\":\"INPUT_NUMBER\"},"
            + "{\"name\":\"品名\",\"field_type\":\"INPUT_TEXT\"},"
            + "{\"name\":\"利润\",\"field_type\":\"FORMULA\"},"
            + "{\"name\":\"汇率\",\"field_type\":\"BASIC_DATA\"}]}]";
        var tabs = QuotationService.parseTemplateTabFields(snap, M);
        assertEquals(1, tabs.size());
        assertEquals("c1", tabs.get(0).componentId);
        assertEquals("产品", tabs.get(0).tabName);
        assertEquals(Set.of("材料管理费", "品名"), tabs.get(0).inputFieldNames);
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -o test -Dtest=QuotationCopyMappingTest`
Expected: 编译失败 `cannot find symbol: class TabFields / method parseTemplateTabFields`。

- [ ] **Step 3: 写最小实现**（`QuotationService` 内）

```java
/** 复制迁移用：模板某页签的标识 + 输入型字段名集合。 */
static final class TabFields {
    final String componentId;
    final String tabName;
    final java.util.Set<String> inputFieldNames;
    TabFields(String componentId, String tabName, java.util.Set<String> inputFieldNames) {
        this.componentId = componentId; this.tabName = tabName; this.inputFieldNames = inputFieldNames;
    }
}

private static final java.util.Set<String> INPUT_FIELD_TYPES =
        java.util.Set.of("INPUT", "INPUT_TEXT", "INPUT_NUMBER");

/** 解析 components_snapshot → 每页签的输入字段名集合。 */
static java.util.List<TabFields> parseTemplateTabFields(String componentsSnapshotJson,
                                                        com.fasterxml.jackson.databind.ObjectMapper mapper) {
    java.util.List<TabFields> result = new java.util.ArrayList<>();
    if (componentsSnapshotJson == null || componentsSnapshotJson.isBlank()) return result;
    try {
        com.fasterxml.jackson.databind.JsonNode arr = mapper.readTree(componentsSnapshotJson);
        if (!arr.isArray()) return result;
        for (com.fasterxml.jackson.databind.JsonNode tab : arr) {
            java.util.Set<String> inputs = new java.util.LinkedHashSet<>();
            com.fasterxml.jackson.databind.JsonNode fields = tab.path("fields");
            if (fields.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode f : fields) {
                    String type = f.path("field_type").asText("");
                    String name = f.path("name").asText("");
                    if (!name.isEmpty() && INPUT_FIELD_TYPES.contains(type)) inputs.add(name);
                }
            }
            result.add(new TabFields(
                tab.path("componentId").asText(""),
                tab.path("tabName").asText(""),
                inputs));
        }
    } catch (Exception ignore) { }
    return result;
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -o test -Dtest=QuotationCopyMappingTest`
Expected: `BUILD SUCCESS`，4 tests passed。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/QuotationCopyMappingTest.java
git commit -m "feat(quotation-copy): 解析模板页签输入字段名 + 单测"
```

---

## Task 3: 后端 — `copy(id, templateId)` 主流程（头+行项目+父子链+页签数据+快照重建）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java:1195-1271`（替换现有 `copy(UUID id)`）

依赖：Task 1/2 的 helper；既有 `CardSnapshotService`（已注入为 `cardSnapshotService`，若未注入需 `@Inject`）。先确认注入名：

- [ ] **Step 1: 确认 CardSnapshotService 注入**

Run: `cd cpq-backend && grep -n "CardSnapshotService" src/main/java/com/cpq/quotation/service/QuotationService.java | head`
Expected: 找到 `@Inject ... CardSnapshotService ...`。若没有，则在类字段区加：

```java
    @jakarta.inject.Inject
    com.cpq.quotation.service.CardSnapshotService cardSnapshotService;
```

- [ ] **Step 2: 替换 copy 方法**

把现有 `public QuotationDTO copy(UUID id) { ... }`（约 1195-1271）整体替换为下面两段（旧签名委托新签名）：

```java
    @Transactional
    public QuotationDTO copy(UUID id) {
        return copy(id, null);
    }

    /**
     * 复制报价单。templateId 非空 → 换模板：新单 customerTemplateId=templateId，
     * 行项目页签按新模板重建，仅迁移用户输入值(INPUT 类型，按字段名)，driver/公式由新模板重算。
     * templateId 为空 → 沿用源 customerTemplateId（同模板复制，同样走重建流程修正历史缺陷）。
     */
    @Transactional
    public QuotationDTO copy(UUID id, UUID templateId) {
        Quotation source = Quotation.findById(id);
        if (source == null) throw new BusinessException(404, "Quotation not found: " + id);

        UUID newTemplateId = (templateId != null) ? templateId : source.customerTemplateId;

        // 读新模板页签输入字段（用于 row_data 迁移）
        java.util.List<TabFields> newTabs;
        {
            Object snap = null;
            if (newTemplateId != null) {
                var rows = getEntityManager().createNativeQuery(
                        "SELECT components_snapshot FROM template WHERE id = :tid")
                        .setParameter("tid", newTemplateId).getResultList();
                if (!rows.isEmpty() && rows.get(0) != null) snap = rows.get(0);
            }
            newTabs = parseTemplateTabFields(snap == null ? null : snap.toString(), MAPPER);
        }

        // 1. 单据头
        Quotation copy = new Quotation();
        copy.quotationNumber = generateQuotationNumber();
        copy.customerId = source.customerId;
        copy.name = source.name + " (Copy)";
        copy.contactId = source.contactId;
        copy.contactName = source.contactName;
        copy.contactPhone = source.contactPhone;
        copy.contactEmail = source.contactEmail;
        copy.projectName = source.projectName;
        copy.opportunityId = source.opportunityId;
        copy.salesRepId = source.salesRepId;
        copy.quoteType = source.quoteType;
        copy.priority = source.priority;
        copy.stage = source.stage;
        copy.expectedCloseDate = source.expectedCloseDate;
        copy.status = "DRAFT";
        copy.expiryDate = LocalDate.now().plusDays(30);
        copy.paymentTerms = source.paymentTerms;
        copy.deliveryCycle = source.deliveryCycle;
        copy.originalAmount = source.originalAmount;
        copy.systemDiscountRate = source.systemDiscountRate;
        copy.finalDiscountRate = source.finalDiscountRate;
        copy.totalAmount = source.totalAmount;
        copy.sourceQuotationId = source.id;
        copy.snapshotCustomerName = source.snapshotCustomerName;
        copy.snapshotCustomerLevel = source.snapshotCustomerLevel;
        copy.snapshotCustomerRegion = source.snapshotCustomerRegion;
        copy.snapshotCustomerIndustry = source.snapshotCustomerIndustry;
        copy.snapshotCustomerAddress = source.snapshotCustomerAddress;
        copy.customerTemplateId = newTemplateId;             // 换模板（报价侧）
        copy.costingCardTemplateId = source.costingCardTemplateId;  // 核价模板沿用源
        copy.persist();

        // 2. 行项目（先建，记录 源id→新id 映射，父子链稍后重映射）
        java.util.Map<UUID, UUID> lineIdMap = new java.util.LinkedHashMap<>();
        List<QuotationLineItem> sourceItems =
                QuotationLineItem.list("quotationId = ?1 ORDER BY sortOrder ASC", id);
        java.util.List<QuotationLineItem> newItems = new java.util.ArrayList<>();
        for (QuotationLineItem srcLi : sourceItems) {
            QuotationLineItem newLi = new QuotationLineItem();
            newLi.quotationId = copy.id;
            newLi.productId = srcLi.productId;
            newLi.templateId = newTemplateId;
            newLi.productNameSnapshot = srcLi.productNameSnapshot;
            newLi.productPartNoSnapshot = srcLi.productPartNoSnapshot;
            newLi.productAttributeValues = srcLi.productAttributeValues;
            newLi.subtotal = java.math.BigDecimal.ZERO;          // 占位，重建后由快照/公式回填
            newLi.systemDiscountRate = srcLi.systemDiscountRate;
            newLi.finalDiscountRate = srcLi.finalDiscountRate;
            newLi.sortOrder = srcLi.sortOrder;
            newLi.customerPartNo = srcLi.customerPartNo;
            newLi.partVersionLocked = srcLi.partVersionLocked;
            newLi.compositeType = srcLi.compositeType;            // 组合产品父子链：类型
            // parentLineItemId 稍后重映射
            // 4 份值快照列留空（重建）
            newLi.persist();
            lineIdMap.put(srcLi.id, newLi.id);
            newItems.add(newLi);

            // 工序
            for (QuotationLineProcess srcP : QuotationLineProcess.<QuotationLineProcess>list("lineItemId = ?1", srcLi.id)) {
                QuotationLineProcess newP = new QuotationLineProcess();
                newP.lineItemId = newLi.id;
                newP.processId = srcP.processId;
                newP.persist();
            }

            // 页签数据：按新模板页签建，row_data 仅迁移 INPUT 字段（按字段名）
            migrateAndCreateComponentData(srcLi.id, newLi.id, newTabs);
        }

        // 3. 重映射父子链
        for (int i = 0; i < sourceItems.size(); i++) {
            UUID srcParent = sourceItems.get(i).parentLineItemId;
            if (srcParent != null) newItems.get(i).parentLineItemId = lineIdMap.get(srcParent);
        }

        // 4. 用新模板重建报价侧 4 份快照（driver 重展开 + 合并迁移 row_data 输入 + 重算公式）
        for (QuotationLineItem newLi : newItems) {
            cardSnapshotService.refreshQuoteCardValues(newLi);
        }
        // 核价侧（若有核价模板）整单重建
        if (copy.costingCardTemplateId != null) {
            cardSnapshotService.refreshCostingCardValues(copy.id);
        }

        LOG.infof("Copied quotation id=%s -> id=%s number=%s template=%s",
                id, copy.id, copy.quotationNumber, newTemplateId);
        QuotationDTO dto = QuotationDTO.from(copy);
        dto.lineItems = loadLineItems(copy.id);
        return dto;
    }

    /** 按新模板页签建 QuotationLineComponentData，row_data 仅迁移 INPUT 字段（先 componentId 后 tabName 配对）。 */
    private void migrateAndCreateComponentData(UUID srcLineItemId, UUID newLineItemId,
                                               java.util.List<TabFields> newTabs) {
        List<QuotationLineComponentData> srcData =
                QuotationLineComponentData.list("lineItemId = ?1", srcLineItemId);
        java.util.Map<String, QuotationLineComponentData> byCompId = new java.util.HashMap<>();
        java.util.Map<String, QuotationLineComponentData> byTabName = new java.util.HashMap<>();
        for (QuotationLineComponentData cd : srcData) {
            if (cd.componentId != null) byCompId.put(cd.componentId.toString(), cd);
            if (cd.tabName != null) byTabName.put(cd.tabName, cd);
        }
        int sort = 0;
        for (TabFields tab : newTabs) {
            QuotationLineComponentData match = byCompId.get(tab.componentId);
            if (match == null) match = byTabName.get(tab.tabName);
            String migratedRowData = (match == null)
                    ? "[]"
                    : mapInputRowData(match.rowData, tab.inputFieldNames, MAPPER);

            QuotationLineComponentData newCd = new QuotationLineComponentData();
            newCd.lineItemId = newLineItemId;
            newCd.componentId = (tab.componentId == null || tab.componentId.isEmpty())
                    ? null : UUID.fromString(tab.componentId);
            newCd.tabName = tab.tabName;
            newCd.rowData = migratedRowData;
            newCd.snapshotRows = null;       // driver 由 refreshQuoteCardValues 重展开
            newCd.subtotal = java.math.BigDecimal.ZERO;
            newCd.sortOrder = sort++;
            newCd.persist();
        }
    }
```

> 注：`MAPPER` 为 `QuotationService` 内既有静态 ObjectMapper；若类内常量名不同（如 `OBJECT_MAPPER`），Step 1 grep 确认后改名。`getEntityManager()` 为 Panache 提供；若类已注入 `EntityManager em` 则用 `em`。

- [ ] **Step 2: 确认 MAPPER / em 命名一致**

Run: `cd cpq-backend && grep -nE "ObjectMapper|EntityManager" src/main/java/com/cpq/quotation/service/QuotationService.java | head`
Expected: 看到既有 ObjectMapper 常量名 + EntityManager 注入名；据此校正上面代码中的 `MAPPER`/`getEntityManager()`。

- [ ] **Step 3: 编译**

Run: `cd cpq-backend && ./mvnw -o compile`
Expected: `BUILD SUCCESS`。

- [ ] **Step 4: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java
git commit -m "feat(quotation-copy): copy(id,templateId) 换模板复制 + 父子链重映射 + 快照重建"
```

---

## Task 4: 后端 — copy 端点接收 `{templateId}`

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/resource/QuotationResource.java:312-316`

- [ ] **Step 1: 替换端点**

把：

```java
    @POST
    @Path("/{id}/copy")
    public ApiResponse<QuotationDTO> copy(@PathParam("id") UUID id) {
        return ApiResponse.success(quotationService.copy(id));
    }
```

替换为：

```java
    @POST
    @Path("/{id}/copy")
    public ApiResponse<QuotationDTO> copy(@PathParam("id") UUID id, java.util.Map<String, Object> body) {
        UUID templateId = null;
        if (body != null && body.get("templateId") != null && !body.get("templateId").toString().isBlank()) {
            templateId = UUID.fromString(body.get("templateId").toString());
        }
        return ApiResponse.success(quotationService.copy(id, templateId));
    }
```

- [ ] **Step 2: 触发后端重启 + 健康检查**

Run: `cd cpq-backend && touch src/main/java/com/cpq/quotation/resource/QuotationResource.java && sleep 7 && curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/api/cpq/quotations`
Expected: `401`（auth 正常，非 500）。

- [ ] **Step 3: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/resource/QuotationResource.java
git commit -m "feat(quotation-copy): copy 端点接收可选 templateId body"
```

---

## Task 5: 前端 — `quotationService.copy(id, templateId?)`

**Files:**
- Modify: `cpq-frontend/src/services/quotationService.ts`（现有 `copy` 约第 140 行）

- [ ] **Step 1: 确认现有 copy 签名**

Run: `cd cpq-frontend && grep -n "copy" src/services/quotationService.ts`
Expected: 形如 `copy: (id: string) => api.post(\`/quotations/${id}/copy\`)`。

- [ ] **Step 2: 改为带可选 templateId**

把现有 copy 行替换为：

```ts
  copy: (id: string, templateId?: string) =>
    api.post(`/quotations/${id}/copy`, templateId ? { templateId } : {}),
```

- [ ] **Step 3: TS 检查**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误。

- [ ] **Step 4: 提交**

```bash
git add cpq-frontend/src/services/quotationService.ts
git commit -m "feat(quotation-copy): 前端 copy 支持传 templateId"
```

---

## Task 6: 前端 — 模板选择抽屉 `CopyQuotationDrawer`

**Files:**
- Create: `cpq-frontend/src/pages/quotation/CopyQuotationDrawer.tsx`

行为：Drawer（右侧，width 480）。打开时拉 `GET /api/cpq/templates?status=PUBLISHED&templateKind=QUOTATION`（用既有 templateService 或 api），下拉/单选列出模板（label=名称+版本），默认选中 `defaultTemplateId`（源单 customerTemplateId）。确认按钮调 `onConfirm(selectedTemplateId)`。

- [ ] **Step 1: 确认模板列表 service**

Run: `cd cpq-frontend && grep -rn "templateKind\|status.*PUBLISHED\|/templates" src/services/templateService.ts | head`
Expected: 找到 templateService.list 或等价；若无合适方法，直接用 `api.get('/templates', { params: { status:'PUBLISHED', templateKind:'QUOTATION', size: 200 } })`。

- [ ] **Step 2: 创建抽屉组件**

```tsx
import React, { useEffect, useState } from 'react';
import { Drawer, Select, Button, Space, Alert, message } from 'antd';
import api from '../../services/api';

interface TemplateOption { id: string; name: string; version?: string; }

interface Props {
  open: boolean;
  defaultTemplateId?: string;
  onClose: () => void;
  onConfirm: (templateId: string) => Promise<void> | void;
}

const CopyQuotationDrawer: React.FC<Props> = ({ open, defaultTemplateId, onClose, onConfirm }) => {
  const [templates, setTemplates] = useState<TemplateOption[]>([]);
  const [selected, setSelected] = useState<string | undefined>(defaultTemplateId);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!open) return;
    setSelected(defaultTemplateId);
    setLoading(true);
    api.get('/templates', { params: { status: 'PUBLISHED', templateKind: 'QUOTATION', size: 200 } })
      .then((res: any) => {
        const list = (res.data?.data ?? res.data ?? []) as any[];
        setTemplates(list.map((t) => ({ id: t.id, name: t.name, version: t.version })));
      })
      .catch((e: any) => message.error(e.message || '加载模板失败'))
      .finally(() => setLoading(false));
  }, [open, defaultTemplateId]);

  const handleOk = async () => {
    if (!selected) { message.warning('请选择模板'); return; }
    setSubmitting(true);
    try { await onConfirm(selected); } finally { setSubmitting(false); }
  };

  const changed = selected && defaultTemplateId && selected !== defaultTemplateId;

  return (
    <Drawer
      title="复制报价单 — 选择模板"
      placement="right"
      width={480}
      open={open}
      onClose={onClose}
      destroyOnClose
      footer={
        <Space style={{ float: 'right' }}>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" loading={submitting} onClick={handleOk}>确认复制</Button>
        </Space>
      }
    >
      <p>默认使用源报价单的模板。换模板后：页签相同的迁移用户输入值，不同的留空，公式/数据由新模板重算。</p>
      <Select
        style={{ width: '100%' }}
        loading={loading}
        showSearch
        optionFilterProp="label"
        placeholder="选择报价模板（仅已发布）"
        value={selected}
        onChange={setSelected}
        options={templates.map((t) => ({
          value: t.id,
          label: t.version ? `${t.name} ${t.version}` : t.name,
        }))}
      />
      {changed ? (
        <Alert style={{ marginTop: 12 }} type="warning" showMessage={false}
          message="已更换模板：仅页签字段相同的输入值会被迁移，其余留空。" />
      ) : null}
    </Drawer>
  );
};

export default CopyQuotationDrawer;
```

> 注：`api` 响应解包形式以本项目既有用法为准（Step 1 已查 service 用法）；若项目统一 `res.data.data`，去掉冗余兜底。`Alert` 无 `showMessage` 属性 → 删除该误写属性，仅留 `message`。

- [ ] **Step 3: 修正并 TS 检查**

去掉上面 `Alert` 的非法 `showMessage` 属性（仅保留 `type/message/showIcon`）。
Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json && curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/CopyQuotationDrawer.tsx`
Expected: TS 0 错误；Vite 200。

- [ ] **Step 4: 提交**

```bash
git add cpq-frontend/src/pages/quotation/CopyQuotationDrawer.tsx
git commit -m "feat(quotation-copy): 模板选择抽屉 CopyQuotationDrawer"
```

---

## Task 7: 前端 — `QuotationList` 复制动作打开抽屉

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationList.tsx:164-176`

- [ ] **Step 1: 接入抽屉状态 + 改 copy 动作**

在组件顶部 import：

```tsx
import CopyQuotationDrawer from './CopyQuotationDrawer';
```

在组件内（其他 useState 附近）加状态：

```tsx
  const [copySource, setCopySource] = useState<{ id: string; templateId?: string } | null>(null);
```

把现有 copy 动作的 `onClick`（约 169-175）替换为打开抽屉：

```tsx
      onClick: (rows) => {
        const r: any = rows[0];
        setCopySource({ id: r.id, templateId: r.customerTemplateId });
      },
```

> 注：行数据字段名以列表数据为准；Step 2 确认 `customerTemplateId` 是否在行对象上，没有则传 `undefined`（抽屉默认不预选，仍可手动选）。

在组件 return 的 JSX 末尾（根节点内）加抽屉：

```tsx
      <CopyQuotationDrawer
        open={!!copySource}
        defaultTemplateId={copySource?.templateId}
        onClose={() => setCopySource(null)}
        onConfirm={async (templateId) => {
          try {
            const res = await quotationService.copy(copySource!.id, templateId);
            message.success('复制成功');
            setCopySource(null);
            navigate(`/quotations/${res.data.id}/edit`);
          } catch (e: any) { message.error(e.message); }
        }}
      />
```

- [ ] **Step 2: 确认行对象是否含 customerTemplateId**

Run: `cd cpq-frontend && grep -n "customerTemplateId\|templateId" src/pages/quotation/QuotationList.tsx src/services/quotationService.ts | head`
Expected: 确认列表行字段名；若列表 DTO 无模板 id，则 `defaultTemplateId` 传 `undefined`（用户手动选），并在 RECORD 记录该限制。

- [ ] **Step 3: TS 检查 + Vite**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json && curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/QuotationList.tsx`
Expected: TS 0 错误；Vite 200。

- [ ] **Step 4: 提交**

```bash
git add cpq-frontend/src/pages/quotation/QuotationList.tsx
git commit -m "feat(quotation-copy): 列表复制动作改为打开模板选择抽屉"
```

---

## Task 8: 集成验证 + E2E + 文档

**Files:**
- Modify: `docs/RECORD.md`（追加记录）
- Verify: 后端 curl 同模板/换模板复制；前端 E2E `quotation-flow.spec.ts`

- [ ] **Step 1: 后端同模板复制冒烟（curl，需登录 cookie）**

用浏览器/已存在 E2E 登录态，或对一个已知 DRAFT 报价单 `{qid}`：
Run（同模板）: `curl -s -X POST http://localhost:8081/api/cpq/quotations/{qid}/copy -H 'Content-Type: application/json' -d '{}' -b <cookie>`
Expected: 200，返回新单 id，status=DRAFT。

- [ ] **Step 2: DB 校验新单（换模板）**

对新单 id `{newid}`：
Run: `PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -tA -c "SELECT customer_template_id, status FROM quotation WHERE id='{newid}'; SELECT count(*) FROM quotation_line_item WHERE quotation_id='{newid}' AND quote_card_values IS NOT NULL;"`
Expected: customer_template_id=所选模板；status=DRAFT；行项目 quote_card_values 非空数 = 行项目数（快照已重建）。

- [ ] **Step 3: 父子链校验（若源单含组合产品）**

Run: `PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -tA -c "SELECT composite_type, (parent_line_item_id IS NOT NULL) FROM quotation_line_item WHERE quotation_id='{newid}' ORDER BY sort_order;"`
Expected: PART 行 parent_line_item_id 非空且指向同新单内的父行（无悬空）。

- [ ] **Step 4: E2E 回归（协议级改动强制）**

Run:
```bash
cd cpq-frontend
rm -f e2e/screenshots/qf-*.png
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```
Expected: `1 passed`，`'加载中' final count = 0`，全 Tab `'加载中'=0`。（首跑若卡 Step1 antd 下拉为已知 flakiness，复跑一次。）

- [ ] **Step 5: 记录 RECORD + 提交**

在 `docs/RECORD.md` 顶部条目区追加：
```
[2026-06-17] 报价单 - 复制支持模板选择+跨模板用户输入迁移 | QuotationService.copy(id,templateId) / QuotationResource / CopyQuotationDrawer / QuotationList | 选模板抽屉(仅QUOTATION+PUBLISHED,默认源模板);换模板只迁移INPUT类型字段值(按字段名,页签先componentId后tabName配对),driver/公式由新模板refreshQuoteCardValues重算;顺带修现有copy丢compositeType/parentLineItemId父子链+不重建4份快照缺陷。
```
Run:
```bash
git add docs/RECORD.md
git commit -m "docs(record): 报价单复制-模板选择+跨模板迁移"
```

---

## Self-Review 结论

- **Spec 覆盖**：模板抽屉(仅 QUOTATION+PUBLISHED+默认源模板)=Task6/7；换模板=Task3 头 customerTemplateId；页签配对(先 componentId 后 tabName)=Task3 migrateAndCreateComponentData；只迁移 INPUT 字段按名映射=Task1/2；公式/driver 重算=Task3 refresh*；父子链=Task3 重映射；修 4 份快照缺陷=Task3 refresh*。全覆盖。
- **字段映射降级**：spec 的「同名一对多按顺序」模糊匹配未在本计划实现（默认仅精确名匹配，未匹配留空），符合 spec「默认只做 key/name 精确匹配，模糊匹配为可选增强」——列为后续增强，非本计划范围。
- **类型一致**：`TabFields`/`mapInputRowData`/`parseTemplateTabFields` 跨 Task1-3 签名一致；`copy(id)`/`copy(id,templateId)` 重载一致。
- **占位扫描**：无 TBD；每个改码步骤含完整代码；Step「确认命名」类步骤为真实校验动作（grep）非占位。
