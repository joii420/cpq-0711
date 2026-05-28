package com.cpq.basicdata.v6.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** V6 §8 工序主数据。业务键 process_no UNIQUE。 */
@Entity
@Table(name = "process_master")
public class ProcessMaster extends V6BaseEntity {

    @Column(name = "process_no", nullable = false, length = 20)
    public String processNo;

    @Column(name = "process_name", nullable = false, length = 50)
    public String processName;

    /** 制造 / 组装 / 电镀 / 外协 / 包装 / 清洗 */
    @Column(name = "process_category", length = 30)
    public String processCategory;

    @Column(name = "is_outsource")
    public Boolean isOutsource;

    @Column(name = "standard_currency", length = 10)
    public String standardCurrency;

    @Column(name = "standard_unit", length = 20)
    public String standardUnit;

    @Column(name = "default_defect_rate", precision = 10, scale = 4)
    public BigDecimal defaultDefectRate;
}
