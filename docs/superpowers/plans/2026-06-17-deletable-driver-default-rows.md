# 报价单 driver 默认行可永久删除 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让报价单编辑页里 driver 自动展开的"默认行"可被用户永久删除（刷新/重算/加产品后不回来），适用所有报价单的报价侧。

**Architecture:** 每页签持久化一份墓碑数组 `deleted_row_keys`（每项 `{effKey, fp}`）。前后端各一份**逐字节对拍**的纯函数：`rowFingerprint`（按 rowKeyFields 值 + driverRow 全字段值的共享规范化串）+ `keepRow`（effKey 与 fp **双命中**才判删）。**头号不变量**：effKey 永远基于「完整 driver 展开集」唯一化，过滤后子集绝不再算 key/重排下标——过滤=在每个唯一化点之后按墓碑剔除整行。删除经**专用追加端点**落库（不混 saveDraft）。`expansion.rowCount` 语义不变，另立 `effectiveRowCount`；`deletedRowKeys` 不进 driverExpansionKey。换模板复制清空墓碑。

**Tech Stack:** Java 17 + Quarkus + Hibernate Panache + Flyway（后端）；React + Ant Design（前端）；JUnit5（后端单测）；Vitest（前端单测）；Playwright（E2E）。

**Spec:** `docs/superpowers/specs/2026-06-17-deletable-driver-default-rows-design.md`

## 共享契约（所有任务一致引用）

- **墓碑 JSON**：`{ "effKey": "<完整集唯一化键>", "fp": "<指纹>" }`；列 `deleted_row_keys jsonb NOT NULL DEFAULT '[]'` 存数组。
- **fp 规范化规则**（前后端必须逐字节一致，由对拍测试守护）：
  - 输入：`rowKeyFieldNames: string[]`、`driverRow: {字段名→值}`。
  - 取值序列 = `rowKeyFieldNames` 按序各取 `driverRow[name]`，再接 `driverRow` 全部键**按键名升序**的值。
  - 每个值 `canon(v)`：`null`/缺失 → `"∅"`；boolean → `"true"`/`"false"`；number → 整数则无小数点（`"7"`），否则去尾零（`"7.12"`、`"7.1"`）；string → 原串。
  - fp = 值序列以 `""` 连接（不哈希，直接用规范串，保证跨语言无哈希算法分歧）。
- **后端纯工具** `com.cpq.quotation.rowkey.DeletedRowKeys`：`List<Tombstone> parse(String json)`、`String rowFingerprint(List<String> rowKeyFieldNames, JsonNode driverRow)`、`boolean[] keepMask(List<String> effKeys, List<String> fps, List<Tombstone> deleted)`。`Tombstone` = record(String effKey, String fp)。
- **前端纯工具** `cpq-frontend/src/pages/quotation/deletedRows.ts`：`rowFingerprint(rowKeyFieldNames, driverRow)`、`keepRow(effKey, fp, tombstones)`、`type Tombstone = { effKey: string; fp: string }`。
- **effectiveRowCount** = `keepMask` 中 true 的数量（= 唯一化后行数 − 双命中墓碑数）。

## File Structure

**后端**：
- Flyway `V<next>__qlcd_add_deleted_row_keys.sql`（加列）
- `QuotationLineComponentData.java`（加字段）
- 新建 `com.cpq.quotation.rowkey.DeletedRowKeys`（纯工具）+ 单测
- `CardSnapshotService.java`（buildResolvedRows / computeRows 路径 / buildBaseRowsFromSnapshotRows / buildExcelValues 过滤；核价隔离）
- `FormulaCalculator.java`（computeRows 过滤——仅报价侧调用传入墓碑）
- `QuotationResource.java` + `QuotationService.java`（追加墓碑端点 + restore-all + 单行重刷；copy 清墓碑）

**前端**：
- 新建 `deletedRows.ts`（纯工具）+ 单测
- `useDriverExpansions.ts`（暴露 effectiveRowCount + 过滤；墓碑不进 key）
- `useCardSnapshots.ts`（组装按过滤后行集）
- `QuotationStep2.tsx`（🔗→✕ 删除交互、调端点、行对齐用 effectiveRowCount、渲染跳过）
- `quotationService.ts`（删行/恢复 API）

---

## Task 1: Flyway 加列 + 实体字段

**Files:**
- Create: `cpq-backend/src/main/resources/db/migration/V<next>__qlcd_add_deleted_row_keys.sql`
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/entity/QuotationLineComponentData.java`

- [ ] **Step 1: 查下一个 Flyway 版本号**

Run: `ls cpq-backend/src/main/resources/db/migration/V*.sql | sed 's/__.*//;s#.*/V##' | sort -n | tail -1`
取结果 +1 作为 `<next>`（如最新 V299 → 用 V300）。

- [ ] **Step 2: 写迁移**

文件 `V<next>__qlcd_add_deleted_row_keys.sql`：
```sql
-- driver 默认行可永久删除：每页签墓碑数组 [{effKey, fp}]
ALTER TABLE quotation_line_component_data
    ADD COLUMN IF NOT EXISTS deleted_row_keys jsonb NOT NULL DEFAULT '[]'::jsonb;
```

- [ ] **Step 3: 实体加字段**

在 `QuotationLineComponentData.java` 的 `snapshotRows` 字段后加：
```java
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "deleted_row_keys", columnDefinition = "jsonb")
    public String deletedRowKeys = "[]";
```

- [ ] **Step 4: 触发迁移 + 校验**

Run: `cd cpq-backend && touch src/main/java/com/cpq/quotation/entity/QuotationLineComponentData.java && sleep 8`
Run: `PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -tA -c "SELECT version,success FROM flyway_schema_history WHERE version='<next>'; SELECT column_name FROM information_schema.columns WHERE table_name='quotation_line_component_data' AND column_name='deleted_row_keys';"`
Expected: `<next>|t` + `deleted_row_keys`。

- [ ] **Step 5: Commit**
```bash
git add cpq-backend/src/main/resources/db/migration/V<next>__qlcd_add_deleted_row_keys.sql cpq-backend/src/main/java/com/cpq/quotation/entity/QuotationLineComponentData.java
git commit -m "feat(row-delete): qlcd 加 deleted_row_keys 列 + 实体字段"
```

---

## Task 2: 后端纯工具 `DeletedRowKeys` + 单测（TDD）

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/quotation/rowkey/DeletedRowKeys.java`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/rowkey/DeletedRowKeysTest.java`

- [ ] **Step 1: 写失败测试**
```java
package com.cpq.quotation.rowkey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DeletedRowKeysTest {
    private final ObjectMapper M = new ObjectMapper();
    private JsonNode row(String json) throws Exception { return M.readTree(json); }

    @Test
    void fingerprintStableForSameValues() throws Exception {
        var fp1 = DeletedRowKeys.rowFingerprint(List.of("料件"), row("{\"料件\":\"P1\",\"单价\":7.12,\"启用\":true}"));
        var fp2 = DeletedRowKeys.rowFingerprint(List.of("料件"), row("{\"启用\":true,\"单价\":7.12,\"料件\":\"P1\"}"));
        assertEquals(fp1, fp2, "键序无关(driverRow 按键名排序)");
    }

    @Test
    void numberCanonTrimsTrailingZeros() throws Exception {
        var a = DeletedRowKeys.rowFingerprint(List.of(), row("{\"x\":7.10}"));
        var b = DeletedRowKeys.rowFingerprint(List.of(), row("{\"x\":7.1}"));
        assertEquals(a, b);
        assertTrue(DeletedRowKeys.rowFingerprint(List.of(), row("{\"x\":7.0}")).contains("7"));
    }

    @Test
    void keepMaskDeletesOnlyDoubleMatch() {
        var deleted = List.of(new DeletedRowKeys.Tombstone("K2", "fpB"));
        // effKey 命中但 fp 不命中 → 保留；两者都命中 → 删
        boolean[] mask = DeletedRowKeys.keepMask(
            List.of("K1", "K2", "K2"),
            List.of("fpA", "fpA", "fpB"),
            deleted);
        assertArrayEquals(new boolean[]{true, true, false}, mask);
    }

    @Test
    void parseRoundTrip() throws Exception {
        var ts = DeletedRowKeys.parse("[{\"effKey\":\"K1\",\"fp\":\"f1\"}]");
        assertEquals(1, ts.size());
        assertEquals("K1", ts.get(0).effKey());
        assertEquals("f1", ts.get(0).fp());
        assertTrue(DeletedRowKeys.parse(null).isEmpty());
        assertTrue(DeletedRowKeys.parse("[]").isEmpty());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -o test -Dtest=DeletedRowKeysTest`
Expected: 编译失败（DeletedRowKeys 不存在）。

- [ ] **Step 3: 实现**
```java
package com.cpq.quotation.rowkey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** driver 默认行墓碑工具：指纹 + 双命中过滤。前端 deletedRows.ts 为对端等价实现。 */
public final class DeletedRowKeys {
    private static final ObjectMapper M = new ObjectMapper();
    private DeletedRowKeys() {}

    public record Tombstone(String effKey, String fp) {}

    public static List<Tombstone> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode arr = M.readTree(json);
            if (!arr.isArray()) return List.of();
            List<Tombstone> out = new ArrayList<>();
            for (JsonNode n : arr) {
                String e = n.path("effKey").asText("");
                String f = n.path("fp").asText("");
                if (!e.isEmpty()) out.add(new Tombstone(e, f));
            }
            return out;
        } catch (Exception ex) { return List.of(); }
    }

    /** canon(v)：null→∅；bool→true/false；number→去尾零；string→原串。 */
    static String canon(JsonNode v) {
        if (v == null || v.isMissingNode() || v.isNull()) return "∅";
        if (v.isBoolean()) return v.asBoolean() ? "true" : "false";
        if (v.isNumber()) {
            java.math.BigDecimal d = v.decimalValue().stripTrailingZeros();
            return d.scale() <= 0 ? d.toBigInteger().toString() : d.toPlainString();
        }
        return v.asText();
    }

    public static String rowFingerprint(List<String> rowKeyFieldNames, JsonNode driverRow) {
        List<String> parts = new ArrayList<>();
        if (rowKeyFieldNames != null)
            for (String name : rowKeyFieldNames)
                parts.add(canon(driverRow == null ? null : driverRow.get(name)));
        if (driverRow != null) {
            List<String> keys = new ArrayList<>();
            driverRow.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys);
            for (String k : keys) parts.add(canon(driverRow.get(k)));
        }
        return String.join("", parts);
    }

    /** effKey 与 fp 双命中才删；返回逐行 keep 掩码。 */
    public static boolean[] keepMask(List<String> effKeys, List<String> fps, List<Tombstone> deleted) {
        Set<String> del = new HashSet<>();
        for (Tombstone t : deleted) del.add(t.effKey() + "" + t.fp());
        boolean[] keep = new boolean[effKeys.size()];
        for (int i = 0; i < effKeys.size(); i++)
            keep[i] = !del.contains(effKeys.get(i) + "" + fps.get(i));
        return keep;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -o test -Dtest=DeletedRowKeysTest`
Expected: BUILD SUCCESS, 4 passed。

- [ ] **Step 5: Commit**
```bash
git add cpq-backend/src/main/java/com/cpq/quotation/rowkey/DeletedRowKeys.java cpq-backend/src/test/java/com/cpq/quotation/rowkey/DeletedRowKeysTest.java
git commit -m "feat(row-delete): 后端墓碑纯工具 DeletedRowKeys + 单测"
```

---

## Task 3: 前端纯工具 `deletedRows.ts` + 单测（TDD，与后端对拍）

**Files:**
- Create: `cpq-frontend/src/pages/quotation/deletedRows.ts`
- Test: `cpq-frontend/src/pages/quotation/deletedRows.test.ts`

- [ ] **Step 1: 写失败测试**
```ts
import { describe, it, expect } from 'vitest';
import { rowFingerprint, keepRow, type Tombstone } from './deletedRows';

describe('deletedRows', () => {
  it('fingerprint 键序无关', () => {
    const a = rowFingerprint(['料件'], { 料件: 'P1', 单价: 7.12, 启用: true });
    const b = rowFingerprint(['料件'], { 启用: true, 单价: 7.12, 料件: 'P1' });
    expect(a).toBe(b);
  });
  it('number 去尾零', () => {
    expect(rowFingerprint([], { x: 7.10 })).toBe(rowFingerprint([], { x: 7.1 }));
  });
  it('与后端对拍向量一致', () => {
    // 后端 rowFingerprint(["料件"], {"料件":"P1","单价":7.12}) 的规范串：
    // parts = ["P1"(rowKey值), "7.12"(单价排序在前), "P1"(料件)] join 
    expect(rowFingerprint(['料件'], { 料件: 'P1', 单价: 7.12 }))
      .toBe(['P1', '7.12', 'P1'].join(''));
  });
  it('keepRow 双命中才删', () => {
    const del: Tombstone[] = [{ effKey: 'K2', fp: 'fpB' }];
    expect(keepRow('K2', 'fpA', del)).toBe(true);   // effKey 命中 fp 不命中
    expect(keepRow('K2', 'fpB', del)).toBe(false);  // 双命中
    expect(keepRow('K1', 'fpB', del)).toBe(true);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/deletedRows.test.ts`
Expected: FAIL（模块不存在）。

- [ ] **Step 3: 实现**（canon 规则与后端逐字节对齐）
```ts
export type Tombstone = { effKey: string; fp: string };

function canon(v: any): string {
  if (v === null || v === undefined) return '∅';
  if (typeof v === 'boolean') return v ? 'true' : 'false';
  if (typeof v === 'number') {
    if (Number.isInteger(v)) return String(v);
    // 去尾零：与后端 BigDecimal.stripTrailingZeros().toPlainString() 对齐
    let s = v.toString();
    if (s.includes('.')) s = s.replace(/0+$/, '').replace(/\.$/, '');
    return s;
  }
  return String(v);
}

export function rowFingerprint(
  rowKeyFieldNames: string[] | undefined | null,
  driverRow: Record<string, any> | undefined | null,
): string {
  const parts: string[] = [];
  for (const name of (rowKeyFieldNames ?? [])) parts.push(canon(driverRow?.[name]));
  if (driverRow) {
    for (const k of Object.keys(driverRow).sort()) parts.push(canon(driverRow[k]));
  }
  return parts.join('');
}

export function keepRow(effKey: string, fp: string, deleted: Tombstone[] | undefined | null): boolean {
  if (!deleted || deleted.length === 0) return true;
  return !deleted.some((t) => t.effKey === effKey && t.fp === fp);
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/deletedRows.test.ts && npx tsc --noEmit -p tsconfig.json`
Expected: 全绿 + tsc 0 错误。

> 注：JS number 去尾零与 Java BigDecimal 在科学计数法/超大数上可能分歧（spec §3.8 已接受残余风险）。本测试覆盖常见小数；若后续发现分歧向量，在此补对拍用例并统一 canon。

- [ ] **Step 5: Commit**
```bash
git add cpq-frontend/src/pages/quotation/deletedRows.ts cpq-frontend/src/pages/quotation/deletedRows.test.ts
git commit -m "feat(row-delete): 前端墓碑纯工具 deletedRows + 对拍单测"
```

---

## Task 4: 后端过滤落点（buildResolvedRows / computeRows / buildBaseRowsFromSnapshotRows / Excel）+ 核价隔离

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java`
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java`

**不变量（每个落点注释）**：先对**完整** baseRows 唯一化（现状代码不动），紧接着用 `DeletedRowKeys.keepMask(uniqKeys, fps, deleted)` 过滤；**fps 用同一份完整 baseRows 的 driverRow 计算**；过滤后的子集不再重算 key。

- [ ] **Step 1: 给 buildResolvedRows 加墓碑参数 + 过滤**

`CardSnapshotService.buildResolvedRows`（`:929`）签名加 `List<DeletedRowKeys.Tombstone> deleted, List<String> rowKeyFieldNames`。在 `uniqKeys` 计算后（`:944` 之后）插入：
```java
        // driver 默认行永久删除：先唯一化(上方完成)，再按墓碑双命中过滤；fps 用完整 baseRows 计算
        List<String> fps = new ArrayList<>();
        for (JsonNode br : baseRows) fps.add(DeletedRowKeys.rowFingerprint(rowKeyFieldNames, br.path("driverRow")));
        boolean[] keep = DeletedRowKeys.keepMask(uniqKeys, fps, deleted == null ? List.of() : deleted);
```
然后把下面 `for (JsonNode br : baseRows)` 迭代体首行加 `if (!keep[ri]) { ri++; continue; }`（保持 `ri` 与完整集对齐，effKey 不漂）。
`rowKeyFieldNames` 由 `rowKeyFields`（JsonNode 数组）转 `List<String>`（已有现成转换可复用；若无则 `List<String> rkfn=new ArrayList<>(); rowKeyFields.forEach(n->rkfn.add(n.asText()));`）。

- [ ] **Step 2: 调用方传墓碑（报价侧传真实，核价侧传空）**

`buildResolvedRows` 的调用点（`assembleTabsWithFormulaResults` 内）：报价侧路径（`buildCardValues`/`refreshQuoteCardValues`）传入该 tab 对应 component 的 `deleted_row_keys`（从 `quotation_line_component_data` 按 lineItemId+componentId 查 `DeletedRowKeys.parse(...)`）；**核价侧**（`buildCostingCardValues`）传 `List.of()`。
读墓碑：在组装 tabs 前，一次性查本 lineItem 各 componentId 的 deleted_row_keys 存 `Map<UUID,List<Tombstone>>`：
```java
Map<String,List<DeletedRowKeys.Tombstone>> delByComp = new HashMap<>();
for (Object[] r : em.createNativeQuery(
    "SELECT component_id, deleted_row_keys FROM quotation_line_component_data WHERE line_item_id=:lid")
    .setParameter("lid", li.id).getResultList()) {
    if (r[0]!=null) delByComp.put(r[0].toString(), DeletedRowKeys.parse(r[1]==null?null:r[1].toString()));
}
```
按 tab 的 componentId 取对应墓碑（核价侧不查、传空）。

- [ ] **Step 3: computeRows 过滤（FormulaCalculator）**

`FormulaCalculator.computeRows`（`:571`）已算 `effKeys`（`:593`）。加可选参数 `List<DeletedRowKeys.Tombstone> deleted, List<String> rowKeyFieldNames`（报价侧传，核价/无则空）。`effKeys` 后插：
```java
        List<String> fps = new ArrayList<>();
        for (JsonNode br : baseRows) fps.add(DeletedRowKeys.rowFingerprint(rowKeyFieldNames, br.path("driverRow")));
        boolean[] keep = DeletedRowKeys.keepMask(effKeys, fps, deleted == null ? List.of() : deleted);
```
行迭代体首加 `if (!keep[i]) continue;`（i 仍遍历完整集，保持 effKey 对齐）。

- [ ] **Step 4: buildBaseRowsFromSnapshotRows / buildCardValues 路径**

`buildCardValues`（`:468`）走 `buildBaseRowsFromSnapshotRows`（`:641`）后再 `assembleTabsWithFormulaResults`→`buildResolvedRows`。由于过滤已落在 `buildResolvedRows`（Step1），此路径**自动覆盖**——只需确认 `buildCardValues` 也把 `delByComp` 传进 `assembleTabsWithFormulaResults`（与 refresh 路径同一入口）。核对：两路径都经 `assembleTabsWithFormulaResults`，在该方法签名加 `Map<String,List<Tombstone>> delByComp`，内部按 componentId 取并传 `buildResolvedRows`/`computeRows`。

- [ ] **Step 5: Excel 取数过滤**

`buildExcelValues`（`:575/583/609`）→ `ExcelViewService`/`CardEffectiveRows` 取有效行处：以**已过滤的** resolved/card 值为输入（若 Excel 直接读 `quote_card_values` 的 resolvedRows，则上游已过滤，无需再处理——核对 Excel 是否复用 cardValuesJson；若是则天然继承过滤）。在 Step 6 编译后用 curl/DB 验证 Excel 行数随删除减少。

- [ ] **Step 6: 编译**

Run: `cd cpq-backend && ./mvnw -o compile`
Expected: BUILD SUCCESS。（运行时验证在 Task 9。）

- [ ] **Step 7: Commit**
```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java
git commit -m "feat(row-delete): 后端 baseRows 过滤落点(唯一化后双命中剔除)+核价隔离"
```

---

## Task 5: 追加墓碑端点 + restore-all + 单行重刷

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/resource/QuotationResource.java`
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java`

- [ ] **Step 1: Service 方法**

`QuotationService` 加：
```java
    @Transactional
    public void deleteDriverRow(UUID lineItemId, UUID componentId, String effKey, String fp) {
        QuotationLineComponentData cd = QuotationLineComponentData
            .find("lineItemId = ?1 and componentId = ?2", lineItemId, componentId).firstResult();
        if (cd == null) throw new BusinessException(404, "component data not found");
        try {
            var arr = MAPPER.readTree(cd.deletedRowKeys == null || cd.deletedRowKeys.isBlank() ? "[]" : cd.deletedRowKeys);
            com.fasterxml.jackson.databind.node.ArrayNode out = arr.isArray()
                ? (com.fasterxml.jackson.databind.node.ArrayNode) arr : MAPPER.createArrayNode();
            boolean exists = false;
            for (var n : out) if (effKey.equals(n.path("effKey").asText()) && fp.equals(n.path("fp").asText())) { exists = true; break; }
            if (!exists) {
                if (out.size() >= 500) LOG.warnf("[row-delete] tombstones >=500 lineItem=%s comp=%s", lineItemId, componentId);
                var t = MAPPER.createObjectNode(); t.put("effKey", effKey); t.put("fp", fp); out.add(t);
                cd.deletedRowKeys = MAPPER.writeValueAsString(out);
            }
        } catch (Exception e) { throw new BusinessException(500, "deleted_row_keys 更新失败: " + e.getMessage()); }
        // 立即重刷本行报价快照，使渲染生效
        QuotationLineItem li = QuotationLineItem.findById(lineItemId);
        if (li != null) cardSnapshotService.refreshQuoteCardValues(li);
    }

    @Transactional
    public void restoreAllDriverRows(UUID lineItemId, UUID componentId) {
        QuotationLineComponentData cd = QuotationLineComponentData
            .find("lineItemId = ?1 and componentId = ?2", lineItemId, componentId).firstResult();
        if (cd == null) return;
        cd.deletedRowKeys = "[]";
        QuotationLineItem li = QuotationLineItem.findById(lineItemId);
        if (li != null) cardSnapshotService.refreshQuoteCardValues(li);
    }
```
（`MAPPER`/`cardSnapshotService` Task3-copy 已注入；若 `cardSnapshotService` 未注入则加 `@Inject`。）

- [ ] **Step 2: 端点**

`QuotationResource` 加：
```java
    @POST
    @Path("/{qid}/line-items/{lid}/delete-driver-row")
    public ApiResponse<Void> deleteDriverRow(@PathParam("qid") UUID qid, @PathParam("lid") UUID lid,
            java.util.Map<String, Object> body) {
        UUID componentId = UUID.fromString(body.get("componentId").toString());
        String effKey = String.valueOf(body.get("effKey"));
        String fp = String.valueOf(body.getOrDefault("fp", ""));
        quotationService.deleteDriverRow(lid, componentId, effKey, fp);
        return ApiResponse.success(null);
    }

    @POST
    @Path("/{qid}/line-items/{lid}/restore-driver-rows")
    public ApiResponse<Void> restoreDriverRows(@PathParam("qid") UUID qid, @PathParam("lid") UUID lid,
            java.util.Map<String, Object> body) {
        UUID componentId = UUID.fromString(body.get("componentId").toString());
        quotationService.restoreAllDriverRows(lid, componentId);
        return ApiResponse.success(null);
    }
```

- [ ] **Step 3: 编译 + 重启 + 端点存活**

Run: `cd cpq-backend && ./mvnw -o compile && touch src/main/java/com/cpq/quotation/resource/QuotationResource.java && sleep 8`
Run: `curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8081/api/cpq/quotations/x/line-items/y/delete-driver-row`
Expected: 401 或 400（非 404 路由缺失、非 500）。

- [ ] **Step 4: Commit**
```bash
git add cpq-backend/src/main/java/com/cpq/quotation/resource/QuotationResource.java cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java
git commit -m "feat(row-delete): 追加墓碑端点 + restore-all + 单行重刷"
```

---

## Task 6: 复制换模板清空墓碑

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java`（`migrateAndCreateComponentData` `:1293`）

- [ ] **Step 1: 读现状**

Run: `cd cpq-backend && sed -n '1318,1350p' src/main/java/com/cpq/quotation/service/QuotationService.java`
确认 `migrateAndCreateComponentData` 新建 `QuotationLineComponentData` 的位置 + 它是否知道"是否换模板"。`copy(id, templateId)` 里 `newTemplateId != source.customerTemplateId` 即换模板。

- [ ] **Step 2: 传 sameTemplate 标志并赋墓碑**

给 `migrateAndCreateComponentData` 加参数 `boolean sameTemplate`。在新建 `newCd` 处加：
```java
            // 同模板复制：墓碑按 componentId 原样拷贝；换模板：清空(旧 effKey/源集必失配)
            newCd.deletedRowKeys = (sameTemplate && match != null && match.deletedRowKeys != null)
                ? match.deletedRowKeys : "[]";
```
调用处传 `boolean sameTemplate = newTemplateId != null && newTemplateId.equals(source.customerTemplateId);`
（`match` 为已按 componentId/tabName 配到的源 componentData；Task3-copy 已有该变量。）

- [ ] **Step 3: 编译**

Run: `cd cpq-backend && ./mvnw -o compile`
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**
```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java
git commit -m "feat(row-delete): 复制-同模板拷墓碑/换模板清空墓碑"
```

---

## Task 7: 前端展开过滤 + effectiveRowCount（useDriverExpansions / useCardSnapshots）

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/useDriverExpansions.ts`
- Modify: `cpq-frontend/src/pages/quotation/useCardSnapshots.ts`

**不变量**：effKey 由 `buildUniqueRowKeys` 在**完整**展开集上算；过滤后子集不再 `computeRowKey`。`deletedRowKeys` **不进** `driverExpansionKey`。

- [ ] **Step 1: 读现状**

Run: `cd cpq-frontend && grep -n "rowCount\|buildUniqueRowKeys\|driverExpansionKey\|return" src/pages/quotation/useDriverExpansions.ts | head -30`
理解 expansion 对象结构（含 `rows`、`rowCount`）与 key 组成，确认 `deletedRowKeys` 不在 key 里。

- [ ] **Step 2: useCardSnapshots 组装按过滤后行**

在 `useCardSnapshots` 组装 baseRows/editRows/formulaResults 处（`buildUniqueRowKeys` 之后），用 `rowFingerprint` + `keepRow`（import 自 `./deletedRows`）过滤完整集，得到保留行；`effectiveRowCount = keep 数`。墓碑来源 = 该 component 的 `deletedRowKeys`（来自后端 quote_card_values tab 或 componentData——确认前端能拿到；若 quote_card_values 未含，则前端调用方从 componentData/enrich 注入 `tab.deletedRowKeys`）。
```ts
import { rowFingerprint, keepRow, type Tombstone } from './deletedRows';
// uniqKeys = buildUniqueRowKeys(...)（完整集，不动）
const deleted: Tombstone[] = (tab.deletedRowKeys ?? []);
const keptIdx = uniqKeys.map((k, i) => keepRow(k, rowFingerprint(rowKeyFields, baseRows[i]?.driverRow), deleted) ? i : -1)
                        .filter(i => i >= 0);
// 用 keptIdx 取 baseRows/editRows/formulaResults；effectiveRowCount = keptIdx.length
```

- [ ] **Step 3: useDriverExpansions 暴露 effectiveRowCount**

expansion 对象增加 `effectiveRowCount`（不改 `rowCount` 语义）。若过滤在 useCardSnapshots 做，则 useDriverExpansions 可仅透传墓碑或保持不变；二选一，保证**单一过滤口径**（建议过滤集中在 useCardSnapshots，useDriverExpansions 只产原始展开）。在本步明确：过滤只在 useCardSnapshots（消费侧），useDriverExpansions 不改。

- [ ] **Step 4: 类型 + 单测/编译**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误。

- [ ] **Step 5: Commit**
```bash
git add cpq-frontend/src/pages/quotation/useCardSnapshots.ts cpq-frontend/src/pages/quotation/useDriverExpansions.ts
git commit -m "feat(row-delete): 前端展开消费侧按墓碑过滤 + effectiveRowCount"
```

---

## Task 8: Step2 删除交互（🔗→✕）+ 行对齐 + 详情页核对

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`
- Modify: `cpq-frontend/src/services/quotationService.ts`
- Verify: `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx`

- [ ] **Step 1: service API**

`quotationService.ts` 加：
```ts
  deleteDriverRow: (qid: string, lid: string, componentId: string, effKey: string, fp: string) =>
    api.post(`/quotations/${qid}/line-items/${lid}/delete-driver-row`, { componentId, effKey, fp }),
  restoreDriverRows: (qid: string, lid: string, componentId: string) =>
    api.post(`/quotations/${qid}/line-items/${lid}/restore-driver-rows`, { componentId }),
```

- [ ] **Step 2: 渲染层 🔗 改 ✕**

`QuotationStep2.tsx:2509-2510`（`isDriverBound` 分支当前渲染 🔗）改为渲染删除按钮，onClick 调新 handler。复用渲染层已算的 `rowKey`（该行完整集唯一化 effKey）+ `driverRow`：
```tsx
                        ) : isDriverBound ? (
                          <button type="button"
                            onClick={() => handleDeleteDriverRow(activeComponent.componentId, rowKey, driverRow)}
                            style={{ background:'none', border:'none', color:'#ff4d4f', cursor:'pointer', fontSize:14, padding:'0 4px' }}
                            title="删除行（永久，刷新不回）">✕</button>
                        ) : (
```
（`rowKey`/`driverRow` 在该 map 作用域已有：见 `:2283` `driverRow`、行 map 解构出的 `rowKey`；若变量名不同按实际取。）

- [ ] **Step 3: handler — 调端点 + 乐观更新**

在组件内加（`qid`=报价单 id、`lid`=当前 lineItem id，从 props/state 取；`item.id`）：
```tsx
  const handleDeleteDriverRow = async (componentId: string, effKey: string, driverRow: Record<string, any>) => {
    const fp = rowFingerprint(activeComponent.rowKeyFields ?? [], driverRow); // import from './deletedRows'
    try {
      await quotationService.deleteDriverRow(quotationId, item.id, componentId, effKey, fp);
      // 触发本行快照重载/重新拉取(复用现有刷新机制，使 effectiveRowCount 生效)
      await reloadLineSnapshot?.();   // 用现有的草稿/快照刷新入口；若无则 onUpdate 触发重算
    } catch (e: any) { message.error(e.message); }
  };
```
（`activeComponent.rowKeyFields`/`quotationId`/`reloadLineSnapshot` 按文件实际命名取；关键：fp 用与渲染同一 `driverRow`，rowKeyFields 用该 tab 的。）

- [ ] **Step 4: 行对齐用 effectiveRowCount（AP-51）**

`QuotationStep2.tsx:1467-1472` 行对齐逻辑：非手动行数对齐到 **effectiveRowCount**（过滤后），不用裸 `exp.rowCount`。结合 Task7 的过滤集：渲染迭代基于过滤后行集；rowCount=0（全删）走 AP-38 "—" 兜底。

- [ ] **Step 5: 详情页核对**

确认 `ReadonlyProductCard.tsx` 渲染经与 Step2 同一过滤后的 quote_card_values（resolvedRows 已在后端 Task4 过滤），不直读未过滤 snapshot_rows。Run: `grep -n "snapshot_rows\|resolvedRows\|baseRows\|deletedRow" cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx`。若它读后端已过滤的卡片值则天然继承；记录确认结论。

- [ ] **Step 6: 类型检查**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误。

- [ ] **Step 7: Commit**
```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx cpq-frontend/src/services/quotationService.ts
git commit -m "feat(row-delete): Step2 driver 行删除交互(🔗→✕)+行对齐 effectiveRowCount"
```

---

## Task 9: 集成验证 + 双 E2E + RECORD

**Files:**
- Verify: 后端 curl 删行 + DB；E2E 双 spec
- Modify: `docs/RECORD.md`

- [ ] **Step 1: 后端删行冒烟（curl，登录态）**

登录拿 cookie（`POST /api/cpq/auth/login {"username":"admin","password":"Admin@2026"}`）。取一个有 driver 行的 DRAFT 报价单 + 其某 lineItem + componentId + 该行 effKey/driverRow（从 `quote_card_values` 取一行的 rowKey + driverRow，算 fp）。
Run: `curl -b cookie -X POST .../quotations/{qid}/line-items/{lid}/delete-driver-row -d '{"componentId":"...","effKey":"...","fp":"..."}'` → 200。

- [ ] **Step 2: DB + 重刷验证**

Run: `psql ... -c "SELECT deleted_row_keys FROM quotation_line_component_data WHERE line_item_id='{lid}' AND component_id='{cid}';"` → 含该墓碑。
再查 `quote_card_values` 对应 tab 的 baseRows/resolvedRows 行数 = 原行数 − 1。**连查 3 次**（触发 refresh）行数稳定不回弹（AP-51）。

- [ ] **Step 3: 双 E2E**

Run:
```bash
cd cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
npx playwright test --config=e2e/playwright.config.ts e2e/composite-product-flow.spec.ts --reporter=list
```
Expected: `quotation-flow` **1 passed** / 全 Tab `'加载中'=0`。（`composite-product-flow` 既有环境失败按 RECORD 2026-06-10 判定，不归本改动；但需确认失败点与本改动无关。）

- [ ] **Step 4: 删除-不串行手测点**

按 spec §5：删某 tab 中间一行后，刷新，确认剩余行的 INPUT 值（如材料管理费）仍对在原行（editRows 按 effKey 对齐，AP-54 不串行）。在 E2E 截图或手动 + F12 Network 验证。

- [ ] **Step 5: RECORD + Commit**

`docs/RECORD.md` 顶部加：
```
[2026-06-17] 报价单 - driver 默认行可永久删除 | V<next>+QuotationLineComponentData / DeletedRowKeys(前后端对拍) / CardSnapshotService+FormulaCalculator 过滤落点 / 追加墓碑端点 / QuotationStep2 🔗→✕ | 每页签 deleted_row_keys[{effKey,fp}]墓碑;前后端统一"完整集唯一化→双命中(effKey+指纹)剔除",过滤后不重算key(守AP-54);snapshot_rows存全量渲染期过滤;rowCount语义不变另立effectiveRowCount(AP-51);墓碑不进driverExpansionKey;换模板复制清墓碑;核价侧side==QUOTE隔离;专用追加端点不混saveDraft。验证:前后端单测+对拍绿+E2E quotation-flow 1 passed+curl删行刷新3次行数稳定-1+剩余行输入不串行。spec/plan见docs/superpowers/。
```
Run（注意 RECORD.md 可能有他人未提交改动，只提交自己条目，必要时 stash/pop）:
```bash
git add docs/RECORD.md && git commit -m "docs(record): driver 默认行可永久删除"
```

---

## Self-Review

- **Spec 覆盖**：§3.0 不变量→各落点注释(Task4/7/8)；§3.1 墓碑双命中→Task2/3；§3.2 列+换模板清墓碑→Task1/6；§3.3 端点→Task5、删除交互→Task8；§3.4 统一纯函数→Task2/3，落点→Task4/7/8；§3.5 snapshot_rows 存全量渲染期过滤→Task4 Step4 + Task7；§3.6 effectiveRowCount 不改 rowCount + 不进 key→Task7/8；§3.7 核价隔离→Task4 Step2；§3.8 指纹→Task2/3；§5 单测三夹具(撞键/全空/漂移)→Task2/3 覆盖撞键+全空+键序，**补**漂移夹具见下；E2E→Task9。
  - **补漏**：Task2/3 单测加"删行后源集增 1 行墓碑不误命中"夹具（漂移），并加"5 行 3 撞键删中间一个剩余 #N 不变"夹具——实现时在 DeletedRowKeysTest/deletedRows.test.ts 各补 1 例（与 spec §5 三夹具对齐）。
- **类型一致**：`Tombstone{effKey,fp}` / `rowFingerprint(rowKeyFieldNames, driverRow)` / `keepMask`(后端)·`keepRow`(前端) 跨任务一致；`deleted_row_keys` 列名/`deletedRowKeys` 字段名一致。
- **占位扫描**：无 TBD；新单元含完整代码；集成落点(Task4/7/8)给出确切插入代码 + 要求先读目标函数（因插入既有复杂函数，属合理）。
- **YAGNI**：未做单行恢复 UI（仅 restore-all 数据兜底）；未做墓碑硬上限（仅软告警）。
