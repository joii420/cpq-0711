package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tesk-0709 Task 9 — P16+P17 / P19+P20 合并单版本组（防双升版）+ P22 电镀成本拆两条整批版本化。
 *
 * <p>P16(来料其他费用-比例)+P17(来料其他固定费用) 与 P19(成品其他比例费用)+P20(成品其他固定费用)
 * 各为两个独立 Sheet，但同 price_type 对同一锚点（finished_material_no / code）属于同一个版本组；
 * 本测试的核心验证目标（一票否决）：合并后同一锚点下 is_current=true 的 version_no 必须唯一
 * （不能因为两个 Sheet 各自独立 writeVersionedGroups 而产生双升版 / 互相覆盖）。
 *
 * <p>P22 电镀成本一行拆两条 cost_type（电镀加工费/电镀材料费），同 code 两条记录共享同一版本，
 * 任一变化整组一起升版。
 */
@QuarkusTest
class PricingMergeVersioningTest {

    @Inject IncomingOtherMergeHandler incomingOtherMerge;
    @Inject FinishedOtherMergeHandler finishedOtherMerge;
    @Inject P22PlatingCostHandler p22;
    @Inject EntityManager em;

    static final String INCOMING_FIN = "TEST-P1617-FIN";
    static final String FINISHED_CODE = "TEST-P1920-CODE";
    static final String PLATING_CODE = "TEST-P22-PLAT";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000916");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE finished_material_no=:f OR code IN (:c1,:c2)")
            .setParameter("f", INCOMING_FIN)
            .setParameter("c1", FINISHED_CODE)
            .setParameter("c2", PLATING_CODE)
            .executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = null; c.systemType = "PRICING"; c.importedBy = UID; return c;
    }

    // ====================== P16(比例) / P17(固定) 行构造 ======================

    private SheetRow p16RatioRow(String incomingCode, String costType, String seqNo, String ratio) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("来料料号", incomingCode);
        m.put("要素编号", costType);
        m.put("销售料号", INCOMING_FIN);
        m.put("二级项次", seqNo);
        m.put("比例", ratio);
        m.put("生产料号", "PN-" + incomingCode);
        return new SheetRow(1, m);
    }

    private SheetRow p17FixedRow(String incomingCode, String costType, String seqNo, String fee) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("来料料号", incomingCode);
        m.put("要素名称", costType);
        m.put("销售料号", INCOMING_FIN);
        m.put("二级项次", seqNo);
        m.put("费用", fee);
        m.put("币种", "CNY");
        m.put("计价单位", "EA");
        m.put("生产料号", "PN-" + incomingCode);
        return new SheetRow(1, m);
    }

    // ====================== P19(比例) / P20(固定) 行构造 ======================

    private SheetRow p19RatioRow(String costType, String seqNo, String ratio) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", FINISHED_CODE);
        m.put("要素名称", costType);
        m.put("项次", seqNo);
        m.put("比例", ratio);
        m.put("生产料号", "PN-" + FINISHED_CODE);
        return new SheetRow(1, m);
    }

    private SheetRow p20FixedRow(String costType, String seqNo, String fee) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", FINISHED_CODE);
        m.put("要素名称", costType);
        m.put("项次", seqNo);
        m.put("费用", fee);
        m.put("币种", "CNY");
        m.put("计价单位", "EA");
        m.put("生产料号", "PN-" + FINISHED_CODE);
        return new SheetRow(1, m);
    }

    // ====================== P22 行构造 ======================

    private SheetRow platingRow(String processFee, String materialFee) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", PLATING_CODE);
        m.put("电镀加工费", processFee);
        m.put("电镀材料费", materialFee);
        m.put("货币", "CNY");
        m.put("计价单位", "EA");
        m.put("不良率", "0.01");
        m.put("生产料号", "PN-" + PLATING_CODE);
        return new SheetRow(1, m);
    }

    private SheetRow platingSchemeRefRow() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", PLATING_CODE);
        m.put("电镀方案编号", "SCHEME-001");
        return new SheetRow(1, m);
    }

    // ====================== 查询工具 ======================

    private List<Object[]> currentRows(String priceType, String anchorCol, String anchorVal) {
        return em.createNativeQuery(
                "SELECT cost_type, version_no, is_current, cost_ratio, pricing_price FROM unit_price " +
                "WHERE system_type='PRICING' AND price_type=:pt AND " + anchorCol + "=:a AND is_current=true " +
                "ORDER BY cost_type")
            .setParameter("pt", priceType).setParameter("a", anchorVal).getResultList();
    }

    private long distinctCurrentVersionCount(String priceType, String anchorCol, String anchorVal) {
        return ((Number) em.createNativeQuery(
                "SELECT count(DISTINCT version_no) FROM unit_price " +
                "WHERE system_type='PRICING' AND price_type=:pt AND " + anchorCol + "=:a AND is_current=true")
            .setParameter("pt", priceType).setParameter("a", anchorVal).getSingleResult()).longValue();
    }

    private long flippedCount(String priceType, String anchorCol, String anchorVal, String version) {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM unit_price WHERE system_type='PRICING' AND price_type=:pt AND "
                + anchorCol + "=:a AND version_no=:v AND is_current=false")
            .setParameter("pt", priceType).setParameter("a", anchorVal).setParameter("v", version)
            .getSingleResult()).longValue();
    }

    // ====================== INCOMING_OTHER (P16+P17 合并) ======================

    @Test void incomingOther_mergedFirstImport_singleVersionGroup2000() {
        incomingOtherMerge.merge(
            List.of(p16RatioRow("TEST-P16-IN", "要素A", "1", "0.10")),
            List.of(p17FixedRow("TEST-P17-IN", "要素B", "2", "5.00")),
            ctx());

        List<Object[]> rows = currentRows(PricingPriceType.INCOMING_OTHER, "finished_material_no", INCOMING_FIN);
        assertEquals(2, rows.size(), "比例行+固定行应合并落在同一版本组");
        assertEquals(1, distinctCurrentVersionCount(PricingPriceType.INCOMING_OTHER, "finished_material_no", INCOMING_FIN),
            "一票否决：同一 finished_material_no 下 INCOMING_OTHER 的 is_current=true 版本号必须唯一");
        for (Object[] r : rows) assertEquals("2000", String.valueOf(r[1]));
    }

    @Test void incomingOther_reimportSameValues_doesNotBumpVersion() {
        incomingOtherMerge.merge(
            List.of(p16RatioRow("TEST-P16-IN", "要素A", "1", "0.10")),
            List.of(p17FixedRow("TEST-P17-IN", "要素B", "2", "5.00")),
            ctx());
        incomingOtherMerge.merge(
            List.of(p16RatioRow("TEST-P16-IN", "要素A", "1", "0.10")),
            List.of(p17FixedRow("TEST-P17-IN", "要素B", "2", "5.00")),
            ctx());

        List<Object[]> rows = currentRows(PricingPriceType.INCOMING_OTHER, "finished_material_no", INCOMING_FIN);
        assertEquals(2, rows.size());
        for (Object[] r : rows) assertEquals("2000", String.valueOf(r[1]));
        assertEquals(1, distinctCurrentVersionCount(PricingPriceType.INCOMING_OTHER, "finished_material_no", INCOMING_FIN));
    }

    @Test void incomingOther_changeRatioOnly_bumpsWholeGroupIncludingFixedRow() {
        incomingOtherMerge.merge(
            List.of(p16RatioRow("TEST-P16-IN", "要素A", "1", "0.10")),
            List.of(p17FixedRow("TEST-P17-IN", "要素B", "2", "5.00")),
            ctx());
        incomingOtherMerge.merge(
            List.of(p16RatioRow("TEST-P16-IN", "要素A", "1", "0.20")),   // 比例改动
            List.of(p17FixedRow("TEST-P17-IN", "要素B", "2", "5.00")),   // 固定费未变
            ctx());

        List<Object[]> rows = currentRows(PricingPriceType.INCOMING_OTHER, "finished_material_no", INCOMING_FIN);
        assertEquals(2, rows.size());
        assertEquals(1, distinctCurrentVersionCount(PricingPriceType.INCOMING_OTHER, "finished_material_no", INCOMING_FIN));
        for (Object[] r : rows) assertEquals("2001", String.valueOf(r[1]), "整组(含未变的固定行)应一起升版");
        assertEquals(2L, flippedCount(PricingPriceType.INCOMING_OTHER, "finished_material_no", INCOMING_FIN, "2000"),
            "旧版本两条(比例+固定)都应翻转保留");
    }

    @Test void incomingOther_changeFixedOnly_bumpsWholeGroupIncludingRatioRow() {
        incomingOtherMerge.merge(
            List.of(p16RatioRow("TEST-P16-IN", "要素A", "1", "0.10")),
            List.of(p17FixedRow("TEST-P17-IN", "要素B", "2", "5.00")),
            ctx());
        incomingOtherMerge.merge(
            List.of(p16RatioRow("TEST-P16-IN", "要素A", "1", "0.10")),   // 比例未变
            List.of(p17FixedRow("TEST-P17-IN", "要素B", "2", "8.00")),   // 固定费改动
            ctx());

        List<Object[]> rows = currentRows(PricingPriceType.INCOMING_OTHER, "finished_material_no", INCOMING_FIN);
        assertEquals(2, rows.size());
        assertEquals(1, distinctCurrentVersionCount(PricingPriceType.INCOMING_OTHER, "finished_material_no", INCOMING_FIN));
        for (Object[] r : rows) assertEquals("2001", String.valueOf(r[1]), "整组(含未变的比例行)应一起升版");
        assertEquals(2L, flippedCount(PricingPriceType.INCOMING_OTHER, "finished_material_no", INCOMING_FIN, "2000"));
    }

    // ====================== FINISHED_OTHER (P19+P20 合并) ======================

    @Test void finishedOther_mergedFirstImport_singleVersionGroup2000() {
        finishedOtherMerge.merge(
            List.of(p19RatioRow("要素C", "1", "0.15")),
            List.of(p20FixedRow("要素D", "2", "6.00")),
            ctx());

        List<Object[]> rows = currentRows(PricingPriceType.FINISHED_OTHER, "code", FINISHED_CODE);
        assertEquals(2, rows.size(), "比例行+固定行应合并落在同一版本组");
        assertEquals(1, distinctCurrentVersionCount(PricingPriceType.FINISHED_OTHER, "code", FINISHED_CODE),
            "一票否决：同一 code 下 FINISHED_OTHER 的 is_current=true 版本号必须唯一");
        for (Object[] r : rows) assertEquals("2000", String.valueOf(r[1]));
    }

    @Test void finishedOther_reimportSameValues_doesNotBumpVersion() {
        finishedOtherMerge.merge(
            List.of(p19RatioRow("要素C", "1", "0.15")),
            List.of(p20FixedRow("要素D", "2", "6.00")),
            ctx());
        finishedOtherMerge.merge(
            List.of(p19RatioRow("要素C", "1", "0.15")),
            List.of(p20FixedRow("要素D", "2", "6.00")),
            ctx());

        List<Object[]> rows = currentRows(PricingPriceType.FINISHED_OTHER, "code", FINISHED_CODE);
        assertEquals(2, rows.size());
        for (Object[] r : rows) assertEquals("2000", String.valueOf(r[1]));
    }

    @Test void finishedOther_changeRatioOnly_bumpsWholeGroupIncludingFixedRow() {
        finishedOtherMerge.merge(
            List.of(p19RatioRow("要素C", "1", "0.15")),
            List.of(p20FixedRow("要素D", "2", "6.00")),
            ctx());
        finishedOtherMerge.merge(
            List.of(p19RatioRow("要素C", "1", "0.30")),   // 比例改动
            List.of(p20FixedRow("要素D", "2", "6.00")),   // 固定费未变
            ctx());

        List<Object[]> rows = currentRows(PricingPriceType.FINISHED_OTHER, "code", FINISHED_CODE);
        assertEquals(2, rows.size());
        assertEquals(1, distinctCurrentVersionCount(PricingPriceType.FINISHED_OTHER, "code", FINISHED_CODE));
        for (Object[] r : rows) assertEquals("2001", String.valueOf(r[1]));
        assertEquals(2L, flippedCount(PricingPriceType.FINISHED_OTHER, "code", FINISHED_CODE, "2000"));
    }

    @Test void finishedOther_changeFixedOnly_bumpsWholeGroupIncludingRatioRow() {
        finishedOtherMerge.merge(
            List.of(p19RatioRow("要素C", "1", "0.15")),
            List.of(p20FixedRow("要素D", "2", "6.00")),
            ctx());
        finishedOtherMerge.merge(
            List.of(p19RatioRow("要素C", "1", "0.15")),   // 比例未变
            List.of(p20FixedRow("要素D", "2", "9.00")),   // 固定费改动
            ctx());

        List<Object[]> rows = currentRows(PricingPriceType.FINISHED_OTHER, "code", FINISHED_CODE);
        assertEquals(2, rows.size());
        assertEquals(1, distinctCurrentVersionCount(PricingPriceType.FINISHED_OTHER, "code", FINISHED_CODE));
        for (Object[] r : rows) assertEquals("2001", String.valueOf(r[1]));
        assertEquals(2L, flippedCount(PricingPriceType.FINISHED_OTHER, "code", FINISHED_CODE, "2000"));
    }

    // ====================== P22 电镀成本拆两条 ======================

    @Test void plating_oneRowSplitsIntoTwoCostTypes_sameVersion2000() {
        p22.handle(List.of(platingRow("10.00", "5.00")), ctx());

        List<Object[]> rows = currentRows(PricingPriceType.PLATING, "code", PLATING_CODE);
        assertEquals(2, rows.size(), "一行应拆成电镀加工费+电镀材料费两条");
        assertEquals(1, distinctCurrentVersionCount(PricingPriceType.PLATING, "code", PLATING_CODE));
        assertEquals("电镀加工费", rows.get(0)[0]);
        assertEquals("电镀材料费", rows.get(1)[0]);
        assertEquals(0, new BigDecimal("10.00").compareTo((BigDecimal) rows.get(0)[4]));
        assertEquals(0, new BigDecimal("5.00").compareTo((BigDecimal) rows.get(1)[4]));
    }

    @Test void plating_changeOneFee_bumpsWholeGroupTogether() {
        p22.handle(List.of(platingRow("10.00", "5.00")), ctx());
        p22.handle(List.of(platingRow("12.00", "5.00")), ctx());   // 只改加工费

        List<Object[]> rows = currentRows(PricingPriceType.PLATING, "code", PLATING_CODE);
        assertEquals(2, rows.size());
        assertEquals(1, distinctCurrentVersionCount(PricingPriceType.PLATING, "code", PLATING_CODE));
        for (Object[] r : rows) assertEquals("2001", String.valueOf(r[1]), "两条 cost_type 应整组一起升版");
        assertEquals(2L, flippedCount(PricingPriceType.PLATING, "code", PLATING_CODE, "2000"),
            "旧版本两条(加工费+材料费)都应翻转保留");
    }

    @Test void plating_schemeRefRow_isSkippedNotWrittenToUnitPrice() {
        var result = p22.handle(List.of(platingSchemeRefRow()), ctx());

        assertEquals(1, result.successRows);
        assertEquals(0, result.failedRows);
        List<Object[]> rows = currentRows(PricingPriceType.PLATING, "code", PLATING_CODE);
        assertEquals(0, rows.size(), "电镀方案引用行不落 unit_price");
    }
}
