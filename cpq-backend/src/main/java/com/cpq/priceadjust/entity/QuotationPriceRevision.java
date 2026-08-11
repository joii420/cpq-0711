package com.cpq.priceadjust.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * task-0729 B0-R · 报价单 R 版本轨迹（屏 7）。
 *
 * <p>初版 {@code basedVersionId} 留空（D6），{@code sealed} 定型标记（§11.10.6）：
 * 未定型时 {@code quoteCardValues}/{@code costingCardValues}/{@code snapshotRows} 留 NULL，
 * 渲染时取当前值；该单首次被任意料号升版时物化 + 置 sealed=true，从此冻结。
 *
 * <p>快照内容 = 整单双侧（§11.7.0）：三个 JSONB 列均以 {@code lineItemId（字符串）→ 值} 的对象
 * 结构存储，覆盖该报价单全部产品行（不是稀疏存储、不只存被升版的料号），
 * {@code snapshotRows} 额外一层按 {@code componentDataId} 展开（同一行下可能有多个组件）：
 * <pre>
 * quoteCardValues   = { "&lt;lineItemId&gt;": &lt;quote_card_values 原始 JSON&gt;, ... }
 * costingCardValues = { "&lt;lineItemId&gt;": &lt;costing_card_values 原始 JSON&gt;, ... }
 * snapshotRows       = { "&lt;lineItemId&gt;": { "&lt;componentDataId&gt;": &lt;snapshot_rows 原始 JSON&gt;, ... }, ... }
 * </pre>
 */
@Entity
@Table(name = "quotation_price_revision")
public class QuotationPriceRevision extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "quotation_id", nullable = false)
    public UUID quotationId;

    @Column(name = "revision_no", nullable = false, length = 20)
    public String revisionNo;

    @Column(name = "based_version_id")
    public UUID basedVersionId;

    @Column(name = "sealed", nullable = false)
    public Boolean sealed = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "upgraded_material_nos", columnDefinition = "jsonb", nullable = false)
    public String upgradedMaterialNos = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "quote_card_values", columnDefinition = "jsonb")
    public String quoteCardValues;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "costing_card_values", columnDefinition = "jsonb")
    public String costingCardValues;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot_rows", columnDefinition = "jsonb")
    public String snapshotRows;

    @Column(name = "quote_total_amount", precision = 26, scale = 12)
    public BigDecimal quoteTotalAmount;

    @Column(name = "first_effective_at", nullable = false)
    public OffsetDateTime firstEffectiveAt = OffsetDateTime.now();

    @Column(name = "last_updated_at", nullable = false)
    public OffsetDateTime lastUpdatedAt = OffsetDateTime.now();

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();

    /** 初版：based_version_id IS NULL（D6）。 */
    public static QuotationPriceRevision findInitial(UUID quotationId) {
        return find("quotationId = ?1 and basedVersionId is null", quotationId).firstResult();
    }

    /** 本期 R：同一 V 版内多次料号升版合并进同一条（UNIQUE(quotation_id, based_version_id)）。 */
    public static QuotationPriceRevision findByVersion(UUID quotationId, UUID basedVersionId) {
        return find("quotationId = ?1 and basedVersionId = ?2", quotationId, basedVersionId).firstResult();
    }

    public static boolean anyExists(UUID quotationId) {
        return count("quotationId", quotationId) > 0;
    }
}
