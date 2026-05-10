package com.cpq.quotation.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "quotation")
public class Quotation extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "quotation_number", nullable = false, unique = true, length = 50)
    public String quotationNumber;

    @Column(name = "customer_id", nullable = false)
    public UUID customerId;

    @Column(nullable = false, length = 500)
    public String name;

    @Column(name = "contact_id")
    public UUID contactId;

    @Column(name = "contact_name", length = 200)
    public String contactName;

    @Column(name = "contact_phone", length = 50)
    public String contactPhone;

    @Column(name = "contact_email", length = 200)
    public String contactEmail;

    @Column(name = "project_name", length = 500)
    public String projectName;

    @Column(name = "opportunity_id", length = 200)
    public String opportunityId;

    @Column(name = "sales_rep_id", nullable = false)
    public UUID salesRepId;

    @Column(name = "quote_type", length = 20)
    public String quoteType = "STANDARD";

    @Column(length = 10)
    public String priority = "MEDIUM";

    @Column(length = 30)
    public String stage = "INITIAL_CONTACT";

    @Column(name = "expected_close_date")
    public LocalDate expectedCloseDate;

    @Column(nullable = false, length = 20)
    public String status = "DRAFT";

    @Column(name = "total_amount", precision = 18, scale = 4)
    public BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "expiry_date")
    public LocalDate expiryDate;

    @Column(name = "payment_terms", columnDefinition = "TEXT")
    public String paymentTerms;

    @Column(name = "delivery_cycle")
    public Integer deliveryCycle;

    @Column(name = "original_amount", precision = 18, scale = 4)
    public BigDecimal originalAmount = BigDecimal.ZERO;

    @Column(name = "system_discount_rate", precision = 5, scale = 2)
    public BigDecimal systemDiscountRate = new BigDecimal("100");

    @Column(name = "final_discount_rate", precision = 5, scale = 2)
    public BigDecimal finalDiscountRate = new BigDecimal("100");

    @Column(name = "discount_adjustment_reason", columnDefinition = "TEXT")
    public String discountAdjustmentReason;

    @Column(name = "tax_rate", precision = 5, scale = 2, nullable = false)
    public BigDecimal taxRate = BigDecimal.ZERO;

    @Column(name = "tax_amount", precision = 18, scale = 4, nullable = false)
    public BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "is_manually_adjusted")
    public Boolean isManuallyAdjusted = false;

    @Column(name = "source_quotation_id")
    public UUID sourceQuotationId;

    @Column(name = "assigned_approver_id")
    public UUID assignedApproverId;

    @Column(name = "customer_template_id")
    public UUID customerTemplateId;

    // V72：核价模板（template 表里 template_kind='COSTING' 的那条）→ 用于「核价单」视图的产品卡片渲染
    @Column(name = "costing_card_template_id")
    public UUID costingCardTemplateId;

    @Column(name = "import_batch_id")
    public UUID importBatchId;

    @Column(columnDefinition = "TEXT")
    public String remarks;

    @Column(name = "snapshot_customer_name", length = 200)
    public String snapshotCustomerName;

    @Column(name = "snapshot_customer_level", length = 20)
    public String snapshotCustomerLevel;

    @Column(name = "snapshot_customer_region", length = 100)
    public String snapshotCustomerRegion;

    @Column(name = "snapshot_customer_industry", length = 100)
    public String snapshotCustomerIndustry;

    @Column(name = "snapshot_customer_address", columnDefinition = "TEXT")
    public String snapshotCustomerAddress;

    /**
     * v5.1 §6.6 DRAFT 漂移检测：记录本报价单创建/保存时所引用的基础数据版本快照。
     * 格式：{"mat_process":{"<hfPartNo>|<customerId>":<version>,...}, "mat_fee":{...}, ...}
     * 仅 DRAFT 状态下写入；submit 后保留原值（历史记录）。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "referenced_versions", columnDefinition = "jsonb")
    public String referencedVersions;

    /**
     * v5.1 §10 提交快照：DRAFT→SUBMITTED 时冻结的全量数据快照。
     * 格式：{ referencedVersions, elementActualPrices, formulaDefinitions, masterDataSnapshot, snapshotAt }
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "submission_snapshot", columnDefinition = "jsonb")
    public String submissionSnapshot;

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
