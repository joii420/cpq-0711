package com.cpq.component.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CreateComponentRequest {
    public String name;
    public String code;
    public UUID directoryId;
    public String componentType;
    /** Y1.5 行驱动 BNF 路径,可选 */
    public String dataDriverPath;
    public String status;
    // Accept fields/formulas as either parsed list or raw JSON string
    public List<Map<String, Object>> fields;
    public List<Map<String, Object>> formulas;

    /**
     * 行键配置（报价单整份快照 Phase 1 §5.1）。
     * 字符串数组（fields[].name 中存在的名称），如 ["子件","元素"] 或哨兵 ["__seq_no__"]。
     * null = 未配置（新建时若需要则被硬拦，更新时仅告警）。
     */
    public List<String> rowKeyFields;

    /** 树表配置(纯展示,可选):{idField, parentField, defaultExpanded} */
    public Map<String, Object> treeConfig;

    /** 核价 BOM 递归展开开关(可选,默认 true):勾选才按 material_bom_item 闭包递归展开 */
    public Boolean bomRecursiveExpand;

    /** EXCEL 类型组件的列配置 JSON（数组），Task 1.1 新增字段 */
    public String excelColumns;

    /**
     * task-0721 B4：页签类型属性(可选)。值域 5 类：BOM / 材质元素 / 零件 / 外购件 / 主件。
     * null = 未配置(不阻断)；非法值 400。
     */
    public String tabType;

    /**
     * task-0721（2026-07-21 补录）：该页签「料号列」对应的字段名（本组件 fields[].name 中的一个）。
     * 树页签(tabType=BOM)可不配；非树页签(材质元素/零件/外购件/主件)保存期强制要求，否则 400。
     */
    public String partNoField;

    /** task-0721（2026-07-21 补录）：该页签「料号名称列」对应的字段名，可空。 */
    public String partNameField;
}
