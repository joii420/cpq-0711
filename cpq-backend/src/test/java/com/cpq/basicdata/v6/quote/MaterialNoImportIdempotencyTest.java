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
    private long nineLeadingCount() {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM material_master WHERE material_no LIKE '9%'")
            .getSingleResult()).longValue();
    }

    @Test
    void reimportSameExcel_noNewMaterialNos() {
        handler.merge(List.of(matRow(1, "IDEM-A"), matRow(2, "IDEM-B")), List.of(), ctx());
        assertEquals(2L, nineLeadingCount(), "首次生成 2 个 9 字头号");
        handler.merge(List.of(matRow(1, "IDEM-A"), matRow(2, "IDEM-B")), List.of(), ctx());
        assertEquals(2L, nineLeadingCount(), "第二次按名称命中，不新增（C5）");
    }
}
