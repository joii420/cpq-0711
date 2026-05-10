package com.cpq.basicdata.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "basic_data_config")
public class BasicDataConfig extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "sheet_name", nullable = false, length = 200)
    public String sheetName;

    @Column(name = "sheet_index", nullable = false)
    public Integer sheetIndex = 0;

    @Column(name = "header_row_index", nullable = false)
    public Integer headerRowIndex = 1;

    @Column(name = "data_start_row_index", nullable = false)
    public Integer dataStartRowIndex = 2;

    @Column(columnDefinition = "TEXT")
    public String description;

    @Column(name = "parent_config_id")
    public UUID parentConfigId;

    /**
     * JSONB 数组字符串：与父 sheet 关联的列名集合，例如 ["HF_PART_NO"]。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "join_columns", nullable = false, columnDefinition = "jsonb")
    public String joinColumns = "[]";

    @Column(name = "sort_order", nullable = false)
    public Integer sortOrder = 0;

    @Column(nullable = false, length = 20)
    public String status = "ACTIVE";

    /**
     * V58: 行数据写入的物理表名（mat_part / mat_bom / plating_plan 等）。
     * null 表示该 sheet 不参与导入。
     */
    @Column(name = "target_table", length = 64)
    public String targetTable;

    /**
     * V58: 写入物理表时附加的固定列值，JSONB 字符串形式。
     * 例如 mat_bom 的 INCOMING/ELEMENT 区分使用 {"bom_type":"INCOMING"}。
     * null 表示无 discriminator。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_discriminator", columnDefinition = "jsonb")
    public String targetDiscriminator;

    /**
     * V79: 模板类型分类，让组件 PathPickerDrawer 按"报价/核价"过滤可选 sheet。
     * 取值：QUOTATION / COSTING / BOTH（缺省 BOTH）
     */
    @Column(name = "template_kind", nullable = false, length = 20)
    public String templateKind = "BOTH";

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
