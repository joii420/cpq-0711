package com.cpq.quotation.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "quotation_line_item_snapshot")
public class QuotationLineItemSnapshot extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "line_item_id", nullable = false)
    public UUID lineItemId;

    @Column(name = "product_part_no", length = 100)
    public String productPartNo;

    @Column(name = "product_category", length = 30)
    public String productCategory;

    @Column(name = "product_specification", length = 500)
    public String productSpecification;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
