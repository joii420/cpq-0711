package com.cpq.basicdata.v6.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** V6 §16 生产设备能耗（料号+工序+设备+计算版本）。设备折旧 + 生产能耗合并写入此表。 */
@Entity
@Table(name = "production_energy")
public class ProductionEnergy extends V6BaseEntity {

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

    @Column(name = "equipment_no", length = 30)
    public String equipmentNo;

    @Column(name = "batch_size", precision = 18, scale = 6)
    public BigDecimal batchSize;

    @Column(name = "round_step", precision = 18, scale = 6)
    public BigDecimal roundStep;

    @Column(name = "working_hours", precision = 18, scale = 6)
    public BigDecimal workingHours;

    @Column(name = "energy_unit_price", precision = 18, scale = 6)
    public BigDecimal energyUnitPrice;

    @Column(name = "depreciation_unit_price", precision = 18, scale = 6)
    public BigDecimal depreciationUnitPrice;

    @Column(name = "currency", length = 10)
    public String currency;

    @Column(name = "unit", length = 20)
    public String unit;

    @Column(name = "conversion_rate", precision = 18, scale = 6)
    public BigDecimal conversionRate;

    @Column(name = "calc_version", length = 20)
    public String calcVersion;
}
