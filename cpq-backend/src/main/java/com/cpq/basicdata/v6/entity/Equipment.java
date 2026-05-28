package com.cpq.basicdata.v6.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/** V6 §15 设备表。业务键 equipment_no UNIQUE。 */
@Entity
@Table(name = "equipment")
public class Equipment extends V6BaseEntity {

    @Column(name = "equipment_no", nullable = false, length = 30)
    public String equipmentNo;

    @Column(name = "equipment_name", nullable = false, length = 100)
    public String equipmentName;

    @Column(name = "equipment_type", length = 50)
    public String equipmentType;

    @Column(name = "resource_group_no", nullable = false, length = 20)
    public String resourceGroupNo;

    @Column(name = "resource_group_name", length = 50)
    public String resourceGroupName;

    @Column(name = "workshop", length = 50)
    public String workshop;

    @Column(name = "original_amount", nullable = false, precision = 18, scale = 2)
    public BigDecimal originalAmount;

    @Column(name = "residual_value", precision = 18, scale = 2)
    public BigDecimal residualValue;

    /** STRAIGHT_LINE / SUM_YEARS / DOUBLE_DECLINING / UNITS */
    @Column(name = "depreciation_method", nullable = false, length = 30)
    public String depreciationMethod;

    @Column(name = "depreciation_years", precision = 10, scale = 2)
    public BigDecimal depreciationYears;

    @Column(name = "annual_available_hours", nullable = false, precision = 18, scale = 2)
    public BigDecimal annualAvailableHours;

    @Column(name = "production_calendar", length = 50)
    public String productionCalendar;

    @Column(name = "purchase_date")
    public LocalDate purchaseDate;

    @Column(name = "annual_depreciation", precision = 18, scale = 6)
    public BigDecimal annualDepreciation;

    @Column(name = "hourly_depreciation", precision = 18, scale = 6)
    public BigDecimal hourlyDepreciation;

    @Column(name = "currency", length = 10)
    public String currency;

    /** IN_USE / IDLE / SCRAPPED */
    @Column(name = "status", length = 20)
    public String status;
}
