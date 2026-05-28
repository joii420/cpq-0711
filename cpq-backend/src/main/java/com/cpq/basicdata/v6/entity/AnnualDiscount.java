package com.cpq.basicdata.v6.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** V6 §23 年降系数表（业务类型+料号+策略+顺序 UNIQUE）。 */
@Entity
@Table(name = "annual_discount")
public class AnnualDiscount extends V6BaseEntity {

    /** INCOMING / ASSEMBLY / FINISHED */
    @Column(name = "biz_type", nullable = false, length = 20)
    public String bizType;

    @Column(name = "material_no", nullable = false, length = 20)
    public String materialNo;

    @Column(name = "discount_strategy", nullable = false, length = 50)
    public String discountStrategy;

    @Column(name = "discount_base", precision = 18, scale = 6)
    public BigDecimal discountBase;

    @Column(name = "discount_order", nullable = false)
    public Integer discountOrder;

    @Column(name = "discount_ratio", precision = 10, scale = 4)
    public BigDecimal discountRatio;

    @Column(name = "fixed_discount_value", precision = 18, scale = 6)
    public BigDecimal fixedDiscountValue;

    @Column(name = "currency", length = 10)
    public String currency;

    @Column(name = "unit", length = 20)
    public String unit;

    @Column(name = "discount_times")
    public Integer discountTimes;
}
