package com.cpq.basicdata.v6.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** V6 §12 零件单价表（元素/材料/组成件/零件/耗材 5 类）。 */
@Entity
@Table(name = "unit_price")
public class UnitPrice extends V6BaseEntity {

    /** QUOTE / PRICING */
    @Column(name = "system_type", nullable = false, length = 10)
    public String systemType;

    /** ELEMENT / MATERIAL / COMPONENT / PART / CONSUMABLE */
    @Column(name = "price_type", nullable = false, length = 20)
    public String priceType;

    @Column(name = "version_no", nullable = false, length = 20)
    public String versionNo;

    @Column(name = "code", nullable = false, length = 30)
    public String code;

    @Column(name = "name", length = 100)
    public String name;

    @Column(name = "specification", length = 100)
    public String specification;

    @Column(name = "dimension", length = 100)
    public String dimension;

    @Column(name = "finished_material_no", length = 20)
    public String finishedMaterialNo;

    @Column(name = "operation_no", length = 20)
    public String operationNo;

    @Column(name = "cost_type", length = 20)
    public String costType;

    @Column(name = "seq_no")
    public Integer seqNo;

    /** 年降顺序（§8 来料年降 / §15 组装年降）。 */
    @Column(name = "discount_order")
    public Integer discountOrder;

    /** 要素项次（§13 组成件其他费用 项次(要素)）。 */
    @Column(name = "item_seq")
    public Integer itemSeq;

    @Column(name = "plating_scheme_no", length = 20)
    public String platingSchemeNo;

    /** D1：固定金额费用写值、比例费用留 NULL（以是否为空区分费用类型）。 */
    @Column(name = "pricing_price", precision = 18, scale = 6)
    public BigDecimal pricingPrice;

    @Column(name = "cost_ratio", precision = 10, scale = 4)
    public BigDecimal costRatio;

    @Column(name = "market_ref_price", precision = 18, scale = 6)
    public BigDecimal marketRefPrice;

    @Column(name = "currency", length = 10)
    public String currency;

    @Column(name = "unit", length = 20)
    public String unit;

    @Column(name = "conversion_rate", precision = 18, scale = 6)
    public BigDecimal conversionRate;

    @Column(name = "recovery_discount", precision = 10, scale = 4)
    public BigDecimal recoveryDiscount;

    @Column(name = "life_qty")
    public Long lifeQty;

    /** TIMES / HOURS / PCS / DAYS */
    @Column(name = "life_unit", length = 20)
    public String lifeUnit;

    @Column(name = "supplier_no", length = 20)
    public String supplierNo;

    @Column(name = "supplier_name", length = 100)
    public String supplierName;

    @Column(name = "customer_no", length = 20)
    public String customerNo;

    @Column(name = "customer_name", length = 100)
    public String customerName;

    @Column(name = "data_type", length = 20)
    public String dataType;

    @Column(name = "source_url", length = 500)
    public String sourceUrl;

    @Column(name = "source_name", length = 100)
    public String sourceName;

    @Column(name = "fetch_rule", length = 200)
    public String fetchRule;

    @Column(name = "premium_fee", precision = 18, scale = 6)
    public BigDecimal premiumFee;

    @Column(name = "fetched_price", precision = 18, scale = 6)
    public BigDecimal fetchedPrice;

    @Column(name = "fetch_time")
    public LocalDateTime fetchTime;

    @Column(name = "effective_date")
    public LocalDate effectiveDate;

    @Column(name = "expire_date")
    public LocalDate expireDate;

    @Column(name = "base_value", precision = 18, scale = 6)
    public BigDecimal baseValue;

    @Column(name = "is_fluctuate_with_material")
    public Boolean isFluctuateWithMaterial;

    @Column(name = "material_increase_ratio", precision = 10, scale = 4)
    public BigDecimal materialIncreaseRatio;

    @Column(name = "material_fixed_increase", precision = 18, scale = 6)
    public BigDecimal materialFixedIncrease;

    @Column(name = "defect_rate", precision = 10, scale = 4)
    public BigDecimal defectRate;
}
