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
 * P12 模具工装成本 → tooling_cost 按 material_no 整批版本化（tesk-0709 Task 5）。
 * <p>groupKey = {system_type:"PRICING", material_no}；一个料号下所有模具明细
 * (process_no/seq_no/tooling_no 区分组内多行) 整批比对、整批升版；production_no 为描述列。
 */
@QuarkusTest
class P12ToolingCostVersioningTest {

    @Inject P12ToolingCostHandler p12;
    @Inject EntityManager em;

    static final String MAT = "TEST-P12TC";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000912");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM tooling_cost WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "_GLOBAL_"; c.systemType = "PRICING"; c.importedBy = UID; return c;
    }

    private SheetRow toolingRow(String proc, String seq, String toolingNo, String price) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", MAT);
        m.put("工序编号", proc);
        m.put("项次", seq);
        m.put("模具编号", toolingNo);
        m.put("模具工装成本单价", price);
        m.put("单个模具", "5.0");
        m.put("寿命", "10000");
        m.put("单循环产量", "4");
        m.put("币种", "CNY");
        m.put("计量单位", "套");
        m.put("是否有效", "是");
        m.put("生产料号", "PN-" + MAT);
        return new SheetRow(1, m);
    }

    private List<Object[]> currentRows() {
        return em.createNativeQuery(
                "SELECT tooling_no, tooling_unit_price, calc_version, is_current FROM tooling_cost " +
                "WHERE material_no=:m AND system_type='PRICING' AND is_current=true ORDER BY tooling_no")
            .setParameter("m", MAT).getResultList();
    }

    private String currentVersion() {
        List<?> r = em.createNativeQuery(
                "SELECT calc_version FROM tooling_cost WHERE material_no=:m AND system_type='PRICING' " +
                "AND is_current=true LIMIT 1")
            .setParameter("m", MAT).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }

    @Test void firstImport_writesAtVersion2000() {
        p12.handle(List.of(toolingRow("OP10", "1", "TOOL-A", "10.5")), ctx());

        List<Object[]> rows = currentRows();
        assertEquals(1, rows.size());
        assertEquals("TOOL-A", rows.get(0)[0]);
        assertEquals(0, new java.math.BigDecimal("10.5").compareTo((java.math.BigDecimal) rows.get(0)[1]));
        assertEquals("2000", String.valueOf(rows.get(0)[2]));
        assertTrue((Boolean) rows.get(0)[3]);
    }

    @Test void reimportSameValue_doesNotBumpVersion() {
        p12.handle(List.of(toolingRow("OP10", "1", "TOOL-A", "10.5")), ctx());
        p12.handle(List.of(toolingRow("OP10", "1", "TOOL-A", "10.5")), ctx());

        assertEquals("2000", currentVersion());
        assertEquals(1, currentRows().size());
    }

    @Test void reimportChangedUnitPrice_bumpsVersionAndFlipsOld() {
        p12.handle(List.of(toolingRow("OP10", "1", "TOOL-A", "10.5")), ctx());
        p12.handle(List.of(toolingRow("OP10", "1", "TOOL-A", "12.0")), ctx());

        assertEquals("2001", currentVersion());
        List<Object[]> current = currentRows();
        assertEquals(1, current.size());
        assertEquals(0, new java.math.BigDecimal("12.0").compareTo((java.math.BigDecimal) current.get(0)[1]));

        long oldCount = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM tooling_cost WHERE material_no=:m AND system_type='PRICING' " +
                "AND calc_version='2000' AND is_current=false")
            .setParameter("m", MAT).getSingleResult()).longValue();
        assertEquals(1L, oldCount, "旧版本行应翻转为 is_current=false 并保留");
    }

    @Test void sameMaterialMultipleToolingDetails_versionTogetherAsOneGroup() {
        p12.handle(List.of(
            toolingRow("OP10", "1", "TOOL-A", "10.5"),
            toolingRow("OP20", "2", "TOOL-B", "8.0")
        ), ctx());

        List<Object[]> firstRows = currentRows();
        assertEquals(2, firstRows.size(), "同料号两条模具明细应整批一组写入");
        assertEquals("2000", currentVersion());

        // 只改其中一条明细(TOOL-A 单价) → 整组一起升版，TOOL-B 未变行也随组升到新版本。
        p12.handle(List.of(
            toolingRow("OP10", "1", "TOOL-A", "13.0"),
            toolingRow("OP20", "2", "TOOL-B", "8.0")
        ), ctx());

        assertEquals("2001", currentVersion(), "组内任一明细变化应整批升版");
        List<Object[]> current = currentRows();
        assertEquals(2, current.size());
        for (Object[] r : current) {
            assertEquals("2001", String.valueOf(r[2]));
            assertTrue((Boolean) r[3]);
        }

        long oldCount = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM tooling_cost WHERE material_no=:m AND system_type='PRICING' " +
                "AND calc_version='2000' AND is_current=false")
            .setParameter("m", MAT).getSingleResult()).longValue();
        assertEquals(2L, oldCount, "旧版本两条明细都应翻转保留(非仅改动的那条)");
    }
}
