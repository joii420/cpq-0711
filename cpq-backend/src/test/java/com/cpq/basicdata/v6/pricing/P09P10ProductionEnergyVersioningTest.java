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

/**
 * P09(设备折旧成本→DEPRECIATION) / P10(生产设备能耗→ENERGY) 按 price_type 独立版本化写入
 * production_energy（tesk-0709 Task 2）。
 * <p>两个 handler 各自 groupKey = {system_type:"PRICING", material_no, price_type}，
 * process_no/unit_price/currency/unit 属 content 列，production_no 为不参与版本比对的描述列。
 */
@QuarkusTest
class P09P10ProductionEnergyVersioningTest {

    @Inject P09EquipmentDepreciationHandler p09;
    @Inject P10ProductionEnergyHandler p10;
    @Inject EntityManager em;

    static final String MAT = "TEST-P0910";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000910");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM production_energy WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "_GLOBAL_"; c.systemType = "PRICING"; c.importedBy = UID; return c;
    }

    private SheetRow depreciationRow(String proc, String price) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", MAT); m.put("工序编号", proc);
        m.put("折旧单价", price); m.put("币种", "CNY"); m.put("计量单位", "件"); m.put("生产料号", "PN-" + MAT);
        return new SheetRow(1, m);
    }

    private SheetRow energyRow(String proc, String price) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", MAT); m.put("工序编号", proc);
        m.put("生产能耗单价", price); m.put("币种", "CNY"); m.put("计量单位", "kWh"); m.put("生产料号", "PN-" + MAT);
        return new SheetRow(1, m);
    }

    private List<Object[]> currentRows(String priceType) {
        return em.createNativeQuery(
                "SELECT process_no, unit_price, calc_version, is_current FROM production_energy " +
                "WHERE material_no=:m AND system_type='PRICING' AND price_type=:pt AND is_current=true")
            .setParameter("m", MAT).setParameter("pt", priceType).getResultList();
    }

    private String currentVersion(String priceType) {
        List<?> r = em.createNativeQuery(
                "SELECT calc_version FROM production_energy WHERE material_no=:m AND system_type='PRICING' " +
                "AND price_type=:pt AND is_current=true LIMIT 1")
            .setParameter("m", MAT).setParameter("pt", priceType).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }

    @Test void firstImport_writesBothPriceTypes_atVersion2000() {
        p10.handle(List.of(energyRow("OP10", "2.5")), ctx());
        p09.handle(List.of(depreciationRow("OP10", "1.5")), ctx());

        List<Object[]> energyRows = currentRows("ENERGY");
        List<Object[]> depRows = currentRows("DEPRECIATION");
        assertEquals(1, energyRows.size());
        assertEquals(1, depRows.size());
        assertEquals("2000", String.valueOf(energyRows.get(0)[2]));
        assertEquals("2000", String.valueOf(depRows.get(0)[2]));
        assertTrue((Boolean) energyRows.get(0)[3]);
        assertTrue((Boolean) depRows.get(0)[3]);

        long total = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM production_energy WHERE material_no=:m AND is_current=true")
            .setParameter("m", MAT).getSingleResult()).longValue();
        assertEquals(2L, total, "同料号同工序 DEPRECIATION+ENERGY 各一行");
    }

    @Test void reimportSameValue_doesNotBumpVersion() {
        p10.handle(List.of(energyRow("OP10", "2.5")), ctx());
        p10.handle(List.of(energyRow("OP10", "2.5")), ctx());
        assertEquals("2000", currentVersion("ENERGY"));
        assertEquals(1L, currentRows("ENERGY").size());
    }

    @Test void reimportChangedUnitPrice_bumpsVersionAndFlipsOld() {
        p10.handle(List.of(energyRow("OP10", "2.5")), ctx());
        p10.handle(List.of(energyRow("OP10", "3.0")), ctx());

        assertEquals("2001", currentVersion("ENERGY"));
        List<Object[]> current = currentRows("ENERGY");
        assertEquals(1, current.size());
        assertEquals(0, new java.math.BigDecimal("3.0").compareTo((java.math.BigDecimal) current.get(0)[1]));

        long oldCount = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM production_energy WHERE material_no=:m AND price_type='ENERGY' " +
                "AND calc_version='2000' AND is_current=false")
            .setParameter("m", MAT).getSingleResult()).longValue();
        assertEquals(1L, oldCount, "旧版本行应翻转为 is_current=false 并保留");
    }

    @Test void depreciationVersionLine_isIndependentFromEnergy() {
        p10.handle(List.of(energyRow("OP10", "2.5")), ctx());
        p10.handle(List.of(energyRow("OP10", "3.0")), ctx()); // ENERGY 升到 2001
        p09.handle(List.of(depreciationRow("OP10", "1.5")), ctx()); // DEPRECIATION 独立仍是首版 2000

        assertEquals("2001", currentVersion("ENERGY"));
        assertEquals("2000", currentVersion("DEPRECIATION"));
    }
}
