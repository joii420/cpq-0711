package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.service.ProcessNoResolver;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-0727 T2 集成测试：{@link QuoteImportValidator} 新增的「组装加工费」/「组装加工费年降」
 * Phase 1 工序解析校验（{@link QuoteImportValidator#validate}）。
 */
@QuarkusTest
class QuoteImportValidatorTest {

    @Inject QuoteImportValidator validator;
    @Inject EntityManager em;

    static final String PROC_NO = "TEST-QIV-Z100";
    static final String PROC_NAME = "TEST-QIV-焊接";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM process_master WHERE process_no=:n")
            .setParameter("n", PROC_NO).executeUpdate();
    }
    @Transactional void seedProcess() {
        em.createNativeQuery("INSERT INTO process_master (process_no, process_name) VALUES (:no, :name)")
            .setParameter("no", PROC_NO).setParameter("name", PROC_NAME).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); seedProcess(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "TEST-QIV-CUST"; c.systemType = "QUOTE"; c.importedBy = UID;
        return c;
    }

    private static SheetRow row(int rowNo, Map<String, String> cells) {
        return new SheetRow(rowNo, cells);
    }

    /** AC①：组装加工费全部可解析 → hasErrors()=false 且 assemblyProcessNo 填充正确。 */
    @Test
    void assemblyProcess_allResolvable_noErrors_outcomeFilled() {
        Map<String, String> r1 = new LinkedHashMap<>();
        r1.put("宏丰料号", "TEST-QIV-MAT-1");
        r1.put("组装工序", PROC_NAME);   // 按名称匹配
        Map<String, List<SheetRow>> sheets = Map.of("组装加工费", List.of(row(1, r1)));

        QuoteImportValidator.Outcome out = validator.validate(sheets, ctx());

        assertFalse(out.hasErrors());
        ProcessNoResolver.Resolved resolved =
            out.assemblyProcessNo.get(List.of("组装加工费", "TEST-QIV-MAT-1", PROC_NAME));
        assertNotNull(resolved, "已解析结果应按 (sheetName, 料号, 原始值) 精确取回");
        assertEquals(PROC_NO, resolved.processNo());
        assertEquals(PROC_NAME, resolved.processName());

        var sheetResult = out.bySheet.get("组装加工费");
        assertEquals(1, sheetResult.totalRows);
        assertEquals(1, sheetResult.successRows);
        assertEquals(0, sheetResult.failedRows);
    }

    /**
     * AC②/技术总监裁决①：料号 A 有 3 行（焊接✅/点胶❌/抛光❌）→ errors 只加 1 条（文案含
     * "点胶、抛光"）；failedRows 补齐为该料号全部行数（含本来能解析成功的"焊接"那行）＝3；
     * successRows 不含该料号任何行＝0——"组级拒绝"＝整个料号作废，不是"只有失败的那几行作废"。
     */
    @Test
    void assemblyProcess_multipleUnresolvable_wholeMaterialVoided_countsIncludeResolvableRow() {
        Map<String, String> r1 = new LinkedHashMap<>();
        r1.put("宏丰料号", "TEST-QIV-MAT-2");
        r1.put("组装工序", PROC_NAME);              // 焊接✅ 可解析
        Map<String, String> r2 = new LinkedHashMap<>();
        r2.put("宏丰料号", "TEST-QIV-MAT-2");
        r2.put("组装工序", "TEST-QIV-点胶");          // 点胶❌ 不可解析
        Map<String, String> r3 = new LinkedHashMap<>();
        r3.put("宏丰料号", "TEST-QIV-MAT-2");
        r3.put("组装工序", "TEST-QIV-抛光");          // 抛光❌ 不可解析
        Map<String, List<SheetRow>> sheets =
            Map.of("组装加工费", List.of(row(1, r1), row(2, r2), row(3, r3)));

        QuoteImportValidator.Outcome out = validator.validate(sheets, ctx());

        assertTrue(out.hasErrors());
        var sheetResult = out.bySheet.get("组装加工费");
        assertEquals(3, sheetResult.totalRows);
        assertEquals(1, sheetResult.errors.size(), "同一料号下多道工序失败应聚合只报一条");
        assertEquals(3, sheetResult.failedRows,
            "组级拒绝：failedRows 应等于该料号全部行数（含本来能解析成功的焊接行），不是失败料号数");
        assertEquals(0, sheetResult.successRows, "该料号任何行都不计入 successRows（整个料号作废）");
        assertEquals(sheetResult.totalRows, sheetResult.successRows + sheetResult.failedRows,
            "totalRows == successRows + failedRows 不变量须成立");

        String msg = sheetResult.errors.get(0).message;
        assertTrue(msg.contains("TEST-QIV-MAT-2"), "错误文案须含料号: " + msg);
        assertTrue(msg.contains("TEST-QIV-点胶") && msg.contains("TEST-QIV-抛光"),
            "错误文案须列出全部失败的工序名(顿号分隔): " + msg);
        assertTrue(msg.contains("未在工序主数据中登记"), "错误文案须含固定提示语: " + msg);
        // 该料号已可解析的"焊接"那道工序也不应残留进 assemblyProcessNo（不变量：不允许部分落库）。
        assertTrue(out.assemblyProcessNo.keySet().stream()
            .noneMatch(k -> k.get(1).equals("TEST-QIV-MAT-2")));
    }

    /**
     * 技术总监裁决②：「销售料号」/「组装工序」必填校验的文案必须与"未登记"聚合文案可区分，
     * 沿用既有 {@code column="宏丰料号/工序编号"}/{@code msg="必填项为空"}（照抄 Q14:55），
     * 不并入聚合消息；且该行独立计 1 次 failedRows，不影响同料号其它行的组级判定。
     */
    @Test
    void assemblyProcess_missingRequiredField_keepsDistinctMessage_notMergedIntoAggregation() {
        Map<String, String> r1 = new LinkedHashMap<>();
        r1.put("宏丰料号", "TEST-QIV-MAT-5");
        r1.put("组装工序", PROC_NAME);   // 可解析，独立一个料号，不受下面缺字段行影响
        Map<String, String> r2 = new LinkedHashMap<>();
        r2.put("宏丰料号", "TEST-QIV-MAT-6");
        // 「组装工序」列缺失 → 必填校验单独报错，不进入按料号聚合的分支
        Map<String, List<SheetRow>> sheets = Map.of("组装加工费", List.of(row(1, r1), row(2, r2)));

        QuoteImportValidator.Outcome out = validator.validate(sheets, ctx());

        assertTrue(out.hasErrors());
        var sheetResult = out.bySheet.get("组装加工费");
        assertEquals(2, sheetResult.totalRows);
        assertEquals(1, sheetResult.successRows, "MAT-5 整料号可解析，不受 MAT-6 缺字段影响");
        assertEquals(1, sheetResult.failedRows, "缺字段行独立计 1 次，不聚合");
        assertEquals(1, sheetResult.errors.size(), "MAT-5 可解析无错误，仅缺字段行产生 1 条错误");
        boolean hasRequiredFieldError = sheetResult.errors.stream()
            .anyMatch(err -> "宏丰料号/工序编号".equals(err.column) && "必填项为空".equals(err.message));
        assertTrue(hasRequiredFieldError, "必填校验文案须沿用既有 column/message，不与聚合文案混淆");
        assertTrue(out.assemblyProcessNo.containsKey(List.of("组装加工费", "TEST-QIV-MAT-5", PROC_NAME)));
    }

    /** AC③：「组装加工费年降」工序列为空 → 不报错、不进 assemblyProcessNo。 */
    @Test
    void assemblyAnnualDiscount_blankProcessColumn_notAnError_notInMap() {
        Map<String, String> r1 = new LinkedHashMap<>();
        r1.put("宏丰料号", "TEST-QIV-MAT-3");   // 「组装工序」列干脆不填
        Map<String, List<SheetRow>> sheets = Map.of("组装加工费年降", List.of(row(1, r1)));

        QuoteImportValidator.Outcome out = validator.validate(sheets, ctx());

        assertFalse(out.hasErrors());
        var sheetResult = out.bySheet.get("组装加工费年降");
        assertEquals(1, sheetResult.totalRows);
        assertEquals(1, sheetResult.successRows);
        assertEquals(0, sheetResult.failedRows);
        assertTrue(out.assemblyProcessNo.isEmpty(), "工序列为空时不应进入 assemblyProcessNo");
    }

    /** 组装加工费年降同样支持解析成功场景（与 Q14 key 空间不冲突）。 */
    @Test
    void assemblyAnnualDiscount_resolvable_outcomeFilledUnderOwnSheetKey() {
        Map<String, String> r1 = new LinkedHashMap<>();
        r1.put("宏丰料号", "TEST-QIV-MAT-4");
        r1.put("组装工序", PROC_NO);   // 按编号匹配
        Map<String, List<SheetRow>> sheets = Map.of("组装加工费年降", List.of(row(1, r1)));

        QuoteImportValidator.Outcome out = validator.validate(sheets, ctx());

        assertFalse(out.hasErrors());
        ProcessNoResolver.Resolved resolved =
            out.assemblyProcessNo.get(List.of("组装加工费年降", "TEST-QIV-MAT-4", PROC_NO));
        assertNotNull(resolved);
        assertEquals(PROC_NO, resolved.processNo());
        // Q14 与 Q15 各自的 key 空间以 sheetName 首段区分，不应互相串号。
        assertNull(out.assemblyProcessNo.get(List.of("组装加工费", "TEST-QIV-MAT-4", PROC_NO)));
    }

    // ============ task-0730：来料回收折扣「值 / 回收折扣（%）」必填其一（Phase 1 零写库拦截） ============

    private static Map<String, String> recycleRow(String ratio, String value) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", "TEST-QIV-MAT-R");
        m.put("投入料号", "TEST-QIV-IN-R");
        if (ratio != null) m.put("回收折扣（%）", ratio);
        if (value != null) m.put("值", value);
        return m;
    }

    /** 两者同时为空 → Phase 1 直接拦下（整单不进写入阶段）。 */
    @Test
    void incomingRecycle_bothValueAndRatioBlank_rejectedInPhase1() {
        Map<String, List<SheetRow>> sheets =
            Map.of("来料回收折扣", List.of(row(1, recycleRow(null, null))));

        QuoteImportValidator.Outcome out = validator.validate(sheets, ctx());

        assertTrue(out.hasErrors());
        var r = out.bySheet.get("来料回收折扣");
        assertEquals(1, r.totalRows);
        assertEquals(0, r.successRows);
        assertEquals(1, r.failedRows);
        assertTrue(r.errors.stream().anyMatch(e ->
            "值/回收折扣（%）".equals(e.column) && e.message.contains("必填其一")),
            "错误须指明是「值/回收折扣（%）」必填其一");
    }

    /** 只填其一、或两者并存，都合法。 */
    @Test
    void incomingRecycle_eitherOrBoth_accepted() {
        Map<String, List<SheetRow>> sheets = Map.of("来料回收折扣", List.of(
            row(1, recycleRow("20", null)),     // 只有折扣%
            row(2, recycleRow(null, "3.5")),    // 只有值
            row(3, recycleRow("20", "3.5"))));  // 并存

        QuoteImportValidator.Outcome out = validator.validate(sheets, ctx());

        assertFalse(out.hasErrors());
        assertEquals(3, out.bySheet.get("来料回收折扣").successRows);
    }

    /** 隔壁两张来料表不受影响（requireValueOrRatio=false）：金额列全空仍放行。 */
    @Test
    void otherIncomingSheets_unaffectedByValueOrRatioRule() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", "TEST-QIV-MAT-R2");
        m.put("投入料号", "TEST-QIV-IN-R2");
        Map<String, List<SheetRow>> sheets = Map.of("来料其他费用", List.of(row(1, m)));

        QuoteImportValidator.Outcome out = validator.validate(sheets, ctx());

        assertFalse(out.hasErrors(), "来料其他费用不受新规则约束");
        assertEquals(1, out.bySheet.get("来料其他费用").successRows);
    }
}
