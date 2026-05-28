package com.cpq.basicdata.v6.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/** V6 §21 电价表（地区+电压等级+峰平谷+生效日期+版本）。 */
@Entity
@Table(name = "electricity_price")
public class ElectricityPrice extends V6BaseEntity {

    @Column(name = "region", nullable = false, length = 50)
    public String region;

    @Column(name = "voltage_level", length = 20)
    public String voltageLevel;

    /** 峰 / 平 / 谷 / 均价 */
    @Column(name = "price_type", nullable = false, length = 20)
    public String priceType;

    @Column(name = "time_range", length = 50)
    public String timeRange;

    @Column(name = "price", nullable = false, precision = 18, scale = 6)
    public BigDecimal price;

    @Column(name = "unit", length = 20)
    public String unit;

    @Column(name = "effective_date", nullable = false)
    public LocalDate effectiveDate;

    @Column(name = "expire_date")
    public LocalDate expireDate;

    @Column(name = "version_no", length = 20)
    public String versionNo;
}
