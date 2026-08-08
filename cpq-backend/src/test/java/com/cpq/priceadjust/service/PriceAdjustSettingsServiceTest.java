package com.cpq.priceadjust.service;

import com.cpq.priceadjust.dto.PriceAdjustSettingsDTO;
import com.cpq.priceadjust.entity.PriceAdjustSettings;
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
 * task-0806 T6 · {@code price_adjust_settings.subtotal_guard_enabled} 开关（FR-9 / D-5 / api.md §1-2）。
 *
 * <p>🔒 单行表是全局共享状态（测试库 {@code cpq_db}，与开发库 {@code cpq_db_0724} 隔离，见
 * CLAUDE.md profile 表），但仍可能被同库并发跑的其它测试撞见——每个测试前后各存/还原一次原始值
 * （记忆 {@code cpq-psql-config-save-restore-pitfall}），不留篡改。
 *
 * <p>核心断言：api.md §2「实现陷阱」—— PUT 请求体两字段各自独立，{@code null} = 不改动该项，
 * 不是"置默认值"。只提交阈值不应把开关冲掉，反之亦然。
 */
@QuarkusTest
class PriceAdjustSettingsServiceTest {

    @Inject
    PriceAdjustSettingsService service;
    @Inject
    EntityManager em;

    private BigDecimal originalThreshold;
    private boolean originalEnabled;

    @BeforeEach
    @Transactional
    void snapshot() {
        PriceAdjustSettings s = PriceAdjustSettings.findSingleton();
        assertNotNull(s, "V378 已种子化单行配置，理论上恒非 null");
        originalThreshold = s.subtotalGuardThreshold;
        originalEnabled = s.subtotalGuardEnabled;
    }

    @AfterEach
    @Transactional
    void restore() {
        PriceAdjustSettings s = PriceAdjustSettings.findSingleton();
        if (s != null) {
            s.subtotalGuardThreshold = originalThreshold;
            s.subtotalGuardEnabled = originalEnabled;
            s.persist();
        }
    }

    @Test
    @Transactional
    void put_onlyThreshold_doesNotResetEnabled() {
        // 先把开关显式置 true，制造一个"非默认值"，才能验证后续只改阈值不会把它冲回去
        PriceAdjustSettingsDTO seed = new PriceAdjustSettingsDTO();
        seed.subtotalGuardEnabled = true;
        seed.subtotalGuardThreshold = new BigDecimal("0.02");
        service.put(seed, UUID.randomUUID());

        PriceAdjustSettingsDTO req = new PriceAdjustSettingsDTO();
        req.subtotalGuardThreshold = new BigDecimal("123.45");
        // subtotalGuardEnabled 故意不设（null）—— 契约：null = 不改
        PriceAdjustSettingsDTO resp = service.put(req, UUID.randomUUID());

        assertEquals(0, new BigDecimal("123.45").compareTo(resp.subtotalGuardThreshold), "阈值应按请求更新");
        assertEquals(Boolean.TRUE, resp.subtotalGuardEnabled, "只提交阈值不应把开关冲回默认值/改动");
        assertTrue(service.isSubtotalGuardEnabled(), "读库口径同样应保持 true（不缓存，读库为准）");
    }

    @Test
    @Transactional
    void put_onlyEnabled_doesNotResetThreshold() {
        PriceAdjustSettingsDTO seed = new PriceAdjustSettingsDTO();
        seed.subtotalGuardThreshold = new BigDecimal("77.7");
        seed.subtotalGuardEnabled = false;
        service.put(seed, UUID.randomUUID());

        PriceAdjustSettingsDTO req = new PriceAdjustSettingsDTO();
        req.subtotalGuardEnabled = true;
        // subtotalGuardThreshold 故意不设（null）—— 契约：null = 不改
        PriceAdjustSettingsDTO resp = service.put(req, UUID.randomUUID());

        assertEquals(0, new BigDecimal("77.7").compareTo(resp.subtotalGuardThreshold),
            "只提交开关不应把阈值冲回默认值/改动");
        assertEquals(Boolean.TRUE, resp.subtotalGuardEnabled, "开关应按请求更新");
    }

    @Test
    @Transactional
    void put_bothNull_isNoOp() {
        PriceAdjustSettingsDTO seed = new PriceAdjustSettingsDTO();
        seed.subtotalGuardThreshold = new BigDecimal("5.5");
        seed.subtotalGuardEnabled = true;
        service.put(seed, UUID.randomUUID());

        PriceAdjustSettingsDTO req = new PriceAdjustSettingsDTO(); // 两字段都 null
        PriceAdjustSettingsDTO resp = service.put(req, UUID.randomUUID());

        assertEquals(0, new BigDecimal("5.5").compareTo(resp.subtotalGuardThreshold), "两字段都不提交，阈值应原样保留");
        assertEquals(Boolean.TRUE, resp.subtotalGuardEnabled, "两字段都不提交，开关应原样保留");
    }

    @Test
    @Transactional
    void get_reflectsEnabledFlag_immediately_noRestart() {
        PriceAdjustSettingsDTO on = new PriceAdjustSettingsDTO();
        on.subtotalGuardEnabled = true;
        service.put(on, UUID.randomUUID());
        assertEquals(Boolean.TRUE, service.get().subtotalGuardEnabled);
        assertTrue(service.isSubtotalGuardEnabled());

        PriceAdjustSettingsDTO off = new PriceAdjustSettingsDTO();
        off.subtotalGuardEnabled = false;
        service.put(off, UUID.randomUUID());
        assertEquals(Boolean.FALSE, service.get().subtotalGuardEnabled);
        assertFalse(service.isSubtotalGuardEnabled());
    }

    @Test
    @Transactional
    void put_negativeThreshold_rejected_evenWhenOnlyEnabledIntended() {
        PriceAdjustSettingsDTO req = new PriceAdjustSettingsDTO();
        req.subtotalGuardThreshold = new BigDecimal("-1");
        req.subtotalGuardEnabled = true;
        assertThrows(com.cpq.common.exception.BusinessException.class, () -> service.put(req, UUID.randomUUID()),
            "负阈值必须拒绝，既有校验不因新增字段而失效");
    }
}
