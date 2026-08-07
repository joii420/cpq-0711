package com.cpq.template.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * task-0806：已发布模板的全量渲染配置冻结。
 *
 * <p>一行 = 一个已发布（PUBLISHED / ARCHIVED）模板的一个页签的<b>完整生效配置</b>。
 * 写入只发生在 {@code TemplateService.publish()}（同事务内按 {@code template_component}
 * 逐行插入，不得按 componentId 聚合——见 {@code uq_tcs_template_tc} 唯一约束
 * （{@code (template_id, template_component_id)}，非 {@code sort_order}——实测存量数据里
 * sort_order 并非天然唯一，见迁移文件 V382 注释），
 * 从 schema 层面消灭 AP-40「同 cid 多 tc 反向污染」）。
 *
 * <p>读取只经 {@link com.cpq.template.service.PublishedTemplateReader}；读不到就报错，
 * <b>禁止回落活表</b> {@code component} / {@code template_component}。
 *
 * <p>{@code template_component_id} / {@code component_id} 刻意<b>不建 FK</b>——快照的全部
 * 意义就是与活表脱钩，组件被停用或被删都不该动摇已发布模板。
 *
 * <p>不含 {@code status} / {@code directory_id}：D5 决策——组件停用不进快照也不进渲染路径
 * （新模板选不到，已发布模板/已生成报价单不受影响）；{@code directory_id} 纯组织维度，不影响渲染。
 */
@Entity
@Table(name = "template_component_snapshot")
public class TemplateComponentSnapshot extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "template_id", nullable = false)
    public UUID templateId;

    /** 溯源用，刻意不建 FK。 */
    @Column(name = "template_component_id", nullable = false)
    public UUID templateComponentId;

    /** 溯源用，刻意不建 FK。 */
    @Column(name = "component_id", nullable = false)
    public UUID componentId;

    @Column(name = "sort_order", nullable = false)
    public Integer sortOrder;

    // ---- 来自 template_component（模板级，已冻结） ----

    @Column(name = "tab_name", length = 200)
    public String tabName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preset_rows", columnDefinition = "jsonb", nullable = false)
    public String presetRows = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "formula_assignments", columnDefinition = "jsonb", nullable = false)
    public String formulaAssignments = "{}";

    // ---- 来自 component（内容层） ----

    @Column(name = "component_name", length = 200)
    public String componentName;

    @Column(name = "component_code", length = 100)
    public String componentCode;

    @Column(name = "component_type", nullable = false, length = 20)
    public String componentType = "NORMAL";

    /** ② 新补（FR-4）。 */
    @Column(name = "column_count", nullable = false)
    public Integer columnCount = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    public String fields = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    public String formulas = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "excel_columns", columnDefinition = "jsonb", nullable = false)
    public String excelColumns = "[]";

    @Column(name = "data_driver_path", columnDefinition = "text")
    public String dataDriverPath;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tree_config", columnDefinition = "jsonb")
    public String treeConfig;

    @Column(name = "bom_recursive_expand", nullable = false)
    public Boolean bomRecursiveExpand = false;

    @Column(name = "tab_type", length = 30)
    public String tabType;

    @Column(name = "part_no_field", length = 100)
    public String partNoField;

    @Column(name = "part_name_field", length = 100)
    public String partNameField;

    /** ② 新补（FR-4）：driver 行的业务标识。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "row_key_fields")
    public String rowKeyFields;

    /** ② 新补（FR-4）：行排序列。 */
    @Column(name = "sort_field", length = 120)
    public String sortField;

    /** ② 新补（FR-4）：元素编码列（task-0729 B7 取价）。 */
    @Column(name = "element_code_field", length = 100)
    public String elementCodeField;

    /** ② 新补（FR-4）：元素单价列。 */
    @Column(name = "element_price_field", length = 100)
    public String elementPriceField;

    /** ② 新补（FR-4）：货币列。 */
    @Column(name = "element_currency_field", length = 100)
    public String elementCurrencyField;

    @Column(name = "frozen_at", nullable = false)
    public OffsetDateTime frozenAt;

    @PrePersist
    public void prePersist() {
        if (frozenAt == null) {
            frozenAt = OffsetDateTime.now();
        }
    }
}
