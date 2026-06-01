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

/** Task 3 集成测试：Q17 电镀费用（一行拆加工费/材料费两组 + 忽略 Excel 版本号 + 跳过电镀方案行）。 */
@QuarkusTest
class Q17PlatingCostHandlerTest {

    @Inject Q17PlatingCostHandler handler;
    @Inject EntityManager em;

    static final String CODE = "TEST-Q17-CODE";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000017");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE code=:c").setParameter("c", CODE).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "C1"; c.systemType = "QUOTE"; c.importedBy = UID; return c;
    }
    private SheetRow row(String process, String material, String excelVersion, String platingSchemeNo) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", CODE);
        if (platingSchemeNo != null) m.put("电镀方案编号", platingSchemeNo);
        if (excelVersion != null) m.put("版本编号", excelVersion);
        m.put("电镀加工费", process); m.put("电镀材料费", material);
        m.put("货币", "CNY"); m.put("计价单位", "PCS"); m.put("不良率", "0.01");
        return new SheetRow(1, m);
    }
    private String version(String costType) {
        List<?> r = em.createNativeQuery(
            "SELECT version_no FROM unit_price WHERE code=:c AND cost_type=:ct AND is_current=true LIMIT 1")
            .setParameter("c", CODE).setParameter("ct", costType).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
    private long total() {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM unit_price WHERE code=:c")
            .setParameter("c", CODE).getSingleResult()).longValue();
    }

    @Test void importTwice_idempotent_twoCostTypes_ignoreExcelVersion() {
        handler.handle(List.of(row("5", "3", "V99", null)), ctx());
        handler.handle(List.of(row("5", "3", "V99", null)), ctx());
        assertEquals("2000", version("电镀加工费"), "version 系统生成, 忽略 Excel 'V99'");
        assertEquals("2000", version("电镀材料费"));
        assertEquals(2L, total(), "一行拆两条, 导两遍不翻倍");
    }

    @Test void changeOneFee_bumpsOnlyThatCostType() {
        handler.handle(List.of(row("5", "3", null, null)), ctx());
        handler.handle(List.of(row("9", "3", null, null)), ctx());   // 仅改加工费
        assertEquals("2001", version("电镀加工费"), "加工费升版");
        assertEquals("2000", version("电镀材料费"), "材料费不变");
        assertEquals(3L, total(), "加工费 2 行(2000下线+2001生效) + 材料费 1 行");
    }

    @Test void platingSchemeNo_skipsRow() {
        handler.handle(List.of(row("5", "3", null, "SCHEME-1")), ctx());
        assertEquals(0L, total(), "有电镀方案编号 → 整行跳过, 不写 unit_price");
    }
}
