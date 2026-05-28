package com.cpq.importexcel.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "import_record")
public class ImportRecord extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "quotation_id")
    public UUID quotationId;

    @Column(name = "customer_id")
    public UUID customerId;

    /** V6 系统区分：QUOTE / PRICING / OTHER */
    @Column(name = "system_type", length = 20)
    public String systemType;

    @Column(name = "template_id")
    public UUID templateId;

    @Column(name = "excel_template_id")
    public UUID excelTemplateId;

    @Column(name = "mapping_template_id")
    public UUID mappingTemplateId;

    @Column(name = "costing_template_id")
    public UUID costingTemplateId;

    @Column(name = "customer_template_id")
    public UUID customerTemplateId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "costing_template_snapshot", columnDefinition = "jsonb")
    public String costingTemplateSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "customer_template_snapshot", columnDefinition = "jsonb")
    public String customerTemplateSnapshot;

    @Column(name = "import_batch_id")
    public UUID importBatchId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mapping_snapshot", columnDefinition = "jsonb")
    public String mappingSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_snapshot", columnDefinition = "jsonb")
    public String configSnapshot;

    @Column(name = "original_file_name", nullable = false, length = 500)
    public String originalFileName;

    @Column(name = "original_file_path", nullable = true, length = 1000)
    public String originalFilePath;

    @Column(name = "total_rows")
    public Integer totalRows;

    @Column(name = "success_rows")
    public Integer successRows;

    @Column(name = "matched_rows")
    public Integer matchedRows;

    @Column(name = "unmatched_rows")
    public Integer unmatchedRows;

    @Column(name = "import_status", nullable = false, length = 20)
    public String importStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "error_detail", columnDefinition = "jsonb")
    public String errorDetail;

    @Column(name = "imported_by", nullable = false)
    public UUID importedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    public String metadata;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
