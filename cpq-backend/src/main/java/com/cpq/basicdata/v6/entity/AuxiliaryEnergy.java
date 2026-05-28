package com.cpq.basicdata.v6.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** V6 §17 辅助设备能耗（按工时/数量摊销至料号工序）。 */
@Entity
@Table(name = "auxiliary_energy")
public class AuxiliaryEnergy extends V6BaseEntity {

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

    /** HOURS / QTY */
    @Column(name = "amortize_basis", length = 20)
    public String amortizeBasis;

    @Column(name = "working_hours", precision = 18, scale = 6)
    public BigDecimal workingHours;

    @Column(name = "total_hours", precision = 18, scale = 6)
    public BigDecimal totalHours;

    @Column(name = "non_production_energy_price", precision = 18, scale = 6)
    public BigDecimal nonProductionEnergyPrice;

    @Column(name = "currency", length = 10)
    public String currency;

    @Column(name = "unit", length = 20)
    public String unit;

    @Column(name = "conversion_rate", precision = 18, scale = 6)
    public BigDecimal conversionRate;

    @Column(name = "calc_version", length = 20)
    public String calcVersion;
}
