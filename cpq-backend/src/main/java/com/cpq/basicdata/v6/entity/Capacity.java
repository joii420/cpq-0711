package com.cpq.basicdata.v6.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

/** V6 §11 产能表。业务键 (material_no, process_no, resource_group_no, calc_version) UNIQUE。 */
@Entity
@Table(name = "capacity")
public class Capacity extends V6BaseEntity {

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

    @Column(name = "resource_group_name", length = 50)
    public String resourceGroupName;

    /** UNIT / BATCH / BATCH_FIXED */
    @Column(name = "production_type", nullable = false, length = 20)
    public String productionType;

    @Column(name = "fixed_lead_time", precision = 18, scale = 6)
    public BigDecimal fixedLeadTime;

    @Column(name = "variable_time", precision = 18, scale = 6)
    public BigDecimal variableTime;

    @Column(name = "variable_time_batch", precision = 18, scale = 6)
    public BigDecimal variableTimeBatch;

    @Column(name = "capacity_unit", length = 20)
    public String capacityUnit;

    @Column(name = "default_defect_rate", precision = 10, scale = 4)
    public BigDecimal defaultDefectRate;

    @Column(name = "cost_type", length = 20)
    public String costType;

    @Column(name = "fixed_cost", precision = 18, scale = 6)
    public BigDecimal fixedCost;

    @Column(name = "cost_ratio", precision = 10, scale = 4)
    public BigDecimal costRatio;

    @Column(name = "annual_discount_factor", precision = 10, scale = 4)
    public BigDecimal annualDiscountFactor;

    @Column(name = "calc_version", length = 20)
    public String calcVersion;

    @Column(name = "is_effective")
    public Boolean isEffective;

    @Column(name = "currency", length = 10)
    public String currency;

    /** task-0721 B1：本行归属的未审核报价单（NULL=正式/历史；非 NULL=该报价单私有 pending 草稿）。 */
    @Column(name = "pending_quotation_id")
    public UUID pendingQuotationId;

    /** task-0721 B1：pending 行点名它取代的旧 current 行 id 集合（供 B3 视图改写"遮蔽"用）。 */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "pending_supersedes")
    public UUID[] pendingSupersedes;
}
