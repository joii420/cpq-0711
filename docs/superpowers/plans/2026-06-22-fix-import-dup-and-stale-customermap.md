# Fix Import Duplication (③) + Stale Customer-Map Accumulation (①) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the V6 quote import from (①) leaving stale customer→product mappings that inflate the candidate count (77→85), and stop the editor's autoPopulate from (③) double-writing line items (85→170).

**Architecture:**
- **①** `material_customer_map` is unversioned (upsert key `(material_no, customer_no, customer_product_no)`, no FK references it). The `客户料号与宏丰料号的关系` sheet is the authoritative full mapping set for one customer, so `Q02CustomerMapHandler` must **replace-per-customer**: delete that customer's existing rows once, then upsert the sheet rows.
- **③** `autoSaveDraft` in `QuotationWizard.tsx` is invoked by two effects (import-auto-save + lineItems-change) with *different* payloads while `driverExpansions` is still streaming in. With `id=null` import rows and overlapping backend transactions, neither save's "delete un-kept" removes the other's inserts → accumulation. Fix: serialize `autoSaveDraft` with an in-flight guard so the first save's id-backfill (`syncLineItemsFromResponse`) lands before the next save runs; a single trailing re-run captures the latest payload.

**Tech Stack:** Java 17 / Quarkus / Hibernate Panache + native SQL; React + TypeScript; Playwright E2E; PostgreSQL.

---

### Task 1: ① Replace-per-customer in Q02CustomerMapHandler

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/repository/MaterialCustomerMapRepository.java`
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q02CustomerMapHandler.java`
- Test: `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q02CustomerMapReplaceTest.java`

- [ ] **Step 1: Write the failing test**

Create `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q02CustomerMapReplaceTest.java`:

```java
package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** ① 客户料号映射 replace-per-customer：脏导入留下的多余 customer_product_no 在清洗后重导必须被清掉。 */
@QuarkusTest
class Q02CustomerMapReplaceTest {

    @Inject Q02CustomerMapHandler handler;
    @Inject EntityManager em;

    static final String CUST = "TST-Q02REPLACE";
    static final String HF   = "TST5121115551";

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM material_customer_map WHERE customer_no=:c")
            .setParameter("c", CUST).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_no=:m")
            .setParameter("m", HF).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = CUST; c.systemType = "QUOTE"; c.importedBy = null; return c;
    }
    private SheetRow row(int seq, String cpn) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", HF);
        m.put("客户产品编号", cpn);
        m.put("基础货币", "RMB");
        m.put("报价货币", "RMB");
        m.put("汇率", "1");
        return new SheetRow(seq, m);
    }
    @Transactional
    long count() {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM material_customer_map WHERE customer_no=:c")
                .setParameter("c", CUST).getSingleResult()).longValue();
    }

    @Test
    void reimport_clean_file_removes_stale_rows() {
        // 第一次脏导入：同一宏丰料号映射 3 个客户产品编号
        handler.handle(List.of(row(1, "DIRTY-A"), row(2, "DIRTY-B"), row(3, "DIRTY-C")), ctx());
        assertEquals(3, count(), "脏导入后应有 3 行");

        // 用户清洗文件后重导：同一宏丰料号只剩 1 个客户产品编号
        handler.handle(List.of(row(1, "CLEAN-A")), ctx());
        assertEquals(1, count(), "清洗后重导应只剩 1 行（旧的 3 行被替换）");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd cpq-backend && ./mvnw -q test -Dtest=Q02CustomerMapReplaceTest`
Expected: FAIL — second assert sees 3 rows (DIRTY-B/C survive) instead of 1, because the handler only upserts.

- [ ] **Step 3: Add `deleteByCustomerNo` to the repository**

In `MaterialCustomerMapRepository.java`, add this method inside the class (after `upsert`):

```java
    /** ① replace-per-customer：删除该客户全部映射（重导前清栈，避免历史脏行残留扇出）。 */
    public int deleteByCustomerNo(String customerNo) {
        return em.createNativeQuery(
                "DELETE FROM material_customer_map WHERE customer_no = :customerNo")
            .setParameter("customerNo", customerNo)
            .executeUpdate();
    }
```

- [ ] **Step 4: Make the handler replace-per-customer**

In `Q02CustomerMapHandler.java`, inside `handle(...)`, immediately after `SheetImportResult result = new SheetImportResult(sheetName());` and BEFORE the `for (SheetRow row : rows)` loop, insert:

```java
        // ① replace-per-customer：本 sheet 是该客户客户料号映射的权威全集。
        // 仅当有数据行时先清掉该客户旧映射，再 upsert 本次行，避免上一次（含脏数据）
        // 导入残留的多余 customer_product_no 在候选查询里扇出（77→85 bug）。
        // 空 sheet 不删，防止误清（缺该 sheet 的部分导入不触发本 handler）。
        if (ctx.customerNo != null && !ctx.customerNo.isBlank() && !rows.isEmpty()) {
            int removed = repo.deleteByCustomerNo(ctx.customerNo);
            result.recordWrite("material_customer_map.deleted", removed);
        }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd cpq-backend && ./mvnw -q test -Dtest=Q02CustomerMapReplaceTest`
Expected: PASS (both asserts: 3 then 1).

- [ ] **Step 6: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/repository/MaterialCustomerMapRepository.java \
        cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q02CustomerMapHandler.java \
        cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q02CustomerMapReplaceTest.java
git commit -m "fix(v6import): Q02 客户料号映射 replace-per-customer 清旧行，修清洗文件重导仍扇出(77→85)"
```

---

### Task 2: ③ Serialize autoSaveDraft to stop double-write

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationWizard.tsx`

- [ ] **Step 1: Add in-flight guard refs**

In `QuotationWizard.tsx`, find the existing `const importAutoSavedRef = useRef(false);` (≈ line 208) and add two refs right after it:

```typescript
  // ③ autoSaveDraft 串行化：导入流下 import-auto-save effect 与 lineItems-change effect
  // 会用「不同」payload（driverExpansions 仍在陆续到位）几乎同时触发两次保存；
  // 两条 id=null payload 的后端事务重叠 → 各插 85 行、谁的「删未保留行」都删不到对方 → 170。
  // savingRef：有保存在飞时不再并发起第二次；pendingSaveRef：飞行中到来的变更，落地后补跑一次（取最新 payload）。
  const savingRef = useRef(false);
  const pendingSaveRef = useRef(false);
```

- [ ] **Step 2: Wrap `autoSaveDraft` body with the guard**

Replace the entire `autoSaveDraft` callback (the `const autoSaveDraft = useCallback(async () => { ... }, [quotationId, form, lineItems, driverExpansions, customerIdValue]);` block, ≈ lines 627-657) with:

```typescript
  const autoSaveDraft = useCallback(async () => {
    if (!quotationId) return;
    // ③ 串行化：已有保存在飞 → 记一个待补跑标记后直接返回，不并发第二条 id=null payload。
    if (savingRef.current) { pendingSaveRef.current = true; return; }
    savingRef.current = true;
    try {
      const values = form.getFieldsValue();
      const payload = normalizeDraftPayloadNumbers(buildDraftPayload(values));
      const payloadStr = JSON.stringify(payload);
      if (payloadStr === lastSaveRef.current) return;
      lastSaveRef.current = payloadStr;
      const res = await quotationService.saveDraft(quotationId, payload);
      // BUMP 后端把新 partVersionLocked 写入 DB，前端本地 state 需同步回填，
      // 避免「卡片版本号停在旧值直到强刷」的 UX 漂移；同时回填重建后的新行 id，
      // 触发 driver 展开按新 id 重拉 → 导入工序等按行快照无需刷新即出现。
      syncLineItemsFromResponse(res?.data);
      // P2-9: backup to localStorage on success
      localStorage.setItem(`cpq-draft-${quotationId}`, JSON.stringify(payload));
    } catch {
      // P2-9: fallback to localStorage on failure
      try {
        const values = form.getFieldsValue();
        const payload = buildDraftPayload(values);
        localStorage.setItem(`cpq-draft-${quotationId}`, JSON.stringify(payload));
        message.warning('网络异常，已保存到本地缓存');
      } catch {
        // ignore
      }
    } finally {
      savingRef.current = false;
      // 飞行期间有新的保存请求被合并 → 现在串行补跑一次（取最新 lineItems/expansion 的 payload）。
      // 此时第一次保存的 syncLineItemsFromResponse 已回填行 id，补跑 payload 带 id → 后端就地复用，不再新增重复行。
      if (pendingSaveRef.current) {
        pendingSaveRef.current = false;
        autoSaveDraftRef.current?.();
      }
    }
    // 把 driverExpansions / customerIdValue 列入 deps，让 buildDraftPayload → snapshotRows
    // 内部访问的是最新的 expansion 缓存。否则 useCallback 会缓存空 expansion 的旧闭包：
    // 导入流自动保存即便等到 expansion ready 才触发，autoSaveDraft 内部仍会读到空 driverExpansions
    // → snapshotRows 落 1 行而不是展开后的 N 行（明细页只看到 1 行 — 数据的根因）。
  }, [quotationId, form, lineItems, driverExpansions, customerIdValue]);
```

- [ ] **Step 3: Type-check**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 errors.

- [ ] **Step 4: Commit**

```bash
git add cpq-frontend/src/pages/quotation/QuotationWizard.tsx
git commit -m "fix(quotation): autoSaveDraft 串行化 in-flight 守卫，修导入 autoPopulate 重叠保存致 line item 翻倍(85→170)"
```

---

### Task 3: Post-merge verification + data cleanup (run AFTER merge to master)

> Dev servers (8081/5174) serve `master`, not the worktree. Run this task only after the auto-merge of this branch into `master`.

**Files:**
- Delete (temp): `cpq-frontend/e2e/investigate-empty-cards.spec.ts` (created during investigation)

- [ ] **Step 1: Remove the temporary investigation spec**

```bash
rm -f cpq-frontend/e2e/investigate-empty-cards.spec.ts
```

- [ ] **Step 2: Restart backend (pick up Q02 change) and re-import the cleaned file via the V6 quote import**

Trigger the V6 import for 罗克韦尔 (CUST-1269) with `docs/table/报价测试数据/（加密）罗克韦尔 导入测试.xlsx`, then verify the candidate count is 77 (not 85):

```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -t -A -F'|' -c \
"SELECT count(*) total, count(DISTINCT customer_product_no) cpn FROM material_customer_map WHERE customer_no='CUST-1269';"
```
Expected after re-import of the clean file: total = 77 (replace-per-customer removed the 8 stale rows + 3 older).

- [ ] **Step 3: Clean the existing dirty quote (QT-20260622-1801, 170 rows → 85) and stale map rows**

```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -c "
WITH ranked AS (
  SELECT id, row_number() OVER (PARTITION BY customer_part_no, sort_order ORDER BY created_at) rn
  FROM quotation_line_item WHERE quotation_id='93051b37-82ec-4a37-a00d-4ce22bb6f7a7')
DELETE FROM quotation_line_item WHERE id IN (SELECT id FROM ranked WHERE rn > 1);"
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -t -A -c \
"SELECT count(*) FROM quotation_line_item WHERE quotation_id='93051b37-82ec-4a37-a00d-4ce22bb6f7a7';"
```
Expected: 85 (was 170). (Child tables cascade via existing FK; if not, also clean orphan `quotation_line_component_data`.)

- [ ] **Step 4: Run the quotation-flow E2E against the merged code**

```bash
cd cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```
Expected: `1 passed`, `'加载中' final count = 0`.

- [ ] **Step 5: Commit the spec removal**

```bash
git add -A
git commit -m "chore(e2e): 移除临时排查 spec investigate-empty-cards"
```

---

## Notes / out of scope
- **② (产品页空)** is NOT fixed here — it is missing source data (no finished-product 品名/规格 source; 单重 sheet empty). Awaiting user decision on data vs. new handler.
- Backend idempotency for saveDraft (e.g., `temp_id` natural key) is deliberately NOT added — the frontend serialization addresses the observed root cause with far less risk. Revisit only if multi-tab concurrent editing surfaces duplicates.
