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

/** P21 电镀方案 (PRICING) 整组版本化：scheme_version 系统生成 2000 起、忽略 Excel 版本、与 QUOTE 独立。 */
@QuarkusTest
class P21PlatingSchemeHandlerTest {

    @Inject P21PlatingSchemeHandler handler;
    @Inject EntityManager em;

    static final String SCHEME = "TEST-P21-SCH";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000021");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM plating_scheme WHERE scheme_no=:s").setParameter("s", SCHEME).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "_GLOBAL_"; c.systemType = "PRICING"; c.importedBy = UID; return c;
    }
    private SheetRow row(int seq, String area) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("方案编号", SCHEME); m.put("版本", "EXCEL-9"); m.put("项次", String.valueOf(seq));
        m.put("电镀元素名称", "EL" + seq); m.put("电镀面积", area); m.put("镀层厚度", "0.01"); m.put("密度", "8.9");
        return new SheetRow(seq, m);
    }
    private String version() {
        List<?> r = em.createNativeQuery(
            "SELECT scheme_version FROM plating_scheme WHERE scheme_no=:s AND system_type='PRICING' AND is_current=true LIMIT 1")
            .setParameter("s", SCHEME).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }

    @Test void firstImport_isVersion2000_ignoresExcelVersion() {
        handler.handle(List.of(row(1, "1.0"), row(2, "2.0")), ctx());
        assertEquals("2000", version(), "scheme_version 应系统生成 2000，忽略 Excel 'EXCEL-9'");
    }
    @Test void changeContent_bumpsTo2001() {
        handler.handle(List.of(row(1, "1.0")), ctx());
        handler.handle(List.of(row(1, "9.0")), ctx());
        assertEquals("2001", version());
    }
}
