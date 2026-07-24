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

/** Task 6 集成测试：Q16 电镀方案 → plating_scheme 版本化（忽略 Excel 版本 + 幂等 + 升版）。 */
@QuarkusTest
class Q16PlatingSchemeHandlerTest {

    @Inject Q16PlatingSchemeHandler handler;
    @Inject EntityManager em;

    static final String SCH = "TEST-Q16-SCH";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000016");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM plating_scheme WHERE scheme_no=:s").setParameter("s", SCH).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "C1"; c.systemType = "QUOTE"; c.importedBy = UID; return c;
    }
    private SheetRow row(int seq, String thickness, String excelVersion) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("方案编号", SCH); m.put("项次", String.valueOf(seq)); m.put("电镀元素名称", "Au");
        m.put("电镀面积", "10"); m.put("镀层厚度", thickness); m.put("电镀要求", "REQ");
        if (excelVersion != null) m.put("版本", excelVersion);
        return new SheetRow(seq, m);
    }
    private String version() {
        List<?> r = em.createNativeQuery(
            "SELECT scheme_version FROM plating_scheme WHERE scheme_no=:s AND is_current=true LIMIT 1")
            .setParameter("s", SCH).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
    private long total() {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM plating_scheme WHERE scheme_no=:s")
            .setParameter("s", SCH).getSingleResult()).longValue();
    }

    @Transactional
    @Test void importTwice_idempotent_ignoreExcelVersion() {
        handler.handle(List.of(row(1, "5", "V5"), row(2, "8", "V5")), ctx());
        handler.handle(List.of(row(1, "5", "V5"), row(2, "8", "V5")), ctx());
        assertEquals("2000", version(), "scheme_version 系统生成, 忽略 Excel 'V5'");
        assertEquals(2L, total());
    }
    @Transactional
    @Test void changeRow_bumps() {
        handler.handle(List.of(row(1, "5", null), row(2, "8", null)), ctx());
        handler.handle(List.of(row(1, "5", null), row(2, "9", null)), ctx());
        assertEquals("2001", version());
        assertEquals(4L, total());
    }
}
