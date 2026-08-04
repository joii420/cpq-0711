# 电镀费用/电镀成本「投入料号」落库 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让电镀两个 sheet 支持非必填的【投入料号】【投入料号名称】列，`unit_price.code` 落零件料号、`finished_material_no` 落销售料号，与该表其余 14 个 handler 口径一致。

**Architecture:** 零 DDL —— `finished_material_no` 早已在 `uq_unit_price` 13 维唯一键内（V276）。报价侧 Q17 照抄 Q06/Q07 的「有码沿用 / 仅名称反查铸号 / 皆空回退」三分支 + groupKey 加成品轴；核价侧 P22 照抄 P15 的「gk 锚 finished_material_no、code 进 content」范式。配套改 `dj_view` + COMP-0063 取数，并清理 18 行旧语义存量。

**Tech Stack:** Java 17 / Quarkus 3.34 / Hibernate Panache / Flyway / PostgreSQL 16 / JUnit5 + `@QuarkusTest`

**需求文档:** `dev-docs/task-0708-导入报价单和导入核价单的数据落库规则澄清/repair-0802-电镀费用投入料号/需求文档.md`

---

## 环境前置（每个 Task 开始前都适用）

- 工作目录：worktree `/home/joii/project/cpq/.claude/worktrees/repair-0802-plating-part-no`，分支 `feat/repair-0802-plating-input-part-no`
- 后端测试命令必须在 **worktree 内的 `cpq-backend/`** 目录跑（`mvnw` 在那里，不在仓库根）：
  ```bash
  cd cpq-backend && ./mvnw -q test -Dtest='<TestClass>' -DfailIfNoTests=false
  ```
- 测试走 `test` profile → 库 `10.177.152.12:5432/cpq_db`（**与 dev 库 `cpq_db_0724` 不同**）
- ⚠️ worktree 内已复制 3 个**并发会话的未提交迁移** `V368/V369/V370`（否则 Flyway validate 报 "Detected applied migration not resolved locally" 致所有 `@QuarkusTest` 起不来）。**这 3 个文件禁止 `git add`，合并前删除。**
- ⚠️ 提交只 `git add` 本任务明确改动的文件，**严禁 `git add -A`**
- 基线（已跑，全绿）：`Q17PlatingCostHandlerTest 3/3` + `Q06FixedProcessFeeHandlerTest 3/3` + `UnitPriceFeeVersioningTest 10/10`

---

## File Structure

| 文件 | 职责 | Task |
|------|------|------|
| `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q17PlatingCostHandler.java` | 报价侧电镀费用落库 | 1 |
| `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q17PlatingCostHandlerTest.java` | 报价侧用例 | 1 |
| `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/QuoteImportValidator.java` | Phase 1 零写库预校验 | 2 |
| `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/QuoteImportValidatorPlatingTest.java` | Phase 1 用例（新建） | 2 |
| `cpq-backend/src/main/java/com/cpq/basicdata/v6/pricing/P22PlatingCostHandler.java` | 核价侧电镀成本落库 | 3 |
| `cpq-backend/src/test/java/com/cpq/basicdata/v6/pricing/P22PlatingCostHandlerTest.java` | 核价侧用例（新建） | 3 |
| `cpq-backend/src/main/resources/db/migration/V372__repair0802_plating_input_part_no.sql` | 存量清理 + dj_view + COMP-0063 | 4 |
| `docs/table/报价系统Excel导入落库方案.md` §17 / `docs/table/核价系统Excel导入落库方案.md` §22 / `docs/RECORD.md` | 文档 | 5 |

---

## Task 1: 报价侧 Q17 —— 投入料号落 `code`、销售料号落 `finished_material_no`

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q17PlatingCostHandler.java`
- Test: `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q17PlatingCostHandlerTest.java`

**背景（读这段再动手）：** 现状 `code` 读的是「销售料号」列、`finished_material_no` 不写。改造后 `code` = 投入料号（零件），`finished_material_no` = 销售料号（成品）。三分支解析逻辑与 `Q06FixedProcessFeeHandler.java:66-98` 逐行同构，**唯一差异**是 Q06 在「料号与名称均为空」时 `recordError`，本 handler 必须**回退为销售料号且不报错**（非必填硬要求）。

⚠️ 读列必须用 `row.exact("投入料号")` 而**不是** `row.getStr("投入料号")` —— `getStr` 是 contains 匹配，会命中「投入料号名称」列并静默取错值。

- [x] **Step 1: 写失败测试**

在 `Q17PlatingCostHandlerTest.java` 中，先把测试夹具改造成支持新列。**替换**现有的 `row(...)` 方法与 `CODE` 常量区，改为：

```java
    static final String SALES_NO = "TEST-Q17-SALES";     // 销售料号 → finished_material_no
    static final String INPUT_NO = "TEST-Q17-INPUT";     // 投入料号 → code
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000017");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE code IN (:a,:b) OR finished_material_no=:a")
            .setParameter("a", SALES_NO).setParameter("b", INPUT_NO).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "C1"; c.systemType = "QUOTE"; c.importedBy = UID; return c;
    }

    /** inputNo/inputName 传 null 表示该列不存在（模拟老模板/空单元格）。 */
    private SheetRow row(String inputNo, String inputName, String process, String material,
                         String excelVersion, String platingSchemeNo) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", SALES_NO);
        if (inputNo != null) m.put("投入料号", inputNo);
        if (inputName != null) m.put("投入料号名称", inputName);
        if (platingSchemeNo != null) m.put("电镀方案编号", platingSchemeNo);
        if (excelVersion != null) m.put("版本编号", excelVersion);
        m.put("电镀加工费", process); m.put("电镀材料费", material);
        m.put("货币", "CNY"); m.put("计价单位", "PCS"); m.put("不良率", "0.01");
        return new SheetRow(1, m);
    }

    private String version(String code, String costType) {
        List<?> r = em.createNativeQuery(
            "SELECT version_no FROM unit_price WHERE code=:c AND cost_type=:ct AND is_current=true LIMIT 1")
            .setParameter("c", code).setParameter("ct", costType).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
    private long total() {
        return ((Number) em.createNativeQuery(
            "SELECT count(*) FROM unit_price WHERE code IN (:a,:b)")
            .setParameter("a", SALES_NO).setParameter("b", INPUT_NO).getSingleResult()).longValue();
    }
    private String fmnOf(String code) {
        List<?> r = em.createNativeQuery(
            "SELECT finished_material_no FROM unit_price WHERE code=:c AND is_current=true LIMIT 1")
            .setParameter("c", code).getResultList();
        return r.isEmpty() || r.get(0) == null ? null : String.valueOf(r.get(0));
    }
```

把现有 3 个测试的 `row(...)` 调用补上新增的两个前置参数（都传 `null`），并把 `version("电镀加工费")` 改为 `version(SALES_NO, "电镀加工费")`——它们验证的是「无投入料号」的回退路径，断言值不变：

```java
    @Transactional
    @Test void importTwice_idempotent_twoCostTypes_ignoreExcelVersion() {
        handler.handle(List.of(row(null, null, "5", "3", "V99", null)), ctx());
        handler.handle(List.of(row(null, null, "5", "3", "V99", null)), ctx());
        assertEquals("2000", version(SALES_NO, "电镀加工费"), "version 系统生成, 忽略 Excel 'V99'");
        assertEquals("2000", version(SALES_NO, "电镀材料费"));
        assertEquals(2L, total(), "一行拆两条, 导两遍不翻倍");
    }

    @Transactional
    @Test void changeOneFee_bumpsOnlyThatCostType() {
        handler.handle(List.of(row(null, null, "5", "3", null, null)), ctx());
        handler.handle(List.of(row(null, null, "9", "3", null, null)), ctx());
        assertEquals("2001", version(SALES_NO, "电镀加工费"), "加工费升版");
        assertEquals("2000", version(SALES_NO, "电镀材料费"), "材料费不变");
        assertEquals(3L, total(), "加工费 2 行(2000下线+2001生效) + 材料费 1 行");
    }

    @Transactional
    @Test void platingSchemeNo_skipsRow() {
        handler.handle(List.of(row(null, null, "5", "3", null, "SCHEME-1")), ctx());
        assertEquals(0L, total(), "有电镀方案编号 → 整行跳过, 不写 unit_price");
    }
```

再追加 3 个新测试：

```java
    @Transactional
    @Test void inputPartNo_landsInCode_salesNoLandsInFinishedMaterialNo() {
        handler.handle(List.of(row(INPUT_NO, "投入零件X", "5", "3", null, null)), ctx());
        assertEquals("2000", version(INPUT_NO, "电镀加工费"), "code = 投入料号");
        assertEquals(SALES_NO, fmnOf(INPUT_NO), "finished_material_no = 销售料号");
        assertNull(version(SALES_NO, "电镀加工费"), "销售料号不再写进 code");
        assertEquals(2L, total(), "一行拆两条");
    }

    @Transactional
    @Test void bothColumnsBlank_fallsBackToSalesNo_noError() {
        var result = handler.handle(List.of(row(null, null, "5", "3", null, null)), ctx());
        assertEquals(0, result.failedRows, "两列皆空是合法输入(非必填)，不得报错");
        assertEquals(1, result.successRows);
        assertEquals("2000", version(SALES_NO, "电镀加工费"), "code 回退为销售料号");
        assertEquals(SALES_NO, fmnOf(SALES_NO), "finished_material_no 仍写销售料号");
    }

    @Transactional
    @Test void sameSalesNo_twoInputParts_coexistWithoutOverwrite() {
        Map<String, String> m2 = new LinkedHashMap<>();
        m2.put("宏丰料号", SALES_NO); m2.put("投入料号", INPUT_NO + "-B");
        m2.put("电镀加工费", "7"); m2.put("电镀材料费", "4");
        m2.put("货币", "CNY"); m2.put("计价单位", "PCS"); m2.put("不良率", "0.01");
        handler.handle(List.of(row(INPUT_NO, null, "5", "3", null, null), new SheetRow(2, m2)), ctx());

        long n = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM unit_price WHERE finished_material_no=:f AND is_current=true")
            .setParameter("f", SALES_NO).getSingleResult()).longValue();
        assertEquals(4L, n, "两个投入料号 × 两个 cost_type = 4 行，互不覆盖");
        em.createNativeQuery("DELETE FROM unit_price WHERE code=:c")
            .setParameter("c", INPUT_NO + "-B").executeUpdate();
    }
```

- [x] **Step 2: 跑测试确认失败**

```bash
cd cpq-backend && ./mvnw -q test -Dtest='Q17PlatingCostHandlerTest' -DfailIfNoTests=false
```
预期：`inputPartNo_landsInCode_salesNoLandsInFinishedMaterialNo` 失败（`code` 仍是销售料号、`fmnOf` 返 null），`sameSalesNo_twoInputParts_coexistWithoutOverwrite` 失败（2 行而非 4 行）。

- [x] **Step 3: 实现 handler**

`Q17PlatingCostHandler.java` 完整替换为：

```java
package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
import com.cpq.basicdata.v6.service.MaterialNoResolver;
import com.cpq.basicdata.v6.service.MaterialNoUnresolvableException;
import com.cpq.basicdata.v6.service.PartTypeInferenceService;
import com.cpq.basicdata.v6.service.PartTypeInferenceService.InferResult;
import com.cpq.basicdata.v6.service.PartTypeInferenceService.TypeIndex;
import com.cpq.basicdata.v6.service.QuoteMaterialNoAllocator;
import com.cpq.basicdata.v6.versioning.VersionedGroupSpec;
import com.cpq.basicdata.v6.versioning.VersionedV6Writer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Q17 电镀费用 → unit_price 一行拆两条 (cost_type=电镀加工费 + 电镀材料费)。
 *
 * <p>版本化（Task 3）：每个 cost_type 独立成组，
 * groupKey=(QUOTE, customer_no, PLATING, cost_type, code, finished_material_no)，
 * content=[pricing_price, currency, unit, defect_rate]。
 * <ul>
 *   <li>规则：电镀方案编号不为空 → 整行跳过（由系统按电镀方案计算）。</li>
 *   <li>决策⑨：**忽略 Excel「版本编号」列**，version_no 由 writeVersionedGroup 系统生成。</li>
 *   <li>repair-0802：{@code code} = 投入料号(零件料号)、{@code finished_material_no} = 销售料号(成品)，
 *       与 Q06/Q07/Q13 及 unit_price 全表口径一致（见
 *       {@code dev-docs/rule-0724-组件模板配置/4-页签属性与树.md} §零件）。
 *       「投入料号」「投入料号名称」**均非必填**：有码沿用原始码（不 resolve/不铸号）；
 *       只有名称则按 {@link TypeIndex} 推断类型后反查/铸号；**两者皆空回退为销售料号**
 *       （语义=电镀针对成品自身，与 Q15 组装加工费年降的退化范式一致），此时不得报错。</li>
 *   <li>同一销售料号下可有多个投入料号，各自独立成行（groupKey 含两个料号维度）。</li>
 * </ul>
 */
@ApplicationScoped
public class Q17PlatingCostHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;
    @Inject MaterialNoResolver materialNoResolver;
    @Inject MaterialMasterRepository materialMasterRepo;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
    boolean setBased;

    @Override public String sheetName() { return "电镀费用"; }

    private static final List<String> CONTENT = List.of("pricing_price", "currency", "unit", "defect_rate");

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        TypeIndex typeIndex = (TypeIndex) ctx.sharedCache.get("partTypeIndex");
        MaterialNoResolver.BatchState batch = MaterialNoResolver.batchStateFor(ctx);
        Map<String, String[]> mmAcc = new LinkedHashMap<>();
        // key=(cost_type, code, finished_material_no) → (groupKey map, content rows)
        Map<List<Object>, Map<String, Object>> groupKeyOf = new LinkedHashMap<>();
        Map<List<Object>, List<Map<String, Object>>> contentOf = new LinkedHashMap<>();

        for (SheetRow row : rows) {
            result.totalRows++;
            String finishedMaterialNo = row.getStr("销售料号", "宏丰料号");
            if (finishedMaterialNo == null) { result.recordError(row.rowNo, "宏丰料号", "为空"); continue; }
            String platingSchemeNo = row.getStr("电镀方案编号");
            if (platingSchemeNo != null && !platingSchemeNo.isBlank()) {
                result.successRows++;   // 整行跳过（成功跳过不算失败）
                continue;
            }
            // repair-0802：投入料号(非必填)三分支。exact 而非 getStr —— 后者 contains 会命中「投入料号名称」。
            String rawNo = row.exact("投入料号");
            String rawName = row.exact("投入料号名称");
            String code;
            if (rawNo != null && !rawNo.isBlank()) {
                code = rawNo;   // 有码：沿用原始码，不 resolve/不铸号（对齐 Q06/Q07 U10 §6.1 第 1 条）
            } else if (rawName != null && !rawName.isBlank()) {
                InferResult infer = typeIndex != null ? typeIndex.infer(null, rawName)
                    : new InferResult(PartTypeInferenceService.ASSEMBLY, PartTypeInferenceService.Source.DEFAULT);
                String characteristic = infer.characteristic();
                if (PartTypeInferenceService.RECIPE.equals(characteristic)) {
                    code = typeIndex.resolveRecipeCode(null, rawName);
                    if (code == null) {
                        result.recordError(row.rowNo, "投入料号名称", "未找到材质「" + rawName + "」");
                        continue;
                    }
                } else {
                    try {
                        code = materialNoResolver.resolve(null, rawName, batch);
                    } catch (MaterialNoUnresolvableException ex) {
                        result.recordError(row.rowNo, "投入料号名称", "无法解析料号"); continue;
                    } catch (QuoteMaterialNoAllocator.CrossCustomerQuoteNoException ex) {
                        result.recordError(row.rowNo, "投入料号名称", "报价料号跨客户串号"); continue;
                    }
                    String materialType = PartTypeInferenceService.OUTSOURCED.equals(characteristic) ? "外购件" : "零件";
                    MaterialMasterRepository.accNameType(mmAcc, code, rawName, materialType);
                    result.recordWrite("material_master", 1);
                }
            } else {
                // 两列皆空（非必填）→ 回退为销售料号，语义=电镀针对成品自身。不报错。
                code = finishedMaterialNo;
            }

            // 忽略 Excel「版本编号」列（决策⑨）
            BigDecimal processFee = row.getDecimal("电镀加工费");
            BigDecimal materialFee = row.getDecimal("电镀材料费");
            String currency = row.getStr("货币");
            String unit = row.getStr("计价单位");
            BigDecimal defectRate = row.getDecimal("不良率");

            accumulate(groupKeyOf, contentOf, ctx, "电镀加工费", code, finishedMaterialNo,
                processFee != null ? processFee : BigDecimal.ZERO, currency, unit, defectRate);
            accumulate(groupKeyOf, contentOf, ctx, "电镀材料费", code, finishedMaterialNo,
                materialFee != null ? materialFee : BigDecimal.ZERO, currency, unit, defectRate);
            result.successRows++;
        }

        if (!mmAcc.isEmpty()) {
            List<MaterialMasterRepository.NameTypeRow> mmRows = new ArrayList<>(mmAcc.size());
            for (Map.Entry<String, String[]> e : mmAcc.entrySet()) {
                mmRows.add(new MaterialMasterRepository.NameTypeRow(e.getKey(), e.getValue()[0], e.getValue()[1]));
            }
            materialMasterRepo.upsertBatchNameType(mmRows, ctx.importedBy, true, ctx.pendingQuotationId);
        }

        if (setBased) {
            LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
            for (Map.Entry<List<Object>, List<Map<String, Object>>> e : contentOf.entrySet())
                groups.put(groupKeyOf.get(e.getKey()), e.getValue());
            try {
                writer.writeVersionedGroups("unit_price", "version_no", CONTENT, null, List.of(), groups, ctx.pendingQuotationId);
                for (List<Map<String, Object>> groupRows : groups.values())
                    result.recordWrite("unit_price", groupRows.size());
            } catch (Exception ex) {
                result.recordError(0, "_batch_", ex.getMessage());
            }
        } else {
            for (Map.Entry<List<Object>, List<Map<String, Object>>> e : contentOf.entrySet()) {
                try {
                    writer.writeVersionedGroup(new VersionedGroupSpec(
                        "unit_price", "version_no", groupKeyOf.get(e.getKey()), CONTENT, e.getValue(), null, ctx.pendingQuotationId));
                    result.recordWrite("unit_price", e.getValue().size());
                } catch (Exception ex) {
                    result.recordError(0, "_group_", ex.getMessage());
                }
            }
        }
        return result;
    }

    private void accumulate(Map<List<Object>, Map<String, Object>> groupKeyOf,
                            Map<List<Object>, List<Map<String, Object>>> contentOf,
                            ImportContext ctx, String costType, String code, String finishedMaterialNo,
                            BigDecimal pricingPrice, String currency, String unit, BigDecimal defectRate) {
        List<Object> key = Arrays.asList(costType, code, finishedMaterialNo);
        groupKeyOf.computeIfAbsent(key, k -> {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("system_type", "QUOTE");
            g.put("customer_no", ctx.customerNo);
            g.put("price_type", "PLATING");
            g.put("cost_type", costType);
            g.put("code", code);
            g.put("finished_material_no", finishedMaterialNo);
            return g;
        });
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("pricing_price", pricingPrice);
        c.put("currency", currency);
        c.put("unit", unit);
        c.put("defect_rate", defectRate);
        contentOf.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
    }
}
```

- [x] **Step 4: 跑测试确认通过**

```bash
cd cpq-backend && ./mvnw -q test -Dtest='Q17PlatingCostHandlerTest' -DfailIfNoTests=false
```
预期：`Tests run: 6, Failures: 0, Errors: 0`

- [x] **Step 5: 跑邻居回归**

```bash
cd cpq-backend && ./mvnw -q test -Dtest='Q06FixedProcessFeeHandlerTest,Q07IncomingOtherFeeHandlerTest,UnitPriceFeeVersioningTest' -DfailIfNoTests=false
```
预期：全绿（本任务未触碰这些 handler，用于确认共享的 `MaterialNoResolver`/`VersionedV6Writer` 无回归）

- [x] **Step 6: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q17PlatingCostHandler.java \
        cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/Q17PlatingCostHandlerTest.java
git commit -m "feat(repair-0802): 报价侧电镀费用支持投入料号，code 落零件料号 finished_material_no 落销售料号"
git show --stat HEAD
```
`git show --stat` 必须只列出这 2 个文件（防夹带并发会话改动）。

---

## Task 2: 报价侧 Phase 1 预校验纳入「电镀费用」

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/QuoteImportValidator.java`
- Create: `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/QuoteImportValidatorPlatingTest.java`

**背景：** Task 1 给电镀费用引入了「只有名称 → 反查/铸号」能力。铸号会**写库**（`material_master` + 占号表），而导入是两阶段协议：Phase 1 零写库全量校验、Phase 2 才写。名称查不到材质、跨客户串号这类错误若拖到 Phase 2 才炸，会触发整单回滚（用户白等一轮）。故必须在 Phase 1 预判。`QuoteImportValidator.java:97-104` 现在把「电镀费用」归入"其余 sheet 仅计数"分支，需要提升为真校验。

⚠️ 与 `validateIncoming` 的**关键差异**：来料三表在「料号与名称均为空」时 `recordError`，电镀费用**不得**如此（非必填）。不要复用 `validateIncoming`，新写一个方法。

- [x] **Step 1: 写失败测试**

新建 `cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/QuoteImportValidatorPlatingTest.java`：

```java
package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** repair-0802：电镀费用 sheet 进入 Phase 1 预校验（投入料号/名称非必填）。 */
@QuarkusTest
class QuoteImportValidatorPlatingTest {

    @Inject QuoteImportValidator validator;

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "C1"; c.systemType = "QUOTE";
        return c;
    }

    private SheetRow platingRow(int rowNo, String salesNo, String inputNo, String inputName) {
        Map<String, String> m = new LinkedHashMap<>();
        if (salesNo != null) m.put("宏丰料号", salesNo);
        if (inputNo != null) m.put("投入料号", inputNo);
        if (inputName != null) m.put("投入料号名称", inputName);
        m.put("电镀加工费", "5"); m.put("电镀材料费", "3");
        return new SheetRow(rowNo, m);
    }

    /**
     * 「物料与元素BOM」是 RECIPE 类型的权威来源（{@code PartTypeInferenceService.buildIndex}
     * 用它的「材质料号」「材质料号名称」两列建 recipeTokens）。测试里塞一行，把某个名称登记为材质，
     * 才能让电镀费用行引用同名时被 {@code infer} 判成 RECIPE —— 否则兜底类型是 ASSEMBLY
     * （`PartTypeInferenceService.java:177` `new InferResult(ASSEMBLY, Source.DEFAULT)`），
     * 走铸号路径而不是材质反查，不会产生「未找到材质」。
     */
    private SheetRow recipeSeedRow(String recipeName) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", "S-PLATE-1");
        m.put("材质料号名称", recipeName);
        return new SheetRow(1, m);
    }

    private SheetImportResult run(List<SheetRow> rows, List<SheetRow> elementBomRows) {
        Map<String, List<SheetRow>> sheets = new LinkedHashMap<>();
        sheets.put("电镀费用", rows);
        sheets.put("物料与元素BOM", elementBomRows);
        return validator.validate(sheets, ctx()).bySheet.get("电镀费用");
    }

    private SheetImportResult run(List<SheetRow> rows) { return run(rows, List.of()); }

    @Test void bothColumnsBlank_isNotAnError() {
        SheetImportResult r = run(List.of(platingRow(1, "S-PLATE-1", null, null)));
        assertEquals(0, r.failedRows, "投入料号与名称均非必填，两列皆空不得报错");
        assertEquals(1, r.successRows);
    }

    @Test void salesNoBlank_isAnError() {
        SheetImportResult r = run(List.of(platingRow(1, null, "P-1", null)));
        assertEquals(1, r.failedRows, "销售料号仍必填");
    }

    @Test void inputNamePresent_butRecipeNotFound_isAnError() {
        // 名称经「物料与元素BOM」登记为材质(RECIPE)，但 material_recipe 表中查无此名 → Phase 1 拦截
        String ghost = "__repair0802虚构材质__";
        SheetImportResult r = run(List.of(platingRow(1, "S-PLATE-1", null, ghost)),
                                  List.of(recipeSeedRow(ghost)));
        assertEquals(1, r.failedRows, "只填名称且材质查无 → Phase 1 拦截");
        assertTrue(r.errors.toString().contains("未找到材质"), "错误文案应指明未找到材质");
    }

    @Test void inputNameOnly_nonRecipe_passesPhase1() {
        // 未被任何权威 sheet 登记的名称 → 兜底 ASSEMBLY(零件) → 走铸号路径，
        // Phase 1 只做只读预判、不实际铸号，故不报错。
        SheetImportResult r = run(List.of(platingRow(1, "S-PLATE-1", null, "某个没登记过的零件名")));
        assertEquals(0, r.failedRows, "零件类只填名称 → Phase 1 放行，Phase 2 铸号");
        assertEquals(1, r.successRows);
    }

    @Test void inputNoPresent_passes() {
        SheetImportResult r = run(List.of(platingRow(1, "S-PLATE-1", "P-1", "随便什么名")));
        assertEquals(0, r.failedRows, "有码即通过，不做名称反查");
        assertEquals(1, r.successRows);
    }
}
```

> 已实证的两个事实（无需再确认，直接按此写）：
> 1. `SheetImportResult.errors` 是 `public final List<RowError>`（`SheetImportResult.java:13`），`errors.toString()` 可直接断言。
> 2. `TypeIndex.infer` 的兜底是 `ASSEMBLY / Source.DEFAULT`（`PartTypeInferenceService.java:177`），**不是 RECIPE**；`resolveRecipeCode(null, name)` 走 `recipeNameToCode.get(name)`，只有名称被「物料与元素BOM」登记为材质、且 `material_recipe` 表查无时才返 null。

- [x] **Step 2: 跑测试确认失败**

```bash
cd cpq-backend && ./mvnw -q test -Dtest='QuoteImportValidatorPlatingTest' -DfailIfNoTests=false
```
预期：`inputNamePresent_butRecipeNotFound_isAnError` 与 `salesNoBlank_isAnError` 失败（当前"仅计数"分支把所有行都记成 success）。

- [x] **Step 3: 实现校验方法**

在 `QuoteImportValidator.java` 的 `validate(...)` 方法内，`validateCustomerMap(...)` 那一行之后、`ProcessNoResolver.Index processIdx = ...` 之前，插入一行：

```java
        validatePlatingCost(sheetsByName.getOrDefault("电镀费用", List.of()), idx, out);
```

并在 `validateCustomerMap` 方法之后新增方法：

```java
    /**
     * repair-0802：电镀费用（Q17 → unit_price）。与 {@link #validateIncoming} 的**关键差异**是
     * 「投入料号」「投入料号名称」**均非必填**——两列皆空是合法输入（Q17 回退为销售料号，语义=
     * 电镀针对成品自身），故此处不得照抄来料三表的「料号与名称均为空」报错分支。
     *
     * <p>预校验的目的只有一个：只填名称时 Q17 会走反查/铸号（**写库**），把"材质查无"这类
     * 必然失败提前到 Phase 1（零写库）拦截，避免拖到 Phase 2 触发整单回滚。
     */
    private void validatePlatingCost(List<SheetRow> rows, TypeIndex idx, Outcome out) {
        SheetImportResult r = result(out, "电镀费用");
        for (SheetRow row : rows) {
            r.totalRows++;
            String materialNo = row.getStr("销售料号", "宏丰料号");
            if (materialNo == null) { r.recordError(row.rowNo, "销售料号", "为空"); continue; }
            String rawNo = row.exact("投入料号");
            String rawName = row.exact("投入料号名称");
            // 投入料号/名称均非必填：两列皆空 → 合法（Q17 回退销售料号）。
            // 只填名称时才需预判材质缺库（与 validateIncoming 同一规则）。
            if (isBlank(rawNo) && !isBlank(rawName)) {
                InferResult infer = idx.infer(null, rawName);
                if (PartTypeInferenceService.RECIPE.equals(infer.characteristic())
                        && idx.resolveRecipeCode(null, rawName) == null) {
                    r.recordError(row.rowNo, "投入料号名称", "未找到材质「" + rawName + "」");
                    continue;
                }
            }
            r.successRows++;
        }
    }
```

同时把类 javadoc（`QuoteImportValidator.java:30-37`）中「其余既有 sheet（成品其他费用、电镀方案/费用、年降类）」里的「电镀方案/费用」改为「电镀方案」，并把 `validate` 方法内 line 97 的注释

```java
        // 其余 sheet（成品其他费用/电镀方案/电镀费用/年降类/单重/元素回收折扣等）：
```

改为

```java
        // 其余 sheet（成品其他费用/电镀方案/年降类/单重/元素回收折扣等）：
```

- [x] **Step 4: 跑测试确认通过**

```bash
cd cpq-backend && ./mvnw -q test -Dtest='QuoteImportValidatorPlatingTest,Q17PlatingCostHandlerTest' -DfailIfNoTests=false
```
预期：`QuoteImportValidatorPlatingTest 5/5` + `Q17PlatingCostHandlerTest 6/6`，0 失败 0 错误

- [x] **Step 5: 跑导入链路回归**

```bash
cd cpq-backend && ./mvnw -q test -Dtest='MaterialBomMergeHandlerTest,MaterialNoImportIdempotencyTest,AsyncImportProcessTest' -DfailIfNoTests=false
```
预期：全绿（确认 Phase 1 新增桶不影响既有 sheet 的计数/回滚语义）

- [x] **Step 6: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/QuoteImportValidator.java \
        cpq-backend/src/test/java/com/cpq/basicdata/v6/quote/QuoteImportValidatorPlatingTest.java
git commit -m "feat(repair-0802): 电镀费用纳入 Phase 1 预校验，投入料号/名称非必填但名称反查失败提前拦截"
git show --stat HEAD
```

---

## Task 3: 核价侧 P22 —— 分组锚点切到 `finished_material_no`、`code` 进 content

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/pricing/P22PlatingCostHandler.java`
- Create: `cpq-backend/src/test/java/com/cpq/basicdata/v6/pricing/P22PlatingCostHandlerTest.java`

**背景：** 核价侧范式与报价侧**不同**——照 `P15IncomingProcessFeeHandler`（来料加工费）：groupKey 锚 `finished_material_no`（销售料号），`code`（明细维度）进 content，一个销售料号 = 一个版本组。核价侧**不做名称反查、不铸号**（与 P15/P16/P17 的「品名」列一致：核价侧物料均已有正式码，名称仅供 Excel 可读，渲染取名由视图 JOIN `material_master` 负责）。

实测 `PRICING/PLATING` 当前 **0 行**，无存量风险。

- [x] **Step 1: 写失败测试**

新建 `cpq-backend/src/test/java/com/cpq/basicdata/v6/pricing/P22PlatingCostHandlerTest.java`：

```java
package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** repair-0802：P22 电镀成本 —— code=投入料号(零件)、finished_material_no=销售料号(成品)。 */
@QuarkusTest
class P22PlatingCostHandlerTest {

    @Inject P22PlatingCostHandler handler;
    @Inject EntityManager em;

    static final String SALES_NO = "TEST-P22-SALES";
    static final String INPUT_NO = "TEST-P22-INPUT";

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE finished_material_no=:f OR code=:f")
            .setParameter("f", SALES_NO).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.systemType = "PRICING";
        return c;
    }

    private SheetRow row(int rowNo, String inputNo, String inputName, String proc, String mat) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("生产料号", "PROD-1");
        m.put("销售料号", SALES_NO);
        if (inputNo != null) m.put("投入料号", inputNo);
        if (inputName != null) m.put("投入料号名称", inputName);
        m.put("电镀加工费", proc); m.put("电镀材料费", mat);
        m.put("货币", "CNY"); m.put("计价单位", "PCS"); m.put("不良率（%）", "0.01");
        return new SheetRow(rowNo, m);
    }

    private List<Object[]> currentRows() {
        @SuppressWarnings("unchecked")
        List<Object[]> r = em.createNativeQuery(
            "SELECT code, cost_type, pricing_price, version_no FROM unit_price "
          + "WHERE finished_material_no=:f AND is_current=true ORDER BY code, cost_type")
            .setParameter("f", SALES_NO).getResultList();
        return r;
    }

    @Transactional
    @Test void inputPartNo_landsInCode_salesNoAnchorsGroup() {
        handler.handle(List.of(row(1, INPUT_NO, "投入零件X", "5", "3")), ctx());
        List<Object[]> rows = currentRows();
        assertEquals(2, rows.size(), "一行拆两条 cost_type");
        assertEquals(INPUT_NO, String.valueOf(rows.get(0)[0]), "code = 投入料号");
        assertEquals("2000", String.valueOf(rows.get(0)[3]));
    }

    @Transactional
    @Test void bothColumnsBlank_fallsBackToSalesNo_noError() {
        var result = handler.handle(List.of(row(1, null, null, "5", "3")), ctx());
        assertEquals(0, result.failedRows, "两列皆空是合法输入(非必填)");
        assertEquals(1, result.successRows);
        List<Object[]> rows = currentRows();
        assertEquals(2, rows.size());
        assertEquals(SALES_NO, String.valueOf(rows.get(0)[0]), "code 回退为销售料号");
    }

    @Transactional
    @Test void twoInputParts_shareOneVersionGroup() {
        handler.handle(List.of(row(1, INPUT_NO, null, "5", "3"),
                               row(2, INPUT_NO + "-B", null, "7", "4")), ctx());
        List<Object[]> rows = currentRows();
        assertEquals(4, rows.size(), "2 个投入料号 × 2 个 cost_type，互不覆盖");
        for (Object[] r : rows) assertEquals("2000", String.valueOf(r[3]), "同一销售料号共享一个版本组");
    }

    @Transactional
    @Test void importTwice_isIdempotent_thenBumpsOnChange() {
        handler.handle(List.of(row(1, INPUT_NO, null, "5", "3")), ctx());
        handler.handle(List.of(row(1, INPUT_NO, null, "5", "3")), ctx());
        assertEquals(2, currentRows().size(), "重复导入不翻倍");
        assertEquals("2000", String.valueOf(currentRows().get(0)[3]));

        handler.handle(List.of(row(1, INPUT_NO, null, "9", "3")), ctx());
        for (Object[] r : currentRows()) {
            assertEquals("2001", String.valueOf(r[3]), "任一明细变化 → 整组升版(与 P15 同构)");
        }
    }
}
```

- [x] **Step 2: 跑测试确认失败**

```bash
cd cpq-backend && ./mvnw -q test -Dtest='P22PlatingCostHandlerTest' -DfailIfNoTests=false
```
预期：`inputPartNo_landsInCode_salesNoAnchorsGroup` 等失败（当前 `finished_material_no` 恒 NULL，`currentRows()` 返 0 行）。

- [x] **Step 3: 实现 handler**

`P22PlatingCostHandler.java` 的三处改动：

其一，`TEMPLATE_HEADERS` 插入两列（**位置紧跟「销售料号」之后**，与权威导入文件列序一致）：

```java
    private static final List<String> TEMPLATE_HEADERS = List.of(
        "生产料号", "销售料号", "投入料号", "投入料号名称", "电镀方案编号", "版本编号",
        "电镀加工费", "电镀材料费", "货币", "计价单位", "不良率（%）");
```

其二，`CONTENT` 增加 `code`：

```java
    private static final List<String> CONTENT = List.of(
        "code", "cost_type", "pricing_price", "currency", "unit", "defect_rate");
```

其三，`handle(...)` 方法体整体替换为：

```java
    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());

        // finished_material_no(销售料号) -> "code|cost_type" -> content row（末值覆盖）
        Map<String, LinkedHashMap<String, Map<String, Object>>> byFinishedMaterial = new LinkedHashMap<>();

        for (SheetRow row : rows) {
            result.totalRows++;
            String finishedMaterialNo = row.getStr("销售料号", "宏丰料号");
            if (finishedMaterialNo == null) { result.recordError(row.rowNo, "销售料号", "为空"); continue; }
            String platingSchemeNo = row.getStr("电镀方案编号");
            if (platingSchemeNo != null && !platingSchemeNo.isBlank()) {
                result.successRows++; continue;
            }
            // repair-0802：投入料号非必填。核价侧不做名称反查/铸号（与 P15/P16/P17 的「品名」列一致），
            // 空则回退为销售料号（语义=电镀针对成品自身）。exact 而非 getStr——后者 contains 会命中「投入料号名称」。
            String rawInputNo = row.exact("投入料号");
            String code = (rawInputNo != null && !rawInputNo.isBlank()) ? rawInputNo : finishedMaterialNo;

            BigDecimal processFee = DecimalScale.at(row.getDecimal("电镀加工费"), 6);
            BigDecimal materialFee = DecimalScale.at(row.getDecimal("电镀材料费"), 6);
            String currency = row.getStr("货币");
            String unit = row.getStr("计价单位");
            BigDecimal defectRate = DecimalScale.at(row.getDecimal("不良率"), 4);
            String productionNo = row.getStr("生产料号");

            LinkedHashMap<String, Map<String, Object>> group =
                byFinishedMaterial.computeIfAbsent(finishedMaterialNo, k -> new LinkedHashMap<>());

            Map<String, Object> c1 = new LinkedHashMap<>();
            c1.put("code", code);
            c1.put("cost_type", "电镀加工费");
            c1.put("pricing_price", processFee != null ? processFee : BigDecimal.ZERO);
            c1.put("currency", currency);
            c1.put("unit", unit);
            c1.put("defect_rate", defectRate);
            c1.put("production_no", productionNo);
            group.put(code + "|电镀加工费", c1);

            Map<String, Object> c2 = new LinkedHashMap<>();
            c2.put("code", code);
            c2.put("cost_type", "电镀材料费");
            c2.put("pricing_price", materialFee != null ? materialFee : BigDecimal.ZERO);
            c2.put("currency", currency);
            c2.put("unit", unit);
            c2.put("defect_rate", defectRate);
            c2.put("production_no", productionNo);
            group.put(code + "|电镀材料费", c2);

            result.successRows++;
        }

        LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashMap<String, Map<String, Object>>> e : byFinishedMaterial.entrySet()) {
            Map<String, Object> gk = new LinkedHashMap<>();
            gk.put("system_type", "PRICING");
            gk.put("price_type", PricingPriceType.PLATING);
            gk.put("finished_material_no", e.getKey());
            groups.put(gk, new ArrayList<>(e.getValue().values()));
        }
        try {
            writer.writeVersionedGroups("unit_price", "version_no", CONTENT, null, DESCRIPTOR, groups);
            for (List<Map<String, Object>> g : groups.values()) result.recordWrite("unit_price", g.size());
        } catch (Exception ex) {
            result.recordError(0, "_batch_", ex.getMessage());
        }
        return result;
    }
```

并把类 javadoc 的 groupKey 描述段替换为：

```java
 * <p>groupKey = {system_type:"PRICING", price_type:"PLATING", finished_material_no(销售料号)}；
 * 同一销售料号下的全部投入料号 × 加工费/材料费两个 cost_type 共享一个版本组，任一变化整组一起升版
 * （与 P15 来料加工费同构）。content = [code(投入料号), cost_type, pricing_price, currency, unit,
 * defect_rate]；production_no 为描述列。忽略 Excel「版本编号」列，交给 {@link VersionedV6Writer}
 * 系统自增（2000 起）。
 *
 * <p>repair-0802：「投入料号」「投入料号名称」均非必填。code 取投入料号（零件料号），空则回退为
 * 销售料号（语义=电镀针对成品自身）；名称列不落库、不参与解析（与 P15/P16/P17 的「品名」一致，
 * 渲染取名由视图 JOIN material_master 负责）。
 *
 * <p>电镀方案引用行（"电镀方案编号"非空）视为非本 Sheet 主体数据，跳过不落 unit_price（沿用原逻辑）。
 * <p>组内去重键 = (code, cost_type)：同批同料号同投入料号若出现多行，取最后一行（末值覆盖）。
```

- [x] **Step 4: 跑测试确认通过**

```bash
cd cpq-backend && ./mvnw -q test -Dtest='P22PlatingCostHandlerTest' -DfailIfNoTests=false
```
预期：`Tests run: 4, Failures: 0, Errors: 0`

- [x] **Step 5: 跑核价侧回归**

```bash
cd cpq-backend && ./mvnw -q test -Dtest='UnitPriceFeeVersioningTest,PricingMergeVersioningTest,PricingVersioningImportE2ETest,PricingTemplateServiceTest' -DfailIfNoTests=false
```
预期：全绿。`PricingTemplateServiceTest` 覆盖模板表头导出，会验证 `TEMPLATE_HEADERS` 改动；若它对电镀成本的列数有硬断言，同步更新断言。

- [x] **Step 6: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/pricing/P22PlatingCostHandler.java \
        cpq-backend/src/test/java/com/cpq/basicdata/v6/pricing/P22PlatingCostHandlerTest.java
git commit -m "feat(repair-0802): 核价侧电镀成本支持投入料号，分组锚点切到销售料号(对齐 P15)"
git show --stat HEAD
```

---

## Task 4: Flyway 迁移 —— 存量清理 + `dj_view` + COMP-0063

**Files:**
- Create: `cpq-backend/src/main/resources/db/migration/V372__repair0802_plating_input_part_no.sql`

> 🔧 **实际落地订正（2026-08-02 执行时发现，以迁移文件 `V372__repair0802_plating_input_part_no.sql` 为准，本节下方 SQL 仅存历史草案）：**
> 1. **组件定位不能用 `code`**：dry-run 时实测 `component.code` 是各库独立自增的配置数据，**同一 code 在不同库指向不同组件** —— dev 库 `cpq_db_0724` 的电镀成本是 `COMP-0063`，而 test 库 `cpq_db` 的 `COMP-0063` 是「材质/元素/材料成本」组件（`COMP-0057` 才是电镀成本）。按 code 硬编码会改错组件。已改为语义锚点 `WHERE trim(coalesce(data_driver_path,''))='$dj_view'`。
> 2. **插入位置不能硬编码 `1.5/1.6`**：dry-run 输出证明 1.5/1.6 会把新列插到「销售料号」**之前**（该字段本身 ord=2），且两库该组件字段序还不一样（test 库销售料号在第 1 位）。已改为按「销售料号」的 `WITH ORDINALITY` 下标 `+0.1/+0.2` 动态计算。
> 3. 迁移号最终用 **V372**（371 已被 task0729 会话占用并应用到两库）。

**背景：** 三件事必须在同一个迁移里做完：

1. **存量清理**：报价侧 18 行旧语义数据 `finished_material_no IS NULL`。改造后 groupKey 多一维 → 重新导入会**另起版本组**而不会下线旧行，两组 `is_current=true` 并存 → 视图重复行。需求说明 §6 已授权"可以清空数据重新导入"。
2. **`dj_view` 重建**：现在 `material_no`/`hf_part_no` 都绑 `up.code`。改造后 `code` 是投入料号，若不改，核价电镀成本页签的「销售料号」列会显示投入料号。
3. **COMP-0063 `fields`**：新增两个 `BASIC_DATA` 字段展示投入料号与名称。

⚠️ **动手前先确认最大迁移版本号**（并发会话在抢号 —— 本计划原定的 V371 已于 2026-08-02 19:56 被 task0729 会话占用，故改用 V372）：
```bash
ls cpq-backend/src/main/resources/db/migration/ | sed -n 's/^V\([0-9]*\)__.*/\1/p' | sort -n | tail -3
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -At -c \
  "SELECT version FROM flyway_schema_history WHERE version::numeric >= 370 ORDER BY version::numeric;"
```
若 372 也已被占用，顺延到未占用号并同步改文件名与后续所有引用。
**注意 V368/V369/V370/V371 都是并发会话的副本（worktree 里存在只是为了让 Flyway validate 通过），不要提交它们。**

⚠️ **不要手工 `psql -f` 执行这个文件**——让 Quarkus 启动时的 `migrate-at-start` 跑（手工跑会导致 checksum 记录与后续启动对账不上）。

- [x] **Step 1: 写迁移文件**

创建 `cpq-backend/src/main/resources/db/migration/V372__repair0802_plating_input_part_no.sql`：

```sql
-- repair-0802：电镀费用/电镀成本增加「投入料号」「投入料号名称」列后的配套迁移。
--
-- 背景：unit_price 的两列语义是 code=零件料号(该费用项针对的零件)、finished_material_no=成品料号
-- （见 dev-docs/rule-0724-组件模板配置/4-页签属性与树.md §零件，2026-07-23 用户澄清）。电镀两个
-- handler 此前把销售料号写进 code 且不写 finished_material_no，与全表其余 14 个 handler 口径不一致。
--
-- 本迁移做三件事：①清理旧语义存量；②dj_view 改按新口径取数并暴露投入料号/名称；③COMP-0063 加两列。
-- 无 DDL：finished_material_no 早在 V276 的 uq_unit_price 13 维唯一键内。

-- ---- 1. 清理旧语义存量 ----
-- 旧行 finished_material_no IS NULL，与新 groupKey 不同组：重导时不会被下线，两组 is_current=true
-- 并存会让视图出重复行。需求说明 §6 已授权清空重导。
DELETE FROM unit_price
WHERE price_type = 'PLATING'
  AND finished_material_no IS NULL;

-- ---- 2. dj_view：material_no/hf_part_no 改绑成品轴，新增投入料号与名称 ----
-- :versionFilter 第 3 实参是「料号键列」（版本切换 override 的匹配键）。核价侧版本按销售料号切换，
-- 故随主轴一起从 up.code 改为 up.finished_material_no。
UPDATE component_sql_view
SET sql_template = $view$select
  up.finished_material_no as material_no,
  up.finished_material_no as hf_part_no,
  up.version_no        as view_version,
  max(up.production_no) as production_no,
  up.code              as input_part_no,
  max(coalesce(mm.material_name, mr.name)) as input_part_name,
  up.plating_scheme_no as plating_scheme_no,
  max(case when up.cost_type = '电镀加工费' then up.pricing_price end) as plating_proc_fee,
  max(case when up.cost_type = '电镀材料费' then up.pricing_price end) as plating_mat_fee,
  max(up.currency)     as currency,
  max(up.unit)         as unit,
  max(up.defect_rate)  as defect_rate
from unit_price up
  left join material_master mm on mm.material_no = up.code
  left join material_recipe mr on mr.code = up.code
where up.system_type = 'PRICING'
  and up.price_type = 'PLATING'
  and :versionFilter(up.is_current, up.version_no, up.finished_material_no)
group by up.finished_material_no, up.code, up.version_no, up.plating_scheme_no$view$
WHERE sql_view_name = 'dj_view';

-- ---- 3. COMP-0063 电镀成本：fields 新增「投入料号」「投入料号名称」 ----
-- 插在「销售料号」之后，其余字段保持原序与原 basic_data_path。
UPDATE component
SET fields = (
    SELECT jsonb_agg(elem ORDER BY ord)
    FROM (
        SELECT elem, ord
        FROM jsonb_array_elements(component.fields) WITH ORDINALITY AS t(elem, ord)
        WHERE elem->>'name' NOT IN ('投入料号', '投入料号名称')
        UNION ALL
        SELECT jsonb_build_object(
                   'name', '投入料号', 'notes', '', 'content', '',
                   'is_amount', false, 'field_type', 'BASIC_DATA', 'is_subtotal', false,
                   'basic_data_path', '$dj_view.input_part_no'),
               1.5
        UNION ALL
        SELECT jsonb_build_object(
                   'name', '投入料号名称', 'notes', '', 'content', '',
                   'is_amount', false, 'field_type', 'BASIC_DATA', 'is_subtotal', false,
                   'basic_data_path', '$dj_view.input_part_name'),
               1.6
    ) s
),
    column_count = (
        SELECT count(*) FROM (
            SELECT 1 FROM jsonb_array_elements(component.fields) elem
            WHERE elem->>'name' NOT IN ('投入料号', '投入料号名称')
            UNION ALL SELECT 1 UNION ALL SELECT 1
        ) c
    ),
    updated_at = now()
WHERE code = 'COMP-0063';
```

> ⚠️ 上面第 3 段用 `WITH ORDINALITY` + 小数序号插队。执行前**先在 dev 库上 dry-run 验证结果顺序与字段数**（见 Step 2），若 PG 对 `UNION ALL` 的 `ord` 类型推断报错（integer vs numeric），把 `1.5`/`1.6` 改成 `1.5::numeric`/`1.6::numeric` 且给第一个分支加 `ord::numeric`。

- [x] **Step 2: 在 dev 库 dry-run 第 3 段（只读验证，不落库）**

```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -At -c "
SELECT string_agg(elem->>'name', ' | ' ORDER BY ord)
FROM (
    SELECT elem, ord FROM component, jsonb_array_elements(component.fields) WITH ORDINALITY AS t(elem, ord)
    WHERE component.code='COMP-0063' AND elem->>'name' NOT IN ('投入料号','投入料号名称')
    UNION ALL SELECT jsonb_build_object('name','投入料号'), 1.5
    UNION ALL SELECT jsonb_build_object('name','投入料号名称'), 1.6
) s;"
```
预期输出：`生产料号 | 销售料号 | 投入料号 | 投入料号名称 | 电镀方案编号 | 版本编号 | 电镀加工费 | ...`
若报类型错误，按 Step 1 的提示加 `::numeric` 转型后重试。

- [x] **Step 3: 触发 Flyway 执行并确认成功**

迁移由测试启动时的 `migrate-at-start` 自动跑（作用于 **test 库 `cpq_db`**）：

```bash
cd cpq-backend && ./mvnw -q test -Dtest='P22PlatingCostHandlerTest' -DfailIfNoTests=false
```
预期：测试通过（Quarkus 起得来即代表迁移成功）。再确认历史记录：

```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -c \
  "SELECT version, description, success FROM flyway_schema_history WHERE version='372';"
```
预期：`success = t`

- [x] **Step 4: 验证 dj_view 与 COMP-0063 落地结果**

```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -c \
  "SELECT count(*) AS plating_orphans FROM unit_price WHERE price_type='PLATING' AND finished_material_no IS NULL;"
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -At -c \
  "SELECT sql_template FROM component_sql_view WHERE sql_view_name='dj_view';" | head -8
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -At -c \
  "SELECT jsonb_array_length(fields), (SELECT string_agg(e->>'name',' | ') FROM jsonb_array_elements(fields) e) FROM component WHERE code='COMP-0063';"
```
预期：孤儿数 `0`；`dj_view` 首行是 `up.finished_material_no as material_no`；COMP-0063 字段列表含「投入料号 | 投入料号名称」且总数 = 原字段数 + 2。

- [x] **Step 5: 提交**

```bash
git add cpq-backend/src/main/resources/db/migration/V372__repair0802_plating_input_part_no.sql
git commit -m "feat(repair-0802): 迁移 —— 清理电镀旧语义存量 + dj_view 改绑成品轴并暴露投入料号 + COMP-0063 加两列"
git show --stat HEAD
```
`git show --stat` 必须只有 1 个文件——**特别确认没有夹带 V368/V369/V370**。

---

## Task 5: 文档同步

**Files:**
- Modify: `docs/table/报价系统Excel导入落库方案.md`（§17 电镀费用）
- Modify: `docs/table/核价系统Excel导入落库方案.md`（§22 电镀成本）
- Modify: `docs/RECORD.md`（追加一条）

- [x] **Step 1: 改报价侧 §17**

打开 `docs/table/报价系统Excel导入落库方案.md`，定位 `### 17. 电镀费用`（约 641 行）。两张「Excel 列名 → 目标表字段」表格各做 4 处修改：

1. 新增首行：`| 投入料号 | \`code\` | ✅ | **非必填**；零件料号。空则回退销售料号（语义=电镀针对成品自身） |`
2. 新增次行：`| 投入料号名称 | — | ❌ | **非必填**；仅当投入料号为空时用于按名反查/铸号并 upsert \`material_master\`，不落 \`unit_price\` |`
3. 把 `| 宏丰料号 | \`code\` | ✅ | 元素代码/材料料号/零件号/耗材料号 |` 改为 `| 销售料号 | \`finished_material_no\` | ✅ | 成品料号（该费用上卷到的成品） |`
4. 把 `| 电镀方案编号 | \`plating_scheme_no\` | ✅ | 不为空时整行跳过不导入 |` 改为 `| 电镀方案编号 | — | ❌ | **不落库**，仅判断用：不为空时整行跳过不导入 |`；把 `| 版本编号 | \`version_no\` | ✅ | 价格版本 |` 改为 `| 版本编号 | — | ❌ | **不落库**，忽略 Excel 值，\`version_no\` 由系统自增（2000 起） |`

并把该节末尾的 📌 脚注替换为：

```markdown
> 📌 每行 Excel 拆分为两条 `unit_price` 记录，仅 `cost_type` 与 `pricing_price` 取值不同。当电镀方案编号不为空时整行跳过。客户编号由系统自动提供。
> 📌 **repair-0802**：`code` = 投入料号（零件料号）、`finished_material_no` = 销售料号（成品），与 §6/§7/§13 及 `unit_price` 全表口径一致。groupKey = (system_type, customer_no, price_type, cost_type, code, finished_material_no)，同一销售料号下可有多个投入料号各自独立成行。
```

- [x] **Step 2: 改核价侧 §22**

打开 `docs/table/核价系统Excel导入落库方案.md`，定位 `### 22. 电镀成本`（约 732 行）。两张表格同样处理：

1. 新增 `| 投入料号 | \`code\` | ✅ | **非必填**；零件料号。空则回退销售料号 |`
2. 新增 `| 投入料号名称 | — | ❌ | **非必填**；不落库、不参与解析（与 §15/§16/§17「品名」一致，渲染取名由视图 JOIN \`material_master\` 负责） |`
3. `宏丰料号/销售料号 → code` 改为 `销售料号 → finished_material_no`（成品料号 / 分组锚点）
4. 电镀方案编号、版本编号两行标注为不落库（同 Step 1 第 4 点）

并把该节末尾 📌 脚注中的 groupKey 描述改为：

```markdown
> 📌 **repair-0802**：`groupKey = (system_type, price_type=PLATING, finished_material_no)` —— 分组锚点从 `code` 改为销售料号，与 §15 来料加工费同构：同一销售料号下的全部投入料号 × 加工费/材料费共享一个版本组，任一变化整组升版。`code`（投入料号）进 content，组内去重键 = (code, cost_type)。
```

- [x] **Step 3: 追加 RECORD**

在 `docs/RECORD.md` 末尾追加：

```markdown
---

## [2026-08-02] 基础数据导入(repair-0802) - 电镀费用/电镀成本增加「投入料号」，unit_price 两列语义归队

**问题**：电镀两个 sheet 把销售料号写进 `unit_price.code` 且 `finished_material_no` 恒空，与该表其余 14 个 handler 的口径相反（`code`=零件料号、`finished_material_no`=成品料号，见 `dev-docs/rule-0724-组件模板配置/4-页签属性与树.md` §零件）。实测佐证：现役 18 行 `QUOTE/PLATING` 的 `code` 全是 `S-80011`，而该料号在 `material_master` 里 `material_name='投入零件1'`、`material_type='零件'`——**业务本就按零件维度记电镀费用，只是 sheet 没有对应的列**。后果：费用无法上卷成品、BOM 闭包类视图取不到数；若把该列绑给零件页签的 `partNoField`，`BomNodeTypeResolver` 会把成品误判成零件节点。

**改动**：两个 sheet 增加**非必填**的【投入料号】【投入料号名称】列。`Q17PlatingCostHandler` 照 Q06/Q07 三分支解析（有码沿用 / 仅名称反查铸号 + `material_master` upsert / 皆空回退销售料号），groupKey 加 `finished_material_no`；`P22PlatingCostHandler` 照 P15 范式把分组锚点从 `code` 切到 `finished_material_no`、`code` 进 content（核价侧不做名称反查，与 P15/P16/P17「品名」列一致）；`QuoteImportValidator` 新增 `validatePlatingCost` 把名称反查失败提前到 Phase 1 零写库阶段拦截；`V371` 清理 18 行旧语义存量 + `dj_view` 改绑成品轴并暴露 `input_part_no`/`input_part_name` + COMP-0063 加两个字段。

**关键决策**：①**「非必填」不等于「可跳过」**——`code` 是 NOT NULL 且在 `uq_unit_price` 13 维唯一键内，两列皆空必须回退为销售料号（语义=电镀针对成品自身），这与 Q15 组装加工费年降的退化范式一致，且使存量数据语义零漂移。②**零 DDL**——`finished_material_no` 早在 V276 就进了唯一键。③**存量必须清理而非迁移**：旧行 groupKey 缺一维，重导时不会被下线，两组 `is_current=true` 并存会让视图出重复行。④**两侧范式故意不同**：报价侧 gk 含 `code`（对齐 Q06/Q07），核价侧 gk 锚 `finished_material_no`（对齐 P15），各自与本侧邻居一致优先于跨侧统一。

**踩坑**：读料号列必须用 `row.exact("投入料号")` —— `getStr` 是 contains 匹配，会命中「投入料号名称」列并**静默取错值**。

**自检**：见本次交付的自检声明。
```

- [x] **Step 4: 提交**

```bash
git add docs/table/报价系统Excel导入落库方案.md docs/table/核价系统Excel导入落库方案.md docs/RECORD.md
git commit -m "docs(repair-0802): 两份落库方案 §17/§22 按投入料号新口径重写 + RECORD 登记"
git show --stat HEAD
```

---

## 收尾（全部 Task 完成后由主线执行，不属于任何单个 Task）

- [ ] （合并前）删除 worktree 内的并发会话迁移副本，确认它们从未被提交：
  ```bash
  rm cpq-backend/src/main/resources/db/migration/V368__task0729_price_adjust_schema.sql \
     cpq-backend/src/main/resources/db/migration/V369__task0729_material_element_price_and_views.sql \
     cpq-backend/src/main/resources/db/migration/V370__task0729_component_element_role_fields_backfill.sql
  git log --oneline --all -- 'cpq-backend/src/main/resources/db/migration/V36[89]*' \
                             'cpq-backend/src/main/resources/db/migration/V370*'
  ```
  第二条命令必须**无输出**（证明副本未入库）。
- [x] 全量确认本分支只改了预期文件：`git diff --stat master...HEAD`
- [x] 跑一轮汇总回归：
  ```bash
  cd cpq-backend && ./mvnw -q test -Dtest='Q17PlatingCostHandlerTest,QuoteImportValidatorPlatingTest,P22PlatingCostHandlerTest,Q06FixedProcessFeeHandlerTest,Q07IncomingOtherFeeHandlerTest,UnitPriceFeeVersioningTest,PricingMergeVersioningTest' -DfailIfNoTests=false
  ```
- [x] 出具自检声明（测试计数 + Flyway `success=t` + dj_view/COMP-0063 验证输出）
