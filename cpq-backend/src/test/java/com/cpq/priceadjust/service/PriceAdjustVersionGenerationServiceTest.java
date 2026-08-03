package com.cpq.priceadjust.service;

import com.cpq.priceadjust.entity.CustomerPriceAdjustElement;
import com.cpq.priceadjust.entity.CustomerPriceAdjustStrategy;
import com.cpq.priceadjust.entity.ElementPriceVersion;
import com.cpq.priceadjust.entity.ElementPriceVersionItem;
import com.cpq.priceadjust.exception.StrategyNoElementsException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0729 B3 · 版本生成核心逻辑单元测试：
 * - scheduledSlot 幂等（同一 slot 二次调用直接返回既有版本，不重复生成/不重复入队）
 * - 参与元素为空 → StrategyNoElementsException（400 STRATEGY_NO_ELEMENTS）
 *
 * <p>测试客户号用随机后缀伪造（{@code f_customer_element_price} 对不存在的客户策略恒返回 0 行），
 * 通过手工预插入一条「上一版」的方式驱动 §11.3.2.1 无价沿用分支拿到非空价，不依赖开发库
 * 现网的 element_price_strategy/element_daily_price 真实数据，避免污染真实客户号。
 * 手动生成路径（PENDING_VERSION_EXISTS / confirmSupersede 真实覆盖 / 版本号自增 / 真实取价）
 * 已在真实单 HTTP 联调中验证过（见 RECORD.md B3 条目，CUST-0001 数据已清理），本文件补的是
 * HTTP 手测覆盖不到的定时补跑幂等路径。
 */
@QuarkusTest
class PriceAdjustVersionGenerationServiceTest {

    @Inject PriceAdjustVersionGenerationService svc;

    private UUID strategyId;
    private String customerNo;

    @AfterEach
    @Transactional
    void cleanup() {
        if (customerNo != null) {
            java.util.List<ElementPriceVersion> versions = ElementPriceVersion.list("customerNo", customerNo);
            for (ElementPriceVersion v : versions) {
                ElementPriceVersionItem.delete("versionId", v.id);
            }
            ElementPriceVersion.delete("customerNo", customerNo);
        }
        if (strategyId != null) {
            CustomerPriceAdjustElement.delete("strategyId", strategyId);
            CustomerPriceAdjustStrategy.deleteById(strategyId);
        }
    }

    @Transactional
    void seedStrategy(String customerNoValue, String... elementCodes) {
        this.customerNo = customerNoValue;
        CustomerPriceAdjustStrategy s = new CustomerPriceAdjustStrategy();
        s.customerNo = customerNoValue;
        s.enabled = true;
        s.cycleType = "DAILY";
        s.executeTime = LocalTime.of(9, 0);
        s.materialScopeMode = "ALL";
        s.persist();
        this.strategyId = s.id;
        for (String code : elementCodes) {
            CustomerPriceAdjustElement e = new CustomerPriceAdjustElement();
            e.strategyId = s.id;
            e.elementCode = code;
            e.persist();
        }
    }

    /**
     * 手工插入一条「上一版」记录（不经过服务方法），使伪造客户号的元素也能命中 §11.3.2.1
     * 无价沿用分支（f_customer_element_price 对伪造客户号恒无结果，纯靠沿用拿到非空价）。
     */
    @Transactional
    void seedPreviousVersion(String customerNoValue, String elementCode, BigDecimal price) {
        ElementPriceVersion prev = new ElementPriceVersion();
        prev.customerNo = customerNoValue;
        prev.versionNo = "V00000000";
        prev.baseDate = java.time.LocalDate.now().minusDays(30);
        prev.status = ElementPriceVersion.STATUS_SUPERSEDED;
        prev.triggerType = "MANUAL";
        prev.persist();
        ElementPriceVersionItem item = new ElementPriceVersionItem();
        item.versionId = prev.id;
        item.elementCode = elementCode;
        item.currentPrice = price;
        item.currency = "CNY";
        item.priceUnit = "kg";
        item.noPrice = false;
        item.inheritedFromPrevious = false;
        item.persist();
    }

    @Test
    void generateVersion_sameScheduledSlot_secondCallReturnsExistingVersion_noDuplicate() {
        seedStrategy("TEST-B3-SLOT-" + UUID.randomUUID().toString().substring(0, 8), "Cu");
        seedPreviousVersion(customerNo, "Cu", new BigDecimal("100.000000"));
        OffsetDateTime slot = OffsetDateTime.now().withNano(0);

        PriceAdjustVersionGenerationService.GenerateResult r1 =
            svc.generateVersion(customerNo, true, "SCHEDULED", slot);
        assertFalse(r1.alreadyExisted);
        assertNotNull(r1.versionId);

        PriceAdjustVersionGenerationService.GenerateResult r2 =
            svc.generateVersion(customerNo, true, "SCHEDULED", slot);
        assertTrue(r2.alreadyExisted, "同一 scheduledSlot 二次调用应命中幂等键，不新建版本");
        assertEquals(r1.versionId, r2.versionId);

        long versionCount = ElementPriceVersion.count("customerNo = ?1 and scheduledSlot = ?2", customerNo, slot);
        assertEquals(1, versionCount, "DB 里该 (customerNo, scheduledSlot) 只能有一条版本记录");
    }

    @Test
    void generateVersion_differentScheduledSlot_secondCallSupersedesFirst() {
        seedStrategy("TEST-B3-SLOT2-" + UUID.randomUUID().toString().substring(0, 8), "Cu");
        seedPreviousVersion(customerNo, "Cu", new BigDecimal("100.000000"));
        OffsetDateTime slot1 = OffsetDateTime.now().withNano(0);
        OffsetDateTime slot2 = slot1.plusDays(1);

        PriceAdjustVersionGenerationService.GenerateResult r1 =
            svc.generateVersion(customerNo, true, "SCHEDULED", slot1);
        PriceAdjustVersionGenerationService.GenerateResult r2 =
            svc.generateVersion(customerNo, true, "SCHEDULED", slot2);

        assertNotEquals(r1.versionId, r2.versionId);
        ElementPriceVersion v1 = ElementPriceVersion.findById(r1.versionId);
        ElementPriceVersion v2 = ElementPriceVersion.findById(r2.versionId);
        assertEquals(ElementPriceVersion.STATUS_SUPERSEDED, v1.status, "旧 slot 版本应被新 slot 版本作废");
        assertEquals(ElementPriceVersion.STATUS_PENDING, v2.status);
    }

    @Test
    void generateVersion_noElements_throwsStrategyNoElementsException() {
        seedStrategy("TEST-B3-NOELEM-" + UUID.randomUUID().toString().substring(0, 8));
        assertThrows(StrategyNoElementsException.class,
            () -> svc.generateVersion(customerNo, false, "MANUAL", null));
    }
}
