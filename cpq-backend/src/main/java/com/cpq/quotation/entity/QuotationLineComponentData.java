package com.cpq.quotation.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "quotation_line_component_data")
public class QuotationLineComponentData extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "line_item_id", nullable = false)
    public UUID lineItemId;

    @Column(name = "component_id")
    public UUID componentId;

    @Column(name = "tab_name", length = 200)
    public String tabName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "row_data", columnDefinition = "jsonb")
    public String rowData = "[]";

    @Column(precision = 18, scale = 4)
    public BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "sort_order")
    public Integer sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
