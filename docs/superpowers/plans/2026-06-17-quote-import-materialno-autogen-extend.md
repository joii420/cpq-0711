# 报价导入「投入料号空→按名称匹配/生成」统一化（推广到全部投入料号键页签）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把已落地于 §3/§12 的「料号为空+名称有值 → 按名称匹配料号表 / 匹配不到按 9 字头规则生成 → 登记料号表 → 回填」规则，统一推广到所有以「投入料号 / 组成件料号」为键的报价(QUOTE)导入页签；其中**更新型**页签只匹配不生成。

**Architecture:** 复用现有 `MaterialNoResolver`（匹配/生成）+ `MaterialMasterRepository.upsertByMaterialNo(...,preserveDescriptive=true)`（登记料号表、保留旧值）。新增两处基础设施：`SheetRow.exact(header)`（精确表头读取，避开 `getStr` contains 命中「…名称」列的已知坑）与 `MaterialNoResolver.resolveMatchOnly(...)`（仅匹配不生成，供更新型 §5 用）。每个 handler 在其 `REQUIRES_NEW` 事务入口 new 一个 `BatchState`（同表同名只生成一次）；handler 顺序保证 §3/§12 先 commit、后续页签可按名匹配其登记的料号；生成走 advisory lock + DB MAX，跨 handler 不重号。

**Tech Stack:** Java 17 + Quarkus 3.23.3 + Hibernate Panache + PostgreSQL 16；测试 `@QuarkusTest`（连共享 dev DB，用独立前缀隔离数据）。

---

## 背景与既有事实（实现者必读）

- 规则源文档：`docs/table/报价系统Excel导入落库方案.md` §3~§13；前序设计 `docs/superpowers/specs/2026-06-15-quote-import-materialno-autogen-design.md`（§3/§12 已实现，是本次样板）。
- 现有可复用件：
  - `MaterialNoResolver.resolve(materialNo, materialName, BatchState)`：料号有值→trim 返回；空+名称有值→`nameToNo` 缓存命中 / 料号表 `findFirstByMaterialName`（升序第一条）命中 / 否则 `generateNextMaterialNo`（advisory lock + `MAX(9字头)+1` + `batchMaxGenerated`）；都空→抛 `MaterialNoUnresolvableException`。**不写料号表**。
  - `MaterialMasterRepository.upsertByMaterialNo(materialNo, materialName, specification, dimension, oldMaterialNo, materialType, usageProperty, unitWeight, standardUnit, updatedBy, preserveDescriptive)`：`preserveDescriptive=true` ⇒ 已存在保留旧 name/type（仅空回填）。
  - 样板调用见 `MaterialBomMergeHandler.java` §3/§12（`exactCell(row,"投入料号")` + `resolve` + `upsertByMaterialNo(...,"3",...,true)`）。
- **已知坑**：`SheetRow.getStr("投入料号")` 用 `header.contains("投入料号")` 匹配，列顺序异常时会命中「投入料号名称」列。读键列**必须**用精确表头。
- **范围内页签 & 类型**（本计划目标）：
  | Sheet | Handler | 键列 | 名称列 | 类型 |
  |------|---------|------|--------|------|
  | §4 物料与元素BOM | `Q04ElementBomHandler` | 投入料号→`element_bom.material_no` | 投入料号名称 | 新建（匹配/生成/登记） |
  | §6 来料固定加工费 | `Q06FixedProcessFeeHandler` | 投入料号→`unit_price.code` | 投入料号名称 | 新建 |
  | §7 来料其他费用 | `Q07IncomingOtherFeeHandler` | 投入料号→`unit_price.code` | 投入料号名称 | 新建 |
  | §8 来料年降 | `Q08IncomingAnnualDiscountHandler` | 投入料号→`unit_price.code` | 投入料号名称 | 新建 |
  | §9 来料回收折扣 | `Q09IncomingRecoveryHandler` | 投入料号→`unit_price.code` | 投入料号名称 | 新建 |
  | §10 自制加工费 | `Q10SelfProcessFeeHandler` | 投入料号→`unit_price.code` | 投入料号名称 | 新建 |
  | §13 组成件其他费用 | `Q13ComponentOtherFeeHandler` | 组成件料号→`unit_price.code` | 组成件名称 | 新建 |
  | §5 元素回收折扣 | `Q05ElementRecoveryHandler` | 投入料号→匹配键 `material_no` | 投入料号名称 | **更新（只匹配不生成）** |
- **登记口径**（已与用户确认）：新建型解析出料号后 `upsertByMaterialNo(no, name, null,null,null, "3", null,null,null, ctx.importedBy, true)`——`material_type` 统一写 `"3"`，保留旧值。
- **不动**：§3 物料BOM、§12 组成件BOM（已实现）；§1 元素单价 / §2 客户映射 / §11 成品其他费用 / §14 组装加工费 / §15 组装加工费年降 / §16 电镀方案 / §17 电镀费用 / §18 单重 / §19 年降系数（均不以投入料号为键）。

## File Structure

| 文件 | 责任 / 改动 |
|------|-------------|
| `cpq-backend/.../parser/SheetRow.java` | **新增** `public String exact(String header)`：精确表头读取（blank→null，trim）。 |
| `cpq-backend/.../service/MaterialNoResolver.java` | **新增** `String resolveMatchOnly(String, String, BatchState)`：仅匹配不生成，未命中返回 `null`。 |
| `cpq-backend/.../quote/Q04ElementBomHandler.java` | 投入料号空→resolve+登记+回填；注入 resolver+repo；入口 new BatchState。 |
| `cpq-backend/.../quote/Q10SelfProcessFeeHandler.java` | code 列 resolve+登记+回填（样板）。 |
| `cpq-backend/.../quote/Q06FixedProcessFeeHandler.java` | 同 Q10（来料族）。 |
| `cpq-backend/.../quote/Q07IncomingOtherFeeHandler.java` | 同 Q10（先判 `要素名称`）。 |
| `cpq-backend/.../quote/Q08IncomingAnnualDiscountHandler.java` | 同 Q10（来料族）。 |
| `cpq-backend/.../quote/Q09IncomingRecoveryHandler.java` | 同 Q10（来料族）。 |
| `cpq-backend/.../quote/Q13ComponentOtherFeeHandler.java` | 键=组成件料号、名称=组成件名称；先判 `要素名称`。 |
| `cpq-backend/.../quote/Q05ElementRecoveryHandler.java` | 投入料号空→`resolveMatchOnly` 匹配；未命中 recordError；**不生成不登记**。 |
| `cpq-backend/src/test/java/com/cpq/basicdata/v6/...` | 各 handler 的 `@QuarkusTest`，外加幂等集成测试。 |
| `docs/RECORD.md` / 落库方案文档 | 回写开发记录 + 标注 §4~§13 已实现。 |

---

### Task 1: SheetRow.exact(header) 精确表头读取

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/parser/SheetRow.java`
- Test: `cpq-backend/src/test/java/com/cpq/basicdata/v6/parser/SheetRowExactTest.java`

- [ ] **Step 1: Write the failing test**

`cpq-backend/src/test/java/com/cpq/basicdata/v6/parser/SheetRowExactTest.java`:

```java
package com.cpq.basicdata.v6.parser;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SheetRowExactTest {

    private SheetRow row(Map<String, String> m) { return new SheetRow(1, m); }

    @Test
    void exact_ignoresContainsSiblingColumn() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("投入料号", "");            // 空
        m.put("投入料号名称", "银点");     // 同前缀兄弟列有值
        SheetRow r = row(m);
        assertNull(r.exact("投入料号"), "精确读空键列应为 null，绝不串到名称列");
        assertEquals("银点", r.exact("投入料号名称"));
    }

    @Test
    void exact_trimsAndBlankToNull() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("组成件料号", "  C-1  ");
        m.put("空列", "   ");
        SheetRow r = row(m);
        assertEquals("C-1", r.exact("组成件料号"));
        assertNull(r.exact("空列"));
        assertNull(r.exact("不存在的列"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd cpq-backend && ./mvnw test -Dtest=SheetRowExactTest -q`
Expected: FAIL — `cannot find symbol: method exact(String)`.

- [ ] **Step 3: Write minimal implementation**

在 `SheetRow.java` 的 `getStr(String...)` 方法之后插入：

```java
    /**
     * 按**精确表头**读取单元格值（非 contains），空白→null、trim。
     * 用于读「料号」键列，避开 {@link #getStr(String...)} 的 contains 匹配命中「…名称」列（如 投入料号 vs 投入料号名称）。
     */
    public String exact(String header) {
        String v = cells.get(header);
        return (v == null || v.isBlank()) ? null : v.trim();
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd cpq-backend && ./mvnw test -Dtest=SheetRowExactTest -q`
Expected: PASS（2 tests）。

- [ ] **Step 5: Refactor — MaterialBomMergeHandler 复用（DRY，不改行为）**

`MaterialBomMergeHandler.java`：把两处 `exactCell(row, "投入料号")`、`exactCell(row, "组成件料号")` 改为 `row.exact("投入料号")`、`row.exact("组成件料号")`，并删除文件末尾的私有 `exactCell` 方法。

Run: `cd cpq-backend && ./mvnw test -Dtest=MaterialBomMaterialNoResolveTest,MaterialBomMergeHandlerTest -q`
Expected: PASS（§3/§12 行为不变）。

- [ ] **Step 6: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/parser/SheetRow.java \
        cpq-backend/src/test/java/com/cpq/basicdata/v6/parser/SheetRowExactTest.java \
        cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandler.java
git commit -m "feat(import): SheetRow.exact() 精确表头读取 + MaterialBomMergeHandler 复用"
```

---

### Task 2: MaterialNoResolver.resolveMatchOnly（仅匹配不生成）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/service/MaterialNoResolver.java`
- Test: `cpq-backend/src/test/java/com/cpq/basicdata/v6/service/MaterialNoResolverMatchOnlyTest.java`

- [ ] **Step 1: Write the failing test**

`cpq-backend/src/test/java/com/cpq/basicdata/v6/service/MaterialNoResolverMatchOnlyTest.java`:

```java
package com.cpq.basicdata.v6.service;

import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class MaterialNoResolverMatchOnlyTest {

    @Inject MaterialNoResolver resolver;
    @Inject MaterialMasterRepository repo;
    @Inject EntityManager em;

    static final String NAME = "MO-银点-0617";

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM material_master WHERE material_name=:n OR material_no LIKE '9%'")
          .setParameter("n", NAME).executeUpdate();
    }
    @Transactional void seed() {
        repo.upsertByMaterialNo("MO-EXIST-1", NAME, null,null,null,"3",null,null,null, null, true);
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    @Test
    void matchOnly_noValue_returnsTrimmed() {
        MaterialNoResolver.BatchState s = new MaterialNoResolver.BatchState();
        assertEquals("X-1", resolver.resolveMatchOnly("  X-1 ", null, s));
    }

    @Test
    void matchOnly_emptyNoMatchedByName() {
        seed();
        MaterialNoResolver.BatchState s = new MaterialNoResolver.BatchState();
        assertEquals("MO-EXIST-1", resolver.resolveMatchOnly(null, NAME, s));
    }

    @Test
    void matchOnly_emptyNoUnmatched_returnsNull_doesNotGenerate() {
        MaterialNoResolver.BatchState s = new MaterialNoResolver.BatchState();
        assertNull(resolver.resolveMatchOnly(null, "MO-从不存在的名字", s), "未命中→null，不生成");
        long gen = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_master WHERE material_no LIKE '9%'").getSingleResult()).longValue();
        assertEquals(0L, gen, "match-only 绝不生成 9 字头");
    }

    @Test
    void matchOnly_bothBlank_returnsNull() {
        MaterialNoResolver.BatchState s = new MaterialNoResolver.BatchState();
        assertNull(resolver.resolveMatchOnly("  ", "  ", s));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd cpq-backend && ./mvnw test -Dtest=MaterialNoResolverMatchOnlyTest -q`
Expected: FAIL — `cannot find symbol: method resolveMatchOnly`.

- [ ] **Step 3: Write minimal implementation**

在 `MaterialNoResolver.java` 的 `resolve(...)` 方法之后插入：

```java
    /**
     * 仅匹配不生成（更新型 sheet 用，如 §5 元素回收折扣）。
     * 料号有值→trim 返回；料号空+名称有值→按名匹配料号表（含 {@link BatchState#nameToNo} 缓存），
     * 命中返回其料号，未命中返回 {@code null}；料号与名称都空→返回 {@code null}。**绝不生成 9 字头。**
     */
    public String resolveMatchOnly(String materialNo, String materialName, BatchState state) {
        String no = trimToNull(materialNo);
        if (no != null) return no;

        String name = trimToNull(materialName);
        if (name == null) return null;

        String cached = state.nameToNo.get(name);
        if (cached != null) return cached;

        Optional<MaterialMaster> existing = repo.findFirstByMaterialName(name);
        if (existing.isPresent()) {
            String existingNo = existing.get().materialNo;
            state.nameToNo.put(name, existingNo);
            return existingNo;
        }
        return null;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd cpq-backend && ./mvnw test -Dtest=MaterialNoResolverMatchOnlyTest -q`
Expected: PASS（4 tests）。

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/service/MaterialNoResolver.java \
        cpq-backend/src/test/java/com/cpq/basicdata/v6/service/MaterialNoResolverMatchOnlyTest.java
git commit -m "feat(import): MaterialNoResolver.resolveMatchOnly 仅匹配不生成(供更新型sheet)"
```

---

### Task 3: §4 物料与元素BOM（Q04，新建型，键=material_no）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q04ElementBomHandler.java`
- Test: `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q04ElementBomResolveTest.java`

- [ ] **Step 1: Write the failing test**

`cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q04ElementBomResolveTest.java`:

```java
package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetImportResult;
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

@QuarkusTest
class Q04ElementBomResolveTest {

    @Inject Q04ElementBomHandler handler;
    @Inject EntityManager em;

    static final String CUST = "Q04CUST0617";
    static final String NAME = "Q04-母件-0617";

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM element_bom_item WHERE customer_no=:c").setParameter("c", CUST).executeUpdate();
        em.createNativeQuery("DELETE FROM element_bom WHERE customer_no=:c").setParameter("c", CUST).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_name=:n OR material_no LIKE '9%'")
          .setParameter("n", NAME).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = CUST; c.systemType = "QUOTE"; c.importedBy = null; return c;
    }
    private SheetRow row(String inputNo, String inputName, String element) {
        Map<String, String> m = new LinkedHashMap<>();
        if (inputNo != null) m.put("投入料号", inputNo);
        if (inputName != null) m.put("投入料号名称", inputName);
        m.put("项次", "1"); m.put("元素", element); m.put("组成含量", "75");
        return new SheetRow(1, m);
    }
    private long masterCount(String name) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM material_master WHERE material_name=:n")
            .setParameter("n", name).getSingleResult()).longValue();
    }
    private String bomMaterialNo() {
        return em.createNativeQuery("SELECT material_no FROM element_bom WHERE customer_no=:c AND is_current=TRUE")
            .setParameter("c", CUST).getResultList().stream().findFirst().map(Object::toString).orElse(null);
    }

    @Test
    void emptyNoWithName_generatesRegistersAndUsesAsMaterialNo() {
        SheetImportResult r = handler.handle(List.of(row(null, NAME, "Ag")), ctx());
        assertEquals(0, r.failedRows);
        assertEquals(1L, masterCount(NAME), "新料件登记进料号表");
        assertEquals("9000000000", bomMaterialNo(), "生成号回填为 element_bom.material_no");
        String type = em.createNativeQuery("SELECT material_type FROM material_master WHERE material_name=:n")
            .setParameter("n", NAME).getSingleResult().toString();
        assertEquals("3", type, "material_type 统一写 3");
    }

    @Test
    void emptyNoAndEmptyName_recordsError() {
        SheetImportResult r = handler.handle(List.of(row(null, null, "Ag")), ctx());
        assertTrue(r.failedRows >= 1);
        assertNull(bomMaterialNo());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd cpq-backend && ./mvnw test -Dtest=Q04ElementBomResolveTest -q`
Expected: FAIL — 当前 `投入料号` 为空直接 recordError，`masterCount=0`、`bomMaterialNo=null`。

- [ ] **Step 3: Write minimal implementation**

`Q04ElementBomHandler.java`：

(a) 加 import 与注入字段（类顶部 `@Inject VersionedV6Writer writer;` 旁）：

```java
import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
import com.cpq.basicdata.v6.service.MaterialNoResolver;
import com.cpq.basicdata.v6.service.MaterialNoUnresolvableException;
```
```java
    @Inject MaterialNoResolver materialNoResolver;
    @Inject MaterialMasterRepository materialMasterRepo;
```

(b) `handle` 方法里，`SheetImportResult result = ...;` 之后、`for` 之前，new 一个 BatchState：

```java
        MaterialNoResolver.BatchState batch = new MaterialNoResolver.BatchState();
```

(c) 把循环体开头的：

```java
            String materialNo = row.getStr("投入料号");
            if (materialNo == null) { result.recordError(row.rowNo, "投入料号", "为空（应作为主件料号）"); continue; }
```

替换为：

```java
            String inputName = row.exact("投入料号名称");
            String materialNo;
            try {
                materialNo = materialNoResolver.resolve(row.exact("投入料号"), inputName, batch);
            } catch (MaterialNoUnresolvableException ex) {
                result.recordError(row.rowNo, "投入料号", "料号与名称均为空"); continue;
            }
            materialMasterRepo.upsertByMaterialNo(materialNo, inputName,
                null, null, null, "3", null, null, null, ctx.importedBy, true);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd cpq-backend && ./mvnw test -Dtest=Q04ElementBomResolveTest -q`
Expected: PASS（2 tests）。

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q04ElementBomHandler.java \
        cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q04ElementBomResolveTest.java
git commit -m "feat(import): §4 物料与元素BOM 投入料号空→匹配/生成/登记/回填(Q04)"
```

---

### Task 4: §6~§10 来料族 + 自制加工费（code 键，新建型）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q10SelfProcessFeeHandler.java`
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q06FixedProcessFeeHandler.java`
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q07IncomingOtherFeeHandler.java`
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q08IncomingAnnualDiscountHandler.java`
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q09IncomingRecoveryHandler.java`
- Test: `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q10SelfProcessFeeResolveTest.java`

**共同改法（5 个 handler 一致）**：注入 `MaterialNoResolver materialNoResolver` + `MaterialMasterRepository materialMasterRepo`（加对应 3 个 import）；`handle` 入口 new `MaterialNoResolver.BatchState batch`；把读 code 的两行替换为 resolve+登记。

- [ ] **Step 1: Write the failing test**

`cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q10SelfProcessFeeResolveTest.java`:

```java
package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetImportResult;
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

@QuarkusTest
class Q10SelfProcessFeeResolveTest {

    @Inject Q10SelfProcessFeeHandler handler;
    @Inject EntityManager em;

    static final String CUST = "Q10CUST0617";
    static final String FIN  = "Q10FIN0617";
    static final String NAME = "Q10-耗材-0617";

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE customer_no=:c").setParameter("c", CUST).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_name=:n OR material_no LIKE '9%'")
          .setParameter("n", NAME).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = CUST; c.systemType = "QUOTE"; c.importedBy = null; return c;
    }
    private SheetRow row(String code, String name) {
        Map<String, String> m = new LinkedHashMap<>();
        if (code != null) m.put("投入料号", code);
        if (name != null) m.put("投入料号名称", name);
        m.put("宏丰料号", FIN); m.put("工序编号", "OP10"); m.put("项次（一级）", "1");
        m.put("值", "12.5"); m.put("货币", "CNY"); m.put("计价单位", "PCS");
        return new SheetRow(1, m);
    }
    private long masterCount(String name) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM material_master WHERE material_name=:n")
            .setParameter("n", name).getSingleResult()).longValue();
    }
    private String upCode() {
        return em.createNativeQuery("SELECT code FROM unit_price WHERE customer_no=:c AND is_current=TRUE")
            .setParameter("c", CUST).getResultList().stream().findFirst().map(Object::toString).orElse(null);
    }

    @Test
    void emptyCodeWithName_generatesRegistersAndUsesAsCode() {
        SheetImportResult r = handler.handle(List.of(row(null, NAME)), ctx());
        assertEquals(0, r.failedRows);
        assertEquals(1L, masterCount(NAME), "新料件登记进料号表(type=3)");
        assertEquals("9000000000", upCode(), "生成号回填为 unit_price.code");
    }

    @Test
    void emptyCodeAndEmptyName_recordsError() {
        SheetImportResult r = handler.handle(List.of(row(null, null)), ctx());
        assertTrue(r.failedRows >= 1);
        assertNull(upCode());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd cpq-backend && ./mvnw test -Dtest=Q10SelfProcessFeeResolveTest -q`
Expected: FAIL — code 为空直接 recordError，`masterCount=0`、`upCode=null`。

- [ ] **Step 3: 改 Q10SelfProcessFeeHandler**

加 import + 注入：

```java
import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
import com.cpq.basicdata.v6.service.MaterialNoResolver;
import com.cpq.basicdata.v6.service.MaterialNoUnresolvableException;
```
```java
    @Inject MaterialNoResolver materialNoResolver;
    @Inject MaterialMasterRepository materialMasterRepo;
```

`handle` 里 `SheetImportResult result = ...;` 之后插入：

```java
        MaterialNoResolver.BatchState batch = new MaterialNoResolver.BatchState();
```

把：

```java
            String code = row.getStr("投入料号");
            if (code == null) { result.recordError(row.rowNo, "投入料号", "为空"); continue; }
```

替换为：

```java
            String inputName = row.exact("投入料号名称");
            String code;
            try {
                code = materialNoResolver.resolve(row.exact("投入料号"), inputName, batch);
            } catch (MaterialNoUnresolvableException ex) {
                result.recordError(row.rowNo, "投入料号", "料号与名称均为空"); continue;
            }
            materialMasterRepo.upsertByMaterialNo(code, inputName,
                null, null, null, "3", null, null, null, ctx.importedBy, true);
```

- [ ] **Step 4: 改 Q06 / Q08 / Q09（与 Q10 同形）**

对 `Q06FixedProcessFeeHandler.java`、`Q08IncomingAnnualDiscountHandler.java`、`Q09IncomingRecoveryHandler.java` 各自做与 Step 3 完全相同的三处改动（import、注入两字段、入口 new BatchState、替换 `String code = row.getStr("投入料号"); if (code == null) {...}` 两行为上面的 resolve+登记块）。三者那两行原文与 Q10 一致。

- [ ] **Step 5: 改 Q07IncomingOtherFeeHandler（先判要素名称，避免给无效行登记料号表）**

加同样的 import + 注入两字段 + 入口 `MaterialNoResolver.BatchState batch = new MaterialNoResolver.BatchState();`。把：

```java
            String code = row.getStr("投入料号");
            String costType = row.getStr("要素名称");
            if (code == null || costType == null) {
                result.recordError(row.rowNo, "投入料号/要素名称", "必填项为空");
                continue;
            }
```

替换为：

```java
            String costType = row.getStr("要素名称");
            if (costType == null) { result.recordError(row.rowNo, "要素名称", "为空"); continue; }
            String inputName = row.exact("投入料号名称");
            String code;
            try {
                code = materialNoResolver.resolve(row.exact("投入料号"), inputName, batch);
            } catch (MaterialNoUnresolvableException ex) {
                result.recordError(row.rowNo, "投入料号", "料号与名称均为空"); continue;
            }
            materialMasterRepo.upsertByMaterialNo(code, inputName,
                null, null, null, "3", null, null, null, ctx.importedBy, true);
```

- [ ] **Step 6: Run tests to verify pass**

Run: `cd cpq-backend && ./mvnw test -Dtest=Q10SelfProcessFeeResolveTest -q`
Expected: PASS（2 tests）。
Run（确保 5 个 handler 编译 + 既有测试不回归）：`cd cpq-backend && ./mvnw test -Dtest='Q0*Handler*Test,Q10*Test' -q`
Expected: 无编译错误、相关测试 PASS（无相关旧测试时 Step 6 第一条 PASS 即可）。

- [ ] **Step 7: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q06FixedProcessFeeHandler.java \
        cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q07IncomingOtherFeeHandler.java \
        cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q08IncomingAnnualDiscountHandler.java \
        cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q09IncomingRecoveryHandler.java \
        cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q10SelfProcessFeeHandler.java \
        cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q10SelfProcessFeeResolveTest.java
git commit -m "feat(import): §6~§10 来料族+自制加工费 投入料号空→匹配/生成/登记/回填"
```

---

### Task 5: §13 组成件其他费用（Q13，键=组成件料号，新建型）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q13ComponentOtherFeeHandler.java`
- Test: `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q13ComponentOtherFeeResolveTest.java`

- [ ] **Step 1: Write the failing test**

`cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q13ComponentOtherFeeResolveTest.java`:

```java
package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetImportResult;
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

@QuarkusTest
class Q13ComponentOtherFeeResolveTest {

    @Inject Q13ComponentOtherFeeHandler handler;
    @Inject EntityManager em;

    static final String CUST = "Q13CUST0617";
    static final String FIN  = "Q13FIN0617";
    static final String NAME = "Q13-组件-0617";

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE customer_no=:c").setParameter("c", CUST).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_name=:n OR material_no LIKE '9%'")
          .setParameter("n", NAME).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = CUST; c.systemType = "QUOTE"; c.importedBy = null; return c;
    }
    private SheetRow row(String code, String name, String costType) {
        Map<String, String> m = new LinkedHashMap<>();
        if (code != null) m.put("组成件料号", code);
        if (name != null) m.put("组成件名称", name);
        if (costType != null) m.put("要素名称", costType);
        m.put("宏丰料号", FIN); m.put("工序编号", "OP1"); m.put("供应商编号", "SUP1");
        // 三个裸"项次"列，item_seq 取第 3 个
        java.util.List<String[]> ord = new java.util.ArrayList<>();
        for (Map.Entry<String,String> e : m.entrySet()) ord.add(new String[]{e.getKey(), e.getValue()});
        ord.add(new String[]{"项次", "1"});
        ord.add(new String[]{"项次", "1"});
        ord.add(new String[]{"项次", "1"});
        ord.add(new String[]{"值", "8.0"}); ord.add(new String[]{"货币", "CNY"}); ord.add(new String[]{"计价单位", "PCS"});
        return new SheetRow(1, ord);
    }
    private long masterCount(String name) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM material_master WHERE material_name=:n")
            .setParameter("n", name).getSingleResult()).longValue();
    }
    private String upCode() {
        return em.createNativeQuery("SELECT code FROM unit_price WHERE customer_no=:c AND is_current=TRUE")
            .setParameter("c", CUST).getResultList().stream().findFirst().map(Object::toString).orElse(null);
    }

    @Test
    void emptyComponentNoWithName_generatesRegistersAndUsesAsCode() {
        SheetImportResult r = handler.handle(List.of(row(null, NAME, "包装费")), ctx());
        assertEquals(0, r.failedRows);
        assertEquals(1L, masterCount(NAME));
        assertEquals("9000000000", upCode());
    }

    @Test
    void emptyCostType_recordsError_noRegister() {
        SheetImportResult r = handler.handle(List.of(row(null, NAME, null)), ctx());
        assertTrue(r.failedRows >= 1);
        assertEquals(0L, masterCount(NAME), "要素名称为空先拦截，不登记料号表");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd cpq-backend && ./mvnw test -Dtest=Q13ComponentOtherFeeResolveTest -q`
Expected: FAIL — 组成件料号空→`必填项为空` recordError，`masterCount=0`、`upCode=null`。

- [ ] **Step 3: Write minimal implementation**

`Q13ComponentOtherFeeHandler.java`：加 import + 注入两字段（同 Task 4 Step 3）；`handle` 入口 new `MaterialNoResolver.BatchState batch`。把：

```java
            String code = row.getStr("组成件料号");
            String costType = row.getStr("要素名称");
            if (code == null || costType == null) {
                result.recordError(row.rowNo, "组成件料号/要素名称", "必填项为空");
                continue;
            }
```

替换为：

```java
            String costType = row.getStr("要素名称");
            if (costType == null) { result.recordError(row.rowNo, "要素名称", "为空"); continue; }
            String componentName = row.exact("组成件名称");
            String code;
            try {
                code = materialNoResolver.resolve(row.exact("组成件料号"), componentName, batch);
            } catch (MaterialNoUnresolvableException ex) {
                result.recordError(row.rowNo, "组成件料号", "料号与名称均为空"); continue;
            }
            materialMasterRepo.upsertByMaterialNo(code, componentName,
                null, null, null, "3", null, null, null, ctx.importedBy, true);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd cpq-backend && ./mvnw test -Dtest=Q13ComponentOtherFeeResolveTest -q`
Expected: PASS（2 tests）。

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q13ComponentOtherFeeHandler.java \
        cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q13ComponentOtherFeeResolveTest.java
git commit -m "feat(import): §13 组成件其他费用 组成件料号空→匹配/生成/登记/回填"
```

---

### Task 6: §5 元素回收折扣（Q05，更新型，只匹配不生成）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q05ElementRecoveryHandler.java`
- Test: `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q05ElementRecoveryResolveTest.java`

- [ ] **Step 1: Write the failing test**

`cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q05ElementRecoveryResolveTest.java`:

```java
package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class Q05ElementRecoveryResolveTest {

    @Inject Q05ElementRecoveryHandler handler;
    @Inject MaterialMasterRepository repo;
    @Inject EntityManager em;

    static final String CUST = "Q05CUST0617";
    static final String MAT  = "Q05MAT0617";
    static final String NAME = "Q05-母件-0617";

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM element_bom_item WHERE customer_no=:c").setParameter("c", CUST).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_name=:n OR material_no LIKE '9%'")
          .setParameter("n", NAME).executeUpdate();
    }
    @Transactional void seed() {
        // 料号表登记母件名称→料号映射
        repo.upsertByMaterialNo(MAT, NAME, null,null,null,"3",null,null,null, null, true);
        // 预置一条当前生效 element_bom_item 供 UPDATE 命中
        em.createNativeQuery(
            "INSERT INTO element_bom_item (system_type, customer_no, material_no, component_no, characteristic, " +
            " is_current, created_at, updated_at) VALUES ('QUOTE', :c, :m, 'Ag', '2000', TRUE, NOW(), NOW())")
          .setParameter("c", CUST).setParameter("m", MAT).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = CUST; c.systemType = "QUOTE"; c.importedBy = null; return c;
    }
    private SheetRow row(String inputNo, String inputName, String element, String discount) {
        Map<String, String> m = new LinkedHashMap<>();
        if (inputNo != null) m.put("投入料号", inputNo);
        if (inputName != null) m.put("投入料号名称", inputName);
        m.put("元素", element); m.put("回收折扣", discount);
        return new SheetRow(1, m);
    }
    private java.math.BigDecimal discountOf() {
        return (java.math.BigDecimal) em.createNativeQuery(
            "SELECT recovery_discount FROM element_bom_item WHERE customer_no=:c AND material_no=:m AND component_no='Ag'")
            .setParameter("c", CUST).setParameter("m", MAT).getSingleResult();
    }

    @Test
    void emptyNoMatchedByName_updatesExisting_noGenerate() {
        seed();
        SheetImportResult r = handler.handle(List.of(row(null, NAME, "Ag", "70")), ctx());
        assertEquals(0, r.failedRows);
        assertEquals(0, new java.math.BigDecimal("70").compareTo(discountOf()), "按名匹配到母件料号→UPDATE 成功");
        long gen = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_master WHERE material_no LIKE '9%'").getSingleResult()).longValue();
        assertEquals(0L, gen, "更新型只匹配不生成");
    }

    @Test
    void emptyNoUnmatchedName_recordsError() {
        seed();
        SheetImportResult r = handler.handle(List.of(row(null, "查无此名0617", "Ag", "70")), ctx());
        assertTrue(r.failedRows >= 1, "名称查不到料号→记错误，不生成");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd cpq-backend && ./mvnw test -Dtest=Q05ElementRecoveryResolveTest -q`
Expected: FAIL — 当前 `投入料号` 为空即 `匹配键不全`，`emptyNoMatchedByName_*` 失败。

- [ ] **Step 3: Write minimal implementation**

`Q05ElementRecoveryHandler.java`：加 import + 注入 resolver：

```java
import com.cpq.basicdata.v6.service.MaterialNoResolver;
```
```java
    @Inject MaterialNoResolver materialNoResolver;
```

`handle` 里 `SheetImportResult result = ...;` 之后、`for` 之前插入：

```java
        MaterialNoResolver.BatchState batch = new MaterialNoResolver.BatchState();
```

把循环体里：

```java
                String materialNo = row.getStr("投入料号");
                String componentNo = row.getStr("元素");
                java.math.BigDecimal recoveryDiscount = row.getDecimal("回收折扣");

                // §5 字段表：项次 ❌ 不导入。匹配键仅 (material_no=投入料号, component_no=元素)，取最新 characteristic。
                if (materialNo == null || componentNo == null) {
                    result.recordError(row.rowNo, "投入料号/元素", "匹配键不全");
                    continue;
                }
```

替换为：

```java
                String inputName = row.exact("投入料号名称");
                String materialNo = materialNoResolver.resolveMatchOnly(row.exact("投入料号"), inputName, batch);
                String componentNo = row.getStr("元素");
                java.math.BigDecimal recoveryDiscount = row.getDecimal("回收折扣");

                // §5 更新型：只按名匹配不生成。料号为空且按名称查不到 → 记错误跳过。
                if (materialNo == null || componentNo == null) {
                    result.recordError(row.rowNo, "投入料号/元素",
                        materialNo == null
                            ? "投入料号为空，且按名称[" + (inputName == null ? "" : inputName) + "]在料号表查无对应料号"
                            : "匹配键不全");
                    continue;
                }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd cpq-backend && ./mvnw test -Dtest=Q05ElementRecoveryResolveTest -q`
Expected: PASS（2 tests）。

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q05ElementRecoveryHandler.java \
        cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q05ElementRecoveryResolveTest.java
git commit -m "feat(import): §5 元素回收折扣 投入料号空→按名匹配(只匹配不生成,更新型)"
```

---

### Task 7: 全量回归 + 文档/记录回写

**Files:**
- Modify: `docs/RECORD.md`
- Modify: `docs/table/报价系统Excel导入落库方案.md`（§4~§13 相关页签标注已实现）
- Modify: `docs/superpowers/specs/2026-06-15-quote-import-materialno-autogen-design.md`（在文末追加"2026-06-17 推广续作"小节，链接本计划）

- [ ] **Step 1: 跑全量相关回归**

Run:
```bash
cd cpq-backend && ./mvnw test -Dtest='MaterialNoResolver*Test,MaterialBom*Test,MaterialNoImportIdempotencyTest,P06MaterialBomHandlerTest,SheetRowExactTest,Q04*ResolveTest,Q05*ResolveTest,Q10*ResolveTest,Q13*ResolveTest' -q
```
Expected: 全部 PASS，0 failures、0 errors。
若任一 FAIL：按 `superpowers:systematic-debugging` 定位修复，重跑至全绿，**不得跳过**。

- [ ] **Step 2: 回写 RECORD.md**

在 `docs/RECORD.md` 顶部追加一行（格式遵循 CLAUDE.md `[日期] 模块 - 描述 | 文件 | 决策`）：

```markdown
[2026-06-17] 报价导入-料号自动维护推广 - 把 §3/§12 的「投入料号空+名称有值→匹配/生成/登记/回填」推广到全部投入料号键页签：新建型 §4/§6/§7/§8/§9/§10/§13 走 resolve+upsert(type=3,preserveDescriptive=true)，更新型 §5 走 resolveMatchOnly(只匹配不生成) | SheetRow.exact / MaterialNoResolver.resolveMatchOnly / Q04/Q05/Q06/Q07/Q08/Q09/Q10/Q13 + 各 *ResolveTest | 决策：material_type 统一写 3；读键列一律 row.exact 避开 getStr contains 命中名称列；每 handler 自持 BatchState，handler 顺序保证先登记后匹配、生成走 advisory lock+DB MAX 跨 handler 不重号
```

- [ ] **Step 3: 标注落库方案文档**

在 `docs/table/报价系统Excel导入落库方案.md` 的 §4、§5、§6、§7、§8、§9、§10、§13 各节开头补一行：

```markdown
> ✅ **2026-06-17 实现**：本 Sheet「投入料号/组成件料号」为空+名称有值时，按名称匹配料号表 / 匹配不到自动生成 9 字头料号并登记料号表(material_type=3)，再回填键列继续落库；§5 为更新型仅匹配不生成。详见 `docs/superpowers/plans/2026-06-17-quote-import-materialno-autogen-extend.md`。
```

- [ ] **Step 4: Commit**

```bash
git add docs/RECORD.md docs/table/报价系统Excel导入落库方案.md \
        docs/superpowers/specs/2026-06-15-quote-import-materialno-autogen-design.md
git commit -m "docs(import): 回写 RECORD + 标注 §4~§13 料号自动维护已实现"
```

---

## Self-Review（计划自检）

- **Spec coverage**：§4(T3)/§5(T6)/§6~§10(T4)/§13(T5) 全覆盖；基础设施 `exact`(T1)、`resolveMatchOnly`(T2)；登记 type=3 + preserveDescriptive=true（各新建型任务内）；更新型只匹配不生成（T6）；幂等/回归(T7)。✅
- **Placeholder scan**：无 TBD/TODO；每个改动步骤给出完整替换代码与精确命令。✅
- **Type consistency**：`resolve`/`resolveMatchOnly` 签名与 `BatchState` 一致；`upsertByMaterialNo(...11 参,preserveDescriptive=true)` 与现有签名一致；`row.exact(String)` 在 T1 定义、T3~T6 使用一致。✅
- **已知坑覆盖**：键列统一 `row.exact`（避开 `getStr` contains 命中「…名称」列）；更新型不生成（避免 UPDATE 0 行）；handler 顺序 + advisory lock 防跨 handler 重号——均在背景与任务中说明。✅

## 自检与收尾（执行者完成全部 Task 后）

1. 后端编译/重启自检：`touch` 任一 java 文件触发 worktree 外的共享 Quarkus dev 重启不是本验证路径；本特性纯 @QuarkusTest 覆盖，以 Task 7 Step 1 全绿为准（连共享 dev DB）。
2. 报告"已自检"行：例如 `./mvnw test -Dtest=... → N passed, 0 failed ✅`。
3. 走 `superpowers:finishing-a-development-branch` 合并 master + 删 worktree（用户确认达标后，按 CLAUDE.md 自动收尾流程）。
