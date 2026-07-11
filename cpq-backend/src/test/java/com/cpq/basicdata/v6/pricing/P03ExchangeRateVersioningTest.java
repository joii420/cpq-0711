package com.cpq.basicdata.v6.pricing;

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

/**
 * P03 汇率管理表 → exchange_rate_v6 按 (base_currency, target_currency) 整组版本化（tesk-0709 Task 7）。
 * <p>groupKey = {base_currency, target_currency}；content = rate, ref_rate, ref_fetch_rule,
 * ref_source_url；忽略 Excel 自带「汇率版本」列，由 {@link com.cpq.basicdata.v6.versioning.VersionedV6Writer}
 * 系统自增（首版 2000，任一内容列变化即整组升版）。详见 §5.1 A 组。
 */
@QuarkusTest
class P03ExchangeRateVersioningTest {

    @Inject P03ExchangeRateHandler p03;
    @Inject EntityManager em;

    static final String BASE_CNY = "TEST-CNY";
    static final String TARGET_USD = "TEST-USD";
    static final String TARGET_EUR = "TEST-EUR";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000070");

    @Transactional void cleanup() {
        em.createNativeQuery(
                "DELETE FROM exchange_rate_v6 WHERE base_currency = :bc")
            .setParameter("bc", BASE_CNY).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "_GLOBAL_"; c.systemType = "PRICING"; c.importedBy = UID; return c;
    }

    /**
     * 列顺序对齐真实 Excel（docs/table/核价系统Excel导入落库方案.md §3「汇率管理表」）：
     * 基础货币/核价货币/核价汇率/参考汇率/参考汇率数据抓取规则/抓取网址/汇率版本（末列）。
     * SheetRow.getStr(...) 按行内列出现顺序做 contains 匹配（首现优先），"汇率版本" 若排在
     * "核价汇率" 之前会因 "汇率版本".contains("汇率") 抢先命中导致误读——真实 Excel 里
     * "汇率版本" 是末列，不会撞车；测试列序须与真实文件一致，不能随意排列（LinkedHashMap 插入序即列序）。
     */
    private SheetRow rateRow(String base, String target, String rate) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("基础货币", base); m.put("核价货币", target);
        m.put("核价汇率", rate); m.put("参考汇率", "1.01");
        m.put("参考汇率数据抓取规则", "取最新值"); m.put("抓取网址", "https://example.com/fx");
        m.put("汇率版本", "V1");
        return new SheetRow(1, m);
    }

    private List<Object[]> currentRows(String base, String target) {
        return em.createNativeQuery(
                "SELECT rate, version_no, is_current FROM exchange_rate_v6 " +
                "WHERE base_currency=:bc AND target_currency=:tc AND is_current=true")
            .setParameter("bc", base).setParameter("tc", target).getResultList();
    }

    private String currentVersion(String base, String target) {
        List<?> r = em.createNativeQuery(
                "SELECT version_no FROM exchange_rate_v6 WHERE base_currency=:bc AND target_currency=:tc " +
                "AND is_current=true LIMIT 1")
            .setParameter("bc", base).setParameter("tc", target).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }

    @Test void firstImport_writesAtVersion2000() {
        p03.handle(List.of(rateRow(BASE_CNY, TARGET_USD, "6.789")), ctx());

        List<Object[]> current = currentRows(BASE_CNY, TARGET_USD);
        assertEquals(1, current.size());
        assertEquals(0, new BigDecimal("6.789").compareTo((BigDecimal) current.get(0)[0]));
        assertEquals("2000", String.valueOf(current.get(0)[1]));
        assertTrue((Boolean) current.get(0)[2]);
    }

    @Test void reimportSameValue_doesNotBumpVersion() {
        p03.handle(List.of(rateRow(BASE_CNY, TARGET_USD, "6.789")), ctx());
        p03.handle(List.of(rateRow(BASE_CNY, TARGET_USD, "6.789")), ctx());

        assertEquals("2000", currentVersion(BASE_CNY, TARGET_USD));
        assertEquals(1L, currentRows(BASE_CNY, TARGET_USD).size());
    }

    @Test void reimportChangedRate_bumpsVersionAndFlipsOld() {
        p03.handle(List.of(rateRow(BASE_CNY, TARGET_USD, "6.789")), ctx());
        p03.handle(List.of(rateRow(BASE_CNY, TARGET_USD, "7.1")), ctx());

        assertEquals("2001", currentVersion(BASE_CNY, TARGET_USD));
        List<Object[]> current = currentRows(BASE_CNY, TARGET_USD);
        assertEquals(1, current.size());
        assertEquals(0, new BigDecimal("7.1").compareTo((BigDecimal) current.get(0)[0]));

        long oldCount = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM exchange_rate_v6 WHERE base_currency=:bc AND target_currency=:tc " +
                "AND version_no='2000' AND is_current=false")
            .setParameter("bc", BASE_CNY).setParameter("tc", TARGET_USD).getSingleResult()).longValue();
        assertEquals(1L, oldCount, "旧版本行应翻转为 is_current=false 并保留");
    }

    @Test void differentCurrencyPairs_areIndependent() {
        p03.handle(List.of(
            rateRow(BASE_CNY, TARGET_USD, "6.789"),
            rateRow(BASE_CNY, TARGET_EUR, "7.5")), ctx());
        // 只改 USD 一组
        p03.handle(List.of(rateRow(BASE_CNY, TARGET_USD, "6.9")), ctx());

        assertEquals("2001", currentVersion(BASE_CNY, TARGET_USD));
        assertEquals("2000", currentVersion(BASE_CNY, TARGET_EUR), "未改动的货币对不应受影响、不应升版");
        assertEquals(0, new BigDecimal("7.5").compareTo((BigDecimal) currentRows(BASE_CNY, TARGET_EUR).get(0)[0]));
    }
}
