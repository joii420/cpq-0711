package com.cpq.basicdata.v6.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** V6 §1 料号主表。业务键 material_no UNIQUE。 */
@Entity
@Table(name = "material_master")
public class MaterialMaster extends V6BaseEntity {

    @Column(name = "material_no", nullable = false, length = 20)
    public String materialNo;

    @Column(name = "material_name", length = 100)
    public String materialName;

    @Column(name = "specification", length = 100)
    public String specification;

    @Column(name = "dimension", length = 100)
    public String dimension;

    @Column(name = "old_material_no", length = 50)
    public String oldMaterialNo;

    /** 1.银点类 / 2.非银点类 / 组成件 / 边角料 */
    @Column(name = "material_type", length = 50)
    public String materialType;

    /** 1.正常 / 2.回收料 */
    @Column(name = "usage_property", length = 50)
    public String usageProperty;

    @Column(name = "unit_weight", precision = 18, scale = 6)
    public BigDecimal unitWeight;

    @Column(name = "standard_unit", length = 20)
    public String standardUnit;
}
