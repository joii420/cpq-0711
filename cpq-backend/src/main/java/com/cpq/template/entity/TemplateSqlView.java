package com.cpq.template.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 产品卡片模板（Template）拥有的 SQL 视图实体（Phase 2 迁移自 costing 包）。
 *
 * <p>与 {@link com.cpq.component.entity.ComponentSqlView} 结构同构，区别：
 * <ul>
 *   <li>owner FK 是 {@code template_id}（而非 {@code component_id}）</li>
 *   <li>{@code scope} 只允许 {@code LOCAL}（不支持跨模板 GLOBAL 引用）</li>
 *   <li>引用语法仍是 {@code $<sql_view_name>.<col>}，owner 上下文由
 *       {@code SqlViewRuntimeContext.ownerType=TEMPLATE} 决定</li>
 * </ul>
 *
 * <p>V249 起替代 Phase 1 的 {@code costing_template_sql_view} 表。
 * 归宿从 costing_template 迁移到 template 是因为 template.excel_view_config
 * 才是 LinkedExcelView 的实际渲染源（V150 合并后确立）。
 */
@Entity
@Table(name = "template_sql_view",
       uniqueConstraints = @UniqueConstraint(
           name = "uq_tsv_template_view_name",
           columnNames = {"template_id", "sql_view_name"}
       ))
public class TemplateSqlView extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    /** 所属产品卡片模板 ID（template.id）。 */
    @Column(name = "template_id", nullable = false)
    public UUID templateId;

    /** BNF 引用名（如 summary_full），同模板内唯一。 */
    @Column(name = "sql_view_name", nullable = false, length = 80)
    public String sqlViewName;

    /** 含命名占位符的 SQL 模板（如 :customerId / :partVersion）。 */
    @Column(name = "sql_template", nullable = false, columnDefinition = "TEXT")
    public String sqlTemplate;

    /**
     * 保存时 dry-run 自动提取的列签名。
     * 结构：[{name: "hf_part_no", dataType: "text", nullable: false}, ...]
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "declared_columns", nullable = false, columnDefinition = "jsonb")
    public String declaredColumns = "[]";

    /**
     * 从 sql_template 中解析出的 :xxx 占位符列表（不含 :hfPartNos）。
     * 存储为 PG text[]。
     */
    @Column(name = "required_variables", nullable = false, columnDefinition = "text[]")
    public String[] requiredVariables = new String[0];

    /**
     * 命名空间范围。当前只允许 LOCAL（V249 DDL CHECK 约束）。
     * 后续若需要"模板视图库"特性可扩 GLOBAL。
     */
    @Column(name = "scope", nullable = false, length = 20)
    public String scope = "LOCAL";

    /** ACTIVE / INACTIVE */
    @Column(name = "status", nullable = false, length = 20)
    public String status = "ACTIVE";

    /** 可选描述（用途说明）。 */
    @Column(name = "description", columnDefinition = "TEXT")
    public String description;

    @Column(name = "created_by")
    public UUID createdBy;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
