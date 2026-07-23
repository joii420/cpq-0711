package com.cpq.elementprice.strategy;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 客户元素价格策略（task-0722 · B8）。{@code elementCode IS NULL} = 客户级默认行；
 * 非空 = 元素级例外。{@code customerNo} 是真实客户编码或字面量 {@code _GLOBAL_}（§11.11.4），
 * 全链路一律用 String，不转 UUID。
 */
@Entity
@Table(name = "element_price_strategy")
public class ElementPriceStrategy extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "customer_no")
    public String customerNo;

    @Column(name = "element_code")
    public String elementCode;

    @Column(name = "source_id")
    public UUID sourceId;

    public String method;

    @Column(name = "window_num")
    public Integer windowNum;

    @Column(name = "window_unit")
    public String windowUnit;

    public BigDecimal factor;

    public BigDecimal premium;

    public String status;

    @Column(name = "created_at")
    public OffsetDateTime createdAt;

    @Column(name = "created_by")
    public UUID createdBy;

    @Column(name = "updated_at")
    public OffsetDateTime updatedAt;

    @Column(name = "updated_by")
    public UUID updatedBy;
}
