package com.cpq.basicdata.v6.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/** V6 §7 商务/物流类费用配置（利润率/税率/运费/清关/保险/银行/其他）。 */
@Entity
@Table(name = "fee_config")
public class FeeConfig extends V6BaseEntity {

    @Column(name = "system_type", nullable = false, length = 10)
    public String systemType;

    /** PROFIT / TAX / FREIGHT / CUSTOMS / INSURANCE / BANK / OTHER */
    @Column(name = "biz_type", nullable = false, length = 30)
    public String bizType;

    @Column(name = "fee_no", nullable = false, length = 30)
    public String feeNo;

    @Column(name = "fee_name", nullable = false, length = 100)
    public String feeName;

    @Column(name = "material_no", length = 20)
    public String materialNo;

    @Column(name = "customer_no", length = 20)
    public String customerNo;

    @Column(name = "region", length = 50)
    public String region;

    /** RATE / FIXED / PER_UNIT / PER_KG */
    @Column(name = "charge_basis", length = 20)
    public String chargeBasis;

    @Column(name = "value", precision = 18, scale = 6)
    public BigDecimal value;

    @Column(name = "ratio", precision = 10, scale = 4)
    public BigDecimal ratio;

    @Column(name = "currency", length = 10)
    public String currency;

    @Column(name = "unit", length = 20)
    public String unit;

    @Column(name = "effective_date")
    public LocalDate effectiveDate;

    @Column(name = "expire_date")
    public LocalDate expireDate;

    @Column(name = "pricing_version_no", length = 20)
    public String pricingVersionNo;
}
