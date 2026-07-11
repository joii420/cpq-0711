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
 * P11 辅助设备能耗 → auxiliary_energy 按 material_no 整组版本化（tesk-0709 Task 4）。
 * <p>groupKey = {system_type:"PRICING", material_no}；process_no/non_production_energy_price/currency/unit
 * 属 content 列（多工序行同组一起版本比较/升版）；production_no 为不参与版本比对的描述列。
 */
@QuarkusTest
class P11AuxiliaryEnergyVersioningTest {

    @Inject P11AuxiliaryEnergyHandler p11;
    @Inject EntityManager em;

    static final String MAT = "TEST-P11-AUX";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000011");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM auxiliary_energy WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "_GLOBAL_"; c.systemType = "PRICING"; c.importedBy = UID; return c;
    }

    private SheetRow row(String proc, String price) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", MAT); m.put("工序编号", proc);
        m.put("非生产能耗单价", price); m.put("币种", "CNY"); m.put("计量单位", "kWh");
        m.put("生产料号", "PN-" + MAT);
        return new SheetRow(1, m);
    }

    private List<Object[]> currentRows() {
        return em.createNativeQuery(
                "SELECT process_no, non_production_energy_price, calc_version, is_current FROM auxiliary_energy " +
                "WHERE material_no=:m AND is_current=true")
            .setParameter("m", MAT).getResultList();
    }

    private String currentVersion() {
        List<?> r = em.createNativeQuery(
                "SELECT calc_version FROM auxiliary_energy WHERE material_no=:m AND is_current=true LIMIT 1")
            .setParameter("m", MAT).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }

    @Test void firstImport_writesAtVersion2000() {
        p11.handle(List.of(row("OP10", "1.2")), ctx());

        List<Object[]> current = currentRows();
        assertEquals(1, current.size());
        assertEquals("2000", String.valueOf(current.get(0)[2]));
        assertTrue((Boolean) current.get(0)[3]);
        assertEquals(0, new java.math.BigDecimal("1.2").compareTo((java.math.BigDecimal) current.get(0)[1]));
    }

    @Test void reimportSameValue_doesNotBumpVersion() {
        p11.handle(List.of(row("OP10", "1.2")), ctx());
        p11.handle(List.of(row("OP10", "1.2")), ctx());

        assertEquals("2000", currentVersion());
        assertEquals(1L, currentRows().size());
    }

    @Test void reimportChangedPrice_bumpsVersionAndFlipsOld() {
        p11.handle(List.of(row("OP10", "1.2")), ctx());
        p11.handle(List.of(row("OP10", "1.8")), ctx());

        assertEquals("2001", currentVersion());
        List<Object[]> current = currentRows();
        assertEquals(1, current.size());
        assertEquals(0, new java.math.BigDecimal("1.8").compareTo((java.math.BigDecimal) current.get(0)[1]));

        long oldCount = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM auxiliary_energy WHERE material_no=:m " +
                "AND calc_version='2000' AND is_current=false")
            .setParameter("m", MAT).getSingleResult()).longValue();
        assertEquals(1L, oldCount, "旧版本行应翻转为 is_current=false 并保留");
    }

    @Test void multiProcessRows_sameGroup_versionedTogether() {
        p11.handle(List.of(row("OP10", "1.2"), row("OP20", "2.4")), ctx());
        List<Object[]> v1 = currentRows();
        assertEquals(2, v1.size());
        assertEquals("2000", currentVersion());

        // 改其中一个工序单价 → 整组升版，两行都在新版本
        p11.handle(List.of(row("OP10", "1.5"), row("OP20", "2.4")), ctx());
        assertEquals("2001", currentVersion());
        List<Object[]> v2 = currentRows();
        assertEquals(2, v2.size());
        for (Object[] r : v2) assertEquals("2001", String.valueOf(r[2]));
    }
}
