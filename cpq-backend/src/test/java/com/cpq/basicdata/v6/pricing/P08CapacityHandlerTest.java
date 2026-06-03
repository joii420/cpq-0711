package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** P08 产能 (PRICING) 整组版本化：calc_version 系统生成 2000 起、工序变升版、与 QUOTE 独立。 */
@QuarkusTest
class P08CapacityHandlerTest {

    @Inject P08CapacityHandler handler;
    @Inject EntityManager em;

    static final String MAT = "TEST-P08-MAT";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000008");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM capacity WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM labor_rate WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "_GLOBAL_"; c.systemType = "PRICING"; c.importedBy = UID; return c;
    }
    private SheetRow row(String proc, String labor) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", MAT); m.put("工序编号", proc); m.put("计算版本", "EXCEL-X");
        m.put("是否有效", "是"); m.put("人工标准单价", labor);
        return new SheetRow(1, m);
    }
    private String version() {
        List<?> r = em.createNativeQuery(
            "SELECT calc_version FROM capacity WHERE material_no=:m AND system_type='PRICING' AND is_current=true LIMIT 1")
            .setParameter("m", MAT).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
    private long currentCount() {
        return ((Number) em.createNativeQuery(
            "SELECT count(*) FROM capacity WHERE material_no=:m AND system_type='PRICING' AND is_current=true")
            .setParameter("m", MAT).getSingleResult()).longValue();
    }

    @Test void firstImport_isVersion2000() {
        handler.handle(List.of(row("OP10", "5.0"), row("OP20", "6.0")), ctx());
        assertEquals("2000", version(), "calc_version 应系统生成 2000");
        assertEquals(2L, currentCount());
    }
    @Test void changeProcess_bumpsTo2001() {
        handler.handle(List.of(row("OP10", "5.0")), ctx());
        handler.handle(List.of(row("OP10", "5.0"), row("OP30", "7.0")), ctx());  // 工序增加 → 升版
        assertEquals("2001", version());
    }
}
