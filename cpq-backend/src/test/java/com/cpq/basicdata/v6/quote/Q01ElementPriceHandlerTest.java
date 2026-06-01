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

/** Task 3 集成测试：Q01 元素单价（ELEMENT, seq_no/customer_name 丢列）→ unit_price 版本化。 */
@QuarkusTest
class Q01ElementPriceHandlerTest {

    @Inject Q01ElementPriceHandler handler;
    @Inject EntityManager em;

    static final String CODE = "TEST-Q01-AG";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE code=:c").setParameter("c", CODE).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "C1"; c.systemType = "QUOTE"; c.importedBy = UID; return c;
    }
    private SheetRow row(String premium) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("元素代码", CODE); m.put("网址", "http://x"); m.put("网站名称", "X网");
        m.put("取用规则", "实时"); m.put("升水价", premium); m.put("货币", "CNY"); m.put("计价单位", "G");
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

    @Test void importTwice_idempotent() {
        handler.handle(List.of(row("3.5")), ctx());
        handler.handle(List.of(row("3.5")), ctx());
        assertEquals("2000", version());
        assertEquals(1L, total());
    }
    @Test void changeValue_bumps() {
        handler.handle(List.of(row("3.5")), ctx());
        handler.handle(List.of(row("4.2")), ctx());
        assertEquals("2001", version());
        assertEquals(2L, total());
    }
}
