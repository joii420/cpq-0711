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
class Q13ComponentOtherFeeResolveTest {

    @Inject Q13ComponentOtherFeeHandler handler;
    @Inject EntityManager em;

    static final String CUST = "Q13CUST0617";
    static final String FIN  = "Q13FIN0617";
    static final String NAME = "Q13-组件-0617";

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
    private SheetRow row(String code, String name, String costType) {
        Map<String, String> m = new LinkedHashMap<>();
        if (code != null) m.put("组成件料号", code);
        if (name != null) m.put("组成件名称", name);
        if (costType != null) m.put("要素名称", costType);
        m.put("宏丰料号", FIN); m.put("工序编号", "OP1"); m.put("供应商编号", "SUP1");
        java.util.List<String[]> ord = new java.util.ArrayList<>();
        for (Map.Entry<String,String> e : m.entrySet()) ord.add(new String[]{e.getKey(), e.getValue()});
        ord.add(new String[]{"项次", "1"});
        ord.add(new String[]{"项次", "1"});
        ord.add(new String[]{"项次", "1"});
        ord.add(new String[]{"值", "8.0"}); ord.add(new String[]{"货币", "CNY"}); ord.add(new String[]{"计价单位", "PCS"});
        return new SheetRow(1, ord);
    }
    private long masterCount(String name) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM material_master WHERE material_name=:n")
            .setParameter("n", name).getSingleResult()).longValue();
    }
    private String upCode() {
        @SuppressWarnings("unchecked")
        java.util.List<Object> list = em.createNativeQuery("SELECT code FROM unit_price WHERE customer_no=:c AND is_current=TRUE")
            .setParameter("c", CUST).getResultList();
        return list.stream().findFirst().map(Object::toString).orElse(null);
    }

    @Test
    void emptyComponentNoWithName_generatesRegistersAndUsesAsCode() {
        SheetImportResult r = handler.handle(List.of(row(null, NAME, "包装费")), ctx());
        assertEquals(0, r.failedRows);
        assertEquals(1L, masterCount(NAME));
        assertEquals("9000000000", upCode());
    }

    @Test
    void emptyCostType_recordsError_noRegister() {
        SheetImportResult r = handler.handle(List.of(row(null, NAME, null)), ctx());
        assertTrue(r.failedRows >= 1);
        assertEquals(0L, masterCount(NAME), "要素名称为空先拦截，不登记料号表");
    }
}
