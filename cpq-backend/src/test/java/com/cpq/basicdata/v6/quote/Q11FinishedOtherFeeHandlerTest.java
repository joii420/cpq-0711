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

/** Task 3 集成测试：Q11 成品其他费用（动态 cost_type, 无 finished_material_no）→ unit_price 版本化。 */
@QuarkusTest
class Q11FinishedOtherFeeHandlerTest {

    @Inject Q11FinishedOtherFeeHandler handler;
    @Inject EntityManager em;

    static final String CODE = "TEST-Q11-CODE";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000011");

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
        m.put("宏丰料号", CODE); m.put("要素名称", "包装费");
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
    @Test void importTwice_idempotent() {
        handler.handle(List.of(row(1, "10"), row(2, "20")), ctx());
        handler.handle(List.of(row(1, "10"), row(2, "20")), ctx());
        assertEquals("2000", version());
        assertEquals(2L, total());
    }
    @Transactional
    @Test void changeValue_bumps() {
        handler.handle(List.of(row(1, "10"), row(2, "20")), ctx());
        handler.handle(List.of(row(1, "10"), row(2, "77")), ctx());
        assertEquals("2001", version());
        assertEquals(4L, total());
    }
}
