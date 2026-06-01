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

/** Task 7 集成测试：Q14 组装加工费 → capacity 版本化（calc_version 系统生成 + is_effective 保全）。 */
@QuarkusTest
class Q14AssemblyProcessFeeHandlerTest {

    @Inject Q14AssemblyProcessFeeHandler handler;
    @Inject EntityManager em;

    static final String MAT = "TEST-Q14-MAT";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000014");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM capacity WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "C1"; c.systemType = "QUOTE"; c.importedBy = UID; return c;
    }
    private SheetRow row(String fee) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", MAT); m.put("组装工序", "OP14"); m.put("项次", "1");
        m.put("组装加工费", fee); m.put("货币", "CNY"); m.put("计价单位", "PCS"); m.put("拒收率", "0.01");
        return new SheetRow(1, m);
    }
    private String version() {
        List<?> r = em.createNativeQuery(
            "SELECT calc_version FROM capacity WHERE material_no=:m AND is_current=true LIMIT 1")
            .setParameter("m", MAT).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
    private long total() {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM capacity WHERE material_no=:m")
            .setParameter("m", MAT).getSingleResult()).longValue();
    }

    @Test void importTwice_idempotent_isEffectiveTrue() {
        handler.handle(List.of(row("100")), ctx());
        handler.handle(List.of(row("100")), ctx());
        assertEquals("2000", version(), "calc_version 系统生成（非 V_DEFAULT）");
        assertEquals(1L, total());
        Number eff = (Number) em.createNativeQuery(
            "SELECT count(*) FROM capacity WHERE material_no=:m AND is_effective=true")
            .setParameter("m", MAT).getSingleResult();
        assertEquals(1L, eff.longValue(), "is_effective=true 保全");
    }
    @Test void changeFee_bumps() {
        handler.handle(List.of(row("100")), ctx());
        handler.handle(List.of(row("200")), ctx());
        assertEquals("2001", version());
        assertEquals(2L, total());
    }
}
