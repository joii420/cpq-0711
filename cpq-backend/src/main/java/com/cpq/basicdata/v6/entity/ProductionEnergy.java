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

    @Column(name = "batch_size", precision = 24, scale = 12)
    public BigDecimal batchSize;

    @Column(name = "round_step", precision = 24, scale = 12)
    public BigDecimal roundStep;

    @Column(name = "working_hours", precision = 24, scale = 12)
    public BigDecimal workingHours;

    @Column(name = "currency", length = 10)
    public String currency;

    @Column(name = "unit", length = 20)
    public String unit;

    @Column(name = "conversion_rate", precision = 24, scale = 12)
    public BigDecimal conversionRate;

    @Column(name = "calc_version", length = 20)
    public String calcVersion;

    /** ENERGY=能耗 / DEPRECIATION=折旧；与 unitPrice 配合区分来源 sheet。 */
    @Column(name = "price_type", length = 24)
    public String priceType;

    /** 版本升级(tesk-0709) 归属系统标记，默认 PRICING。 */
    @Column(name = "system_type", length = 16)
    public String systemType;

    /** 单价（按 priceType 区分类型；替代原 energyUnitPrice / depreciationUnitPrice 两列）。
     *  task-0813 §6.2：DB 早已是 (24,12)，此处修正此前落后的实体声明（scale 6→12 漂移）。 */
    @Column(name = "unit_price", precision = 24, scale = 12)
    public BigDecimal unitPrice;

    @Column(name = "is_current")
    public Boolean isCurrent;
}
