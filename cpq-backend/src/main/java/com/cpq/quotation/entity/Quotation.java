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

    // task-0810：工作值列 numeric(26,12)；9 位显示在呈现边界处理。
    @Column(name = "total_amount", precision = 26, scale = 12)
    public BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "expiry_date")
    public LocalDate expiryDate;

    @Column(name = "payment_terms", columnDefinition = "TEXT")
    public String paymentTerms;

    @Column(name = "delivery_cycle")
    public Integer deliveryCycle;

    // task-0801 B7：precision/scale 18,4 → 20,6（V366）。
    @Column(name = "original_amount", precision = 26, scale = 12)
    public BigDecimal originalAmount = BigDecimal.ZERO;

    @Column(name = "system_discount_rate", precision = 5, scale = 2)
    public BigDecimal systemDiscountRate = new BigDecimal("100");

    @Column(name = "final_discount_rate", precision = 5, scale = 2)
    public BigDecimal finalDiscountRate = new BigDecimal("100");

    @Column(name = "discount_adjustment_reason", columnDefinition = "TEXT")
    public String discountAdjustmentReason;

    @Column(name = "tax_rate", precision = 5, scale = 2, nullable = false)
    public BigDecimal taxRate = BigDecimal.ZERO;

    // task-0801 B7：precision/scale 18,4 → 20,6（V366）。
    @Column(name = "tax_amount", precision = 26, scale = 12, nullable = false)
    public BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "is_manually_adjusted")
    public Boolean isManuallyAdjusted = false;

    @Column(name = "source_quotation_id")
    public UUID sourceQuotationId;

    @Column(name = "assigned_approver_id")
    public UUID assignedApproverId;

    @Column(name = "customer_template_id")
    public UUID customerTemplateId;

    /**
     * task-0729: 建单时的产品分类（前端匹配模板用的一次性参数，落库后成为一等数据）。
     * 建单时前端传什么就存什么，后端不做二次推导（不反查客户绑定），避免与 D4
     * （客户改绑分类不追溯已有报价单）冲突。存量单本列为 NULL，前端回落到
     * 「从 customer_template_id 反查模板分类」兜底。
     */
    @Column(name = "product_category_id")
    public UUID productCategoryId;

    /**
     * task-260901 B-3：<b>用户数据</b>版本号（V398）。saveDraft / quote-card-edit 等「用户改了东西」
     * 的写入 +1；ensureCardValues / ensureExcelValues / snapshotQuotation / 建单物化 / priceReconcile
     * 这些<b>系统自算派生数据</b>的写入<b>绝不</b>递增（api.md §4.2，AC-13）。
     *
     * <p>🚫 不是 JPA {@code @Version}：这里要的是「用户数据变了没有」的语义，不是「这一行被写过没有」。
     * 挂 {@code @Version} 会让上面那些派生写入自动把它 +1，等于用户什么都没做就被要求刷新，
     * 形成「保存 → 重算 → 必冲突 → 刷新 → 保存」死循环。
     *
     * <p>也与 {@code quotation_line_item.row_version}（V368 price-adjust 的原生 SQL 乐观锁）无关。
     *
     * <h3>🔒 {@code insertable=false, updatable=false} —— B-3e 的结构性保证，不要摘掉</h3>
     * {@code Quotation} 实体<b>没有</b> {@code @DynamicUpdate}，任何事务只要碰过这个实体的<b>任何一个</b>
     * 字段，Hibernate 就会发一条<b>全列</b> UPDATE，把事务开始时读到的 {@code user_data_version} 一起写回。
     * 而 {@code ensureCardValues} / {@code recomputeDraftHeaderTotals} / 建单物化 / 归位这些派生路径
     * 全都会改 {@code total_amount} 之类的列并跑上一两秒——中间只要有一次 saveDraft 提交，版本号就会被
     * 它们<b>倒退</b>回旧值。倒退的后果和递增一样糟：前端手里的 baseVersion 比库里大 ⇒ 用户什么都没做错
     * 却被判 409 强制刷新（AC-13 要防的正是这个）。
     *
     * <p>所以本列<b>只读映射</b>：读得到（{@code QuotationDTO} 直接取），但 Hibernate 一个字节都写不了。
     * 唯一的写入口是 {@code QuotationService#bumpUserDataVersion} 的原生
     * {@code UPDATE quotation SET user_data_version = user_data_version + 1}——与 V368
     * {@code row_version} 的既有做法同一个套路（原生 SQL 乐观锁列，不是 JPA {@code @Version}）。
     */
    @Column(name = "user_data_version", insertable = false, updatable = false)
    public Integer userDataVersion = 0;

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
