package com.cpq.basicdata.v6.service;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 报价料号统一 Spec 1 · Task 4：MaterialNoResolver 发号改走 {@link QuoteMaterialNoAllocator}，
 * 取消 9 字头生成路径。覆盖：新名铸号+登记 / 同批同名去重只铸一次 / 料号有值原样返回+幂等登记 / 都空抛异常。
 */
@QuarkusTest
class MaterialNoResolverTest {

    @Inject MaterialNoResolver resolver;
    @Inject QuoteMaterialNoAllocator allocator;
    @Inject EntityManager em;

    static final String CUSTOMER_NO = "TESTMNR-CUST-001";
    static final String YYMM = "2607";

    @Transactional
    void cleanup() {
        em.createNativeQuery(
            "DELETE FROM quote_material_no_seq WHERE customer_code IN " +
            "(SELECT code FROM quote_customer_code WHERE customer_no = :c)")
            .setParameter("c", CUSTOMER_NO).executeUpdate();
        em.createNativeQuery("DELETE FROM material_customer_map WHERE customer_no = :c")
            .setParameter("c", CUSTOMER_NO).executeUpdate();
        em.createNativeQuery("DELETE FROM quote_customer_code WHERE customer_no = :c")
            .setParameter("c", CUSTOMER_NO).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_name LIKE 'TESTMNR%'")
            .executeUpdate();
    }

    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private static MaterialNoResolver.BatchState newState() {
        MaterialNoResolver.BatchState st = new MaterialNoResolver.BatchState();
        st.customerNo = CUSTOMER_NO;
        st.yyMm = YYMM;
        return st;
    }

    @Test
    @Transactional
    void nameNotMatched_mintsQuoteFormatNumber_andRegisters() {
        var st = newState();
        String no = resolver.resolve(null, "TESTMNR-NEW-NAME", st);

        assertTrue(no.matches("^\\d{4}-\\d{4}\\d{6}$"), "应为报价料号格式(非9字头): " + no);

        Object owner = em.createNativeQuery(
            "SELECT customer_no FROM material_customer_map WHERE material_no=:m AND system_type='QUOTE'")
            .setParameter("m", no).getSingleResult();
        assertEquals(CUSTOMER_NO, owner, "生成的报价料号应已登记为 QUOTE 行");
    }

    @Test
    @Transactional
    void sameBatchSameName_reusesOneNumber_mintsOnce() {
        var st = newState();
        String a = resolver.resolve(null, "TESTMNR-DUP", st);
        String b = resolver.resolve(null, "TESTMNR-DUP", st);
        assertEquals(a, b, "同批同名只生成一个（nameToNo 缓存命中）");

        // 序号连续 +1 证明 DUP 第二次 resolve 没有再次铸号（否则会跳一格）
        String c = resolver.resolve(null, "TESTMNR-NEXT", st);
        int serialA = Integer.parseInt(a.substring(a.length() - 6));
        int serialC = Integer.parseInt(c.substring(c.length() - 6));
        assertEquals(serialA + 1, serialC, "DUP 未重复发号，序号应紧邻连续");
    }

    @Test
    @Transactional
    void materialNoPresent_returnsAsIs_andEnsuresRegisteredIdempotently() {
        var st = newState();
        String minted = allocator.mintAndRegister(CUSTOMER_NO, YYMM);

        String result = resolver.resolve(minted, "任意名", st);
        assertEquals(minted, result, "料号有值应原样返回，不再生成");

        // 幂等：已登记的报价料号再次经 resolve -> ensureRegistered 不应抛异常
        assertDoesNotThrow(() -> resolver.resolve(minted, "任意名2", st));

        Long cnt = ((Number) em.createNativeQuery(
            "SELECT COUNT(*) FROM material_customer_map WHERE material_no=:m AND system_type='QUOTE'")
            .setParameter("m", minted).getSingleResult()).longValue();
        assertEquals(1L, cnt, "ensureRegistered 幂等，不产生重复行");
    }

    @Test
    void bothBlank_throws() {
        var st = newState();
        assertThrows(MaterialNoUnresolvableException.class, () -> resolver.resolve("  ", "  ", st));
        assertThrows(MaterialNoUnresolvableException.class, () -> resolver.resolve(null, null, st));
    }
}
