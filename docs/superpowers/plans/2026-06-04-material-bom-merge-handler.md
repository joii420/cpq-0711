# MaterialBomMergeHandler（物料BOM ⇄ 组成件BOM 去重合并）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 同一料号同时出现在「物料BOM」和「组成件BOM」时，合并为**一条当前 BOM 记录**（组成件优先 → `characteristic='ASSEMBLY'`），消除当前出现的"NULL + ASSEMBLY 双 current 行"。

**Architecture:** 新增 `MaterialBomMergeHandler`，解析两 sheet 后按 `material_no` 在**单一事务**内汇总两表子行（合并键 = `component_no`，去 characteristic/seq_no，冲突取组成件值）→ 写入前 **FLIP 反向 characteristic 旧当前行为 is_current=false（保留历史，依赖已落地的 material_bom_item 版本化）** → 每料号单次 `writeVersionedMasterDetail`。`Q03/Q12` 删除（解析逻辑并入 MergeHandler），`QuoteImportService` 改为显式喂两 sheet。

**Tech Stack:** Java 17 + Quarkus、Hibernate（EntityManager native query）、JUnit5 `@QuarkusTest`、PostgreSQL。

**Spec:** `docs/table/报价系统Excel导入落库方案.md` §一【物料BOM ⇄ 组成件BOM 同料号去重合并规则】+【去重合并实现细则】。

---

## 前置事实（已验证）

- `material_bom_item` 已版本化（V293：有 `bom_version` 列 + 多版本保留），`writeVersionedMasterDetail` 的 `childVersionColumn="bom_version"` 走多版本路径（element_bom_item 同款）。**所以第 4 步可用 FLIP 保留历史，而非 DELETE。**
- 版本作用域 per-`(料号, characteristic)`，选配 COMBO 双行契约不受影响。
- 选配料号固定 `CFG-` 前缀，导入料号非 `CFG-`，两者不相交 → FLIP 按单料号绝不误伤选配。

## 文件结构

| 文件 | 责任 | 动作 |
|------|------|------|
| `quote/MaterialBomMergeHandler.java` | 解析两 sheet + 合并 + FLIP + 单次写入 + CFG- 守卫 + material_master upsert | Create |
| `quote/MaterialBomMergeHandlerTest.java` | 合并/FLIP/CFG- 集成测试 | Create |
| `quote/QuoteImportService.java` | 去掉 q03/q12 调度，加合并步骤 | Modify |
| `quote/Q03MaterialBomHandler.java` / `Q12AssemblyBomHandler.java` | 解析逻辑已并入 MergeHandler，删除 | Delete |
| `quote/Q03MaterialBomHandlerTest.java` / `Q12AssemblyBomHandlerTest.java` | 随类删除 | Delete |

---

### Task 1: 创建 MaterialBomMergeHandler + 集成测试

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandler.java`
- Create: `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandlerTest.java`

- [ ] **Step 1: 写 MaterialBomMergeHandler**

```java
package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
import com.cpq.basicdata.v6.versioning.VersionedV6Writer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 物料BOM ⇄ 组成件BOM 同料号去重合并（V3.2）。
 *
 * <p>解析「物料BOM」(MATERIAL/characteristic=NULL) + 「组成件BOM」(ASSEMBLY) 两 sheet，
 * 按 material_no 在单一事务内汇总两表子行（合并键=component_no，去 characteristic/seq_no），
 * 组成件优先判类型/取冲突值；写入前 FLIP 反向 characteristic 旧当前行为 is_current=false（保留历史，
 * 依赖 V293 子表版本化）；每料号单次 {@link VersionedV6Writer#writeVersionedMasterDetail}。
 *
 * <p>替代原 Q03/Q12 各写各的（会产生 NULL + ASSEMBLY 双 current 行）。material_master upsert
 * 副作用（物料BOM 投入料号）保留。CFG- 前缀料号拒绝导入（封死选配料号回填）。
 */
@ApplicationScoped
public class MaterialBomMergeHandler {

    @Inject VersionedV6Writer writer;
    @Inject MaterialMasterRepository materialMasterRepo;
    @Inject EntityManager em;

    /** 合并后子表内容列 = 物料BOM ∪ 组成件BOM。 */
    private static final List<String> CHILD_CONTENT = List.of(
        "seq_no", "component_no", "component_usage_type", "composition_qty",
        "base_qty", "issue_unit", "scrap_rate", "defect_rate",
        "operation_no", "item_seq");

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult merge(List<SheetRow> materialRows, List<SheetRow> assemblyRows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult("物料BOM+组成件BOM(合并)");

        // material_no -> (component_no -> 子字段 map)
        Map<String, Map<String, Map<String, Object>>> matByMat = new LinkedHashMap<>();
        Map<String, Map<String, Map<String, Object>>> asmByMat = new LinkedHashMap<>();

        // ---- 解析「物料BOM」(+ material_master upsert 副作用) ----
        for (SheetRow row : materialRows) {
            result.totalRows++;
            String materialNo = row.getStr("宏丰料号");
            if (materialNo == null) { result.recordError(row.rowNo, "宏丰料号", "为空"); continue; }
            if (isCfg(materialNo)) { result.recordError(row.rowNo, "宏丰料号", "禁止导入系统生成料号(CFG- 前缀): " + materialNo); continue; }
            String componentUsageType = row.getStr("产出料号类型");
            String componentNo = row.getStr("投入料号");
            if (componentNo != null) {
                materialMasterRepo.upsertByMaterialNo(componentNo, row.getStr("投入料号名称"),
                    null, null, null, digitsOnly(componentUsageType), null, null, null, ctx.importedBy);
                result.recordWrite("material_master", 1);
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("seq_no", row.getInt("项次"));
            c.put("component_no", componentNo);
            c.put("component_usage_type", componentUsageType);
            c.put("composition_qty", row.getDecimal("材料毛重", "毛重"));
            c.put("base_qty", row.getDecimal("材料净重", "净重"));
            c.put("issue_unit", row.getStr("重量单位"));
            c.put("scrap_rate", row.getDecimal("损耗率"));
            c.put("defect_rate", row.getDecimal("不良率"));
            matByMat.computeIfAbsent(materialNo, k -> new LinkedHashMap<>())
                    .put(String.valueOf(componentNo), c);
            result.successRows++;
        }

        // ---- 解析「组成件BOM」----
        for (SheetRow row : assemblyRows) {
            result.totalRows++;
            String materialNo = row.getStr("宏丰料号");
            if (materialNo == null) { result.recordError(row.rowNo, "宏丰料号", "为空"); continue; }
            if (isCfg(materialNo)) { result.recordError(row.rowNo, "宏丰料号", "禁止导入系统生成料号(CFG- 前缀): " + materialNo); continue; }
            String componentNo = row.getStr("组成件料号");
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

        // ---- 按料号合并 + 写入 ----
        Set<String> allMats = new LinkedHashSet<>();
        allMats.addAll(matByMat.keySet());
        allMats.addAll(asmByMat.keySet());

        for (String materialNo : allMats) {
            try {
                Map<String, Map<String, Object>> matChild = matByMat.getOrDefault(materialNo, Map.of());
                Map<String, Map<String, Object>> asmChild = asmByMat.getOrDefault(materialNo, Map.of());
                boolean isAssembly = !asmChild.isEmpty();           // 组成件优先
                String targetChar = isAssembly ? "ASSEMBLY" : null;
                String bomType = isAssembly ? "ASSEMBLY" : "MATERIAL";

                // 合并子行：键=component_no（去 characteristic/seq_no），组成件优先（非空覆盖）
                Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
                for (Map.Entry<String, Map<String, Object>> e : matChild.entrySet()) {
                    merged.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
                }
                for (Map.Entry<String, Map<String, Object>> e : asmChild.entrySet()) {
                    Map<String, Object> tgt = merged.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>());
                    for (Map.Entry<String, Object> f : e.getValue().entrySet()) {
                        if (f.getValue() != null) tgt.put(f.getKey(), f.getValue());   // 组成件非空覆盖
                    }
                }
                List<Map<String, Object>> childRows = new ArrayList<>(merged.values());

                // 第 4 步：FLIP 反向 characteristic 旧当前行 → is_current=false（保留历史，不删）
                flipReverse(ctx.customerNo, materialNo, isAssembly ? null : "ASSEMBLY");

                // 写入目标 characteristic 版本（单次）
                Map<String, Object> masterGk = new LinkedHashMap<>();
                masterGk.put("system_type", "QUOTE");
                masterGk.put("customer_no", ctx.customerNo);
                masterGk.put("material_no", materialNo);
                masterGk.put("bom_type", bomType);
                masterGk.put("characteristic", targetChar);
                Map<String, Object> childGk = new LinkedHashMap<>();
                childGk.put("system_type", "QUOTE");
                childGk.put("customer_no", ctx.customerNo);
                childGk.put("material_no", materialNo);
                childGk.put("characteristic", targetChar);
                writer.writeVersionedMasterDetail(
                    "material_bom", "bom_version", masterGk, Map.of(),
                    "material_bom_item", "bom_version", childGk, CHILD_CONTENT, childRows);
                result.recordWrite("material_bom", 1);
                result.recordWrite("material_bom_item", childRows.size());
            } catch (Exception ex) {
                result.recordError(0, "_group_", "material_no=" + materialNo + ": " + ex.getMessage());
            }
        }
        return result;
    }

    /**
     * FLIP 反向 characteristic 的当前主+子行 → is_current=false（保留为历史，不删除）。
     * 仅按单料号、QUOTE 作用域；revChar=null 表示翻 characteristic IS NULL 的行。
     */
    private void flipReverse(String customerNo, String materialNo, String revChar) {
        String charPred = (revChar == null) ? "characteristic IS NULL" : "characteristic = :rc";
        for (String table : List.of("material_bom", "material_bom_item")) {
            var q = em.createNativeQuery(
                "UPDATE " + table + " SET is_current=false " +
                "WHERE system_type='QUOTE' AND customer_no=:cn AND material_no=:mn " +
                "  AND " + charPred + " AND is_current=true")
              .setParameter("cn", customerNo).setParameter("mn", materialNo);
            if (revChar != null) q.setParameter("rc", revChar);
            q.executeUpdate();
        }
    }

    private static boolean isCfg(String materialNo) {
        return materialNo != null && materialNo.startsWith("CFG-");
    }

    /** material_type 只写数字：从「1.银点类」提取首段数字；无数字返 null。 */
    private static String digitsOnly(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') sb.append(ch);
            else if (sb.length() > 0) break;
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
```

- [ ] **Step 2: 写集成测试**

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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class MaterialBomMergeHandlerTest {

    @Inject MaterialBomMergeHandler handler;
    @Inject EntityManager em;

    static final String CUST = "TEST-MBM-CUST";
    static final String MAT  = "TEST-MBM-0001";
    static final String CFG  = "CFG-TEST-MBM-9999";

    @Transactional
    void cleanup() {
        for (String t : List.of("material_bom_item", "material_bom")) {
            em.createNativeQuery("DELETE FROM " + t + " WHERE material_no IN (:a,:b)")
              .setParameter("a", MAT).setParameter("b", CFG).executeUpdate();
        }
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = CUST; c.systemType = "QUOTE"; c.importedBy = null;
        return c;
    }
    private SheetRow matRow(int rowNo, int seq, String comp, String qty) {
        Map<String, String> m = new HashMap<>();
        m.put("宏丰料号", MAT); m.put("项次", String.valueOf(seq));
        m.put("投入料号", comp); m.put("产出料号类型", "2.非银点类");
        m.put("材料毛重", qty); m.put("重量单位", "KG");
        return new SheetRow(rowNo, m);
    }
    private SheetRow asmRow(int rowNo, int seq, String comp, String qty) {
        Map<String, String> m = new HashMap<>();
        m.put("宏丰料号", MAT); m.put("项次（一级）", String.valueOf(seq));
        m.put("组成件料号", comp); m.put("组成数量", qty); m.put("组成单位", "PCS");
        return new SheetRow(rowNo, m);
    }
    private long count(String sql) {
        return ((Number) em.createNativeQuery(sql).setParameter("m", MAT).getSingleResult()).longValue();
    }

    @Test
    void sameMaterialInBothSheets_collapsesToOneAssemblyCurrentRow() {
        handler.merge(
            List.of(matRow(1, 1, "C1", "0.5"), matRow(2, 2, "C2", "1.0")),
            List.of(asmRow(1, 1, "C1", "1"),   asmRow(2, 2, "C3", "2")),
            ctx());

        assertEquals(1L, count("SELECT count(*) FROM material_bom WHERE material_no=:m AND is_current=TRUE"),
            "主表只剩一条 current");
        assertEquals("ASSEMBLY", em.createNativeQuery(
            "SELECT characteristic FROM material_bom WHERE material_no=:m AND is_current=TRUE")
            .setParameter("m", MAT).getSingleResult(), "组成件优先 → ASSEMBLY");
        // 子表合并键=component_no → C1(共有合并)/C2(物料独有)/C3(组成件独有) = 3 条当前
        assertEquals(3L, count("SELECT count(*) FROM material_bom_item WHERE material_no=:m AND is_current=TRUE"),
            "子行按 component_no 合并为 3 条");
        assertEquals(0L, count("SELECT count(*) FROM material_bom_item WHERE material_no=:m AND is_current=TRUE AND characteristic IS NULL"),
            "无残留 NULL 当前子行");
    }

    @Test
    void materialOnlyThenBoth_flipsNullToHistory() {
        // 第一次：只填物料BOM → NULL current
        handler.merge(List.of(matRow(1, 1, "C1", "0.5")), List.of(), ctx());
        assertEquals(1L, count("SELECT count(*) FROM material_bom WHERE material_no=:m AND is_current=TRUE AND characteristic IS NULL"),
            "首次只填物料BOM → NULL current");

        // 第二次：两表都填 → 目标 ASSEMBLY，旧 NULL 翻历史
        handler.merge(List.of(matRow(1, 1, "C1", "0.5")), List.of(asmRow(1, 1, "C1", "1")), ctx());

        assertEquals(1L, count("SELECT count(*) FROM material_bom WHERE material_no=:m AND is_current=TRUE"),
            "只剩一条 current 主行");
        assertEquals("ASSEMBLY", em.createNativeQuery(
            "SELECT characteristic FROM material_bom WHERE material_no=:m AND is_current=TRUE")
            .setParameter("m", MAT).getSingleResult(), "当前=ASSEMBLY");
        assertEquals(1L, count("SELECT count(*) FROM material_bom WHERE material_no=:m AND is_current=FALSE AND characteristic IS NULL"),
            "旧 NULL 行降为历史(is_current=false)保留");
    }

    @Test
    void cfgPrefixMaterial_rejected() {
        Map<String, String> m = new HashMap<>();
        m.put("宏丰料号", CFG); m.put("项次", "1"); m.put("投入料号", "C1"); m.put("材料毛重", "1");
        SheetImportResult r = handler.merge(List.of(new SheetRow(1, m)), List.of(), ctx());
        assertTrue(r.failedRows >= 1, "CFG- 料号被拒");
        assertEquals(0L, ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_bom WHERE material_no=:c").setParameter("c", CFG).getSingleResult()).longValue(),
            "CFG- 料号未写入");
    }
}
```

- [ ] **Step 3: 运行测试，确认通过**

Run: `cd /home/joii/project/cpq/cpq-backend && ./mvnw test -Dtest=MaterialBomMergeHandlerTest 2>&1 | grep -E "Tests run:|BUILD"`
Expected: `Tests run: 3, Failures: 0, Errors: 0` + `BUILD SUCCESS`。
> 若 `materialOnlyThenBoth` 报"当前主行 >1" → FLIP 没生效，检查 flipReverse 的 characteristic 谓词。

- [ ] **Step 4: Commit**

```bash
cd /home/joii/project/cpq
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandler.java \
        cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandlerTest.java
git commit -m "feat(import): MaterialBomMergeHandler 物料BOM⇄组成件BOM 去重合并(组成件优先+FLIP反向characteristic留历史+CFG-守卫)"
```

---

### Task 2: 接入 QuoteImportService + 删除 Q03/Q12

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/QuoteImportService.java`
- Delete: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q03MaterialBomHandler.java`
- Delete: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q12AssemblyBomHandler.java`
- Delete: `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q03MaterialBomHandlerTest.java`
- Delete: `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q12AssemblyBomHandlerTest.java`

- [ ] **Step 1: 确认 Q03/Q12 仅被 QuoteImportService 引用**

Run: `cd /home/joii/project/cpq/cpq-backend && grep -rn "Q03MaterialBomHandler\|Q12AssemblyBomHandler" src/main/java/ src/test/java/`
Expected: 只出现在 `QuoteImportService.java`（注入 + orderedHandlers）和它们自己的类/测试文件里。**若有其它引用，停下报告 BLOCKED。**

- [ ] **Step 2: 改 QuoteImportService —— 去掉 q03/q12 注入，加 MergeHandler 注入**

把：
```java
    @Inject Q03MaterialBomHandler q03;           // 物料BOM + material_master.material_type
    @Inject Q04ElementBomHandler q04;            // 元素BOM
    @Inject Q05ElementRecoveryHandler q05;       // 元素回收折扣 UPDATE
    @Inject Q12AssemblyBomHandler q12;           // 组成件BOM
```
改为：
```java
    @Inject MaterialBomMergeHandler bomMerge;    // 物料BOM⇄组成件BOM 去重合并(替代 Q03/Q12)
    @Inject Q04ElementBomHandler q04;            // 元素BOM
    @Inject Q05ElementRecoveryHandler q05;       // 元素回收折扣 UPDATE
```

- [ ] **Step 3: 改 orderedHandlers() —— 移除 q03/q12**

把：
```java
    private List<SheetHandler> orderedHandlers() {
        return List.of(q18, q02, q03, q04, q05, q12,
                       q01, q06, q07, q08, q09, q10, q11, q13, q14, q15, q16, q17, q19);
    }
```
改为：
```java
    private List<SheetHandler> orderedHandlers() {
        return List.of(q18, q02, q04, q05,
                       q01, q06, q07, q08, q09, q10, q11, q13, q14, q15, q16, q17, q19);
    }
```

- [ ] **Step 4: 在 importExcel 的 workbook try 块内，loop 之前加合并步骤**

在 `try (XSSFWorkbook wb = parser.open(stream)) {` 之后、`for (SheetHandler h : orderedHandlers())` 之前，插入：
```java
            // 物料BOM ⇄ 组成件BOM 去重合并（两 sheet 单一事务，组成件优先；替代 Q03/Q12 各写各的）
            {
                var matSheet = wb.getSheet("物料BOM");
                var asmSheet = wb.getSheet("组成件BOM");
                List<SheetRow> matRows = matSheet != null ? parser.parseSheet(matSheet) : List.of();
                List<SheetRow> asmRows = asmSheet != null ? parser.parseSheet(asmSheet) : List.of();
                SheetImportResult mr;
                try {
                    mr = bomMerge.merge(matRows, asmRows, ctx);
                } catch (Exception ex) {
                    Log.error("物料BOM/组成件BOM 合并导入异常", ex);
                    mr = new SheetImportResult("物料BOM+组成件BOM(合并)");
                    mr.recordError(0, "_sheet_", ex.getClass().getSimpleName() + ": " + ex.getMessage());
                }
                sheetDtos.add(SheetResultDTO.from(mr));
                totalSuccess += mr.successRows;
                totalFailed += mr.failedRows;
            }
```

- [ ] **Step 5: 删除 Q03/Q12 类与测试**

```bash
cd /home/joii/project/cpq
git rm cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q03MaterialBomHandler.java \
       cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q12AssemblyBomHandler.java \
       cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q03MaterialBomHandlerTest.java \
       cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q12AssemblyBomHandlerTest.java
```

- [ ] **Step 6: 编译 + 健康 + 合并测试仍过**

Run:
```bash
cd /home/joii/project/cpq/cpq-backend && ./mvnw -q compile 2>&1 | tail -5
touch src/main/java/com/cpq/basicdata/v6/quote/QuoteImportService.java && sleep 8
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/api/cpq/components
./mvnw test -Dtest=MaterialBomMergeHandlerTest 2>&1 | grep -E "Tests run:|BUILD"
```
Expected: 编译无错误；健康 401；测试 `Tests run: 3, Failures: 0` + BUILD SUCCESS。

- [ ] **Step 7: Commit**

```bash
cd /home/joii/project/cpq
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/QuoteImportService.java
git commit -m "refactor(import): QuoteImportService 接 MaterialBomMergeHandler + 删除 Q03/Q12(并入合并器)"
```

---

### Task 3: 端到端验证 + 存量清理 + 文档回写

**Files:**
- Modify: `docs/table/报价系统Excel导入落库方案.md`（标注 MergeHandler 已实现）
- Modify: `docs/RECORD.md`

- [ ] **Step 1: 现网脏数据一次性清理（3120018220 双 current → 组成件优先留 ASSEMBLY）**

> 这条料号是旧 Q03/Q12 行为遗留的双 current 行。重导会自愈，但先清干净。只清非 CFG、QUOTE。

Run:
```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db -tA <<'SQL'
-- 对同时存在 NULL-current 和 ASSEMBLY-current 的料号：把 NULL 侧主+子翻历史（组成件优先）
WITH dup AS (
  SELECT customer_no, material_no
  FROM material_bom WHERE system_type='QUOTE' AND is_current AND material_no NOT LIKE 'CFG-%'
  GROUP BY customer_no, material_no
  HAVING count(*) FILTER (WHERE characteristic IS NULL)>0
     AND count(*) FILTER (WHERE characteristic='ASSEMBLY')>0)
UPDATE material_bom m SET is_current=false
FROM dup WHERE m.system_type='QUOTE' AND m.customer_no=dup.customer_no
  AND m.material_no=dup.material_no AND m.characteristic IS NULL AND m.is_current;
WITH dup AS (
  SELECT customer_no, material_no
  FROM material_bom WHERE system_type='QUOTE' AND material_no NOT LIKE 'CFG-%'
  GROUP BY customer_no, material_no
  HAVING count(*) FILTER (WHERE characteristic IS NULL AND is_current=false)>0
     AND count(*) FILTER (WHERE characteristic='ASSEMBLY' AND is_current)>0)
UPDATE material_bom_item mi SET is_current=false
FROM dup WHERE mi.system_type='QUOTE' AND mi.customer_no=dup.customer_no
  AND mi.material_no=dup.material_no AND mi.characteristic IS NULL AND mi.is_current;
SQL
```
> 注意：这是一次性手工清理（非 Flyway，因只动数据不动 schema，且只跑一次）。跑完核对 3120018220：
```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db -tA -c "
SELECT characteristic, count(*) FILTER (WHERE is_current) cur FROM material_bom
WHERE system_type='QUOTE' AND customer_no='8000137' AND material_no='3120018220'
GROUP BY characteristic ORDER BY characteristic NULLS FIRST;"
```
Expected: 只有 `ASSEMBLY` 行 `cur=1`；NULL 行 `cur=0`（已翻历史）。

- [ ] **Step 2: E2E 双 spec 回归（合并器接入后渲染不破）**

Run:
```bash
cd /home/joii/project/cpq/cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list 2>&1 | tail -25
```
Expected: `1 passed`，`'加载中' final count = 0`，8 Tab 全 0。
> 若失败且是真回归，报告 BLOCKED 附 Tab + 线索；若环境问题(前后端没起)说明。

- [ ] **Step 3: 文档回写**

`docs/table/报价系统Excel导入落库方案.md`【去重合并实现细则】首段补一句：
```
> ✅ **已实现（2026-06-04）**：`MaterialBomMergeHandler` 已落地（解析两 sheet + 按 component_no 合并 + 组成件优先 + 第 4 步 FLIP 反向 characteristic 留历史 + CFG- 守卫），`Q03/Q12` 已删除并入合并器，`QuoteImportService` 改为显式喂两 sheet。计划见 `docs/superpowers/plans/2026-06-04-material-bom-merge-handler.md`。
```

`docs/RECORD.md` 末尾追加：
```
### [2026-06-04] MaterialBomMergeHandler 物料BOM⇄组成件BOM 去重合并 | MaterialBomMergeHandler + QuoteImportService(删Q03/Q12)
- **问题**: 同料号两表都填 → Q03(NULL)/Q12(ASSEMBLY)各写各的 → material_bom 双 current 行(如 8000137/3120018220)。
- **修法**: 新增 MaterialBomMergeHandler——两 sheet 单一事务解析,按 component_no 合并(去 characteristic/seq_no,冲突取组成件值),组成件优先判 ASSEMBLY,写入前 FLIP 反向 characteristic 旧当前行为 is_current=false(保留历史,依赖 V293 子表版本化),每料号单次 writeVersionedMasterDetail。Q03/Q12 删除并入;QuoteImportService 显式喂两 sheet;material_master upsert 保留;CFG- 前缀料号拒绝导入。
- **关键**: FLIP 而非 DELETE(因 material_bom_item 已版本化,保留历史版);FLIP/合并仅按非 CFG 单料号,不碰选配 CFG 双行。
- **验证**: MaterialBomMergeHandlerTest 3 passed(同料号塌缩 ASSEMBLY/正反向 FLIP/CFG- 拒);存量 3120018220 一次性清理为单 ASSEMBLY current;E2E quotation-flow 8 Tab 加载中=0。
- **计划**: docs/superpowers/plans/2026-06-04-material-bom-merge-handler.md
```

- [ ] **Step 4: Commit**

```bash
cd /home/joii/project/cpq
git add "docs/table/报价系统Excel导入落库方案.md" docs/RECORD.md
git commit -m "docs(import): 回写 MaterialBomMergeHandler 已实现 + RECORD + 存量清理记录"
```

---

## 自检声明模板（每个含代码 Task 完成后必带）

> "编译 0 错误 ✅；MaterialBomMergeHandlerTest 3 passed ✅；后端 401 ✅；存量 3120018220 → 单 ASSEMBLY current ✅；E2E quotation-flow 8 Tab 加载中=0 ✅"
