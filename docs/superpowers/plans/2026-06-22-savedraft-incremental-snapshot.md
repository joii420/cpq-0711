# saveDraft 增量快照（消除高频 autosave 全量重 expand 超时）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `PUT /api/cpq/quotations/{id}/draft`（saveDraft）的整单快照只对"新行/无快照行"重 expand，复用行搬运旧 `snapshot_rows` 并整行跳过 driver 展开，根治每次防抖保存都全量冷重 expand（73×8≈584 次远程查询、~13s）导致的持续超时。

**Architecture:** 两段互相依赖的后端改动 + 一个可选开关：
1. **Part A（saveDraft 全量重建时保留 snapshot_rows）** —— 在 `QuotationService.saveDraft` 里，对**复用行**（`liDraft.id` 命中既有行）在 `clearLineItemChildren` 前捕获各组件现有 `snapshot_rows`（完全照搬已存在的 `preservedTombstones` 模式），重建 `QuotationLineComponentData` 时回写 `cd.snapshotRows`。新行留空（null）。
2. **Part B（快照管线按行跳过）** —— 给 `ConfigureSnapshotService.snapshotQuotation` / `snapshotLines` 增加 `skipRowsWithSnapshot` 重载；为 true 时，某行所有 driver 组件都已有**非 null** `snapshot_rows` 即整行跳过 expand + materialize。`QuotationResource.saveDraft` 传 `true`；`ConfigureProductResource.refreshSnapshot`（"刷新基础数据"按钮）与 `configureProduct`（加产品，只传新行）走旧签名 = `false`，行为完全不变。
3. 跳过判定抽成纯静态方法 `lineNeedsExpand(driverCompIds, snapshotByComp)`，单测覆盖（不依赖 DB）。

**Tech Stack:** Java 17 + Quarkus 3 + Hibernate Panache + PostgreSQL（远程 10.177.152.12）；测试 JUnit5（`./mvnw test`，**必须在 worktree 的 `cpq-backend/` 内跑**）；协议级强制 Playwright E2E `quotation-flow.spec.ts`。

---

## 关键事实（实现者必读，避免踩坑）

- `QuotationLineComponentData` 字段：`snapshotRows`（列 `snapshot_rows` jsonb，**默认 null**）、`rowData`（列 `row_data`，默认 `"[]"`）、`deletedRowKeys`（默认 `"[]"`）。见 `cpq-backend/src/main/java/com/cpq/quotation/entity/QuotationLineComponentData.java:30-39`。
- saveDraft 全量重建：复用行（`liDraft.id != null && existingById.containsKey(liDraft.id)`）走"就地 UPDATE，id 不变"，并在 `clearLineItemChildren(li.id)` 前已有一段读旧 `QuotationLineComponentData` 填 `preservedTombstones` 的循环 —— **Part A 在同一循环里顺带填 `preservedSnapshots`**。
- componentData 重建在 `QuotationService.java`（约 502-519）：`new QuotationLineComponentData()` 只设 `rowData`，**未设 `snapshotRows`** → 这就是重建后 snapshot_rows 全空、`snapshotQuotation` 被迫全量重 expand 的根因。
- `snapshotLines` 调用点共三处（grep 确认）：
  - `QuotationResource.saveDraft` → `snapshotQuotation(id)`（**改为传 true**）
  - `ConfigureProductResource.configureProduct` → `snapshotLines(quotationId, resp.lineItems)`（只传新行，保持旧签名 = false）
  - `ConfigureProductResource.refreshSnapshot` → `snapshotQuotation(quotationId)`（强制刷新，保持旧签名 = false）
- 跳过判定用 **非 null**（不是"非空数组"）：合法的 0 行组件其 `snapshot_rows` 是 `"[]"`（非 null）应被视为已快照、可跳过；只有 null（=重建后未回写=新行）才需 expand。
- `snapshotLines` 内 `componentDriverService.evictAll()` 维持不动（force 路径需要清缓存拿最新基础数据；跳过路径下被跳过的行根本不 expand，evictAll 只影响仍要展开的新行，符合预期）。

---

## File Structure

- `cpq-backend/src/main/java/com/cpq/configure/service/ConfigureSnapshotService.java` —— Part B：新增静态纯函数 `lineNeedsExpand`；`snapshotQuotation`/`snapshotLines` 增加 `skipRowsWithSnapshot` 重载；新增 `@Transactional(REQUIRES_NEW)` 读 `snapshotByComp` 的辅助查询。
- `cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java` —— Part A：`saveDraft` 内 `preservedSnapshots` 捕获 + 回写 `cd.snapshotRows`。
- `cpq-backend/src/main/java/com/cpq/quotation/resource/QuotationResource.java` —— saveDraft 改调 `snapshotQuotation(id, true)`。
- `cpq-backend/src/test/java/com/cpq/configure/service/SnapshotLineNeedsExpandTest.java` —— 新建：`lineNeedsExpand` 纯函数单测（不依赖 DB）。

---

### Task 1: 跳过判定纯函数 `lineNeedsExpand` + 单测（TDD）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/configure/service/ConfigureSnapshotService.java`
- Test: `cpq-backend/src/test/java/com/cpq/configure/service/SnapshotLineNeedsExpandTest.java`

- [ ] **Step 1: 写失败测试**

新建 `cpq-backend/src/test/java/com/cpq/configure/service/SnapshotLineNeedsExpandTest.java`：

```java
package com.cpq.configure.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Part B 跳过判定纯函数单测：不依赖 DB / Quarkus 容器。 */
class SnapshotLineNeedsExpandTest {

    private final UUID c1 = UUID.randomUUID();
    private final UUID c2 = UUID.randomUUID();

    @Test
    void allCompsHaveSnapshot_skips() {
        // 所有 driver 组件都有非 null snapshot_rows（含合法空数组 "[]"）→ 不需 expand（可跳过）
        boolean needs = ConfigureSnapshotService.lineNeedsExpand(
                List.of(c1, c2),
                Map.of(c1, "[{\"driverRow\":{}}]", c2, "[]"));
        assertFalse(needs, "所有组件已有 snapshot_rows（含空数组）应跳过");
    }

    @Test
    void missingCompSnapshot_needsExpand() {
        // c2 无 snapshot_rows（重建后新行 = null / 缺行）→ 需要 expand
        boolean needs = ConfigureSnapshotService.lineNeedsExpand(
                List.of(c1, c2),
                Map.of(c1, "[{\"driverRow\":{}}]"));
        assertTrue(needs, "缺任一组件 snapshot_rows 应整行重 expand");
    }

    @Test
    void nullSnapshotValue_needsExpand() {
        // 显式 null 值（列存在但为 null）→ 需要 expand
        java.util.HashMap<UUID, String> m = new java.util.HashMap<>();
        m.put(c1, "[]");
        m.put(c2, null);
        boolean needs = ConfigureSnapshotService.lineNeedsExpand(List.of(c1, c2), m);
        assertTrue(needs, "snapshot_rows 为 null 应重 expand");
    }

    @Test
    void noDriverComps_skips() {
        // 无 driver 组件 → 无可展开内容 → 不需 expand
        boolean needs = ConfigureSnapshotService.lineNeedsExpand(List.of(), Map.of());
        assertFalse(needs);
    }
}
```

- [ ] **Step 2: 跑测试确认失败（编译失败：方法不存在）**

Run（**在 worktree 的 cpq-backend 内**）：
```bash
cd cpq-backend && ./mvnw -q -o test -Dtest=SnapshotLineNeedsExpandTest
```
Expected: 编译错误 `cannot find symbol: method lineNeedsExpand`。

- [ ] **Step 3: 实现纯函数**

在 `ConfigureSnapshotService` 类中新增（放在 `snapshotLines` 附近，public static 便于单测）：

```java
/**
 * Part B 跳过判定：给定本行的 driver 组件集合与各组件现有 snapshot_rows，
 * 判断是否仍需重 expand。任一 driver 组件缺 snapshot_rows（不在 map 或值为 null）→ 需 expand。
 * 合法的 0 行组件其值为 "[]"（非 null）视为已快照、可跳过。
 *
 * @param driverCompIds   本行所有 driver 组件 id
 * @param snapshotByComp  componentId → snapshot_rows（可能缺键或 null 值）
 * @return true=仍需 expand；false=全部已快照，可整行跳过
 */
public static boolean lineNeedsExpand(java.util.Collection<UUID> driverCompIds,
                                      Map<UUID, String> snapshotByComp) {
    if (driverCompIds == null || driverCompIds.isEmpty()) return false;
    for (UUID cid : driverCompIds) {
        String sr = snapshotByComp == null ? null : snapshotByComp.get(cid);
        if (sr == null) return true;   // 缺键或 null 值 → 需 expand
    }
    return false;
}
```

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd cpq-backend && ./mvnw -q -o test -Dtest=SnapshotLineNeedsExpandTest
```
Expected: `Tests run: 4, Failures: 0, Errors: 0`。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/test/java/com/cpq/configure/service/SnapshotLineNeedsExpandTest.java \
        cpq-backend/src/main/java/com/cpq/configure/service/ConfigureSnapshotService.java
git commit -m "feat(snapshot): add lineNeedsExpand skip-decision pure helper + unit test"
```

---

### Task 2: Part B —— snapshotLines/snapshotQuotation 加 skip 重载并接入跳过

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/configure/service/ConfigureSnapshotService.java`

- [ ] **Step 1: 新增按行读 snapshot_rows 的辅助查询**

在 `ConfigureSnapshotService` 中新增（紧邻 `loadCustomerId` 等 REQUIRES_NEW 查询）：

```java
/** 读某行各组件现有 snapshot_rows（componentId → snapshot_rows，可能 null 值）。Part B 跳过判定用。 */
@Transactional(Transactional.TxType.REQUIRES_NEW)
@SuppressWarnings("unchecked")
public Map<UUID, String> loadSnapshotRowsByComp(UUID lineItemId) {
    Map<UUID, String> out = new HashMap<>();
    if (lineItemId == null) return out;
    List<Object[]> rows = em.createNativeQuery(
            "SELECT component_id, snapshot_rows FROM quotation_line_component_data WHERE line_item_id = :li")
            .setParameter("li", lineItemId).getResultList();
    for (Object[] r : rows) {
        if (r[0] == null) continue;
        out.put(UUID.fromString(r[0].toString()), r[1] == null ? null : r[1].toString());
    }
    return out;
}
```

- [ ] **Step 2: snapshotQuotation 加 skip 重载（旧签名委托为 false，行为不变）**

把现有 `public void snapshotQuotation(UUID quotationId)` 改成委托 + 新重载：

```java
/** 旧签名：强制全量（refreshSnapshot / 向后兼容）。 */
public void snapshotQuotation(UUID quotationId) {
    snapshotQuotation(quotationId, false);
}

/**
 * @param skipRowsWithSnapshot true（saveDraft 高频路径）：某行所有 driver 组件都已有
 *        snapshot_rows 即整行跳过 expand；false（强制刷新/加产品）：行为同改造前。
 */
public void snapshotQuotation(UUID quotationId, boolean skipRowsWithSnapshot) {
    if (quotationId == null) return;
    try {
        List<Map<String, Object>> lines = self.loadQuotationLines(quotationId);
        snapshotLines(quotationId, lines, skipRowsWithSnapshot);
    } catch (Exception e) {
        LOG.warnf("[add-snapshot] quotation=%s 整单重快照失败(已降级): %s", quotationId, e.getMessage());
    }
}
```

- [ ] **Step 3: snapshotLines 加 skip 重载（旧签名委托为 false）+ 接入跳过**

把现有 `public void snapshotLines(UUID quotationId, List<Map<String, Object>> lineItems)` 改成委托 + 新重载，并在**每行循环开头**插入跳过判定。新重载完整体（保留原逻辑，仅新增标注 `// >>> Part B` 的两处）：

```java
/** 旧签名：不跳过（加产品只传新行 / 向后兼容）。 */
public void snapshotLines(UUID quotationId, List<Map<String, Object>> lineItems) {
    snapshotLines(quotationId, lineItems, false);
}

public void snapshotLines(UUID quotationId, List<Map<String, Object>> lineItems,
                          boolean skipRowsWithSnapshot) {
    if (quotationId == null || lineItems == null || lineItems.isEmpty()) return;
    try {
        componentDriverService.evictAll();
        UUID customerId = self.loadCustomerId(quotationId);
        List<DriverComp> comps = self.loadDriverComponents(quotationId);
        if (comps.isEmpty()) return;
        // >>> Part B: 预备 driver 组件 id 集合用于跳过判定
        java.util.List<UUID> driverCompIds = new java.util.ArrayList<>();
        for (DriverComp dc : comps) driverCompIds.add(dc.id);
        QuotationIdContext.set(quotationId);
        try {
            JsonNode componentsSnapshot = self.loadComponentsSnapshot(quotationId);
            for (Map<String, Object> li : lineItems) {
                UUID lineItemId = asUuid(li.get("id"));
                String partNo = li.get("productPartNo") != null ? li.get("productPartNo").toString() : null;
                String compositeType = li.get("compositeType") != null ? li.get("compositeType").toString() : null;
                if (lineItemId == null || partNo == null || partNo.isBlank()) continue;
                // >>> Part B: 复用行已有全部 snapshot_rows → 整行跳过 expand + materialize
                if (skipRowsWithSnapshot
                        && !lineNeedsExpand(driverCompIds, self.loadSnapshotRowsByComp(lineItemId))) {
                    LOG.debugf("[add-snapshot] line=%s 已有完整 snapshot_rows, 跳过重 expand(增量)", lineItemId);
                    continue;
                }
                Map<UUID, String> snapByComp = new LinkedHashMap<>();
                for (DriverComp comp : comps) {
                    try {
                        ExpandDriverResponse exp = componentDriverService.expand(
                                comp.id, customerId, partNo, null, null, null, lineItemId, compositeType);
                        List<ExpandDriverResponse.Row> rows = (exp != null && exp.rows != null) ? exp.rows : new ArrayList<>();
                        String rowsJson = MAPPER.writeValueAsString(rows);
                        self.writeSnapshot(lineItemId, comp.id, comp.name, rowsJson);
                        snapByComp.put(comp.id, rowsJson);
                    } catch (Exception e) {
                        LOG.warnf("[add-snapshot] line=%s comp=%s 跳过: %s", lineItemId, comp.id, e.getMessage());
                    }
                }
                try {
                    materializeRowData(lineItemId, componentsSnapshot, snapByComp);
                } catch (Exception e) {
                    LOG.warnf("[add-snapshot] line=%s 物化 row_data 失败(已降级,仍可编辑后修正): %s",
                            lineItemId, e.getMessage());
                }
            }
        } finally {
            QuotationIdContext.clear();
        }
    } catch (Exception e) {
        LOG.warnf("[add-snapshot] quotation=%s 快照整体失败(已降级): %s", quotationId, e.getMessage());
    }
}
```

> 注意：以上把原方法体**逐行保留**，仅删除原方法签名行、补两段 `>>> Part B`。实现者请对照当前 `snapshotLines`（约 111-160 行）确保中间业务逻辑（含注释）不丢；若当前版本与此处略有出入，以"保留原逻辑 + 插入两处 Part B"为准。

- [ ] **Step 4: 编译验证**

Run:
```bash
cd cpq-backend && ./mvnw -q -o compile
```
Expected: `BUILD SUCCESS`（0 编译错误）。

- [ ] **Step 5: 跑既有 configure/snapshot 相关测试确认无回归**

Run:
```bash
cd cpq-backend && ./mvnw -q -o test -Dtest='ConfigureProductServiceTest,LineRowDataMaterializeCrossTabTest,SnapshotLineNeedsExpandTest'
```
Expected: 全部 `Failures: 0, Errors: 0`（configure 路径走旧签名 false，行为 1:1 不变）。

- [ ] **Step 6: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/configure/service/ConfigureSnapshotService.java
git commit -m "feat(snapshot): snapshotLines/snapshotQuotation skipRowsWithSnapshot overload (incremental)"
```

---

### Task 3: Part A —— saveDraft 保留复用行 snapshot_rows + 接入 skip 调用

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java`
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/resource/QuotationResource.java`

- [ ] **Step 1: 声明 preservedSnapshots（紧邻 preservedTombstones 声明处）**

在 `saveDraft` 内、`preservedTombstones` 声明之后加：

```java
// Part A: 复用行 snapshot_rows 保留 —— 全量重建会清子表，重建时回写避免 snapshotQuotation 全量重 expand
java.util.Map<java.util.UUID, String> preservedSnapshots = new java.util.HashMap<>();
```

- [ ] **Step 2: 复用行捕获 + 新行清空**

在读旧 `QuotationLineComponentData` 填 `preservedTombstones` 的循环里**同步**填 `preservedSnapshots`，并在循环前 `clear()`。改动后该段（复用行分支）：

```java
preservedTombstones.clear();
preservedSnapshots.clear();   // Part A
for (QuotationLineComponentData old :
        QuotationLineComponentData.<QuotationLineComponentData>list("lineItemId = ?1", li.id)) {
    if (old.componentId != null && old.deletedRowKeys != null)
        preservedTombstones.put(old.componentId, old.deletedRowKeys);
    if (old.componentId != null && old.snapshotRows != null)   // Part A
        preservedSnapshots.put(old.componentId, old.snapshotRows);
}
clearLineItemChildren(li.id);
```

在新行分支（`else { li = new QuotationLineItem(); preservedTombstones.clear(); }`）补一行：

```java
} else {
    li = new QuotationLineItem();
    preservedTombstones.clear();
    preservedSnapshots.clear();   // Part A: 新行无快照
}
```

- [ ] **Step 3: 重建 componentData 时回写 snapshotRows**

在创建 `QuotationLineComponentData cd` 的循环里（紧邻设置 `cd.deletedRowKeys` 处）加回写：

```java
// 现有：墓碑回填
String preserved = (cdDraft.componentId != null)
        ? preservedTombstones.get(cdDraft.componentId) : null;
cd.deletedRowKeys = (preserved != null) ? preserved : "[]";
// Part A: 复用行回写旧 snapshot_rows（新行 = null，由 snapshotQuotation 重 expand 填充）
String preservedSr = (cdDraft.componentId != null)
        ? preservedSnapshots.get(cdDraft.componentId) : null;
if (preservedSr != null) cd.snapshotRows = preservedSr;
cd.persist();
```

- [ ] **Step 4: QuotationResource.saveDraft 改调 skip=true**

`cpq-backend/src/main/java/com/cpq/quotation/resource/QuotationResource.java` 约 119 行：

```java
// 改前
snapshotService.snapshotQuotation(id);
// 改后（增量：复用行已回写 snapshot_rows → 跳过重 expand）
snapshotService.snapshotQuotation(id, true);
```

- [ ] **Step 5: 编译验证**

Run:
```bash
cd cpq-backend && ./mvnw -q -o compile
```
Expected: `BUILD SUCCESS`。

- [ ] **Step 6: 跑后端相关测试**

Run:
```bash
cd cpq-backend && ./mvnw -q -o test -Dtest='*Quotation*,ConfigureProductServiceTest,SnapshotLineNeedsExpandTest'
```
Expected: 全绿（无回归）。若某既有 spec 因测试数据漂移失败（与本改动无关），记录但不阻塞。

- [ ] **Step 7: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java \
        cpq-backend/src/main/java/com/cpq/quotation/resource/QuotationResource.java
git commit -m "feat(savedraft): preserve reused-row snapshot_rows + skip full re-expand on autosave"
```

---

### Task 4: 端到端验证（LIVE 计时 + 数据正确性 + E2E）

> 后端 dev server(8081) 服务**主工作区**代码；按 CLAUDE.md/记忆 `cpq-auto-finish-merge-e2e-cleanup`：先在 worktree 完成 + 评审，再合并 master，**合并后**用 dev server 做 LIVE 自检。本 Task 在合并后执行。

- [ ] **Step 1: 合并后强制后端重启并健康检查**

```bash
# (合并 master 后，主工作区)
touch cpq-backend/src/main/java/com/cpq/quotation/resource/QuotationResource.java
sleep 7
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health   # 期望 200
```

- [ ] **Step 2: LIVE —— 二次 saveDraft 计时应从 ~13s 降到 <2s，且复用行 snapshot_rows 不变**

记录改造前基线已知：QT-20260622-1816（`1b4a5c70-...`）首存 snapshot 段 ~57s、稳态 snapshotQuotation ~13s。
验证：在浏览器（已登录会话）打开该草稿，触发一次 autosave（改任一单元格再失焦/等防抖），F12 Network 看 `PUT /draft` 耗时。
- 期望：`PUT /draft` 总耗时显著下降（<2s 量级，无 13s 全量 expand）。
- DB 旁证（跳过未重 expand → snapshot 时间戳不变）：

```bash
export PGPASSWORD=joii5231
psql -h 10.177.152.12 -U postgres -d cpq_db -c "
SELECT count(*) lines, count(quote_card_values) has_qc,
       min(card_snapshot_at) min_snap, max(card_snapshot_at) max_snap
FROM quotation_line_item WHERE quotation_id='1b4a5c70-6e32-470a-a6ed-c3a7bb5c17af';"
# 期望：lines/has_qc 不变（73/73），二次保存后 card_snapshot_at 不应整体刷新（复用行被跳过）
```

- [ ] **Step 3: LIVE —— refresh-snapshot 按钮仍强制全量（回归保护）**

在该草稿点"刷新基础数据"按钮（→ `POST /configure/quotations/{id}/refresh-snapshot`）。
- 期望：返回 200；该路径走 `snapshotQuotation(id)`（旧签名=false）→ 仍全量重 expand（行为不变）。可由 DB `snapshot_rows` 被刷新（内容/时间相关列变化）佐证强制路径未被 skip 影响。

- [ ] **Step 4: 协议级强制 E2E（quotation-flow.spec.ts）**

```powershell
cd cpq-frontend
Remove-Item e2e\screenshots\qf-*.png -ErrorAction SilentlyContinue
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```
Expected: test `passed`；`'加载中' final count = 0`；全 8 Tab `'加载中'=0`；`PUT/draft` 渲染无异常。
（注：若因测试数据漂移——模板版本号不匹配等——导致 Step1 卡住，按 RECORD 既有遗留处理，记录后以"本改动不触达该失败点"说明，并用 LIVE Step2/3 作为实证。）

- [ ] **Step 5: 自检声明 + RECORD 追加**

向 `docs/RECORD.md` 追加：`[2026-06-22] 报价-saveDraft 增量快照根治高频 autosave 全量重 expand 超时 | 文件 | 关键决策（Part A 保留 snapshot_rows + Part B skipRowsWithSnapshot；refresh/加产品走 false 不变）| LIVE 计时 13s→<2s 实证`。
输出一行"已自检"声明（编译 0 错 / 单测 4 passed / 后端 health 200 / LIVE 计时下降 / E2E 结果）。

---

## Self-Review

**1. Spec coverage：**
- 根因（每次 autosave 无条件全量重 expand）→ Task 2（skip 重载）+ Task 3 Step 4（saveDraft 传 true）✅
- 复用行重建后 snapshot_rows 丢失 → Task 3 Part A（捕获 + 回写）✅
- 不误伤 refresh-snapshot / 加产品 → 旧签名委托 false（Task 2 Step 2/3）✅
- 可测性 → Task 1 纯函数单测 ✅
- 协议级 E2E → Task 4 Step 4 ✅

**2. Placeholder scan：** 无 TBD/TODO；每个改动步骤含完整代码。✅

**3. Type consistency：**
- `lineNeedsExpand(Collection<UUID>, Map<UUID,String>)` —— Task 1 定义，Task 2 Step 3 以 `lineNeedsExpand(driverCompIds, self.loadSnapshotRowsByComp(lineItemId))` 调用，类型一致（`driverCompIds` 为 `List<UUID>`，`loadSnapshotRowsByComp` 返回 `Map<UUID,String>`）✅
- `snapshotQuotation(UUID,boolean)` / `snapshotLines(UUID,List,boolean)` 重载签名在 Task 2 定义、Task 3 Step 4 调用 `snapshotQuotation(id, true)` 一致 ✅
- `loadSnapshotRowsByComp` 经 `self.`（CDI 代理）调用以确保 REQUIRES_NEW 生效，与现有 `self.loadCustomerId` 模式一致 ✅
- `snapshotRows` 字段名与实体 `QuotationLineComponentData.snapshotRows` 一致 ✅

**注意事项（实现者）：** `self` 是 `ConfigureSnapshotService` 注入自身的 CDI 代理（已存在）。`loadSnapshotRowsByComp` 必须经 `self.` 调用才触发 `REQUIRES_NEW`。
