package com.cpq.component.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 组件级用户自定义 SQL 视图实体。
 *
 * <p>每条记录对应用户在"组件管理 → SQL 视图 Tab"中配置的一条 SELECT 语句。
 * BNF path 通过 {@code $<sql_view_name>} 或 {@code $$<componentCode>.<sql_view_name>}
 * 引用此实体，后端在解析时将其展开为 inline subquery。
 *
 * <p>详见 {@code docs/组件级数据源SQL方案.md} §3.1。
 */
@Entity
@Table(name = "component_sql_view",
       uniqueConstraints = @UniqueConstraint(
           name = "uq_csv_component_view_name",
           columnNames = {"component_id", "sql_view_name"}
       ))
public class ComponentSqlView extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "component_id", nullable = false)
    public UUID componentId;

    /** BNF 引用名（如 element_view），同 component 内唯一。 */
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
     * 命名空间范围。
     * COMPONENT = 仅本组件可引用（$name）；GLOBAL = 可跨组件引用（$$code.name）。
     */
    @Column(name = "scope", nullable = false, length = 20)
    public String scope = "COMPONENT";

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
