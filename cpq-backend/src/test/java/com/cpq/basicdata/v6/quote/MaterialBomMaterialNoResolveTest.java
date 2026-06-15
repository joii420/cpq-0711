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
        handler.merge(List.of(matRow(1, "EXIST-001", "RSV-MATCH")), List.of(), ctx());
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
