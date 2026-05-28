package com.cpq.template.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "template")
public class Template extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "template_series_id", nullable = false)
    public UUID templateSeriesId;

    @Column(nullable = false, length = 200)
    public String name;

    @Column(length = 20)
    public String version;

    @Column(length = 30)
    public String category;

    @Column(name = "customer_id")
    public UUID customerId;

    @Column(name = "category_id")
    public UUID categoryId;

    @Column(columnDefinition = "TEXT")
    public String description;

    @Column(name = "usage_note", columnDefinition = "TEXT")
    public String usageNote;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "product_attributes", columnDefinition = "jsonb")
    public String productAttributes = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "subtotal_formula", columnDefinition = "jsonb")
    public String subtotalFormula = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "components_snapshot", columnDefinition = "jsonb")
    public String componentsSnapshot;

    /**
     * 阶段 2: 组件级数据源 SQL 视图 snapshot —— 模板 DRAFT → PUBLISHED 时由
     * {@code TemplateService.publish} 调 {@code ComponentSqlViewService.snapshotForComponents}
     * 序列化，冻结该模板挂载组件引用的所有 SQL 视图（含 GLOBAL scope 闭包）。
     *
     * <p>结构: {@code { "componentId::sql_view_name": { sql_template, declared_columns,
     * required_variables, scope } }}
     *
     * <p>渲染期 lookupSqlView 优先级：报价单 snapshot &gt; 模板 snapshot &gt; 实时 component_sql_view。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sql_views_snapshot", columnDefinition = "jsonb")
    public String sqlViewsSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "excel_view_config", columnDefinition = "jsonb")
    public String excelViewConfig;

    /**
     * V250：发布时把本模板拥有的所有 template_sql_view 快照到此 JSONB，实现发布冻结。
     *
     * <p>结构: {@code {"<sql_view_name>": {"sqlTemplate": "...", "declaredColumns": [...], "requiredVariables": [...]}}}
     *
     * <p>注意：不要与 {@link #sqlViewsSnapshot} 混淆：
     * <ul>
     *   <li>{@code sql_views_snapshot} — 组件 SQL 视图（component_sql_view）的冻结快照</li>
     *   <li>{@code template_sql_views_snapshot}（本字段）— 模板自有 SQL 视图（template_sql_view）的冻结快照</li>
     * </ul>
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "template_sql_views_snapshot", columnDefinition = "jsonb", nullable = false)
    public String templateSqlViewsSnapshot = "{}";

    /**
     * V145 (Stage 1) — 模板公式数组 JSONB.
     * 结构: [{name, expression, data_type, depends_on, description}]
     * 仅 DRAFT 状态可改；保存时由 TemplateFormulaService 做拓扑排序 + 循环依赖检测。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "formulas", columnDefinition = "jsonb", nullable = false)
    public String formulas = "[]";

    @Column(nullable = false, length = 20)
    public String status = "DRAFT";

    /**
     * 模板类型 — V71 引入。
     *  QUOTATION 报价模板：按客户专属 / 通用兜底维度（customer_id 可有可无）
     *  COSTING   核价模板：默认所有客户可用（customer_id 留空 = 通用）
     */
    @Column(name = "template_kind", nullable = false, length = 20)
    public String templateKind = "QUOTATION";

    @Column(name = "created_by")
    public UUID createdBy;

    @Column(name = "published_at")
    public OffsetDateTime publishedAt;

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
