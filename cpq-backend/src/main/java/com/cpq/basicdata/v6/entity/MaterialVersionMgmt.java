package com.cpq.basicdata.v6.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDate;

/** V6 §13 料号生效版本管理（核价版本绑定元素/材料/汇率版本）。 */
@Entity
@Table(name = "material_version_mgmt")
public class MaterialVersionMgmt extends V6BaseEntity {

    @Column(name = "material_no", nullable = false, length = 20)
    public String materialNo;

    @Column(name = "customer_no", length = 20)
    public String customerNo;

    @Column(name = "material_name", length = 100)
    public String materialName;

    @Column(name = "specification", length = 100)
    public String specification;

    @Column(name = "dimension", length = 100)
    public String dimension;

    @Column(name = "seq_no", nullable = false)
    public Integer seqNo;

    @Column(name = "pricing_version_no", nullable = false, length = 20)
    public String pricingVersionNo;

    @Column(name = "pricing_version_name", length = 50)
    public String pricingVersionName;

    @Column(name = "element_price_version", length = 20)
    public String elementPriceVersion;

    @Column(name = "material_price_version", length = 20)
    public String materialPriceVersion;

    @Column(name = "exchange_rate_version", length = 20)
    public String exchangeRateVersion;

    @Column(name = "is_effective", nullable = false)
    public Boolean isEffective = Boolean.TRUE;

    @Column(name = "effective_date")
    public LocalDate effectiveDate;

    @Column(name = "expire_date")
    public LocalDate expireDate;
}
