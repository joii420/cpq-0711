package com.cpq.quotation.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 报价单级 4 份结构快照（报价单整份快照 Phase 1）。
 *
 * <p>grain = quotation × view_kind（4 行/单）。结构在报价单创建/确定模板时固定，永不变更。
 * <p>view_kind 枚举：QUOTE_CARD / QUOTE_EXCEL / COSTING_CARD / COSTING_EXCEL。
 */
@Entity
@Table(name = "quotation_view_structure")
public class QuotationViewStructure extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    /** 所属报价单（ON DELETE CASCADE） */
    @Column(name = "quotation_id", nullable = false)
    public UUID quotationId;

    /**
     * 视图类型：QUOTE_CARD | QUOTE_EXCEL | COSTING_CARD | COSTING_EXCEL
     * 数据库层有 CHECK 约束保证合法值。
     */
    @Column(name = "view_kind", nullable = false)
    public String viewKind;

    /**
     * 结构 JSON（不可变，创建即冻）。
     * 卡片结构形状见 design §3.1；Excel 结构为 columns 列定义数组。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structure", nullable = false)
    public String structure;

    @Column(name = "created_at")
    public OffsetDateTime createdAt;

    // -----------------------------------------------------------------------
    // 查询助手
    // -----------------------------------------------------------------------

    /**
     * 按报价单 ID + 视图类型查唯一行（利用 DB UNIQUE 约束）。
     * 不存在时返回 null。
     */
    public static QuotationViewStructure findByQuotationAndKind(UUID quotationId, String viewKind) {
        return find("quotationId = ?1 and viewKind = ?2", quotationId, viewKind).firstResult();
    }
}
