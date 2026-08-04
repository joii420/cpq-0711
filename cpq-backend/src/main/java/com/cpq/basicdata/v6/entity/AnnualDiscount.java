package com.cpq.basicdata.v6.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * V6 §23 年降表（repair-0804 单表化）：三个报价 Sheet 共用。
 *
 * <p>groupKey = (system_type, customer_no, discount_type, material_no, target_no)；
 * 组内行区分列 = discount_order；其余为 content。版本化 + pending 语义与
 * unit_price / capacity 等 7 张表一致，见 {@code VersionedV6Writer}。
 */
@Entity
@Table(name = "annual_discount")
public class AnnualDiscount extends V6BaseEntity {

    /** QUOTE / PRICING（当前只有 QUOTE 写入，核价侧无年降 Sheet）。 */
    @Column(name = "system_type", nullable = false, length = 10)
    public String systemType;

    /** INCOMING_MATERIAL=来料年降 / ASSEMBLY_PROCESS=组装加工费年降 / FINISHED=年降系数。 */
    @Column(name = "discount_type", nullable = false, length = 30)
    public String discountType;

    @Column(name = "customer_no", length = 20)
    public String customerNo;

    /** 销售料号（主轴）。 */
    @Column(name = "material_no", nullable = false, length = 20)
    public String materialNo;

    /**
     * 年降挂载目标，语义由 {@link #discountType} 决定：
     * INCOMING_MATERIAL → 材质料号；ASSEMBLY_PROCESS → 工序编号；FINISHED → null。
     * 名称不冗余存，由视图 JOIN material_recipe / process_master 取。
     */
    @Column(name = "target_no", length = 30)
    public String targetNo;

    @Column(name = "seq_no")
    public Integer seqNo;

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

    @Column(name = "version_no", nullable = false, length = 20)
    public String versionNo;

    @Column(name = "is_current", nullable = false)
    public Boolean isCurrent;

    @Column(name = "pending_quotation_id")
    public UUID pendingQuotationId;
}
