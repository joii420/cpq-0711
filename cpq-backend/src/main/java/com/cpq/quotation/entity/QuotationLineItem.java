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

    // ─── Step3 行级折扣（V302）─────────────────────────────────────────────
    @Column(name = "annual_volume")
    public Integer annualVolume;

    @Column(name = "discount_source", length = 64)
    public String discountSource;

    @Column(name = "discount_base_amount", precision = 18, scale = 4)
    public BigDecimal discountBaseAmount;

    @Column(name = "discount_rate_applied", precision = 5, scale = 2)
    public BigDecimal discountRateApplied;

    @Column(name = "line_discount_amount", precision = 18, scale = 4)
    public BigDecimal lineDiscountAmount;

    @Column(name = "line_unit_price", precision = 18, scale = 4)
    public BigDecimal lineUnitPrice;

    @Column(name = "line_final_price", precision = 18, scale = 4)
    public BigDecimal lineFinalPrice;

    @Column(name = "line_total_amount", precision = 18, scale = 4)
    public BigDecimal lineTotalAmount;

    @Column(name = "discount_rule_code", length = 64)
    public String discountRuleCode;

    @Column(name = "customer_part_no", length = 200)
    public String customerPartNo;

    /**
     * 料号版本管理 (V155): 本行报价使用的 (customer_product_no, hf_part_no) 版本号.
     * 创建时从 mat_customer_part_mapping.current_version 拷贝, 已发布后锁死.
     * S5 阶段 QuotationService.createDraft 写入, ExcelViewService/SnapshotCollectorService 读取.
     */
    @Column(name = "part_version_locked", nullable = false)
    public Integer partVersionLocked = 2000;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "excel_view_snapshot", columnDefinition = "jsonb")
    public String excelViewSnapshot;

    // -------------------------------------------------------------------------
    // 报价单整份快照 Phase 1 — 产品行级 4 份值快照（P2 物理分开）
    // -------------------------------------------------------------------------

    /** 报价卡片值：tabs[].{baseRows, editRows, formulaResults}，草稿重刷/编辑回写 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "quote_card_values")
    public String quoteCardValues;

    /** 报价 Excel 值：rows[]（算好的最终列值），草稿重刷/编辑回写 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "quote_excel_values")
    public String quoteExcelValues;

    /** 核价卡片值：tabs[].{baseRows, formulaResults}，加产品写、永久只读 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "costing_card_values")
    public String costingCardValues;

    /** 核价 Excel 值：rows[]，加产品写、永久只读 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "costing_excel_values")
    public String costingExcelValues;

    /** 加产品四份冻结时间（card + excel 一起冻） */
    @Column(name = "card_snapshot_at")
    public OffsetDateTime cardSnapshotAt;

    /** 报价侧最近重刷/编辑回写时间 */
    @Column(name = "quote_values_at")
    public OffsetDateTime quoteValuesAt;

    /**
     * V169 加的列, 标识选配组合产品的父子关系 (SIMPLE / COMPOSITE / PART).
     * SIMPLE: 普通产品 (默认); COMPOSITE: 选配组合产品父级; PART: COMPOSITE 的子件
     */
    @Column(name = "composite_type", length = 16)
    public String compositeType = "SIMPLE";

    /** V169 加的列, PART 行指向父级 line_item.id, 其他类型为 null */
    @Column(name = "parent_line_item_id")
    public UUID parentLineItemId;

    /**
     * task-0721 B7：BOM 树节点级墓碑（剪枝）。JSON 字符串数组 {@code ["<nodeId>", ...]}。
     * 树页签渲染时按 {@code __nodeId} 前缀匹配隐藏整枝（跨该报价行所有树页签联动）。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "deleted_tree_nodes")
    public String deletedTreeNodes;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
