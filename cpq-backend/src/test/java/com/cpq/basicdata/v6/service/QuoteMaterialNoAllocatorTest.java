package com.cpq.basicdata.v6.service;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class QuoteMaterialNoAllocatorTest {
    @Inject QuoteMaterialNoAllocator alloc;
    @Inject EntityManager em;
    static final String C1 = "QMNT-CUST-1", C2 = "QMNT-CUST-2";

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM material_customer_map WHERE customer_no IN (:a,:b)").setParameter("a",C1).setParameter("b",C2).executeUpdate();
        em.createNativeQuery("DELETE FROM quote_material_no_seq WHERE customer_code IN (SELECT code FROM quote_customer_code WHERE customer_no IN (:a,:b))").setParameter("a",C1).setParameter("b",C2).executeUpdate();
        em.createNativeQuery("DELETE FROM quote_customer_code WHERE customer_no IN (:a,:b)").setParameter("a",C1).setParameter("b",C2).executeUpdate();
    }
    @BeforeEach void b(){cleanup();} @AfterEach void a(){cleanup();}

    @Test void customerCode_allocatesAndReuses() {
        String code = alloc.getOrAllocateCustomerCode(C1);
        assertTrue(code.matches("\\d{4}"), "四位数字码");
        assertEquals(code, alloc.getOrAllocateCustomerCode(C1), "同客户复用同码");
        assertNotEquals(code, alloc.getOrAllocateCustomerCode(C2), "不同客户不同码");
    }
    @Test void mint_formatAndSerialPerMonth() {
        String r1 = alloc.mintAndRegister(C1, "2607");
        String r2 = alloc.mintAndRegister(C1, "2607");
        String code = alloc.getOrAllocateCustomerCode(C1);
        assertEquals(code+"-2607000001", r1);
        assertEquals(code+"-2607000002", r2);
        assertEquals(code+"-2608000001", alloc.mintAndRegister(C1,"2608"), "跨月流水归1");
        Object[] row = (Object[]) em.createNativeQuery(
          "SELECT system_type, customer_product_no, production_no FROM material_customer_map WHERE material_no=:m")
          .setParameter("m", r1).getSingleResult();
        assertEquals("QUOTE", row[0]); assertNull(row[1]); assertNull(row[2]);
    }
    @Test void ensureRegistered_idempotent_sameCustomer() {
        String r = alloc.mintAndRegister(C1, "2607");
        alloc.ensureRegistered(C1, r);
        long n = ((Number) em.createNativeQuery("SELECT count(*) FROM material_customer_map WHERE material_no=:m").setParameter("m", r).getSingleResult()).longValue();
        assertEquals(1L, n);
    }
    @Test void ensureRegistered_crossCustomer_throws() {
        String r = alloc.mintAndRegister(C1, "2607");
        assertThrows(QuoteMaterialNoAllocator.CrossCustomerQuoteNoException.class,
            () -> alloc.ensureRegistered(C2, r), "别客户复用同报价料号→报错");
    }
}
