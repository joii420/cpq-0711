package com.cpq.priceadjust.service;

import com.cpq.priceadjust.dto.ElementPrice;
import com.cpq.priceadjust.dto.UpgradeResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0729 B0 · S1（读版本价）+ S2（定位价格承载组件）单元测试。
 *
 * <p>自建测试数据（组件 + 版本 + 明细），不依赖开发库现网数据，测后自行清理。
 */
@QuarkusTest
class MaterialVersionUpgradeServiceS1S2Test {

    @Inject
    MaterialVersionUpgradeService svc;
    @Inject
    EntityManager em;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private UUID testComponentId;
    private UUID testComponentNoRoleId; // 角色字段留空的对照组件
    private UUID testVersionId;

    @AfterEach
    @Transactional
    void cleanup() {
        if (testVersionId != null) {
            em.createNativeQuery("DELETE FROM element_price_version_item WHERE version_id = :id")
                .setParameter("id", testVersionId).executeUpdate();
            em.createNativeQuery("DELETE FROM element_price_version WHERE id = :id")
                .setParameter("id", testVersionId).executeUpdate();
        }
        if (testComponentId != null) {
            em.createNativeQuery("DELETE FROM component WHERE id = :id")
                .setParameter("id", testComponentId).executeUpdate();
        }
        if (testComponentNoRoleId != null) {
            em.createNativeQuery("DELETE FROM component WHERE id = :id")
                .setParameter("id", testComponentNoRoleId).executeUpdate();
        }
    }

    /**
     * repair-0807 D-10（代码评审 P0 修复，BUG-1）：{@code loadVersionPrices} 不再过滤
     * {@code current_price IS NULL}——无价元素（如 Ni）必须<b>出现</b>在结果 map 里、
     * {@code ElementPrice.price} 为 {@code null}，而不是被排除在 map 之外。
     *
     * <p>🚨 <b>这条断言反了旧版本的行为，是刻意的</b>：旧实现过滤掉无价元素，导致
     * {@code versionPrices.get(ec) == null} 同时代表"不在本版明细"与"在明细但无价"两种不同语义，
     * 使 S3a/S3b/S4a/S4b 三个写点里"在明细但无价 → 应删值撤锁"的 else 分支在真实路径上永远走不到
     * （D-8/D-9 补的 else 分支形同虚设，AC-5 实为未交付）。本方法不含清单概念（区别于
     * {@code PriceReconciler}），map 本身就是唯一判定依据，故必须收录全部元素。
     */
    @Test
    @Transactional
    void loadVersionPrices_includesNullPriceElements() {
        testVersionId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO element_price_version " +
                "(id, customer_no, version_no, base_date, trigger_type, status) " +
                "VALUES (:id, 'TEST-S1', 'VTEST0001', CURRENT_DATE, 'MANUAL', 'PENDING')")
            .setParameter("id", testVersionId).executeUpdate();
        em.createNativeQuery(
                "INSERT INTO element_price_version_item (version_id, element_code, current_price, currency) " +
                "VALUES (:vid, 'Ag', 100.0000, 'CNY')")
            .setParameter("vid", testVersionId).executeUpdate();
        em.createNativeQuery(
                "INSERT INTO element_price_version_item (version_id, element_code, current_price, currency) " +
                "VALUES (:vid, 'Cu', 50.0000, 'USD')")
            .setParameter("vid", testVersionId).executeUpdate();
        // Ni 本期无价（current_price NULL）——D-10 起 S1 必须【收录】它，price 字段为 null。
        em.createNativeQuery(
                "INSERT INTO element_price_version_item (version_id, element_code, current_price, currency) " +
                "VALUES (:vid, 'Ni', NULL, NULL)")
            .setParameter("vid", testVersionId).executeUpdate();

        Map<String, ElementPrice> prices = svc.loadVersionPrices(testVersionId);

        assertEquals(3, prices.size(), "D-10：无价元素 Ni 也必须收录，全部三条都要在 map 里");
        assertEquals(0, new BigDecimal("100.0000").compareTo(prices.get("Ag").price));
        assertEquals("CNY", prices.get("Ag").currency);
        assertEquals(0, new BigDecimal("50.0000").compareTo(prices.get("Cu").price));
        assertEquals("USD", prices.get("Cu").currency);
        assertTrue(prices.containsKey("Ni"), "D-10 要害断言：Ni 必须出现在 map 的 key 集合里（不是被排除）");
        assertNull(prices.get("Ni").price, "Ni 的 price 字段应为 null（区别于「不在明细」的 map.get 返回 null）");
    }

    @Test
    @Transactional
    void locatePriceBearingComponents_findsConfiguredOnly() throws Exception {
        testComponentId = UUID.randomUUID();
        testComponentNoRoleId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO component (id, name, code, fields, formulas, element_code_field, " +
                "element_price_field, element_currency_field) " +
                "VALUES (:id, 'S1S2测试组件', :code, '[]', '[]', '元素', '元素单价', '货币')")
            .setParameter("id", testComponentId)
            .setParameter("code", "TEST-S2-" + testComponentId)
            .executeUpdate();
        // 对照组件：角色字段留空（未接价格策略）——不应被判定为价格承载组件
        em.createNativeQuery(
                "INSERT INTO component (id, name, code, fields, formulas) " +
                "VALUES (:id, 'S1S2对照组件', :code, '[]', '[]')")
            .setParameter("id", testComponentNoRoleId)
            .setParameter("code", "TEST-S2-NOROLE-" + testComponentNoRoleId)
            .executeUpdate();

        String tabsJson = String.format(
            "[{\"componentId\":\"%s\",\"componentCode\":\"TEST-S2\",\"tabName\":\"材料成本\"}," +
            " {\"componentId\":\"%s\",\"componentCode\":\"TEST-S2-NOROLE\",\"tabName\":\"其他费用\"}]",
            testComponentId, testComponentNoRoleId);
        JsonNode tabs = MAPPER.readTree(tabsJson);

        List<UpgradeResult.PriceBearingComponent> found = svc.locatePriceBearingComponents(tabs);

        assertEquals(1, found.size(), "只有配齐三角色字段(至少前两项)的组件才算价格承载组件");
        UpgradeResult.PriceBearingComponent pbc = found.get(0);
        assertEquals(testComponentId.toString(), pbc.componentId);
        assertEquals("元素", pbc.elementCodeField);
        assertEquals("元素单价", pbc.elementPriceField);
        assertEquals("货币", pbc.elementCurrencyField);
    }
}
