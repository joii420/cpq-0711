package com.cpq.costingbasic.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "costing_element_price")
public class CostingElementPrice extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "version_id", nullable = false)
    public UUID versionId;

    @Column(name = "element_code", nullable = false, length = 50)
    public String elementCode;

    @Column(name = "costing_price", nullable = false, precision = 18, scale = 4)
    public BigDecimal costingPrice;

    @Column(name = "market_ref_price", precision = 18, scale = 4)
    public BigDecimal marketRefPrice;

    @Column(name = "source_url", length = 500)
    public String sourceUrl;

    @Column(name = "source_name", length = 200)
    public String sourceName;

    @Column(name = "source_rule", columnDefinition = "TEXT")
    public String sourceRule;

    @Column(nullable = false, length = 10)
    public String currency = "CNY";

    @Column(nullable = false, length = 20)
    public String unit = "KG";

    @Column(name = "discount_rate", precision = 5, scale = 2)
    public BigDecimal discountRate;

    @Column(name = "sort_order", nullable = false)
    public Integer sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
