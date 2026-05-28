package com.cpq.basicdata.v6.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** V6 §19 生产耗材（料号+工序+资源群组+项次+耗材料号 UNIQUE）。 */
@Entity
@Table(name = "production_consumable")
public class ProductionConsumable extends V6BaseEntity {

    @Column(name = "material_no", nullable = false, length = 20)
    public String materialNo;

    @Column(name = "material_name", length = 100)
    public String materialName;

    @Column(name = "specification", length = 100)
    public String specification;

    @Column(name = "dimension", length = 100)
    public String dimension;

    @Column(name = "process_no", nullable = false, length = 20)
    public String processNo;

    @Column(name = "process_name", length = 50)
    public String processName;

    @Column(name = "resource_group_no", nullable = false, length = 20)
    public String resourceGroupNo;

    @Column(name = "seq_no", nullable = false)
    public Integer seqNo;

    @Column(name = "consumable_no", nullable = false, length = 30)
    public String consumableNo;

    @Column(name = "consumable_name", length = 100)
    public String consumableName;

    @Column(name = "usage_qty", precision = 18, scale = 6)
    public BigDecimal usageQty;

    @Column(name = "life_qty")
    public Long lifeQty;

    /** TIMES / PCS / HOURS */
    @Column(name = "life_unit", length = 20)
    public String lifeUnit;

    @Column(name = "usage_unit", length = 20)
    public String usageUnit;

    @Column(name = "consumable_version", length = 20)
    public String consumableVersion;
}
