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
class Q10SelfProcessFeeResolveTest {

    @Inject Q10SelfProcessFeeHandler handler;
    @Inject EntityManager em;

    static final String CUST = "Q10CUST0617";
    static final String FIN  = "Q10FIN0617";
    static final String NAME = "Q10-耗材-0617";

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE customer_no=:c").setParameter("c", CUST).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_name=:n OR material_no LIKE '9%'")
          .setParameter("n", NAME).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = CUST; c.systemType = "QUOTE"; c.importedBy = null; return c;
    }
    private SheetRow row(String code, String name) {
        Map<String, String> m = new LinkedHashMap<>();
        if (code != null) m.put("投入料号", code);
        if (name != null) m.put("投入料号名称", name);
        m.put("宏丰料号", FIN); m.put("工序编号", "OP10"); m.put("项次（一级）", "1");
        m.put("值", "12.5"); m.put("货币", "CNY"); m.put("计价单位", "PCS");
        return new SheetRow(1, m);
    }
    private long masterCount(String name) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM material_master WHERE material_name=:n")
            .setParameter("n", name).getSingleResult()).longValue();
    }
    @SuppressWarnings("unchecked")
    private String upCode() {
        List<Object> rows = em.createNativeQuery("SELECT code FROM unit_price WHERE customer_no=:c AND is_current=TRUE")
            .setParameter("c", CUST).getResultList();
        return rows.stream().findFirst().map(Object::toString).orElse(null);
    }

    @Test
    void emptyCodeWithName_generatesRegistersAndUsesAsCode() {
        SheetImportResult r = handler.handle(List.of(row(null, NAME)), ctx());
        assertEquals(0, r.failedRows);
        assertEquals(1L, masterCount(NAME), "新料件登记进料号表(type=3)");
        assertEquals("9000000000", upCode(), "生成号回填为 unit_price.code");
    }

    @Test
    void emptyCodeAndEmptyName_recordsError() {
        SheetImportResult r = handler.handle(List.of(row(null, null)), ctx());
        assertTrue(r.failedRows >= 1);
        assertNull(upCode());
    }
}
