package com.cpq.priceadjust.entity;

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
}
