package com.cpq.priceadjust.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * task-0729 调价系统参数（api.md §6.1）。**单行表**，主键恒为 {@link #SINGLETON_ID}，
 * DB 侧 {@code chk_pas_singleton CHECK (id = 1)} 兜底，故服务层可以无条件 {@code findById(1)}。
 *
 * <p>🔒 **禁止在本实体或其服务上加进程级缓存**：验收 #70④ 要求阈值「可配且即时生效，不需要
 * 重启服务」。加了缓存就必须再补失效路径，而漏补的失败方式是静默的（改了阈值但守卫仍用旧值，
 * 表现为"配置不生效"，无任何报错）。单行表的读成本是一次主键命中，不值得为它引缓存。
 */
@Entity
@Table(name = "price_adjust_settings")
public class PriceAdjustSettings extends PanacheEntityBase {

    /** 单行表的唯一主键值。 */
    public static final short SINGLETON_ID = 1;

    @Id
    @Column(name = "id", nullable = false)
    public Short id = SINGLETON_ID;

    /** L3 升版口径守卫阈值（金额/元，E14-11）。 */
    @Column(name = "subtotal_guard_threshold", nullable = false, precision = 20, scale = 6)
    public BigDecimal subtotalGuardThreshold;

    /**
     * 🆕 task-0806 FR-9 / D-5：S0 L3 口径守卫总开关。默认 {@code false}（关闭）——升版时跳过
     * S0 旧价重算（省下 0.46s/项）；{@code true} = 行为与该开关引入前逐位一致。
     */
    @Column(name = "subtotal_guard_enabled", nullable = false)
    public boolean subtotalGuardEnabled = false;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt = OffsetDateTime.now();

    @Column(name = "updated_by")
    public UUID updatedBy;

    /** 单行读取；V378 已种子化，理论上恒非 null（调用方仍须对 null 兜底，防手工清表）。 */
    public static PriceAdjustSettings findSingleton() {
        return findById(SINGLETON_ID);
    }
}
