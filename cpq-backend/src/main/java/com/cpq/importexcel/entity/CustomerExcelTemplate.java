package com.cpq.importexcel.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "customer_excel_template")
public class CustomerExcelTemplate extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(nullable = false, length = 300)
    public String name;

    @Column(name = "customer_id", nullable = false)
    public UUID customerId;

    @Column(columnDefinition = "TEXT")
    public String description;

    @Column(name = "header_row_index", nullable = false)
    public int headerRowIndex = 1;

    @Column(name = "data_start_row_index", nullable = false)
    public int dataStartRowIndex = 2;

    @Column(name = "sheet_index", nullable = false)
    public int sheetIndex = 0;

    @Column(name = "part_no_column", nullable = false, length = 200)
    public String partNoColumn;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "excel_columns", nullable = false, columnDefinition = "jsonb")
    public String excelColumns = "[]";

    @Column(name = "sample_file_name", length = 500)
    public String sampleFileName;

    @Column(name = "created_by")
    public UUID createdBy;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
