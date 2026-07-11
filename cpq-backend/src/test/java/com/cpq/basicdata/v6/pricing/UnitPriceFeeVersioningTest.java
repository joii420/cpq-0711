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
 * P13/P14/P15/P18/P23 五个 unit_price 费用类 handler 整组版本化（tesk-0709 Task 8）。
 * <p>P13/P14/P18/P23 groupKey 锚点 = code(销售料号)；P15 特殊，锚点 = finished_material_no(销售料号)，
 * code 在 P15 语义是"来料料号"，进 content。
 */
@QuarkusTest
class UnitPriceFeeVersioningTest {

    @Inject P13ProductionConsumableHandler p13;
    @Inject P14PackagingConsumableHandler p14;
    @Inject P15IncomingProcessFeeHandler p15;
    @Inject P18SelfProcessAssemblyFeeHandler p18;
    @Inject P23OutsourceProcessFeeHandler p23;
    @Inject EntityManager em;

    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000913");

    @Transactional void cleanup() {
        em.createNativeQuery(
            "DELETE FROM unit_price WHERE system_type='PRICING' AND (code LIKE 'UPFV%' OR finished_material_no LIKE 'UPFV%')")
          .executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "_GLOBAL_"; c.systemType = "PRICING"; c.importedBy = UID; return c;
    }

    private String currentVersion(String priceType, String col, String val) {
        List<?> r = em.createNativeQuery(
                "SELECT version_no FROM unit_price WHERE system_type='PRICING' AND price_type=:pt " +
                "AND " + col + "=:v AND is_current=true LIMIT 1")
            .setParameter("pt", priceType).setParameter("v", val).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }

    private List<Object[]> currentRows(String priceType, String col, String val) {
        return em.createNativeQuery(
                "SELECT operation_no, pricing_price, version_no, is_current FROM unit_price " +
                "WHERE system_type='PRICING' AND price_type=:pt AND " + col + "=:v AND is_current=true " +
                "ORDER BY operation_no")
            .setParameter("pt", priceType).setParameter("v", val).getResultList();
    }

    private long flippedCount(String priceType, String col, String val, String version) {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM unit_price WHERE system_type='PRICING' AND price_type=:pt " +
                "AND " + col + "=:v AND version_no=:ver AND is_current=false")
            .setParameter("pt", priceType).setParameter("v", val).setParameter("ver", version)
            .getSingleResult()).longValue();
    }

    // ====================== P18 加工费&组装费（首版/复用/升版+flip 完整覆盖） ======================

    private SheetRow p18Row(String code, String op, String fee) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", code);
        m.put("工序编号", op);
        m.put("加工费", fee);
        m.put("币种", "CNY");
        m.put("计量单位", "件");
        m.put("不良率", "0.01");
        m.put("生产料号", "PN-" + code);
        return new SheetRow(1, m);
    }

    @Test void p18_firstImport_writesAtVersion2000() {
        String code = "UPFV18A";
        p18.handle(List.of(p18Row(code, "OP10", "1.5")), ctx());

        List<Object[]> rows = currentRows(PricingPriceType.SELF_PROCESS, "code", code);
        assertEquals(1, rows.size());
        assertEquals("OP10", rows.get(0)[0]);
        assertEquals(0, new BigDecimal("1.5").compareTo((BigDecimal) rows.get(0)[1]));
        assertEquals("2000", String.valueOf(rows.get(0)[2]));
        assertTrue((Boolean) rows.get(0)[3]);
    }

    @Test void p18_reimportSameValue_doesNotBumpVersion() {
        String code = "UPFV18B";
        p18.handle(List.of(p18Row(code, "OP10", "1.5")), ctx());
        p18.handle(List.of(p18Row(code, "OP10", "1.5")), ctx());

        assertEquals("2000", currentVersion(PricingPriceType.SELF_PROCESS, "code", code));
        assertEquals(1, currentRows(PricingPriceType.SELF_PROCESS, "code", code).size());
    }

    @Test void p18_reimportChangedPrice_bumpsVersionAndFlipsOld() {
        String code = "UPFV18C";
        p18.handle(List.of(p18Row(code, "OP10", "1.5")), ctx());
        p18.handle(List.of(p18Row(code, "OP10", "2.0")), ctx());

        assertEquals("2001", currentVersion(PricingPriceType.SELF_PROCESS, "code", code));
        List<Object[]> current = currentRows(PricingPriceType.SELF_PROCESS, "code", code);
        assertEquals(1, current.size());
        assertEquals(0, new BigDecimal("2.0").compareTo((BigDecimal) current.get(0)[1]));
        assertEquals(1L, flippedCount(PricingPriceType.SELF_PROCESS, "code", code, "2000"),
            "旧版本行应翻转为 is_current=false 并保留");
    }

    @Test void p18_duplicateRowsInSameBatch_dedupedWithinGroup() {
        String code = "UPFV18D";
        // 同批同 code+operation_no 重复两行（末值覆盖），不应撞 uq_unit_price
        p18.handle(List.of(p18Row(code, "OP10", "1.5"), p18Row(code, "OP10", "3.0")), ctx());

        List<Object[]> rows = currentRows(PricingPriceType.SELF_PROCESS, "code", code);
        assertEquals(1, rows.size(), "组内重复行应折叠为 1 条(末值覆盖)");
        assertEquals(0, new BigDecimal("3.0").compareTo((BigDecimal) rows.get(0)[1]));
    }

    // ====================== P15 来料加工费（锚点=finished_material_no 特殊场景） ======================

    private SheetRow p15Row(String incomingCode, String finishedMaterialNo, String fee) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("来料料号", incomingCode);
        m.put("销售料号", finishedMaterialNo);
        m.put("加工费", fee);
        m.put("币种", "CNY");
        m.put("计量单位", "件");
        m.put("损耗", "0.02");
        m.put("生产料号", "PN-" + finishedMaterialNo);
        return new SheetRow(1, m);
    }

    @Test void p15_firstImport_writesAtVersion2000() {
        String fm = "UPFV15FMA";
        String inCode = "UPFV15INA";
        p15.handle(List.of(p15Row(inCode, fm, "4.0")), ctx());

        List<?> r = em.createNativeQuery(
                "SELECT code, pricing_price, version_no, is_current FROM unit_price WHERE system_type='PRICING' " +
                "AND price_type=:pt AND finished_material_no=:fm AND is_current=true")
            .setParameter("pt", PricingPriceType.INCOMING_PROCESS).setParameter("fm", fm).getResultList();
        assertEquals(1, r.size());
        Object[] row = (Object[]) r.get(0);
        assertEquals(inCode, row[0]);
        assertEquals(0, new BigDecimal("4.0").compareTo((BigDecimal) row[1]));
        assertEquals("2000", String.valueOf(row[2]));
        assertTrue((Boolean) row[3]);
    }

    @Test void p15_reimportSameValue_doesNotBumpVersion() {
        String fm = "UPFV15FMB";
        String inCode = "UPFV15INB";
        p15.handle(List.of(p15Row(inCode, fm, "4.0")), ctx());
        p15.handle(List.of(p15Row(inCode, fm, "4.0")), ctx());

        assertEquals("2000", currentVersion(PricingPriceType.INCOMING_PROCESS, "finished_material_no", fm));
    }

    @Test void p15_reimportChangedPrice_bumpsVersionAndFlipsOld() {
        String fm = "UPFV15FMC";
        String inCode = "UPFV15INC";
        p15.handle(List.of(p15Row(inCode, fm, "4.0")), ctx());
        p15.handle(List.of(p15Row(inCode, fm, "5.5")), ctx());

        assertEquals("2001", currentVersion(PricingPriceType.INCOMING_PROCESS, "finished_material_no", fm));
        assertEquals(1L, flippedCount(PricingPriceType.INCOMING_PROCESS, "finished_material_no", fm, "2000"),
            "旧版本行应翻转为 is_current=false 并保留");
    }

    // ====================== P13 生产耗材BOM（首版轻用例） ======================

    @Test void p13_firstImport_writesAtVersion2000() {
        String code = "UPFV13A";
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", code);
        m.put("工序编号", "OP10");
        m.put("耗材成本单价", "0.8");
        m.put("币种", "CNY");
        m.put("计量单位", "个");
        m.put("生产料号", "PN-" + code);
        p13.handle(List.of(new SheetRow(1, m)), ctx());

        assertEquals("2000", currentVersion("CONSUMABLE", "code", code));
        List<Object[]> rows = currentRows("CONSUMABLE", "code", code);
        assertEquals(1, rows.size());
        assertEquals(0, new BigDecimal("0.8").compareTo((BigDecimal) rows.get(0)[1]));
    }

    // ====================== P14 包装材料BOM（首版轻用例） ======================

    @Test void p14_firstImport_writesAtVersion2000() {
        String code = "UPFV14A";
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", code);
        m.put("工序编号", "OP10");
        m.put("包装成本单价", "1.2");
        m.put("币种", "CNY");
        m.put("计量单位", "个");
        m.put("生产料号", "PN-" + code);
        p14.handle(List.of(new SheetRow(1, m)), ctx());

        assertEquals("2000", currentVersion(PricingPriceType.PACKAGING, "code", code));
        List<Object[]> rows = currentRows(PricingPriceType.PACKAGING, "code", code);
        assertEquals(1, rows.size());
        assertEquals(0, new BigDecimal("1.2").compareTo((BigDecimal) rows.get(0)[1]));
    }

    // ====================== P23 其他外加工成本（首版轻用例） ======================

    @Test void p23_firstImport_writesAtVersion2000() {
        String code = "UPFV23A";
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", code);
        m.put("工序编号", "OP10");
        m.put("外加工费用", "6.6");
        m.put("币种", "CNY");
        m.put("单位", "件");
        m.put("生产料号", "PN-" + code);
        p23.handle(List.of(new SheetRow(1, m)), ctx());

        assertEquals("2000", currentVersion(PricingPriceType.OUTSOURCE_PROCESS, "code", code));
        List<Object[]> rows = currentRows(PricingPriceType.OUTSOURCE_PROCESS, "code", code);
        assertEquals(1, rows.size());
        assertEquals(0, new BigDecimal("6.6").compareTo((BigDecimal) rows.get(0)[1]));
    }
}
