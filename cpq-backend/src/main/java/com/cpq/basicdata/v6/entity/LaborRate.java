package com.cpq.basicdata.v6.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/** V6 §22 工时单价表（版本+料号+工序+工种 UNIQUE）。 */
@Entity
@Table(name = "labor_rate")
public class LaborRate extends V6BaseEntity {

    @Column(name = "version_no", nullable = false, length = 20)
    public String versionNo;

    @Column(name = "material_no", length = 20)
    public String materialNo;

    @Column(name = "process_no", nullable = false, length = 20)
    public String processNo;

    @Column(name = "process_name", length = 50)
    public String processName;

    @Column(name = "labor_grade", length = 30)
    public String laborGrade;

    @Column(name = "standard_labor_rate", nullable = false, precision = 18, scale = 6)
    public BigDecimal standardLaborRate;

    @Column(name = "currency", length = 10)
    public String currency;

    @Column(name = "unit", length = 20)
    public String unit;

    @Column(name = "effective_date")
    public LocalDate effectiveDate;
}
