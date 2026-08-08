package com.cpq.priceadjust.service;

import com.cpq.priceadjust.dto.ElementPrice;
import com.cpq.priceadjust.entity.ElementPriceVersionItem;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-0807 FR-6 · {@code PriceAdjustReviewService} 的 {@code computeUsageQty}（用量除零守卫）
 * 与 {@code buildOverridePricesForElement}（逐元素影响试算的覆盖 map 构造，D-5）单测。
 *
 * <p>两个方法都是纯内存计算，不触库，直接调包内可见方法（同包，CDI 代理正确委派，与
 * {@code MaterialVersionUpgradeServiceS3Test} 同一模式）。
 */
@QuarkusTest
class PriceAdjustReviewServiceFR6Test {

    @Inject
    PriceAdjustReviewService svc;

    // -------------------------------------------------------------------------
    // computeUsageQty：分母为 0 / 任一侧 null → null；不得返回 0 冒充"没有用量"
    // -------------------------------------------------------------------------

    @Test
    void computeUsageQty_normalCase() {
        // unitPriceImpact=0.587137, current=3000, previous=2500 → denom=500 → 0.587137/500=0.001174274
        BigDecimal usageQty = svc.computeUsageQty(
            new BigDecimal("0.587137"), new BigDecimal("3000"), new BigDecimal("2500"));
        assertNotNull(usageQty);
        assertEquals(0, new BigDecimal("0.001174").compareTo(usageQty));
    }

    @Test
    void computeUsageQty_zeroDenominator_returnsNull() {
        // currentPrice == previousPrice → 分母为 0，不得除零、不得填 0
        BigDecimal usageQty = svc.computeUsageQty(
            new BigDecimal("10"), new BigDecimal("3000"), new BigDecimal("3000"));
        assertNull(usageQty, "分母为 0 必须返回 null，不得抛异常、不得返回 0");
    }

    @Test
    void computeUsageQty_nullImpact_returnsNull() {
        assertNull(svc.computeUsageQty(null, new BigDecimal("3000"), new BigDecimal("2500")));
    }

    @Test
    void computeUsageQty_nullPreviousPrice_returnsNull() {
        assertNull(svc.computeUsageQty(new BigDecimal("10"), new BigDecimal("3000"), null));
    }

    @Test
    void computeUsageQty_nullCurrentPrice_returnsNull() {
        assertNull(svc.computeUsageQty(new BigDecimal("10"), null, new BigDecimal("2500")));
    }

    // -------------------------------------------------------------------------
    // buildOverridePricesForElement（D-5）：target 用 currentPrice，其余用 previousPrice，
    // previousPrice 为 null 的元素不放进 map（= 不动该元素）
    // -------------------------------------------------------------------------

    private static ElementPriceVersionItem item(String code, BigDecimal current, BigDecimal previous, String currency) {
        ElementPriceVersionItem it = new ElementPriceVersionItem();
        it.elementCode = code;
        it.currentPrice = current;
        it.previousPrice = previous;
        it.currency = currency;
        return it;
    }

    @Test
    void buildOverridePricesForElement_targetUsesCurrentPrice_othersUsePreviousPrice() {
        List<ElementPriceVersionItem> versionItems = List.of(
            item("Ag", new BigDecimal("3000"), new BigDecimal("2500"), "CNY"),
            item("Cu", new BigDecimal("70"), new BigDecimal("65"), "CNY"));

        Map<String, ElementPrice> override = svc.buildOverridePricesForElement(versionItems, "Ag");

        assertEquals(2, override.size());
        assertEquals(0, new BigDecimal("3000").compareTo(override.get("Ag").price), "target 元素用 currentPrice");
        assertEquals(0, new BigDecimal("65").compareTo(override.get("Cu").price), "其余元素用 previousPrice");
    }

    @Test
    void buildOverridePricesForElement_previousPriceNull_elementOmittedFromMap() {
        List<ElementPriceVersionItem> versionItems = List.of(
            item("Ag", new BigDecimal("3000"), new BigDecimal("2500"), "CNY"),
            item("Zn", new BigDecimal("30"), null, "CNY")); // Zn 上一版无价

        Map<String, ElementPrice> override = svc.buildOverridePricesForElement(versionItems, "Ag");

        assertTrue(override.containsKey("Ag"));
        assertFalse(override.containsKey("Zn"), "previousPrice 为 null 的元素必须不放进 map（= 不动该元素）");
    }

    @Test
    void buildOverridePricesForElement_targetCurrentPriceNull_targetOmittedFromMap() {
        List<ElementPriceVersionItem> versionItems = List.of(
            item("Ag", null, new BigDecimal("2500"), "CNY"), // target 本身本期无价
            item("Cu", new BigDecimal("70"), new BigDecimal("65"), "CNY"));

        Map<String, ElementPrice> override = svc.buildOverridePricesForElement(versionItems, "Ag");

        assertFalse(override.containsKey("Ag"), "target 元素 currentPrice 为 null 时不放入（无法模拟“只改它”）");
        assertTrue(override.containsKey("Cu"));
    }
}
