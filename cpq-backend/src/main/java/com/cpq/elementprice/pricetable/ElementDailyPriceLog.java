package com.cpq.elementprice.pricetable;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 元素价格变更历史（update-0724 · B2，需求 §11 U5）。存快照、展示差异；只读、不做回滚。
 * 形状逐字比照 {@code com.cpq.elementprice.strategy.ElementPriceStrategyLog}。
 * 不加 FK 到 {@code element_daily_price}：DELETE 后原行已不存在，建 FK 会阻止删除。
 */
@Entity
@Table(name = "element_daily_price_log")
public class ElementDailyPriceLog extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "price_id")
    public UUID priceId;

    @Column(name = "element_name")
    public String elementName;

    @Column(name = "source_id")
    public UUID sourceId;

    @Column(name = "price_date")
    public LocalDate priceDate;

    public String action;   // CREATE / UPDATE / DELETE

    @JdbcTypeCode(SqlTypes.JSON)
    public String snapshot;  // CREATE/UPDATE 存变更后完整值；DELETE 存删除前完整值（JSON 字符串）

    @Column(name = "changed_at")
    public OffsetDateTime changedAt;

    @Column(name = "changed_by")
    public UUID changedBy;

    @Column(name = "changed_by_name")
    public String changedByName;
}
