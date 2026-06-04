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
            List.of(matRow(1, 1, "TEST-MBM-C1", "0.5"), matRow(2, 2, "TEST-MBM-C2", "1.0")),
            List.of(asmRow(1, 1, "TEST-MBM-C1", "1"),   asmRow(2, 2, "TEST-MBM-C3", "2")),
            ctx());

        assertEquals(1L, count("SELECT count(*) FROM material_bom WHERE material_no=:m AND is_current=TRUE"));
        assertEquals("ASSEMBLY", em.createNativeQuery(
            "SELECT characteristic FROM material_bom WHERE material_no=:m AND is_current=TRUE")
            .setParameter("m", MAT).getSingleResult());
        assertEquals(3L, count("SELECT count(*) FROM material_bom_item WHERE material_no=:m AND is_current=TRUE"));
        assertEquals(0L, count("SELECT count(*) FROM material_bom_item WHERE material_no=:m AND is_current=TRUE AND characteristic IS NULL"));
    }

    @Test
    void materialOnlyThenBoth_flipsNullToHistory() {
        handler.merge(List.of(matRow(1, 1, "TEST-MBM-C1", "0.5")), List.of(), ctx());
        assertEquals(1L, count("SELECT count(*) FROM material_bom WHERE material_no=:m AND is_current=TRUE AND characteristic IS NULL"));

        handler.merge(List.of(matRow(1, 1, "TEST-MBM-C1", "0.5")), List.of(asmRow(1, 1, "TEST-MBM-C1", "1")), ctx());

        assertEquals(1L, count("SELECT count(*) FROM material_bom WHERE material_no=:m AND is_current=TRUE"));
        assertEquals("ASSEMBLY", em.createNativeQuery(
            "SELECT characteristic FROM material_bom WHERE material_no=:m AND is_current=TRUE")
            .setParameter("m", MAT).getSingleResult());
        assertEquals(1L, count("SELECT count(*) FROM material_bom WHERE material_no=:m AND is_current=FALSE AND characteristic IS NULL"));
    }

    @Test
    void cfgPrefixMaterial_rejected() {
        Map<String, String> m = new HashMap<>();
        m.put("宏丰料号", CFG); m.put("项次", "1"); m.put("投入料号", "TEST-MBM-C1"); m.put("材料毛重", "1");
        SheetImportResult r = handler.merge(List.of(new SheetRow(1, m)), List.of(), ctx());
        assertTrue(r.failedRows >= 1);
        assertEquals(0L, ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_bom WHERE material_no=:c").setParameter("c", CFG).getSingleResult()).longValue());
    }
}
