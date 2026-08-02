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

    @Test
    @Transactional
    void loadVersionPrices_onlyNonNullCurrentPrice() {
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
        // Ni 本期无价（current_price NULL）——S1 必须排除，不当 0 处理
        em.createNativeQuery(
                "INSERT INTO element_price_version_item (version_id, element_code, current_price, currency) " +
                "VALUES (:vid, 'Ni', NULL, NULL)")
            .setParameter("vid", testVersionId).executeUpdate();

        Map<String, ElementPrice> prices = svc.loadVersionPrices(testVersionId);

        assertEquals(2, prices.size(), "Ni 无价应被排除，只剩 Ag/Cu 两条");
        assertEquals(0, new BigDecimal("100.0000").compareTo(prices.get("Ag").price));
        assertEquals("CNY", prices.get("Ag").currency);
        assertEquals(0, new BigDecimal("50.0000").compareTo(prices.get("Cu").price));
        assertEquals("USD", prices.get("Cu").currency);
        assertFalse(prices.containsKey("Ni"), "无价元素不应出现在结果里");
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
