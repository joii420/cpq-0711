package com.cpq.basicdata.v6.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** V6 §18 模具工装成本（料号+工序+项次+模具号 UNIQUE）。 */
@Entity
@Table(name = "tooling_cost")
public class ToolingCost extends V6BaseEntity {

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

    @Column(name = "seq_no", nullable = false)
    public Integer seqNo;

    @Column(name = "tooling_no", nullable = false, length = 30)
    public String toolingNo;

    @Column(name = "tooling_unit_cost", precision = 18, scale = 6)
    public BigDecimal toolingUnitCost;

    @Column(name = "tool_life")
    public Long toolLife;

    @Column(name = "cycle_output", precision = 18, scale = 6)
    public BigDecimal cycleOutput;

    @Column(name = "tooling_unit_price", nullable = false, precision = 18, scale = 8)
    public BigDecimal toolingUnitPrice;

    @Column(name = "currency", length = 10)
    public String currency;

    @Column(name = "unit", length = 20)
    public String unit;

    @Column(name = "is_effective")
    public Boolean isEffective;

    @Column(name = "conversion_rate", precision = 18, scale = 6)
    public BigDecimal conversionRate;
}
