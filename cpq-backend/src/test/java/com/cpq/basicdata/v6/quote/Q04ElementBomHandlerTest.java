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

/** Task 5 集成测试：Q04 物料与元素BOM 主从版本化（element_bom_item uq 含 characteristic → 多版本保留）。 */
@QuarkusTest
class Q04ElementBomHandlerTest {

    @Inject Q04ElementBomHandler handler;
    @Inject EntityManager em;

    static final String MAT = "TEST-Q04-MAT";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM element_bom_item WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM element_bom WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "C1"; c.systemType = "QUOTE"; c.importedBy = UID; return c;
    }
    private SheetRow row(int seq, String element, String content) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("投入料号", MAT); m.put("项次", String.valueOf(seq)); m.put("元素", element);
        m.put("组成含量", content); m.put("损耗率", "0.01"); m.put("毛用量", "1"); m.put("毛用量单位", "G"); m.put("净用量", "0.99");
        return new SheetRow(seq, m);
    }
    private String characteristic() {
        List<?> r = em.createNativeQuery(
            "SELECT characteristic FROM element_bom WHERE material_no=:m AND is_current=true LIMIT 1")
            .setParameter("m", MAT).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
    private long childCount(String extra) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM element_bom_item WHERE material_no=:m"
            + (extra == null ? "" : " AND " + extra)).setParameter("m", MAT).getSingleResult()).longValue();
    }

    @Test void importTwice_idempotent() {
        handler.handle(List.of(row(1, "Ag", "75"), row(2, "Ni", "25")), ctx());
        handler.handle(List.of(row(1, "Ag", "75"), row(2, "Ni", "25")), ctx());
        assertEquals("2000", characteristic());
        assertEquals(2L, childCount("is_current=true"));
        assertEquals(2L, childCount(null));
    }
    @Test void changeChild_bumps_keepsMultiVersion() {
        handler.handle(List.of(row(1, "Ag", "75"), row(2, "Ni", "25")), ctx());
        handler.handle(List.of(row(1, "Ag", "70"), row(2, "Ni", "30")), ctx());
        assertEquals("2001", characteristic());
        assertEquals(2L, childCount("is_current=true"), "仅新版本生效");
        assertEquals(4L, childCount(null), "旧版本子表保留（uq 含 characteristic）");
    }
}
