package com.cpq.priceadjust.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.priceadjust.dto.PriceAdjustSettingsDTO;
import com.cpq.priceadjust.entity.PriceAdjustSettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * task-0729 调价系统参数（api.md §6.1，验收 #70④）。
 *
 * <p>🔒 **无缓存是本类的核心约束，不是疏漏**。验收 #70 步骤 4 的原文是：把阈值 PUT 成 1000 后，
 * 「对同一个此前被拦的问题单重新走一次升版流程，确认此时能正常通过……不需要重启服务」。
 * 任何进程级缓存（哪怕带失效）都会引入一条"改了配置但守卫仍用旧值"的静默失败路径——它不报错、
 * 不抛异常，只表现为配置不生效，而这恰恰是 #70④ 要验的那一点。单行表主键命中的读成本可以忽略。
 */
@ApplicationScoped
public class PriceAdjustSettingsService {

    private static final Logger LOG = Logger.getLogger(PriceAdjustSettingsService.class);

    /**
     * L3 守卫阈值的兜底默认值。仅在 {@code price_adjust_settings} 种子行被手工删除时生效——
     * 与 V378 的种子值、以及 {@link MaterialVersionUpgradeService#DEFAULT_SUBTOTAL_GUARD_THRESHOLD}
     * 三处同值（0.01），保证配置化前后守卫行为逐字节不变。
     */
    public static final BigDecimal FALLBACK_SUBTOTAL_GUARD_THRESHOLD = new BigDecimal("0.01");

    /**
     * 🆕 task-0806 FR-9 / D-5：S0 口径守卫开关的兜底默认值——{@code false}（关闭）。仅在
     * {@code price_adjust_settings} 种子行被手工删除时生效，与 V383 的列默认值同值。
     */
    public static final boolean FALLBACK_SUBTOTAL_GUARD_ENABLED = false;

    /**
     * 读 L3 守卫阈值。{@code @Transactional} 为默认 REQUIRED：既能被升版流程的既有事务直接 join
     * （不新开事务、不影响回滚语义），也能独立支撑 GET 端点的只读调用。
     */
    @Transactional
    public BigDecimal getSubtotalGuardThreshold() {
        PriceAdjustSettings s = PriceAdjustSettings.findSingleton();
        if (s == null || s.subtotalGuardThreshold == null) {
            LOG.warnf("[price-adjust-settings] 单行配置缺失，回落默认阈值 %s", FALLBACK_SUBTOTAL_GUARD_THRESHOLD);
            return FALLBACK_SUBTOTAL_GUARD_THRESHOLD;
        }
        return s.subtotalGuardThreshold;
    }

    /**
     * 🆕 task-0806 FR-9：读 S0 口径守卫开关。同 {@link #getSubtotalGuardThreshold()}：每次读库、
     * 不缓存——{@code MaterialVersionUpgradeService#evaluateSubtotalGuard} 每次升版都调用本方法，
     * 保证 PUT 后即时生效不需要重启（AC-7「PUT 后不重启即时生效」）。
     */
    @Transactional
    public boolean isSubtotalGuardEnabled() {
        PriceAdjustSettings s = PriceAdjustSettings.findSingleton();
        if (s == null) {
            LOG.warnf("[price-adjust-settings] 单行配置缺失，回落默认开关 %s", FALLBACK_SUBTOTAL_GUARD_ENABLED);
            return FALLBACK_SUBTOTAL_GUARD_ENABLED;
        }
        return s.subtotalGuardEnabled;
    }

    @Transactional
    public PriceAdjustSettingsDTO get() {
        PriceAdjustSettings s = PriceAdjustSettings.findSingleton();
        PriceAdjustSettingsDTO dto = new PriceAdjustSettingsDTO();
        if (s == null) {
            dto.subtotalGuardThreshold = FALLBACK_SUBTOTAL_GUARD_THRESHOLD;
            dto.subtotalGuardEnabled = FALLBACK_SUBTOTAL_GUARD_ENABLED;
            return dto;
        }
        dto.subtotalGuardThreshold = s.subtotalGuardThreshold;
        dto.subtotalGuardEnabled = s.subtotalGuardEnabled;
        dto.updatedAt = s.updatedAt;
        return dto;
    }

    /**
     * 写系统参数（仅 SYSTEM_ADMIN，权限在 Resource 层拦）。种子行缺失时补建，保持幂等。
     *
     * <p>🚨 <b>{@code null} = 不改动该项，不是"置默认值"</b>（api.md §2 实现陷阱备注）：
     * {@code subtotalGuardThreshold} / {@code subtotalGuardEnabled} 两字段各自独立判断——
     * 只提交其中一个字段的调用，另一个字段必须保持数据库里的现值不变。这是 task-0806 对
     * task-0729 既有 PUT 契约的显式放宽（原契约要求 {@code subtotalGuardThreshold} 必填）。
     */
    @Transactional
    public PriceAdjustSettingsDTO put(PriceAdjustSettingsDTO req, UUID actorId) {
        if (req == null) {
            throw new BusinessException(400, "请求体不能为空");
        }
        if (req.subtotalGuardThreshold != null && req.subtotalGuardThreshold.signum() < 0) {
            // 负阈值 = |diff| > 负数 恒成立 = L3 守卫恒触发（所有单都失败）。DB 侧也有 CHECK，
            // 这里先拦是为了给出可读的中文提示而不是裸的约束违反。
            throw new BusinessException(400, "subtotalGuardThreshold 不能为负数");
        }

        PriceAdjustSettings s = PriceAdjustSettings.findSingleton();
        if (s == null) {
            s = new PriceAdjustSettings();
            s.id = PriceAdjustSettings.SINGLETON_ID;
            // 种子行缺失时的兜底默认值，与 V378/V383 的列默认值同源
            s.subtotalGuardThreshold = FALLBACK_SUBTOTAL_GUARD_THRESHOLD;
            s.subtotalGuardEnabled = FALLBACK_SUBTOTAL_GUARD_ENABLED;
        }
        // 🔒 null = 不改：每个字段独立判断是否被本次请求携带，不是整体覆盖
        if (req.subtotalGuardThreshold != null) {
            s.subtotalGuardThreshold = req.subtotalGuardThreshold;
        }
        if (req.subtotalGuardEnabled != null) {
            s.subtotalGuardEnabled = req.subtotalGuardEnabled;
        }
        s.updatedAt = OffsetDateTime.now();
        s.updatedBy = actorId;
        s.persist();

        LOG.infof("[price-adjust-settings] subtotalGuardThreshold=%s subtotalGuardEnabled=%s（actor=%s）",
                s.subtotalGuardThreshold, s.subtotalGuardEnabled, actorId);

        PriceAdjustSettingsDTO dto = new PriceAdjustSettingsDTO();
        dto.subtotalGuardThreshold = s.subtotalGuardThreshold;
        dto.subtotalGuardEnabled = s.subtotalGuardEnabled;
        dto.updatedAt = s.updatedAt;
        return dto;
    }
}
