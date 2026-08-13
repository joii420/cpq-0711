package com.cpq.basicdata.v6.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

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

    @Column(name = "unit_weight", precision = 24, scale = 12)
    public BigDecimal unitWeight;

    @Column(name = "standard_unit", length = 20)
    public String standardUnit;

    /** 生产料号(repair-1 决策A: material_master 作生产料号权威归属; 与销售料号 material_no 1:1, 不进键)。 */
    @Column(name = "production_no", length = 32)
    public String productionNo;

    /** repair-0726 B1(V362)：非 null = 该料号由某未核准报价单首次带入、尚未核价通过转正，
     *  正表内的"暂存"标记(取代已退役的 pending_material_master_staging)。
     *  CRUD 层(见 {@code MaterialMasterCrudService}) 不写此字段、DTO 不暴露(接口零变化)。 */
    @Column(name = "pending_quotation_id")
    public UUID pendingQuotationId;
}
