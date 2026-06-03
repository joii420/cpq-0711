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

/** P07 元素BOM (PRICING) 主从版本化：characteristic 2000 起、内容变升版、与 QUOTE 独立。 */
@QuarkusTest
class P07ElementBomHandlerTest {

    @Inject P07ElementBomHandler handler;
    @Inject EntityManager em;

    static final String MAT = "TEST-P07-MAT";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000007");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM element_bom_item WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM element_bom WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "_GLOBAL_"; c.systemType = "PRICING"; c.importedBy = UID; return c;
    }
    private SheetRow row(int seq, String content) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("物料料号", MAT); m.put("元素代码", "EL" + seq); m.put("项次", String.valueOf(seq));
        m.put("组成含量", content); m.put("损耗率", "0.01");
        return new SheetRow(seq, m);
    }
    private String version() {
        List<?> r = em.createNativeQuery(
            "SELECT characteristic FROM element_bom WHERE material_no=:m AND system_type='PRICING' AND is_current=true LIMIT 1")
            .setParameter("m", MAT).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }

    @Test void firstImport_isVersion2000() {
        handler.handle(List.of(row(1, "0.5"), row(2, "0.3")), ctx());
        assertEquals("2000", version(), "首版 characteristic 应为 2000，不是 PRICING_V1");
    }
    @Test void changeContent_bumpsTo2001() {
        handler.handle(List.of(row(1, "0.5")), ctx());
        handler.handle(List.of(row(1, "0.9")), ctx());
        assertEquals("2001", version());
    }
}
