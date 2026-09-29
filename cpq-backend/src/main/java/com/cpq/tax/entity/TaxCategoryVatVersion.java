package com.cpq.tax.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "tax_category_vat_version")
public class TaxCategoryVatVersion extends PanacheEntityBase {
    @Id @GeneratedValue public UUID id;
    @Column(name = "tax_category_id", nullable = false) public UUID taxCategoryId;
    @Column(name = "vat_rate", nullable = false, precision = 7, scale = 4) public BigDecimal vatRate;
    @Column(name = "effective_from", nullable = false) public LocalDate effectiveFrom;
    @Column(name = "effective_to") public LocalDate effectiveTo;
    @Column(nullable = false, length = 16) public String status = "ACTIVE";
    @Column(name = "created_at", nullable = false) public OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false) public OffsetDateTime updatedAt;
    @Column(name = "created_by") public UUID createdBy;
    @Column(name = "updated_by") public UUID updatedBy;

    @PrePersist public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }
    @PreUpdate public void preUpdate() { updatedAt = OffsetDateTime.now(); }
}
