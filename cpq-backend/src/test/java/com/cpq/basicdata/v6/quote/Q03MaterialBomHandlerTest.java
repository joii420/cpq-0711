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

/** Task 5 集成测试：Q03 物料BOM 主从版本化（material_bom_item uq 无版本 → 仅当前版本，§5.3）。 */
@QuarkusTest
class Q03MaterialBomHandlerTest {

    @Inject Q03MaterialBomHandler handler;
    @Inject EntityManager em;

    static final String MAT = "TEST-Q03-MAT";
    static final String COMP = "TEST-Q03-COMP";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM material_bom_item WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_bom WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_no=:m").setParameter("m", COMP).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "C1"; c.systemType = "QUOTE"; c.importedBy = UID; return c;
    }
    private SheetRow row(int seq, String gross) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", MAT); m.put("投入料号", COMP); m.put("产出料号类型", "1.银点类");
        m.put("项次", String.valueOf(seq)); m.put("材料毛重", gross); m.put("材料净重", "0.9");
        m.put("重量单位", "G"); m.put("损耗率", "0.01"); m.put("不良率", "0.02");
        return new SheetRow(seq, m);
    }
    private String bomVersion() {
        List<?> r = em.createNativeQuery(
            "SELECT bom_version FROM material_bom WHERE material_no=:m AND is_current=true LIMIT 1")
            .setParameter("m", MAT).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
    private long masterCount(String extra) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM material_bom WHERE material_no=:m"
            + (extra == null ? "" : " AND " + extra)).setParameter("m", MAT).getSingleResult()).longValue();
    }
    private long childCount(String extra) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM material_bom_item WHERE material_no=:m"
            + (extra == null ? "" : " AND " + extra)).setParameter("m", MAT).getSingleResult()).longValue();
    }

    @Test void importTwice_idempotent() {
        handler.handle(List.of(row(1, "1.0"), row(2, "2.0")), ctx());
        handler.handle(List.of(row(1, "1.0"), row(2, "2.0")), ctx());
        assertEquals("2000", bomVersion());
        assertEquals(1L, masterCount(null));
        assertEquals(2L, childCount("is_current=true"));
        assertEquals(2L, childCount(null));
    }
    @Test void changeChild_bumpsMaster_childCurrentOnly() {
        handler.handle(List.of(row(1, "1.0"), row(2, "2.0")), ctx());
        handler.handle(List.of(row(1, "1.0"), row(2, "9.0")), ctx());
        assertEquals("2001", bomVersion());
        assertEquals(1L, masterCount("is_current=true"), "主表最新生效");
        assertEquals(2L, masterCount(null), "主表两版本保留(bom_version 2000/2001)");
        assertEquals(2L, childCount("is_current=true"));
        assertEquals(2L, childCount(null), "子表仅当前版本(§5.3, upsert+删残留)");
    }
}
