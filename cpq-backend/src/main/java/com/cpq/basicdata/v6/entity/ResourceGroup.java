package com.cpq.basicdata.v6.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDate;

/** V6 §14 资源群组。业务键 group_no UNIQUE。 */
@Entity
@Table(name = "resource_group")
public class ResourceGroup extends V6BaseEntity {

    @Column(name = "group_no", nullable = false, length = 20)
    public String groupNo;

    @Column(name = "group_name", nullable = false, length = 50)
    public String groupName;

    /** MACHINE / PLATING / ASSEMBLY / TEST */
    @Column(name = "group_type", length = 30)
    public String groupType;

    @Column(name = "seq_no")
    public Integer seqNo;

    @Column(name = "process_no", length = 20)
    public String processNo;

    @Column(name = "process_name", length = 50)
    public String processName;

    @Column(name = "workshop", length = 50)
    public String workshop;

    @Column(name = "equipment_id", length = 50)
    public String equipmentId;

    @Column(name = "description", length = 200)
    public String description;

    @Column(name = "effective_date")
    public LocalDate effectiveDate;

    @Column(name = "expire_date")
    public LocalDate expireDate;
}
