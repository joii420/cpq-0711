package com.cpq.priceadjust.entity;

import com.cpq.system.entity.User;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * task-0729 调价策略变更审计（断链 9）。四类变更（周期/料号范围/元素清单/比对列）均记录。
 * 🔒 {@code strategy_id NOT NULL} FK——若该客户尚无策略记录，写审计会失败；调用方（如 B6
 * 比对列保存）在无策略时按 best-effort 跳过写审计，不阻断主流程（见 PriceAdjustComparisonColumn
 * Service 注释）。
 */
@Entity
@Table(name = "customer_price_adjust_strategy_log")
public class CustomerPriceAdjustStrategyLog extends PanacheEntityBase {

    public static final String CHANGE_TYPE_COMPARISON_COLUMN = "COMPARISON_COLUMN";
    public static final String CHANGE_TYPE_STRATEGY = "STRATEGY";
    public static final String CHANGE_TYPE_MATERIAL_SCOPE = "MATERIAL_SCOPE";
    public static final String CHANGE_TYPE_ELEMENT_LIST = "ELEMENT_LIST";

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "strategy_id", nullable = false)
    public UUID strategyId;

    @Column(name = "customer_no", nullable = false, length = 64)
    public String customerNo;

    @Column(name = "change_type", nullable = false, length = 30)
    public String changeType;

    @Column(name = "summary", length = 500)
    public String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_snapshot", columnDefinition = "jsonb")
    public String beforeSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_snapshot", columnDefinition = "jsonb")
    public String afterSnapshot;

    @Column(name = "changed_by")
    public UUID changedBy;

    @Column(name = "changed_by_name", length = 100)
    public String changedByName;

    @Column(name = "changed_at", nullable = false)
    public OffsetDateTime changedAt = OffsetDateTime.now();

    /**
     * 记录变更人：<b>{@code changedBy} 与 {@code changedByName} 必须同时落</b>。
     *
     * <p>🚨 <b>2026-08-05 新增（验收 #54）</b>：原先两个写点各自手写这两行赋值，
     * {@code PriceAdjustComparisonColumnService} 只写了 {@code changedBy}、漏了
     * {@code changedByName} → `COMPARISON_COLUMN` 类型日志的「变更人」列全库为空
     * （其余三类 STRATEGY/MATERIAL_SCOPE/ELEMENT_LIST 都非空），四类变更里唯独比对列
     * 查不到是谁改的。改成本方法后<b>物理上无法只落一半</b>，同类遗漏不会再发生。
     *
     * <p>🔒 本类的两个写点（{@code PriceAdjustStrategyService#writeAuditLog} /
     * {@code PriceAdjustComparisonColumnService#writeAuditLog}）<b>都必须走这里</b>，
     * 不要各自再写一份 {@code User.findById().fullName}。
     */
    public void stampActor(UUID actorId) {
        this.changedBy = actorId;
        this.changedByName = resolveUserName(actorId);
    }

    /** uuid → 用户显示名，全 priceadjust 包唯一实现（口径漂移是本任务反复出现的问题）。 */
    public static String resolveUserName(UUID id) {
        if (id == null) return null;
        User u = User.findById(id);
        return u != null ? u.fullName : null;
    }
}
