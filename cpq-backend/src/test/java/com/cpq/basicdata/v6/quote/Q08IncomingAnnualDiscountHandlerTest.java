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

/** repair-0804：Q08 来料年降 → annual_discount(discount_type=INCOMING_MATERIAL, target_no=投入料号)。 */
@QuarkusTest
class Q08IncomingAnnualDiscountHandlerTest {

    @Inject Q08IncomingAnnualDiscountHandler handler;
    @Inject EntityManager em;

    static final String TARGET = "TEST-Q08-CODE";
    static final String MAT = "TEST-Q08-FMN";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000008");

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
        m.put("项次", String.valueOf(order));
        m.put("投入料号", TARGET);
        m.put("投入料号名称", "不导入的名称");
        m.put("年降顺序", String.valueOf(order));
        m.put("年降系数（%）", ratio);
        m.put("货币", "CNY");
        m.put("计价单位", "PCS");
        m.put("降价次数", "2");
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
    @Test void writesIncomingTypeWithTargetNo() {
        handler.handle(List.of(row(1, "5.5")), ctx());

        Object[] r = (Object[]) em.createNativeQuery(
            "SELECT discount_type, target_no, customer_no, seq_no, discount_times " +
            "FROM annual_discount WHERE material_no=:m AND is_current=true")
            .setParameter("m", MAT).getSingleResult();

        assertEquals("INCOMING_MATERIAL", r[0]);
        assertEquals(TARGET, r[1], "target_no 存投入料号（材质料号）原样，不 resolve 不铸号");
        assertEquals("C1", r[2]);
        assertEquals(1, ((Number) r[3]).intValue(), "项次此前被丢弃，本次必须落库");
        assertEquals(2, ((Number) r[4]).intValue(), "降价次数此前被丢弃，本次必须落库");
    }

    @Transactional
    @Test void importTwice_idempotent() {
        handler.handle(List.of(row(1, "0.95"), row(2, "0.90")), ctx());
        handler.handle(List.of(row(1, "0.95"), row(2, "0.90")), ctx());
        assertEquals("2000", version());
        assertEquals(2L, total());
    }

    @Transactional
    @Test void changeValue_bumps() {
        handler.handle(List.of(row(1, "0.95"), row(2, "0.90")), ctx());
        handler.handle(List.of(row(1, "0.95"), row(2, "0.80")), ctx());
        assertEquals("2001", version());
        assertEquals(4L, total());
    }

    @Transactional
    @Test void blankInputPartNo_recordsError() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", MAT);
        m.put("投入料号", "");
        m.put("年降顺序", "1");
        m.put("年降系数（%）", "5.5");
        var result = handler.handle(List.of(new SheetRow(1, m)), ctx());
        assertEquals(1, result.failedRows);
        assertEquals(0L, total());
    }
}
