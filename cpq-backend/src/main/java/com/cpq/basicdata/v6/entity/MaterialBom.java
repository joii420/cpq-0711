package com.cpq.basicdata.v6.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.util.UUID;

/**
 * V6 §3 物料BOM主表。
 * 业务键 (system_type, customer_no, material_no, bom_version, characteristic) UNIQUE。
 */
@Entity
@Table(name = "material_bom")
public class MaterialBom extends V6BaseEntity {

    /** QUOTE / PRICING / BOTH */
    @Column(name = "system_type", nullable = false, length = 10)
    public String systemType;

    @Column(name = "customer_no", nullable = false, length = 20)
    public String customerNo;

    /** MATERIAL / ASSEMBLY */
    @Column(name = "bom_type", nullable = false, length = 20)
    public String bomType;

    @Column(name = "bom_version", nullable = false, length = 20)
    public String bomVersion;

    /** DRAFT / RELEASED / OBSOLETE */
    @Column(name = "bom_status", length = 20)
    public String bomStatus;

    @Column(name = "plant", length = 20)
    public String plant;

    @Column(name = "valid_from")
    public LocalDate validFrom;

    @Column(name = "valid_to")
    public LocalDate validTo;

    @Column(name = "material_no", nullable = false, length = 20)
    public String materialNo;

    @Column(name = "characteristic", length = 100)
    public String characteristic;

    @Column(name = "batch_qty", length = 100)
    public String batchQty;

    @Column(name = "production_unit", length = 100)
    public String productionUnit;

    /** task-0721 B1：本行归属的未审核报价单（NULL=正式/历史；非 NULL=该报价单私有 pending 草稿）。 */
    @Column(name = "pending_quotation_id")
    public UUID pendingQuotationId;

    /** task-0721 B1：pending 行点名它取代的旧 current 行 id 集合（供 B3 视图改写"遮蔽"用）。 */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "pending_supersedes")
    public UUID[] pendingSupersedes;
}
