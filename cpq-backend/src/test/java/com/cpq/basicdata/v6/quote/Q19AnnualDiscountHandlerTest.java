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

/** repair-0804：Q19 年降系数 → annual_discount(discount_type=FINISHED) 版本化。 */
@QuarkusTest
class Q19AnnualDiscountHandlerTest {

    @Inject Q19AnnualDiscountHandler handler;
    @Inject EntityManager em;

    static final String MAT = "TEST-Q19-MAT";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000019");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM annual_discount WHERE material_no=:m")
          .setParameter("m", MAT).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "C1"; c.systemType = "QUOTE"; c.importedBy = UID; return c;
    }
    private SheetRow row(int order, String ratio) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", MAT);
        m.put("年降顺序", String.valueOf(order));
        m.put("年降系数（%/年）", ratio);
        m.put("单次固定年降金额", "");
        m.put("货币", "CNY");
        m.put("计价单位", "PCS");
        m.put("降价次数", "3");
        return new SheetRow(order, m);
    }
    private String version() {
        List<?> r = em.createNativeQuery(
            "SELECT version_no FROM annual_discount WHERE material_no=:m AND is_current=true LIMIT 1")
            .setParameter("m", MAT).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
    private long total() {
        return ((Number) em.createNativeQuery(
            "SELECT count(*) FROM annual_discount WHERE material_no=:m")
            .setParameter("m", MAT).getSingleResult()).longValue();
    }

    @Transactional
    @Test void writesFinishedTypeWithNullTarget() {
        handler.handle(List.of(row(1, "5.5")), ctx());

        Object[] r = (Object[]) em.createNativeQuery(
            "SELECT discount_type, system_type, customer_no, target_no, discount_times, seq_no " +
            "FROM annual_discount WHERE material_no=:m AND is_current=true")
            .setParameter("m", MAT).getSingleResult();

        assertEquals("FINISHED", r[0]);
        assertEquals("QUOTE", r[1]);
        assertEquals("C1", r[2], "年降系数以前没有客户维度，本次必须补上");
        assertNull(r[3], "FINISHED 类型无挂载目标，target_no 必须为 null");
        assertEquals(3, ((Number) r[4]).intValue());
        assertNull(r[5], "年降系数 sheet 无「项次」列");
    }

    @Transactional
    @Test void importTwice_idempotent() {
        handler.handle(List.of(row(1, "5.5"), row(2, "3.0")), ctx());
        handler.handle(List.of(row(1, "5.5"), row(2, "3.0")), ctx());
        assertEquals("2000", version());
        assertEquals(2L, total());
    }

    @Transactional
    @Test void changeValue_bumpsVersion() {
        handler.handle(List.of(row(1, "5.5"), row(2, "3.0")), ctx());
        handler.handle(List.of(row(1, "5.5"), row(2, "2.0")), ctx());
        assertEquals("2001", version());
        assertEquals(4L, total(), "老组保留但 is_current=false");
    }

    @Transactional
    @Test void differentCustomers_coexist() {
        ImportContext c1 = ctx();
        ImportContext c2 = ctx(); c2.customerNo = "C2";
        handler.handle(List.of(row(1, "5.5")), c1);
        handler.handle(List.of(row(1, "9.9")), c2);
        assertEquals(2L, total(), "同料号不同客户必须并存，不得互相覆盖（改造前会覆盖）");
    }
}
