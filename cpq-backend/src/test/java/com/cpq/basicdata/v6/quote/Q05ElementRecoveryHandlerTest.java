package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Task 8 集成测试：Q05 元素回收折扣 UPDATE 限 is_current=true 版本组（2 键，不依赖 seq_no/最新 characteristic）。 */
@QuarkusTest
class Q05ElementRecoveryHandlerTest {

    @Inject Q05ElementRecoveryHandler q05;
    @Inject Q04ElementBomHandler q04;   // 建 element_bom_item 作底
    @Inject EntityManager em;

    static final String MAT = "TEST-Q05-MAT";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000005");

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
    private SheetRow bomRow(String element, String content) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("投入料号", MAT); m.put("项次", "1"); m.put("元素", element);
        m.put("组成含量", content); m.put("毛用量", "1"); m.put("毛用量单位", "G"); m.put("净用量", "1");
        return new SheetRow(1, m);
    }
    private SheetRow recRow(String element, String discount) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("投入料号", MAT); m.put("元素", element); m.put("回收折扣", discount);
        return new SheetRow(1, m);
    }
    private BigDecimal currentDiscount(String element) {
        List<?> r = em.createNativeQuery(
            "SELECT recovery_discount FROM element_bom_item WHERE material_no=:m AND component_no=:cn AND is_current=true LIMIT 1")
            .setParameter("m", MAT).setParameter("cn", element).getResultList();
        return r.isEmpty() || r.get(0) == null ? null : new BigDecimal(r.get(0).toString());
    }

    @Test void update_currentVersionRow() {
        q04.handle(List.of(bomRow("Ag", "75")), ctx());
        int before = 0;
        q05.handle(List.of(recRow("Ag", "0.3")), ctx());
        assertEquals(0, before);
        assertEquals(0, new BigDecimal("0.3").compareTo(currentDiscount("Ag")), "is_current 行 recovery_discount 被更新");
    }

    @Test void onlyTouchesCurrent_notOldVersion() {
        q04.handle(List.of(bomRow("Ag", "75")), ctx());           // characteristic 2000
        q04.handle(List.of(bomRow("Ag", "70")), ctx());           // 升版 → 2001 current, 2000 下线
        q05.handle(List.of(recRow("Ag", "0.5")), ctx());
        // 当前版本被更新
        assertEquals(0, new BigDecimal("0.5").compareTo(currentDiscount("Ag")));
        // 旧版本(is_current=false)不应被更新
        List<?> old = em.createNativeQuery(
            "SELECT recovery_discount FROM element_bom_item WHERE material_no=:m AND component_no='Ag' AND is_current=false")
            .setParameter("m", MAT).getResultList();
        assertFalse(old.isEmpty(), "存在旧版本行");
        assertNull(old.get(0), "旧版本 recovery_discount 未被触碰");
    }
}
