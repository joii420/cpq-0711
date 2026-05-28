package com.cpq.basicdata.v6.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** V6 §20 包装耗材（成品料号+项次+包装耗材 UNIQUE）。 */
@Entity
@Table(name = "packaging_consumable")
public class PackagingConsumable extends V6BaseEntity {

    @Column(name = "material_no", nullable = false, length = 20)
    public String materialNo;

    @Column(name = "material_name", length = 100)
    public String materialName;

    @Column(name = "specification", length = 100)
    public String specification;

    @Column(name = "dimension", length = 100)
    public String dimension;

    @Column(name = "seq_no", nullable = false)
    public Integer seqNo;

    @Column(name = "consumable_no", nullable = false, length = 30)
    public String consumableNo;

    @Column(name = "consumable_name", length = 100)
    public String consumableName;

    @Column(name = "usage_qty", nullable = false, precision = 18, scale = 6)
    public BigDecimal usageQty;

    @Column(name = "usage_unit", length = 20)
    public String usageUnit;

    /** INNER / MIDDLE / OUTER / PALLET */
    @Column(name = "packaging_level", length = 20)
    public String packagingLevel;

    @Column(name = "packaging_version", length = 20)
    public String packagingVersion;
}
