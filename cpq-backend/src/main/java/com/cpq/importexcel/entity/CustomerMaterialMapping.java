package com.cpq.importexcel.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "customer_material_mapping")
public class CustomerMaterialMapping extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "customer_id", nullable = false)
    public UUID customerId;

    @Column(name = "customer_part_no", nullable = false, length = 200)
    public String customerPartNo;

    @Column(name = "material_id", nullable = false)
    public UUID materialId;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
