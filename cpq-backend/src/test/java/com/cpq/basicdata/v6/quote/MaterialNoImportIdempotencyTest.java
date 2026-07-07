package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
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

/** C5：同一 Excel 连导两次，第二次按名称命中第一次生成的号，不再新增（决策 #2/#3）。 */
@QuarkusTest
class MaterialNoImportIdempotencyTest {

    @Inject MaterialBomMergeHandler handler;
    @Inject EntityManager em;

    static final String CUST = "TSTIDEM0615";
    static final String MAT  = "TESTIDEM0615";

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM material_bom_item WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_bom WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_no LIKE '9%' OR material_name LIKE 'IDEM-%'").executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = CUST; c.systemType = "QUOTE"; c.importedBy = null; return c;
    }
    private SheetRow matRow(int seq, String name) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", MAT); m.put("项次", String.valueOf(seq));
        m.put("投入料号名称", name); m.put("产出料号类型", "2.非银点类");
        m.put("材料毛重", "1.0"); m.put("重量单位", "KG");
        return new SheetRow(seq, m);
    }
    private long generatedCount() {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM material_master WHERE material_name LIKE 'IDEM-%'")
            .getSingleResult()).longValue();
    }
    @SuppressWarnings("unchecked")
    private List<String> generatedNos() {
        return em.createNativeQuery("SELECT material_no FROM material_master WHERE material_name LIKE 'IDEM-%' ORDER BY material_no")
            .getResultList();
    }

    @Test
    void reimportSameExcel_noNewMaterialNos() {
        handler.merge(List.of(matRow(1, "IDEM-A"), matRow(2, "IDEM-B")), List.of(), ctx());
        assertEquals(2L, generatedCount(), "首次生成 2 个报价料号");
        List<String> firstNos = generatedNos();
        firstNos.forEach(no -> assertTrue(no.matches("^\\d{4}-\\d{10}$"), "生成号需为报价料号格式(XXXX-YYMMNNNNNN)，实得: " + no));
        handler.merge(List.of(matRow(1, "IDEM-A"), matRow(2, "IDEM-B")), List.of(), ctx());
        assertEquals(2L, generatedCount(), "第二次按名称命中，不新增（C5）");
        assertEquals(firstNos, generatedNos(), "第二次导入沿用同一批已生成号，不重新铸号");
    }
}
