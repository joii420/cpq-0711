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

/** Task 3 集成测试：Q07 来料其他费用（动态 cost_type）→ unit_price 版本化（幂等 + 升版 + cost_type 落库）。 */
@QuarkusTest
class Q07IncomingOtherFeeHandlerTest {

    @Inject Q07IncomingOtherFeeHandler handler;
    @Inject EntityManager em;

    static final String CODE = "TEST-Q07-CODE";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000007");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE code=:c").setParameter("c", CODE).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "C1"; c.systemType = "QUOTE"; c.importedBy = UID; return c;
    }
    private SheetRow row(int seq, String val) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("投入料号", CODE); m.put("要素名称", "运费"); m.put("宏丰料号", "TEST-Q07-FMN");
        m.put("项次", String.valueOf(seq)); m.put("值", val); m.put("货币", "CNY"); m.put("计价单位", "PCS");
        return new SheetRow(seq, m);
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
    @Test void importTwice_idempotent_costTypeStored() {
        handler.handle(List.of(row(1, "10")), ctx());
        handler.handle(List.of(row(1, "10")), ctx());
        assertEquals("2000", version());
        assertEquals(1L, total());
        Number n = (Number) em.createNativeQuery(
            "SELECT count(*) FROM unit_price WHERE code=:c AND cost_type='运费'")
            .setParameter("c", CODE).getSingleResult();
        assertEquals(1L, n.longValue(), "动态 cost_type 应落库为'运费'");
    }
    @Transactional
    @Test void changeValue_bumps() {
        handler.handle(List.of(row(1, "10")), ctx());
        handler.handle(List.of(row(1, "88")), ctx());
        assertEquals("2001", version());
        assertEquals(2L, total());
    }
}
