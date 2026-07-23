package com.cpq.elementprice.strategy;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 价格策略变更历史（task-0722 · B8/B9，需求 §11.14F）。存快照、展示差异；只读、不做回滚。
 * 不加 FK 到 {@link ElementPriceStrategy}：策略删除后历史必须留存。
 */
@Entity
@Table(name = "element_price_strategy_log")
public class ElementPriceStrategyLog extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "strategy_id")
    public UUID strategyId;

    @Column(name = "customer_no")
    public String customerNo;

    @Column(name = "element_code")
    public String elementCode;

    public String action;   // CREATE / UPDATE / DELETE

    @JdbcTypeCode(SqlTypes.JSON)
    public String snapshot;  // 变更后完整快照（JSON 字符串）；DELETE 存删除前快照

    @Column(name = "changed_at")
    public OffsetDateTime changedAt;

    @Column(name = "changed_by")
    public UUID changedBy;

    @Column(name = "changed_by_name")
    public String changedByName;
}
