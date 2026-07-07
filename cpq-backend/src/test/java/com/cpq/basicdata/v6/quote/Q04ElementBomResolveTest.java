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

@QuarkusTest
class Q04ElementBomResolveTest {

    @Inject Q04ElementBomHandler handler;
    @Inject EntityManager em;

    static final String CUST = "Q04CUST0617";
    static final String NAME = "Q04-母件-0617";

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM element_bom_item WHERE customer_no=:c").setParameter("c", CUST).executeUpdate();
        em.createNativeQuery("DELETE FROM element_bom WHERE customer_no=:c").setParameter("c", CUST).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_name=:n OR material_no LIKE '9%'")
          .setParameter("n", NAME).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = CUST; c.systemType = "QUOTE"; c.importedBy = null; return c;
    }
    private SheetRow row(String inputNo, String inputName, String element) {
        Map<String, String> m = new LinkedHashMap<>();
        if (inputNo != null) m.put("投入料号", inputNo);
        if (inputName != null) m.put("投入料号名称", inputName);
        m.put("项次", "1"); m.put("元素", element); m.put("组成含量", "75");
        return new SheetRow(1, m);
    }
    private long masterCount(String name) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM material_master WHERE material_name=:n")
            .setParameter("n", name).getSingleResult()).longValue();
    }
    private String bomMaterialNo() {
        List<?> rows = em.createNativeQuery("SELECT material_no FROM element_bom WHERE customer_no=:c AND is_current=TRUE")
            .setParameter("c", CUST).getResultList();
        return rows.isEmpty() ? null : String.valueOf(rows.get(0));
    }

    @Test
    void emptyNoWithName_generatesRegistersAndUsesAsMaterialNo() {
        SheetImportResult r = handler.handle(List.of(row(null, NAME, "Ag")), ctx());
        assertEquals(0, r.failedRows);
        assertEquals(1L, masterCount(NAME), "新料件登记进料号表");
        String generated = bomMaterialNo();
        assertNotNull(generated, "生成号回填为 element_bom.material_no");
        assertTrue(generated.matches("^\\d{4}-\\d{10}$"), "生成号需为报价料号格式(XXXX-YYMMNNNNNN)，实得: " + generated);
        String type = em.createNativeQuery("SELECT material_type FROM material_master WHERE material_name=:n")
            .setParameter("n", NAME).getSingleResult().toString();
        assertEquals("组成件", type, "material_type 统一写汉字「组成件」(对齐 master §12 约定)");
    }

    @Test
    void emptyNoAndEmptyName_recordsError() {
        SheetImportResult r = handler.handle(List.of(row(null, null, "Ag")), ctx());
        assertTrue(r.failedRows >= 1);
        assertNull(bomMaterialNo());
    }
}
