# 报价导入料号自动维护与生成 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 报价 Excel 导入时，组成件/投入料号为空但名称有值则按名称匹配料号表、匹配不到则按 9 字头递增规则自动生成料号；§12 组成件BOM 新增料号表同步(type=3)与工序回填。

**Architecture:** 新建 `MaterialNoResolver`（只解析号码：缓存/按名查/`MAX(9字头)+1` 生成，advisory lock + 事务级 `batchMaxGenerated` 保证递增正确）。`MaterialBomMergeHandler` §3/§12 两循环先 resolve 再用解析号做 BOM 子行 key + 料号表 upsert。`upsertByMaterialNo` 用重载新增 `preserveDescriptive`，报价侧传 true（保留旧名称/类型），核价 P05/单重沿用旧 10 参重载（行为不变，零回归）。

**Tech Stack:** Java 17 + Quarkus 3.23.3 + Hibernate Panache + PostgreSQL 16；测试 `@QuarkusTest` + JUnit5 + EntityManager native query。

**权威依据：** 设计 spec `docs/superpowers/specs/2026-06-15-quote-import-materialno-autogen-design.md`（含 11 项需求决策 + 评审采纳项 A1~A4/B1/B2/C1~C6）。

**关键事实（已核对代码）：**
- 实体字段：`MaterialMaster.materialNo / materialName / materialType`；`ProcessMaster.processNo / processName`。两仓储均 `PanacheRepositoryBase`。
- `SheetRow.getStr(...)` 已 `trim` 且空白→`null`（C3 在取值层已满足；resolver 仍做防御性 `trimToNull`）。
- `material_master.material_no VARCHAR(20)` UNIQUE，无 `customer_no`。
- 现 `MaterialBomMergeHandler.merge()` 是 `@Transactional(REQUIRES_NEW)`；§3 material 循环逐行 upsert 料号表（行 58），§12 assembly 循环只收集 Map、**不写料号表/不读工序名称**。
- `upsertByMaterialNo` 现 4 个调用点：`MaterialBomMergeHandler`(§3)、`Q18UnitWeightHandler`、`P05CustomerMapHandler`(核价,传品名)、`P24UnitWeightHandler`(核价)。

**测试运行约定（worktree 内）：** 复用主工作区共享后端，不另起 dev server。跑单测用 Maven（在主仓根目录或 worktree 均可，Java 编译独立）：
```bash
cd /home/joii/project/cpq && ./mvnw -q -f cpq-backend/pom.xml test -Dtest=<TestClass>
```
（若 `./mvnw` 不存在用 `mvn`。）

---

## Task 1: 新建异常类 `MaterialNoUnresolvableException`

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/basicdata/v6/service/MaterialNoUnresolvableException.java`

- [ ] **Step 1: 创建异常类**

```java
package com.cpq.basicdata.v6.service;

/** 料号与名称均为空、无法解析/生成料号时抛出。调用方应 recordError 跳过该行。 */
public class MaterialNoUnresolvableException extends RuntimeException {
    public MaterialNoUnresolvableException(String message) {
        super(message);
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `cd /home/joii/project/cpq && ./mvnw -q -f cpq-backend/pom.xml compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/service/MaterialNoUnresolvableException.java
git commit -m "feat(v6import): add MaterialNoUnresolvableException"
```

---

## Task 2: `MaterialMasterRepository` 新增查询/生成辅助 + `upsertByMaterialNo` 参数化重载

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/repository/MaterialMasterRepository.java`
- Test: `cpq-backend/src/test/java/com/cpq/basicdata/v6/repository/MaterialMasterRepositoryTest.java` (Create)

- [ ] **Step 1: 写失败测试**

Create `cpq-backend/src/test/java/com/cpq/basicdata/v6/repository/MaterialMasterRepositoryTest.java`:

```java
package com.cpq.basicdata.v6.repository;

import com.cpq.basicdata.v6.entity.MaterialMaster;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class MaterialMasterRepositoryTest {

    @Inject MaterialMasterRepository repo;
    @Inject EntityManager em;

    static final String N1 = "TESTMMR-NAME-1";
    static final String NO_A = "TESTMMRA01";
    static final String NO_B = "TESTMMRB02";

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM material_master WHERE material_no LIKE 'TESTMMR%' OR material_name LIKE 'TESTMMR%'")
          .executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    @Test
    @Transactional
    void findFirstByMaterialName_returnsLowestMaterialNo() {
        repo.upsertByMaterialNo(NO_B, N1, null, null, null, "2", null, null, null, null);
        repo.upsertByMaterialNo(NO_A, N1, null, null, null, "2", null, null, null, null);
        Optional<MaterialMaster> got = repo.findFirstByMaterialName(N1);
        assertTrue(got.isPresent());
        assertEquals(NO_A, got.get().materialNo, "同名多条取 material_no 升序第一条");
    }

    @Test
    @Transactional
    void upsert_preserveDescriptiveTrue_keepsOldNameAndType() {
        repo.upsertByMaterialNo("TESTMMR900", "OLD-NAME", null, null, null, "1", null, null, null, null);
        // preserveDescriptive=true → 旧名称/类型保留
        repo.upsertByMaterialNo("TESTMMR900", "NEW-NAME", null, null, null, "3", null, null, null, null, true);
        MaterialMaster m = repo.findByMaterialNo("TESTMMR900").orElseThrow();
        assertEquals("OLD-NAME", m.materialName);
        assertEquals("1", m.materialType);
    }

    @Test
    @Transactional
    void upsert_default10arg_overwritesNameAndType() {
        repo.upsertByMaterialNo("TESTMMR901", "OLD-NAME", null, null, null, "1", null, null, null, null);
        // 10 参重载 == preserveDescriptive=false → 非空覆盖（现状语义，核价 P05 依赖）
        repo.upsertByMaterialNo("TESTMMR901", "NEW-NAME", null, null, null, "3", null, null, null, null);
        MaterialMaster m = repo.findByMaterialNo("TESTMMR901").orElseThrow();
        assertEquals("NEW-NAME", m.materialName);
        assertEquals("3", m.materialType);
    }

    @Test
    @Transactional
    void maxNineLeading_emptyReturnsBase_andRespectsExisting() {
        // 无 9 字头时回退 8999999999
        assertEquals(8_999_999_999L, repo.maxNineLeadingMaterialNo());
        repo.upsertByMaterialNo("9000000005", "TESTMMR-X", null, null, null, "3", null, null, null, null);
        assertEquals(9_000_000_005L, repo.maxNineLeadingMaterialNo());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /home/joii/project/cpq && ./mvnw -q -f cpq-backend/pom.xml test -Dtest=MaterialMasterRepositoryTest`
Expected: 编译失败（`findFirstByMaterialName`/`maxNineLeadingMaterialNo`/11 参 `upsertByMaterialNo` 不存在）

- [ ] **Step 3: 实现仓储改动**

编辑 `MaterialMasterRepository.java`。在 import 区补 `import com.cpq.basicdata.v6.entity.MaterialMaster;`（已存在则跳过）。在 `findByMaterialNo` 后追加：

```java
    /** 同名多条取 material_no 升序第一条（决策 #4）。 */
    public java.util.Optional<MaterialMaster> findFirstByMaterialName(String name) {
        return find("materialName = ?1 ORDER BY materialNo ASC", name).firstResultOptional();
    }

    /** 当前最大「恰好 10 位、9 字头」料号的数值；无则回退 8999999999（生成基数，+1=9000000000）。 */
    public long maxNineLeadingMaterialNo() {
        Object r = em.createNativeQuery(
            "SELECT COALESCE(MAX(material_no::bigint), 8999999999) " +
            "FROM material_master WHERE material_no ~ '^9[0-9]{9}$'")
            .getSingleResult();
        return ((Number) r).longValue();
    }

    /** 料号生成专用事务级 advisory lock（B2，提交/回滚自动释放），串行化跨导入的「读 MAX→生成」窗口。 */
    public void lockForMaterialNoGeneration() {
        em.createNativeQuery("SELECT pg_advisory_xact_lock(:k)")
          .setParameter("k", MATERIAL_NO_GEN_LOCK_KEY)
          .getSingleResult();
    }
    private static final long MATERIAL_NO_GEN_LOCK_KEY = 906_000_000_001L;
```

将原 `upsertByMaterialNo`（10 参）改为**委派重载**，并新增 11 参实现。把原方法体整体替换为下面两个方法：

```java
    /** 现状语义（preserveDescriptive=false：名称/类型非空覆盖）。核价 P05 / 单重沿用此重载，行为不变。 */
    public int upsertByMaterialNo(String materialNo, String materialName, String specification,
                                  String dimension, String oldMaterialNo, String materialType,
                                  String usageProperty, BigDecimal unitWeight, String standardUnit,
                                  UUID updatedBy) {
        return upsertByMaterialNo(materialNo, materialName, specification, dimension, oldMaterialNo,
            materialType, usageProperty, unitWeight, standardUnit, updatedBy, false);
    }

    /**
     * Upsert material_master by material_no。
     * @param preserveDescriptive true=已存在则保留旧 material_name/material_type（仅空才回填，决策 #6/#9）；
     *                            false=非空覆盖（现状语义）。其余列恒为非空覆盖。
     */
    public int upsertByMaterialNo(String materialNo, String materialName, String specification,
                                  String dimension, String oldMaterialNo, String materialType,
                                  String usageProperty, BigDecimal unitWeight, String standardUnit,
                                  UUID updatedBy, boolean preserveDescriptive) {
        String nameClause = preserveDescriptive
            ? "COALESCE(material_master.material_name, EXCLUDED.material_name)"
            : "COALESCE(EXCLUDED.material_name, material_master.material_name)";
        String typeClause = preserveDescriptive
            ? "COALESCE(material_master.material_type, EXCLUDED.material_type)"
            : "COALESCE(EXCLUDED.material_type, material_master.material_type)";
        String sql =
            "INSERT INTO material_master (material_no, material_name, specification, dimension, " +
            "  old_material_no, material_type, usage_property, unit_weight, standard_unit, " +
            "  created_at, updated_at, updated_by) " +
            "VALUES (:materialNo, :materialName, :specification, :dimension, " +
            "  :oldMaterialNo, :materialType, :usageProperty, :unitWeight, :standardUnit, " +
            "  NOW(), NOW(), :updatedBy) " +
            "ON CONFLICT (material_no) DO UPDATE SET " +
            "  material_name    = " + nameClause + ", " +
            "  material_type    = " + typeClause + ", " +
            "  specification    = COALESCE(EXCLUDED.specification,    material_master.specification), " +
            "  dimension        = COALESCE(EXCLUDED.dimension,        material_master.dimension), " +
            "  old_material_no  = COALESCE(EXCLUDED.old_material_no,  material_master.old_material_no), " +
            "  usage_property   = COALESCE(EXCLUDED.usage_property,   material_master.usage_property), " +
            "  unit_weight      = COALESCE(EXCLUDED.unit_weight,      material_master.unit_weight), " +
            "  standard_unit    = COALESCE(EXCLUDED.standard_unit,    material_master.standard_unit), " +
            "  updated_at       = NOW(), " +
            "  updated_by       = EXCLUDED.updated_by";
        return em.createNativeQuery(sql)
            .setParameter("materialNo", materialNo)
            .setParameter("materialName", materialName)
            .setParameter("specification", specification)
            .setParameter("dimension", dimension)
            .setParameter("oldMaterialNo", oldMaterialNo)
            .setParameter("materialType", materialType)
            .setParameter("usageProperty", usageProperty)
            .setParameter("unitWeight", unitWeight)
            .setParameter("standardUnit", standardUnit)
            .setParameter("updatedBy", updatedBy)
            .executeUpdate();
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /home/joii/project/cpq && ./mvnw -q -f cpq-backend/pom.xml test -Dtest=MaterialMasterRepositoryTest`
Expected: Tests run: 4, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/repository/MaterialMasterRepository.java \
        cpq-backend/src/test/java/com/cpq/basicdata/v6/repository/MaterialMasterRepositoryTest.java
git commit -m "feat(v6import): material_master findByName/maxNineLeading/advisory-lock + preserveDescriptive upsert"
```

---

## Task 3: 新建 `MaterialNoResolver`（解析器 + BatchState + 生成规则）

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/basicdata/v6/service/MaterialNoResolver.java`
- Test: `cpq-backend/src/test/java/com/cpq/basicdata/v6/service/MaterialNoResolverTest.java` (Create)

- [ ] **Step 1: 写失败测试**

Create `cpq-backend/src/test/java/com/cpq/basicdata/v6/service/MaterialNoResolverTest.java`:

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
class MaterialNoResolverTest {

    @Inject MaterialNoResolver resolver;
    @Inject MaterialMasterRepository repo;
    @Inject EntityManager em;

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM material_master WHERE material_no LIKE '9%' OR material_name LIKE 'TESTRES%'")
          .executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    @Test
    void materialNoPresent_returnedAsIs() {
        var st = new MaterialNoResolver.BatchState();
        assertEquals("ABC123", resolver.resolve("ABC123", null, st));
        assertEquals("ABC123", resolver.resolve("  ABC123  ", "ignored", st), "trim 后返回");
    }

    @Test
    @Transactional
    void nameMatchesExisting_returnsExistingNo() {
        repo.upsertByMaterialNo("9000000007", "TESTRES-MATCH", null, null, null, "3", null, null, null, null);
        var st = new MaterialNoResolver.BatchState();
        assertEquals("9000000007", resolver.resolve(null, "TESTRES-MATCH", st));
    }

    @Test
    @Transactional
    void nameNotMatched_emptyTable_generatesFirst() {
        var st = new MaterialNoResolver.BatchState();
        assertEquals("9000000000", resolver.resolve(null, "TESTRES-NEW", st), "空表首个生成 9000000000");
    }

    @Test
    @Transactional
    void increment_respectsExistingNineLeading() {
        repo.upsertByMaterialNo("9000000005", "TESTRES-SEED", null, null, null, "3", null, null, null, null);
        var st = new MaterialNoResolver.BatchState();
        assertEquals("9000000006", resolver.resolve(null, "TESTRES-INC", st));
    }

    @Test
    @Transactional
    void sameBatchSameName_reusesOneNumber() {
        var st = new MaterialNoResolver.BatchState();
        String a = resolver.resolve(null, "TESTRES-DUP", st);
        String b = resolver.resolve(null, "TESTRES-DUP", st);
        assertEquals(a, b, "同批同名只生成一个（决策 #3）");
    }

    @Test
    @Transactional
    void sameBatchDifferentNames_incrementWithoutWritebackVisibility() {
        // A2 核心：未先 upsert 也不重号——靠 batchMaxGenerated
        var st = new MaterialNoResolver.BatchState();
        assertEquals("9000000000", resolver.resolve(null, "TESTRES-A", st));
        assertEquals("9000000001", resolver.resolve(null, "TESTRES-B", st));
        assertEquals("9000000002", resolver.resolve(null, "TESTRES-C", st));
    }

    @Test
    void bothBlank_throws() {
        var st = new MaterialNoResolver.BatchState();
        assertThrows(MaterialNoUnresolvableException.class, () -> resolver.resolve("  ", "  ", st));
        assertThrows(MaterialNoUnresolvableException.class, () -> resolver.resolve(null, null, st));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /home/joii/project/cpq && ./mvnw -q -f cpq-backend/pom.xml test -Dtest=MaterialNoResolverTest`
Expected: 编译失败（`MaterialNoResolver` / `BatchState` 不存在）

- [ ] **Step 3: 实现解析器**

Create `cpq-backend/src/main/java/com/cpq/basicdata/v6/service/MaterialNoResolver.java`:

```java
package com.cpq.basicdata.v6.service;

import com.cpq.basicdata.v6.entity.MaterialMaster;
import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 料号解析器（规则单一归处，决策 #1~#4/#10/#11）。
 *
 * <p>只负责「解析出料号号码」：料号有值直接返回；料号空+名称有值则按名称匹配料号表
 * （同名取 material_no 升序第一条），匹配不到则按 MAX(9字头)+1 生成；都空抛
 * {@link MaterialNoUnresolvableException}。<b>不写 material_master</b>（由调用方 upsert）。
 *
 * <p>生成正确性：advisory lock 串行化跨导入（B2）+ 事务级 {@link BatchState#batchMaxGenerated}
 * 消除对「上一行 upsert 是否已可见」的依赖（A2）。
 */
@ApplicationScoped
public class MaterialNoResolver {

    static final long GEN_BASE = 8_999_999_999L; // +1 = 9000000000

    @Inject MaterialMasterRepository repo;

    /** 单次导入的事务级状态：调用方在 merge() 入口 new 一个，贯穿 §3/§12 两循环。 */
    public static final class BatchState {
        final Map<String, String> nameToNo = new HashMap<>();
        long batchMaxGenerated = 0L;
    }

    /**
     * 解析最终落库料号。
     * @throws MaterialNoUnresolvableException 当料号与名称都为空（isBlank）
     */
    public String resolve(String materialNo, String materialName, BatchState state) {
        String no = trimToNull(materialNo);
        if (no != null) return no;

        String name = trimToNull(materialName);
        if (name == null) {
            throw new MaterialNoUnresolvableException("料号与名称均为空，无法解析/生成料号");
        }

        String cached = state.nameToNo.get(name);
        if (cached != null) return cached;

        Optional<MaterialMaster> existing = repo.findFirstByMaterialName(name);
        if (existing.isPresent()) {
            String existingNo = existing.get().materialNo;
            state.nameToNo.put(name, existingNo);
            return existingNo;
        }

        String generated = generateNextMaterialNo(state);
        state.nameToNo.put(name, generated);
        return generated;
    }

    private String generateNextMaterialNo(BatchState state) {
        repo.lockForMaterialNoGeneration();                       // B2
        long dbMax = repo.maxNineLeadingMaterialNo();             // 无则 GEN_BASE
        long base = Math.max(dbMax, Math.max(state.batchMaxGenerated, GEN_BASE));
        long next = base + 1;                                     // A2：不依赖写可见性
        state.batchMaxGenerated = next;
        return String.valueOf(next);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /home/joii/project/cpq && ./mvnw -q -f cpq-backend/pom.xml test -Dtest=MaterialNoResolverTest`
Expected: Tests run: 7, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/service/MaterialNoResolver.java \
        cpq-backend/src/test/java/com/cpq/basicdata/v6/service/MaterialNoResolverTest.java
git commit -m "feat(v6import): MaterialNoResolver (match-by-name / 9-prefix gen / batch increment)"
```

---

## Task 4: `ProcessMasterRepository.findFirstByProcessName`

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/repository/ProcessMasterRepository.java`
- Test: `cpq-backend/src/test/java/com/cpq/basicdata/v6/repository/ProcessMasterRepositoryTest.java` (Create)

- [ ] **Step 1: 写失败测试**

Create `cpq-backend/src/test/java/com/cpq/basicdata/v6/repository/ProcessMasterRepositoryTest.java`:

```java
package com.cpq.basicdata.v6.repository;

import com.cpq.basicdata.v6.entity.ProcessMaster;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ProcessMasterRepositoryTest {

    @Inject ProcessMasterRepository repo;
    @Inject EntityManager em;

    @Transactional
    void seed() {
        em.createNativeQuery("DELETE FROM process_master WHERE process_no LIKE 'TESTPMR%'").executeUpdate();
        em.createNativeQuery("INSERT INTO process_master (id, process_no, process_name, created_at, updated_at) " +
            "VALUES (gen_random_uuid(), 'TESTPMR-01', '电镀', NOW(), NOW())").executeUpdate();
    }
    @Transactional
    void clean() { em.createNativeQuery("DELETE FROM process_master WHERE process_no LIKE 'TESTPMR%'").executeUpdate(); }
    @BeforeEach void before() { seed(); }
    @AfterEach  void after()  { clean(); }

    @Test
    void findFirstByProcessName_hit() {
        Optional<ProcessMaster> got = repo.findFirstByProcessName("电镀");
        assertTrue(got.isPresent());
        assertEquals("TESTPMR-01", got.get().processNo);
    }

    @Test
    void findFirstByProcessName_miss() {
        assertTrue(repo.findFirstByProcessName("不存在的工序XYZ").isEmpty());
    }
}
```

> 注：若 `process_master` 表有额外 NOT NULL 列导致上面 INSERT 失败，按报错补齐最小必填列即可（保持 process_no/process_name 不变）。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /home/joii/project/cpq && ./mvnw -q -f cpq-backend/pom.xml test -Dtest=ProcessMasterRepositoryTest`
Expected: 编译失败（`findFirstByProcessName` 不存在）

- [ ] **Step 3: 实现仓储方法**

在 `ProcessMasterRepository.java` 的 `search(...)` 方法后追加（并确保 import `java.util.Optional`）：

```java
    /** 按工序名称精确取第一条（process_no 升序）。供导入工序回填用（决策 #5）。 */
    public java.util.Optional<ProcessMaster> findFirstByProcessName(String name) {
        return find("processName = ?1 ORDER BY processNo ASC", name).firstResultOptional();
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /home/joii/project/cpq && ./mvnw -q -f cpq-backend/pom.xml test -Dtest=ProcessMasterRepositoryTest`
Expected: Tests run: 2, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/repository/ProcessMasterRepository.java \
        cpq-backend/src/test/java/com/cpq/basicdata/v6/repository/ProcessMasterRepositoryTest.java
git commit -m "feat(v6import): ProcessMasterRepository.findFirstByProcessName"
```

---

## Task 5: `MaterialBomMergeHandler` §3 走解析器（resolve→key→upsert preserveDescriptive）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandler.java`
- Test: `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/MaterialBomMaterialNoResolveTest.java` (Create)

- [ ] **Step 1: 写失败测试**

Create `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/MaterialBomMaterialNoResolveTest.java`:

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

/** §3 物料BOM：投入料号为空+名称匹配/生成（决策 #1~#4/#11）。 */
@QuarkusTest
class MaterialBomMaterialNoResolveTest {

    @Inject MaterialBomMergeHandler handler;
    @Inject EntityManager em;

    static final String CUST = "TSTRSV0615";
    static final String MAT  = "TESTRSV0615";

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM material_bom_item WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_bom WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_no LIKE '9%' OR material_name LIKE 'RSV-%'").executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = CUST; c.systemType = "QUOTE"; c.importedBy = null; return c;
    }
    /** 投入料号可空，靠名称解析。 */
    private SheetRow matRow(int seq, String comp, String name) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", MAT); m.put("项次", String.valueOf(seq));
        if (comp != null) m.put("投入料号", comp);
        if (name != null) m.put("投入料号名称", name);
        m.put("产出料号类型", "2.非银点类"); m.put("材料毛重", "1.0"); m.put("重量单位", "KG");
        return new SheetRow(seq, m);
    }
    private long masterCount(String name) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM material_master WHERE material_name=:n")
            .setParameter("n", name).getSingleResult()).longValue();
    }
    private String childComponentNos() {
        return em.createNativeQuery(
            "SELECT string_agg(component_no, ',' ORDER BY component_no) FROM material_bom_item " +
            "WHERE material_no=:m AND is_current=TRUE").setParameter("m", MAT).getSingleResult().toString();
    }

    @Test
    void emptyNoWithName_generatesAndBomChildUsesGeneratedNo() {
        SheetImportResult r = handler.merge(List.of(matRow(1, null, "RSV-GEN-1")), List.of(), ctx());
        assertEquals(0, r.failedRows);
        assertEquals(1L, masterCount("RSV-GEN-1"), "名称未命中→生成新料号写料号表");
        assertEquals("9000000000", childComponentNos(), "BOM 子行 component_no = 生成料号");
    }

    @Test
    void emptyNoWithName_matchesExistingMaster() {
        // 预置已有同名料号
        handler.merge(List.of(matRow(1, "EXIST-001", "RSV-MATCH")), List.of(), ctx());
        // 再次：料号空、同名 → 命中已有 EXIST-001，不生成 9 字头
        handler.merge(List.of(matRow(1, null, "RSV-MATCH")), List.of(), ctx());
        assertEquals("EXIST-001", childComponentNos());
        assertEquals(0L, ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_master WHERE material_no LIKE '9%'").getSingleResult()).longValue());
    }

    @Test
    void emptyNoAndEmptyName_recordsError() {
        SheetImportResult r = handler.merge(List.of(matRow(1, null, null)), List.of(), ctx());
        assertTrue(r.failedRows >= 1, "料号与名称都空→记错误跳过");
        assertEquals(0L, ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_bom_item WHERE material_no=:m").setParameter("m", MAT).getSingleResult()).longValue());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /home/joii/project/cpq && ./mvnw -q -f cpq-backend/pom.xml test -Dtest=MaterialBomMaterialNoResolveTest`
Expected: FAIL — `emptyNoWithName_*` 失败（现状：投入料号空直接 recordError，不解析）。

- [ ] **Step 3: 改 §3 material 循环**

编辑 `MaterialBomMergeHandler.java`。

3a. 注入解析器 + 在类字段区（`@Inject MaterialMasterRepository materialMasterRepo;` 旁）加：
```java
    @Inject com.cpq.basicdata.v6.service.MaterialNoResolver materialNoResolver;
```

3b. 在 `merge(...)` 方法体开头（`SheetImportResult result = ...;` 之后、`Map<String, ...> matByMat = ...` 之前）创建批状态：
```java
        com.cpq.basicdata.v6.service.MaterialNoResolver.BatchState batch =
            new com.cpq.basicdata.v6.service.MaterialNoResolver.BatchState();
```

3c. 替换 §3 material 循环里「读 componentNo + 为空 recordError + upsert」那段（现约行 55-60）：

原代码：
```java
            String componentUsageType = row.getStr("产出料号类型");
            String componentNo = row.getStr("投入料号");
            if (componentNo == null) { result.recordError(row.rowNo, "投入料号", "为空"); continue; }
            materialMasterRepo.upsertByMaterialNo(componentNo, row.getStr("投入料号名称"),
                null, null, null, digitsOnly(componentUsageType), null, null, null, ctx.importedBy);
            result.recordWrite("material_master", 1);
```

改为：
```java
            String componentUsageType = row.getStr("产出料号类型");
            String componentName = row.getStr("投入料号名称");
            String componentNo;
            try {
                componentNo = materialNoResolver.resolve(row.getStr("投入料号"), componentName, batch);
            } catch (com.cpq.basicdata.v6.service.MaterialNoUnresolvableException ex) {
                result.recordError(row.rowNo, "投入料号", "料号与名称均为空"); continue;
            }
            // 决策 #9：报价 §3 已存在则保留旧名称/类型 → preserveDescriptive=true
            materialMasterRepo.upsertByMaterialNo(componentNo, componentName,
                null, null, null, digitsOnly(componentUsageType), null, null, null, ctx.importedBy, true);
            result.recordWrite("material_master", 1);
```

> 关键：`componentNo` 现在是 resolve 后的值，后续 `matByMat...put(String.valueOf(componentNo), c)` 自然用解析号做 key（C2，无需额外改）。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /home/joii/project/cpq && ./mvnw -q -f cpq-backend/pom.xml test -Dtest=MaterialBomMaterialNoResolveTest`
Expected: Tests run: 3, Failures: 0

- [ ] **Step 5: 回归既有 handler 测试**

Run: `cd /home/joii/project/cpq && ./mvnw -q -f cpq-backend/pom.xml test -Dtest=MaterialBomMergeHandlerTest`
Expected: 全部通过（§3 既有行为不破坏）。

- [ ] **Step 6: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandler.java \
        cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/MaterialBomMaterialNoResolveTest.java
git commit -m "feat(v6import): §3 material BOM resolve material_no by name + preserve descriptive upsert"
```

---

## Task 6: `MaterialBomMergeHandler` §12 料号表同步(type=3) + 工序回填 + 交叉料件

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandler.java`
- Test: `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/AssemblyBomMaterialSyncTest.java` (Create)

- [ ] **Step 1: 写失败测试**

Create `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/AssemblyBomMaterialSyncTest.java`:

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

/** §12 组成件BOM：料号表同步(type=3) + 工序回填 + §3/§12 交叉料件（决策 #5/#6, B1）。 */
@QuarkusTest
class AssemblyBomMaterialSyncTest {

    @Inject MaterialBomMergeHandler handler;
    @Inject EntityManager em;

    static final String CUST = "TSTASM0615";
    static final String MAT  = "TESTASM0615";
    static final String PROC_NO = "TESTASM-PROC-01";

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM material_bom_item WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_bom WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_no LIKE '9%' OR material_name LIKE 'ASM-%' OR material_no IN ('ASM-EXIST')").executeUpdate();
        em.createNativeQuery("DELETE FROM process_master WHERE process_no=:p").setParameter("p", PROC_NO).executeUpdate();
    }
    @Transactional
    void seedProcess() {
        em.createNativeQuery("INSERT INTO process_master (id, process_no, process_name, created_at, updated_at) " +
            "VALUES (gen_random_uuid(), :p, '组装A', NOW(), NOW())").setParameter("p", PROC_NO).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); seedProcess(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = CUST; c.systemType = "QUOTE"; c.importedBy = null; return c;
    }
    private SheetRow matRow(int seq, String comp, String name, String usageType) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", MAT); m.put("项次", String.valueOf(seq));
        m.put("投入料号", comp); m.put("投入料号名称", name);
        m.put("产出料号类型", usageType); m.put("材料毛重", "1.0"); m.put("重量单位", "KG");
        return new SheetRow(seq, m);
    }
    /** 组成件行：料号可空(靠名称)，工序编号可空(靠"组装工序"=工序名称回填)。 */
    private SheetRow asmRow(int seq, String comp, String name, String opNo, String procName) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", MAT); m.put("项次（一级）", String.valueOf(seq));
        if (comp != null) m.put("组成件料号", comp);
        if (name != null) m.put("组成件名称", name);
        if (opNo != null) m.put("工序编号", opNo);
        if (procName != null) m.put("组装工序", procName);
        m.put("组成数量", "1"); m.put("组成单位", "PCS");
        return new SheetRow(seq, m);
    }
    private String typeOf(String no) {
        var r = em.createNativeQuery("SELECT material_type FROM material_master WHERE material_no=:n")
            .setParameter("n", no).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
    private String opNoOf(String compNo) {
        var r = em.createNativeQuery(
            "SELECT operation_no FROM material_bom_item WHERE material_no=:m AND component_no=:c AND is_current=TRUE")
            .setParameter("m", MAT).setParameter("c", compNo).getResultList();
        return r.isEmpty() ? null : (r.get(0) == null ? null : String.valueOf(r.get(0)));
    }

    @Test
    void newComponent_materialTypeIs3() {
        handler.merge(List.of(), List.of(asmRow(1, "ASM-NEW", "ASM-N1", "OP1", null)), ctx());
        assertEquals("3", typeOf("ASM-NEW"), "§12 新料件 material_type 固定 3（决策 #6）");
    }

    @Test
    void existingComponent_keepsOriginalType() {
        // 预置已有料号 type=1
        em_upsertMaster("ASM-EXIST", "ASM-E1", "1");
        handler.merge(List.of(), List.of(asmRow(1, "ASM-EXIST", "ASM-E1", "OP1", null)), ctx());
        assertEquals("1", typeOf("ASM-EXIST"), "已存在保留原 type，不被改成 3（决策 #6）");
    }

    @Test
    void crossing_materialKeepsSection3NumericType() {
        // 同料件先 §3(type=2) 后 §12(应写3) → 保留 §3 的 2（B1）
        handler.merge(
            List.of(matRow(1, "ASM-CROSS", "ASM-C1", "2.非银点类")),
            List.of(asmRow(1, "ASM-CROSS", "ASM-C1", "OP1", null)),
            ctx());
        assertEquals("2", typeOf("ASM-CROSS"), "交叉料件保留 §3 数字类型（B1）");
    }

    @Test
    void processBackfill_hit() {
        handler.merge(List.of(), List.of(asmRow(1, "ASM-PB", "ASM-PB1", null, "组装A")), ctx());
        assertEquals(PROC_NO, opNoOf("ASM-PB"), "工序编号空+组装工序匹配→回填 process_no");
    }

    @Test
    void processBackfill_miss_leavesEmpty() {
        handler.merge(List.of(), List.of(asmRow(1, "ASM-PM", "ASM-PM1", null, "不存在工序")), ctx());
        assertNull(opNoOf("ASM-PM"), "查不到→operation_no 留空，行照常导入（决策 #5）");
    }

    @Test
    void emptyComponentNo_withName_generates() {
        handler.merge(List.of(), List.of(asmRow(1, null, "ASM-GEN", "OP1", null)), ctx());
        assertEquals("3", typeOf("9000000000"), "料号空+名称→生成 9 字头，type=3");
    }

    @Transactional
    void em_upsertMaster(String no, String name, String type) {
        em.createNativeQuery("INSERT INTO material_master (id, material_no, material_name, material_type, created_at, updated_at) " +
            "VALUES (gen_random_uuid(), :no, :nm, :tp, NOW(), NOW()) ON CONFLICT (material_no) DO UPDATE SET material_type=:tp")
            .setParameter("no", no).setParameter("nm", name).setParameter("tp", type).executeUpdate();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /home/joii/project/cpq && ./mvnw -q -f cpq-backend/pom.xml test -Dtest=AssemblyBomMaterialSyncTest`
Expected: FAIL（现状 §12 不写料号表、不读工序名称）。

- [ ] **Step 3: 改 §12 assembly 循环**

编辑 `MaterialBomMergeHandler.java` 的 assembly 循环（现约行 75-92）。

原代码：
```java
        for (SheetRow row : assemblyRows) {
            result.totalRows++;
            String materialNo = row.getStr("宏丰料号");
            if (materialNo == null) { result.recordError(row.rowNo, "宏丰料号", "为空"); continue; }
            if (isCfg(materialNo)) { result.recordError(row.rowNo, "宏丰料号", "禁止导入系统生成料号(CFG- 前缀): " + materialNo); continue; }
            String componentNo = row.getStr("组成件料号");
            if (componentNo == null) { result.recordError(row.rowNo, "组成件料号", "为空"); continue; }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("seq_no", row.getInt("项次（一级）", "项次"));
            c.put("operation_no", row.getStr("工序编号"));
            c.put("item_seq", row.getIntNth("项次", 2));
            c.put("component_no", componentNo);
            c.put("composition_qty", row.getDecimal("组成数量"));
            c.put("issue_unit", row.getStr("组成单位"));
            asmByMat.computeIfAbsent(materialNo, k -> new LinkedHashMap<>())
                    .put(String.valueOf(componentNo), c);
            result.successRows++;
        }
```

改为：
```java
        for (SheetRow row : assemblyRows) {
            result.totalRows++;
            String materialNo = row.getStr("宏丰料号");
            if (materialNo == null) { result.recordError(row.rowNo, "宏丰料号", "为空"); continue; }
            if (isCfg(materialNo)) { result.recordError(row.rowNo, "宏丰料号", "禁止导入系统生成料号(CFG- 前缀): " + materialNo); continue; }
            String componentName = row.getStr("组成件名称");
            String componentNo;
            try {
                componentNo = materialNoResolver.resolve(row.getStr("组成件料号"), componentName, batch);
            } catch (com.cpq.basicdata.v6.service.MaterialNoUnresolvableException ex) {
                result.recordError(row.rowNo, "组成件料号", "料号与名称均为空"); continue;
            }
            // §12 料号表同步：material_type 固定 3，已存在保留原值（决策 #6 → preserveDescriptive=true）
            materialMasterRepo.upsertByMaterialNo(componentNo, componentName,
                null, null, null, "3", null, null, null, ctx.importedBy, true);
            result.recordWrite("material_master", 1);

            // 工序回填（决策 #5）：工序编号空 + 组装工序(工序名称)有值 → 按名取第一条 process_no
            String operationNo = row.getStr("工序编号");
            if (operationNo == null) {
                String procName = row.getStr("组装工序");
                if (procName != null) {
                    operationNo = processMasterRepo.findFirstByProcessName(procName)
                        .map(p -> p.processNo).orElse(null);
                }
            }

            Map<String, Object> c = new LinkedHashMap<>();
            c.put("seq_no", row.getInt("项次（一级）", "项次"));
            c.put("operation_no", operationNo);
            c.put("item_seq", row.getIntNth("项次", 2));
            c.put("component_no", componentNo);
            c.put("composition_qty", row.getDecimal("组成数量"));
            c.put("issue_unit", row.getStr("组成单位"));
            asmByMat.computeIfAbsent(materialNo, k -> new LinkedHashMap<>())
                    .put(String.valueOf(componentNo), c);   // 解析号做 key（C2）
            result.successRows++;
        }
```

3b. 在类字段区注入 `ProcessMasterRepository`：
```java
    @Inject com.cpq.basicdata.v6.repository.ProcessMasterRepository processMasterRepo;
```

> 注：`materialNoResolver` 与 `batch` 已在 Task 5 引入。交叉料件（B1）无需特殊代码——material 循环先跑写入数字 type，assembly 循环用 `preserveDescriptive=true` 自然保留。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /home/joii/project/cpq && ./mvnw -q -f cpq-backend/pom.xml test -Dtest=AssemblyBomMaterialSyncTest`
Expected: Tests run: 6, Failures: 0

- [ ] **Step 5: 回归 §3 + 既有 handler 测试**

Run: `cd /home/joii/project/cpq && ./mvnw -q -f cpq-backend/pom.xml test -Dtest=MaterialBomMergeHandlerTest,MaterialBomMaterialNoResolveTest`
Expected: 全部通过。

- [ ] **Step 6: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandler.java \
        cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/AssemblyBomMaterialSyncTest.java
git commit -m "feat(v6import): §12 assembly BOM material_master sync(type=3) + process backfill + crossing"
```

---

## Task 7: 幂等/核价零回归测试 + 文档回写

**Files:**
- Test: `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/MaterialNoImportIdempotencyTest.java` (Create)
- Modify: `docs/RECORD.md`
- Modify: `docs/table/报价系统Excel导入落库方案.md`

- [ ] **Step 1: 写幂等测试**

Create `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/MaterialNoImportIdempotencyTest.java`:

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

/** C5：同一 Excel 连导两次，第二次按名称命中第一次生成的号，不再新增（决策 #2/#3）。 */
@QuarkusTest
class MaterialNoImportIdempotencyTest {

    @Inject MaterialBomMergeHandler handler;
    @Inject EntityManager em;

    static final String CUST = "TSTIDEM0615";
    static final String MAT  = "TESTIDEM0615";

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM material_bom_item WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_bom WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_no LIKE '9%' OR material_name LIKE 'IDEM-%'").executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = CUST; c.systemType = "QUOTE"; c.importedBy = null; return c;
    }
    private SheetRow matRow(int seq, String name) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", MAT); m.put("项次", String.valueOf(seq));
        m.put("投入料号名称", name); m.put("产出料号类型", "2.非银点类");
        m.put("材料毛重", "1.0"); m.put("重量单位", "KG");
        return new SheetRow(seq, m);
    }
    private long nineLeadingCount() {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM material_master WHERE material_no LIKE '9%'")
            .getSingleResult()).longValue();
    }

    @Test
    void reimportSameExcel_noNewMaterialNos() {
        List<SheetRow> rows = List.of(matRow(1, "IDEM-A"), matRow(2, "IDEM-B"));
        handler.merge(rows, List.of(), ctx());
        long after1 = nineLeadingCount();
        assertEquals(2L, after1, "首次生成 2 个 9 字头号");
        handler.merge(List.of(matRow(1, "IDEM-A"), matRow(2, "IDEM-B")), List.of(), ctx());
        assertEquals(2L, nineLeadingCount(), "第二次按名称命中，不新增（C5）");
    }
}
```

- [ ] **Step 2: 跑幂等测试**

Run: `cd /home/joii/project/cpq && ./mvnw -q -f cpq-backend/pom.xml test -Dtest=MaterialNoImportIdempotencyTest`
Expected: Tests run: 1, Failures: 0

- [ ] **Step 3: 全量回归本特性相关测试 + 核价 P05/单重回归**

Run:
```bash
cd /home/joii/project/cpq && ./mvnw -q -f cpq-backend/pom.xml test \
  -Dtest='MaterialMasterRepositoryTest,MaterialNoResolverTest,ProcessMasterRepositoryTest,MaterialBomMergeHandlerTest,MaterialBomMaterialNoResolveTest,AssemblyBomMaterialSyncTest,MaterialNoImportIdempotencyTest,P05CustomerMapHandlerTest,Q18UnitWeightHandlerTest'
```
Expected: 全绿。
> 若 `P05CustomerMapHandlerTest` / `Q18UnitWeightHandlerTest` 不存在则忽略对应项；核价 P05 因沿用 10 参重载（=false）行为不变，无需新增回归用例（重载机制保证零回归）。

- [ ] **Step 4: 回写 `docs/RECORD.md`**

在 `docs/RECORD.md` 末尾追加一行（格式同文件既有约定）：
```
[2026-06-15] 报价导入-料号自动维护 - 组成件/投入料号空+名称有值则按名匹配料号表、匹配不到按9字头(MAX+1)生成; §12新增料号表同步(type=3)+工序按名回填; upsertByMaterialNo新增preserveDescriptive重载(报价true保留旧名/类型, 核价P05沿用false零回归) | MaterialNoResolver.java/MaterialMasterRepository.java/ProcessMasterRepository.java/MaterialBomMergeHandler.java | advisory lock+batchMaxGenerated保证生成递增; 交叉料件保留§3数字类型
```

- [ ] **Step 5: 标注落库方案文档已实现**

在 `docs/table/报价系统Excel导入落库方案.md` 的两处 `20260615更新:` 行后各补一行：
```
> ✅ 已实现（2026-06-15）：见 MaterialNoResolver + MaterialBomMergeHandler；规则与本说明一致。
```

- [ ] **Step 6: Commit**

```bash
git add cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/MaterialNoImportIdempotencyTest.java \
        docs/RECORD.md "docs/table/报价系统Excel导入落库方案.md"
git commit -m "test(v6import): idempotent re-import + docs RECORD/落库方案 回写"
```

---

## 自检清单（实现全部完成后）

- [ ] 后端 `touch` 一个 java 文件触发 Quarkus dev 重启 → `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health` 返回 200。
- [ ] Task 7 Step 3 全量回归命令全绿。
- [ ] `git log --oneline` 7 个 task commit 均在 `worktree-materialno-autogen` 分支。
- [ ] 形成一行「已自检」声明（TS 不涉及；Java 编译 0 错误 + 各 @QuarkusTest 通过 + /q/health 200）。

## 范围之外（勿做）
- 不动前端、不动 §2 写料号表、不新建 sequence/标识字段、不改核价 P05/单重业务语义（仅靠重载保持不变）。
