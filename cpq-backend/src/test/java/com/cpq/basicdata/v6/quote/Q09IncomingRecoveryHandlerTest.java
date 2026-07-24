package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Task 3 集成测试：Q09 来料回收折扣 → unit_price 版本化（幂等 + 升版）。 */
@QuarkusTest
class Q09IncomingRecoveryHandlerTest {

    @Inject Q09IncomingRecoveryHandler handler;
    @Inject EntityManager em;

    static final String CODE = "TEST-Q09-CODE";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000009");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE code=:c").setParameter("c", CODE).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "C1"; c.systemType = "QUOTE"; c.importedBy = UID; return c;
    }
    private SheetRow row(String ratio) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("投入料号", CODE); m.put("宏丰料号", "TEST-Q09-FMN"); m.put("回收折扣", ratio);
        return new SheetRow(1, m);
    }
    private String version() {
        List<?> r = em.createNativeQuery(
            "SELECT version_no FROM unit_price WHERE code=:c AND is_current=true LIMIT 1")
            .setParameter("c", CODE).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
    private long total() {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM unit_price WHERE code=:c")
            .setParameter("c", CODE).getSingleResult()).longValue();
    }

    @Transactional
    @Test void importTwice_idempotent() {
        handler.handle(List.of(row("0.5")), ctx());
        handler.handle(List.of(row("0.5")), ctx());
        assertEquals("2000", version());
        assertEquals(1L, total());
    }
    @Transactional
    @Test void changeValue_bumps() {
        handler.handle(List.of(row("0.5")), ctx());
        handler.handle(List.of(row("0.7")), ctx());
        assertEquals("2001", version());
        assertEquals(2L, total());
    }
}
