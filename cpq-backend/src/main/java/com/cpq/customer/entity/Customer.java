package com.cpq.customer.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "customer")
public class Customer extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(nullable = false, length = 200)
    public String name;

    @Column(nullable = false, unique = true, length = 50)
    public String code;

    @Column(nullable = false, length = 20)
    public String level = "STANDARD";

    @Column(length = 100)
    public String industry;

    @Column(name = "industry_code", length = 50)
    public String industryCode;

    @Column(name = "product_category_id", nullable = false)
    public UUID productCategoryId;

    @Column(length = 100)
    public String region;

    @Column(columnDefinition = "TEXT")
    public String address;

    @Column(name = "accumulated_amount", nullable = false, precision = 18, scale = 4)
    public BigDecimal accumulatedAmount = BigDecimal.ZERO;

    @Column(name = "credit_limit", precision = 18, scale = 4)
    public BigDecimal creditLimit;

    @Column(name = "payment_method", length = 100)
    public String paymentMethod;

    @Column(columnDefinition = "TEXT")
    public String remarks;

    @Column(nullable = false, length = 20)
    public String status = "ACTIVE";

    @Version
    @Column(nullable = false)
    public Integer version = 0;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
