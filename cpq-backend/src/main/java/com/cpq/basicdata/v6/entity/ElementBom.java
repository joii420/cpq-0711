package com.cpq.basicdata.v6.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDate;

/** V6 §5 元素BOM主表。characteristic 必填。 */
@Entity
@Table(name = "element_bom")
public class ElementBom extends V6BaseEntity {

    @Column(name = "system_type", nullable = false, length = 10)
    public String systemType;

    @Column(name = "customer_no", nullable = false, length = 20)
    public String customerNo;

    @Column(name = "bom_type", nullable = false, length = 20)
    public String bomType;

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

    @Column(name = "characteristic", nullable = false, length = 100)
    public String characteristic;

    @Column(name = "batch_qty", length = 100)
    public String batchQty;

    @Column(name = "production_unit", length = 100)
    public String productionUnit;
}
