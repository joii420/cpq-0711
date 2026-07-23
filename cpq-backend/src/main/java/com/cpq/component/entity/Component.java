package com.cpq.component.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "component")
public class Component extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "directory_id")
    public UUID directoryId;

    @Column(nullable = false, length = 200)
    public String name;

    @Column(nullable = false, unique = true, length = 100)
    public String code;

    @Column(name = "column_count", nullable = false)
    public Integer columnCount = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    public String fields = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    public String formulas = "[]";

    /** EXCEL 组件列定义（component_type=EXCEL 时有效）：{col_key,title,source_type,hidden,formula,sort} 数组。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "excel_columns", columnDefinition = "jsonb", nullable = false)
    public String excelColumns = "[]";

    @Column(name = "component_type", nullable = false, length = 20)
    public String componentType = "NORMAL";

    /**
     * Y1.5 行驱动 BNF 路径(可选)。
     * 非空 → 组件展开为该路径返回的 N 行,字段路径自动隐式 JOIN driver 行字段。
     */
    @Column(name = "data_driver_path", columnDefinition = "TEXT")
    public String dataDriverPath;

    @Column(nullable = false, length = 20)
    public String status = "ACTIVE";

    /**
     * 行键配置（报价单整份快照 Phase 1）。
     * JSON 数组，元素为 fields[].name 中存在的字段名，作为该组件 driver 行的业务标识。
     * 例如 ["子件","元素"] 或哨兵 ["__seq_no__"]（显式豁免，按行号对齐）。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "row_key_fields")
    public String rowKeyFields;

    /**
     * 树表配置(纯展示,可选)。JSON 对象 {idField,parentField,defaultExpanded}。
     * 非空 → 报价/核价/详情渲染时按邻接表重排成树;不改行集合/行序。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tree_config")
    public String treeConfig;

    /** 核价 BOM 递归展开开关：true=按 material_bom_item 闭包递归展开子料号(树+系统列)；false=按根料号单料号普通渲染。默认 false(勾选才递归)。仅核价侧生效。与 tree_config(组件数据自带树) 正交。 */
    @Column(name = "bom_recursive_expand", nullable = false)
    public Boolean bomRecursiveExpand = false;

    /**
     * task-0721 B4：页签类型属性（可选）。值域 5 类：{@code BOM}(树状页签,结构角色) /
     * {@code 材质元素} / {@code 零件} / {@code 外购件} / {@code 主件}(成品=树根)。
     * 用于报价侧 BOM 树上「加叶子」时的类型判定（{@code BomNodeTypeResolver}）——料号出现在
     * 哪个类型的页签即推导为该类型。与 {@code bomRecursiveExpand}（控制渲染行为）是独立字段：
     * {@code tabType=BOM} 表达业务语义，不等价于 {@code bomRecursiveExpand=true}（两者一致性
     * 校验见 {@code ComponentService#validateTabType}，仅告警不阻断）。
     */
    @Column(name = "tab_type", length = 16)
    public String tabType;

    /**
     * task-0721（2026-07-21 补录）：该页签哪个字段是「料号列」（取值 = 本组件 {@code fields[].name}
     * 中的一个）。类型判定（{@code BomNodeTypeResolver}）与加叶子候选料号采集**必须依据此字段显式取值，
     * 禁止按字段名启发式猜测**（如"字段名含料号"）。树页签（{@code tabType=BOM}）料号取系统列
     * {@code __hfPartNo}，本字段可不配；非树页签（材质元素/零件/外购件/主件）保存期强制要求配置
     * （见 {@code ComponentService#applyTabType}），否则该页签不参与类型判定匹配。
     */
    @Column(name = "part_no_field", length = 100)
    public String partNoField;

    /** task-0721（2026-07-21 补录）：该页签哪个字段是「料号名称列」（可空，语义同上）。 */
    @Column(name = "part_name_field", length = 100)
    public String partNameField;

    /**
     * task-0722：多行页签「行排序列」（可空）。值 = 本组件 {@code fields[].name} 之一。
     * 设置后快照组装层按该字段对 driver 行做数字感知升序排列（数字段数字序、文本段字典序）；
     * null = 保持 driver 返回序（不排）。用于让 项次/序号 之类列稳定按数字正序显示——
     * 视图 ORDER BY 在报价单 pending 改写管线下会被丢弃，故排序落在快照层。树页签(BOM)按树序不受此约束。
     */
    @Column(name = "sort_field", length = 120)
    public String sortField;

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
