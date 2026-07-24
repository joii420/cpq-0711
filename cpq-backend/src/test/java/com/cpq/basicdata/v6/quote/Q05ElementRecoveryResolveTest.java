package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
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
class Q05ElementRecoveryResolveTest {

    @Inject Q05ElementRecoveryHandler handler;
    @Inject MaterialMasterRepository repo;
    @Inject EntityManager em;

    static final String CUST = "Q05CUST0617";
    static final String MAT  = "Q05MAT0617";
    static final String NAME = "Q05-母件-0617";

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM element_bom_item WHERE customer_no=:c").setParameter("c", CUST).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_name=:n OR material_no LIKE '9%'")
          .setParameter("n", NAME).executeUpdate();
    }
    @Transactional void seed() {
        repo.upsertByMaterialNo(MAT, NAME, null,null,null,"3",null,null,null, null, null, true);
        em.createNativeQuery(
            "INSERT INTO element_bom_item (system_type, customer_no, material_no, component_no, characteristic, " +
            " is_current, created_at, updated_at) VALUES ('QUOTE', :c, :m, 'Ag', '2000', TRUE, NOW(), NOW())")
          .setParameter("c", CUST).setParameter("m", MAT).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = CUST; c.systemType = "QUOTE"; c.importedBy = null; return c;
    }
    private SheetRow row(String inputNo, String inputName, String element, String discount) {
        Map<String, String> m = new LinkedHashMap<>();
        if (inputNo != null) m.put("投入料号", inputNo);
        if (inputName != null) m.put("投入料号名称", inputName);
        m.put("元素", element); m.put("回收折扣", discount);
        return new SheetRow(1, m);
    }
    private java.math.BigDecimal discountOf() {
        return (java.math.BigDecimal) em.createNativeQuery(
            "SELECT recovery_discount FROM element_bom_item WHERE customer_no=:c AND material_no=:m AND component_no='Ag'")
            .setParameter("c", CUST).setParameter("m", MAT).getSingleResult();
    }

    @Transactional
    @Test
    void emptyNoMatchedByName_updatesExisting_noGenerate() {
        seed();
        SheetImportResult r = handler.handle(List.of(row(null, NAME, "Ag", "70")), ctx());
        assertEquals(0, r.failedRows);
        assertEquals(0, new java.math.BigDecimal("70").compareTo(discountOf()), "按名匹配到母件料号→UPDATE 成功");
        long gen = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_master WHERE material_no LIKE '9%'").getSingleResult()).longValue();
        assertEquals(0L, gen, "更新型只匹配不生成");
    }

    @Transactional
    @Test
    void emptyNoUnmatchedName_recordsError() {
        seed();
        SheetImportResult r = handler.handle(List.of(row(null, "查无此名0617", "Ag", "70")), ctx());
        assertTrue(r.failedRows >= 1, "名称查不到料号→记错误，不生成");
    }
}
