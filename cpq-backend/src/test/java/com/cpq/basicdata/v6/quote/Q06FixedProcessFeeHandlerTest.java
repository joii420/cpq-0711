package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.service.QuoteMaterialNoAllocator;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 3 集成测试：Q06 来料固定加工费 → unit_price 版本化。
 * 覆盖：导两遍幂等（版本不变 + 行数不翻倍）/ 改值升版 + is_current 翻转 / 跨客户隔离。
 */
@QuarkusTest
class Q06FixedProcessFeeHandlerTest {

    @Inject Q06FixedProcessFeeHandler handler;
    @Inject EntityManager em;

    static final String CODE = "TEST-Q06-CODE";
    static final String FMN = "TEST-Q06-FMN";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-0000000000a6");

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE code = :c").setParameter("c", CODE).executeUpdate();
    }

    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx(String customerNo) {
        ImportContext c = new ImportContext();
        c.customerNo = customerNo; c.systemType = "QUOTE"; c.importedBy = UID;
        return c;
    }

    private SheetRow row(int seq, String base, String ratio) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("投入料号", CODE);
        m.put("宏丰料号", FMN);
        m.put("项次", String.valueOf(seq));
        m.put("基准值", base);
        m.put("比例", ratio);
        m.put("货币", "CNY");
        m.put("计价单位", "PCS");
        return new SheetRow(seq, m);
    }

    private String currentVersion(String customerNo) {
        List<?> r = em.createNativeQuery(
            "SELECT version_no FROM unit_price WHERE code=:c AND customer_no=:cust AND is_current=true LIMIT 1")
            .setParameter("c", CODE).setParameter("cust", customerNo).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }

    private long count(String customerNo, String extra) {
        String w = "code='" + CODE + "' AND customer_no='" + customerNo + "'" + (extra == null ? "" : " AND " + extra);
        return ((Number) em.createNativeQuery("SELECT count(*) FROM unit_price WHERE " + w).getSingleResult()).longValue();
    }

    @Test
    void importTwice_idempotent() {
        handler.handle(List.of(row(1, "10", "0.1"), row(2, "20", "0.2")), ctx("C1"));
        handler.handle(List.of(row(1, "10", "0.1"), row(2, "20", "0.2")), ctx("C1"));  // 同内容第二遍
        assertEquals("2000", currentVersion("C1"));
        assertEquals(2L, count("C1", "is_current=true"));
        assertEquals(2L, count("C1", null), "导两遍不翻倍");
    }

    @Test
    void changeValue_bumpsAndFlips() {
        handler.handle(List.of(row(1, "10", "0.1"), row(2, "20", "0.2")), ctx("C1"));
        handler.handle(List.of(row(1, "10", "0.1"), row(2, "20", "0.9")), ctx("C1"));  // 改 seq2 比例
        assertEquals("2001", currentVersion("C1"));
        assertEquals(2L, count("C1", "is_current=true"), "新版本 2 行生效");
        assertEquals(2L, count("C1", "is_current=false"), "旧版本 2 行下线保留");
        assertEquals(4L, count("C1", null));
    }

    /**
     * 新契约（报价料号统一 Spec1 §7 跨客户守卫）：「投入料号」在通道②（文件里显式给值）语义已升级为
     * 全局报价料号，同一字面量不能分属两个客户。原用例故意让 C1/C2 复用同一 CODE 来验证 unit_price
     * 版本隔离（"各自独立成功"），在新语义下这本身就是非法的跨客户串号场景 —— 应被
     * {@link QuoteMaterialNoAllocator.CrossCustomerQuoteNoException} 拦截，而不是各自成功。
     * 断言改为：C2 复用 C1 的报价料号→抛跨客户异常，且 C1 已落库的数据不受影响（C2 整批因异常回滚）。
     */
    @Test
    void crossCustomer_isolated() {
        handler.handle(List.of(row(1, "10", "0.1")), ctx("C1"));
        QuoteMaterialNoAllocator.CrossCustomerQuoteNoException ex = assertThrows(
            QuoteMaterialNoAllocator.CrossCustomerQuoteNoException.class,
            () -> handler.handle(List.of(row(1, "99", "0.9")), ctx("C2")),
            "C2 复用 C1 已登记的报价料号 → 跨客户守卫应拒绝");
        assertTrue(ex.getMessage().contains(CODE), "异常信息应含冲突的报价料号: " + ex.getMessage());
        assertEquals(1L, count("C1", "is_current=true"), "C1 数据不受影响（C2 整批因跨客户异常回滚）");
        assertEquals("2000", currentVersion("C1"));
        assertNull(currentVersion("C2"), "C2 的写入整批回滚，不应落库");
    }
}
