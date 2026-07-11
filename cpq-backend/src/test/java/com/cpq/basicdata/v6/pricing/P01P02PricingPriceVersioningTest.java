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
 * P01 元素核价价格表 / P02 材料核价价格表 → unit_price 按 code 整组版本化（tesk-0709 Task 6）。
 * <p>groupKey = {system_type:"PRICING", price_type, cost_type, code}；content = pricing_price,
 * market_ref_price, source_url, source_name, fetch_rule, currency, unit, recovery_discount；
 * 忽略 Excel 自带版本列，由 {@link com.cpq.basicdata.v6.versioning.VersionedV6Writer} 系统自增
 * （首版 2000，任一内容列变化即整组升版）。详见 §5.1 A 组。
 */
@QuarkusTest
class P01P02PricingPriceVersioningTest {

    @Inject P01ElementPricingPriceHandler p01;
    @Inject P02MaterialPricingPriceHandler p02;
    @Inject EntityManager em;

    static final String ELEMENT_CODE = "TEST-P01-EL";
    static final String MATERIAL_CODE = "TEST-P02-MT";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000060");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE code IN (:c1,:c2)")
            .setParameter("c1", ELEMENT_CODE).setParameter("c2", MATERIAL_CODE).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "_GLOBAL_"; c.systemType = "PRICING"; c.importedBy = UID; return c;
    }

    private SheetRow elementRow(String code, String price) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("元素代码", code); m.put("元素价格版本", "V1");
        m.put("核价单价", price); m.put("市场参考价", "9.9");
        m.put("参考价来源网址", "https://example.com"); m.put("网站名称", "示例网站");
        m.put("参考价取用规则", "取最新值"); m.put("币种", "CNY"); m.put("计量单位", "KG");
        m.put("回收折扣", "0.85");
        return new SheetRow(1, m);
    }

    private SheetRow materialRow(String code, String price) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("材料料号", code); m.put("材料价格版本", "V1");
        m.put("核价单价", price); m.put("市场参考价", "8.8");
        m.put("参考价来源网址", "https://example.com/m"); m.put("网站名称", "示例材料网站");
        m.put("参考价取用规则", "取平均值"); m.put("币种", "CNY"); m.put("计量单位", "PCS");
        m.put("回收折扣", "0.75");
        return new SheetRow(1, m);
    }

    private List<Object[]> currentRows(String priceType, String code) {
        return em.createNativeQuery(
                "SELECT pricing_price, version_no, is_current, cost_type FROM unit_price " +
                "WHERE system_type='PRICING' AND price_type=:pt AND code=:c AND is_current=true")
            .setParameter("pt", priceType).setParameter("c", code).getResultList();
    }

    private String currentVersion(String priceType, String code) {
        List<?> r = em.createNativeQuery(
                "SELECT version_no FROM unit_price WHERE system_type='PRICING' AND price_type=:pt " +
                "AND code=:c AND is_current=true LIMIT 1")
            .setParameter("pt", priceType).setParameter("c", code).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }

    // ====================== P01 元素核价价格表 ======================

    @Test void p01_firstImport_writesAtVersion2000() {
        p01.handle(List.of(elementRow(ELEMENT_CODE, "1.234")), ctx());

        List<Object[]> current = currentRows("ELEMENT", ELEMENT_CODE);
        assertEquals(1, current.size());
        assertEquals("2000", String.valueOf(current.get(0)[1]));
        assertTrue((Boolean) current.get(0)[2]);
        assertEquals("元素核价价格", current.get(0)[3]);
        assertEquals(0, new BigDecimal("1.234").compareTo((BigDecimal) current.get(0)[0]));
    }

    @Test void p01_reimportSameValue_doesNotBumpVersion() {
        p01.handle(List.of(elementRow(ELEMENT_CODE, "1.234")), ctx());
        p01.handle(List.of(elementRow(ELEMENT_CODE, "1.234")), ctx());

        assertEquals("2000", currentVersion("ELEMENT", ELEMENT_CODE));
        assertEquals(1L, currentRows("ELEMENT", ELEMENT_CODE).size());
    }

    @Test void p01_reimportChangedPrice_bumpsVersionAndFlipsOld() {
        p01.handle(List.of(elementRow(ELEMENT_CODE, "1.234")), ctx());
        p01.handle(List.of(elementRow(ELEMENT_CODE, "2.5")), ctx());

        assertEquals("2001", currentVersion("ELEMENT", ELEMENT_CODE));
        List<Object[]> current = currentRows("ELEMENT", ELEMENT_CODE);
        assertEquals(1, current.size());
        assertEquals(0, new BigDecimal("2.5").compareTo((BigDecimal) current.get(0)[0]));

        long oldCount = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM unit_price WHERE system_type='PRICING' AND price_type='ELEMENT' " +
                "AND code=:c AND version_no='2000' AND is_current=false")
            .setParameter("c", ELEMENT_CODE).getSingleResult()).longValue();
        assertEquals(1L, oldCount, "旧版本行应翻转为 is_current=false 并保留");
    }

    // ====================== P02 材料核价价格表 ======================

    @Test void p02_firstImport_writesAtVersion2000() {
        p02.handle(List.of(materialRow(MATERIAL_CODE, "3.456")), ctx());

        List<Object[]> current = currentRows("MATERIAL_PRICE", MATERIAL_CODE);
        assertEquals(1, current.size());
        assertEquals("2000", String.valueOf(current.get(0)[1]));
        assertTrue((Boolean) current.get(0)[2]);
        assertEquals("材料核价价格", current.get(0)[3]);
        assertEquals(0, new BigDecimal("3.456").compareTo((BigDecimal) current.get(0)[0]));
    }

    @Test void p02_reimportSameValue_doesNotBumpVersion() {
        p02.handle(List.of(materialRow(MATERIAL_CODE, "3.456")), ctx());
        p02.handle(List.of(materialRow(MATERIAL_CODE, "3.456")), ctx());

        assertEquals("2000", currentVersion("MATERIAL_PRICE", MATERIAL_CODE));
        assertEquals(1L, currentRows("MATERIAL_PRICE", MATERIAL_CODE).size());
    }

    @Test void p02_reimportChangedPrice_bumpsVersionAndFlipsOld() {
        p02.handle(List.of(materialRow(MATERIAL_CODE, "3.456")), ctx());
        p02.handle(List.of(materialRow(MATERIAL_CODE, "4.2")), ctx());

        assertEquals("2001", currentVersion("MATERIAL_PRICE", MATERIAL_CODE));
        List<Object[]> current = currentRows("MATERIAL_PRICE", MATERIAL_CODE);
        assertEquals(1, current.size());
        assertEquals(0, new BigDecimal("4.2").compareTo((BigDecimal) current.get(0)[0]));

        long oldCount = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM unit_price WHERE system_type='PRICING' AND price_type='MATERIAL_PRICE' " +
                "AND code=:c AND version_no='2000' AND is_current=false")
            .setParameter("c", MATERIAL_CODE).getSingleResult()).longValue();
        assertEquals(1L, oldCount, "旧版本行应翻转为 is_current=false 并保留");
    }
}
