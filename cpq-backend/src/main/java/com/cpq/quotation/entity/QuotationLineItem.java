package com.cpq.quotation.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "quotation_line_item")
public class QuotationLineItem extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "quotation_id", nullable = false)
    public UUID quotationId;

    @Column(name = "product_id")
    public UUID productId;

    @Column(name = "template_id")
    public UUID templateId;

    @Column(name = "product_name_snapshot", length = 500)
    public String productNameSnapshot;

    @Column(name = "product_part_no_snapshot", length = 200)
    public String productPartNoSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "product_attribute_values", columnDefinition = "jsonb")
    public String productAttributeValues = "{}";

    @Column(precision = 18, scale = 4)
    public BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "system_discount_rate", precision = 5, scale = 2)
    public BigDecimal systemDiscountRate = new BigDecimal("100");

    @Column(name = "final_discount_rate", precision = 5, scale = 2)
    public BigDecimal finalDiscountRate = new BigDecimal("100");

    @Column(name = "discount_adjustment_reason", columnDefinition = "TEXT")
    public String discountAdjustmentReason;

    @Column(name = "is_manually_adjusted")
    public Boolean isManuallyAdjusted = false;

    @Column(name = "sort_order")
    public Integer sortOrder = 0;

    @Column(name = "customer_part_no", length = 200)
    public String customerPartNo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "excel_view_snapshot", columnDefinition = "jsonb")
    public String excelViewSnapshot;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
