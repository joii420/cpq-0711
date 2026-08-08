package com.cpq.priceadjust.service;

import com.cpq.priceadjust.dto.PriceAdjustSettingsDTO;
import com.cpq.priceadjust.entity.PriceAdjustSettings;
import com.cpq.quotation.entity.QuotationLineItem;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0806 T6 · FR-9 / D-5：S0 L3 口径守卫按开关短路（AC-7）。
 *
 * <p>用 {@code templateId=null} 制造一个"必然差异"场景——{@code buildCardValues(li, null)} 因
 * {@code templateId==null} 恒返回 {@code null}，{@code CostingSubtotalUtil.extractUnitSubtotal}
 * 对 {@code null} 恒返回 {@code ZERO}，故 {@code diff = |0 - li.subtotal|} 恒大于阈值。这样不需要
 * 搭建真实核价模板/组件即可确定性触发 SUBTOTAL_MISMATCH，专注测开关本身的短路行为，与
 * {@code MaterialVersionUpgradeServiceS1S2Test} / {@code S3Test} 直调包内可见方法同一模式。
 */
@QuarkusTest
class MaterialVersionUpgradeServiceS0Test {

    @Inject
    MaterialVersionUpgradeService svc;
    @Inject
    PriceAdjustSettingsService settingsService;
    @Inject
    EntityManager em;

    private UUID quotationId, lineItemId;
    private BigDecimal originalThreshold;
    private boolean originalEnabled;

    @BeforeEach
    @Transactional
    void snapshotSettings() {
        PriceAdjustSettings s = PriceAdjustSettings.findSingleton();
        originalThreshold = s != null ? s.subtotalGuardThreshold : null;
        originalEnabled = s != null && s.subtotalGuardEnabled;
    }

    @AfterEach
    @Transactional
    void cleanup() {
        PriceAdjustSettings s = PriceAdjustSettings.findSingleton();
        if (s != null) {
            s.subtotalGuardThreshold = originalThreshold;
            s.subtotalGuardEnabled = originalEnabled;
            s.persist();
        }
        if (lineItemId != null) {
            em.createNativeQuery("DELETE FROM quotation_line_item WHERE id=:id")
                .setParameter("id", lineItemId).executeUpdate();
        }
        if (quotationId != null) {
            em.createNativeQuery("DELETE FROM quotation WHERE id=:id")
                .setParameter("id", quotationId).executeUpdate();
        }
    }

    private QuotationLineItem buildFixtureLi(BigDecimal subtotal) {
        UUID anyCustomerId = (UUID) em.createNativeQuery("SELECT id FROM customer LIMIT 1").getSingleResult();
        UUID anyUserId = (UUID) em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getSingleResult();
        quotationId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, created_at, updated_at) " +
                "VALUES (:id, :no, :cust, 'S0开关测试单', :rep, 'DRAFT', now(), now())")
            .setParameter("id", quotationId).setParameter("no", "TEST-S0-" + quotationId)
            .setParameter("cust", anyCustomerId).setParameter("rep", anyUserId)
            .executeUpdate();
        lineItemId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation_line_item (id, quotation_id, subtotal, created_at) " +
                "VALUES (:id, :qid, :st, now())")
            .setParameter("id", lineItemId).setParameter("qid", quotationId).setParameter("st", subtotal)
            .executeUpdate();
        return QuotationLineItem.findById(lineItemId);
    }

    private void setEnabled(boolean enabled, BigDecimal threshold) {
        PriceAdjustSettingsDTO req = new PriceAdjustSettingsDTO();
        req.subtotalGuardEnabled = enabled;
        req.subtotalGuardThreshold = threshold;
        settingsService.put(req, UUID.randomUUID());
    }

    @Test
    @Transactional
    void guardDisabled_skipsEntirely_noWarn() {
        setEnabled(false, new BigDecimal("0.01"));
        QuotationLineItem li = buildFixtureLi(new BigDecimal("100.00"));

        MaterialVersionUpgradeService.S0GuardOutcome outcome = svc.evaluateSubtotalGuard(li);

        assertNull(outcome, "开关关闭：即使 diff(=100) 远超阈值(=0.01)，也不应产生任何 warn（AC-7 false 分支）");
    }

    @Test
    @Transactional
    void guardEnabled_detectsMismatch_producesWarn() {
        setEnabled(true, new BigDecimal("0.01"));
        QuotationLineItem li = buildFixtureLi(new BigDecimal("100.00"));

        MaterialVersionUpgradeService.S0GuardOutcome outcome = svc.evaluateSubtotalGuard(li);

        assertNotNull(outcome,
            "开关打开：diff(=100) 超阈值(=0.01) 应检出 SUBTOTAL_MISMATCH（AC-7 true 分支，与开关引入前逐位一致）");
        assertEquals("SUBTOTAL_MISMATCH", outcome.warnCode);
        assertNotNull(outcome.warnMessage);
        assertEquals(0, new BigDecimal("100.00").compareTo(outcome.diffValue));
    }

    @Test
    @Transactional
    void guardEnabled_diffWithinThreshold_noWarn() {
        // 阈值调到极大，diff(=100) 落在阈值内 —— 验证打开状态下"未超阈值"分支仍不告警
        setEnabled(true, new BigDecimal("1000"));
        QuotationLineItem li = buildFixtureLi(new BigDecimal("100.00"));

        MaterialVersionUpgradeService.S0GuardOutcome outcome = svc.evaluateSubtotalGuard(li);

        assertNull(outcome, "开关打开但差异未超阈值，不应告警");
    }
}
