package com.cpq.component.dto;

import com.cpq.component.entity.Component;
import com.cpq.component.service.FormulaIdBinder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ComponentDTO {

    public UUID id;
    public UUID directoryId;
    public String name;
    public String code;
    public Integer columnCount;
    public List<Map<String, Object>> fields;
    public List<Map<String, Object>> formulas;
    public String componentType;
    /** Y1.5 行驱动路径(可选) */
    public String dataDriverPath;
    /** 行键字段(组件级,草稿重刷按此对齐编辑值);entity 存 JSON 字符串,DTO 解析为 List */
    public List<String> rowKeyFields;
    /** 树表配置(纯展示);entity 存 JSON 字符串,DTO 透传为 Map(null=非树表) */
    public Map<String, Object> treeConfig;
    /** 核价 BOM 递归展开开关(默认 true,仅核价侧生效;与 treeConfig 正交) */
    public Boolean bomRecursiveExpand;
    /** task-0721 B4：页签类型属性(可选)。值域 5 类：BOM / 材质元素 / 零件 / 外购件 / 主件。 */
    public String tabType;
    /** task-0721（2026-07-21 补录）：该页签「料号列」字段名。 */
    public String partNoField;
    /** task-0721（2026-07-21 补录）：该页签「料号名称列」字段名，可空。 */
    public String partNameField;
    /** task-0722：多行页签「行排序列」字段名，可空。 */
    public String sortField;
    /** task-0729 B7：元素编码列（fields[].name 之一），接价格策略的组件必填。 */
    public String elementCodeField;
    /** task-0729 B7：元素单价列，语义同上。 */
    public String elementPriceField;
    /** task-0729 B7：货币列，可空。 */
    public String elementCurrencyField;
    public String status;
    /** EXCEL 类型组件的列配置 JSON（数组），Task 1.1 新增字段 */
    public String excelColumns;
    /** task-0805 §1.4：派生字段（不落库、不加迁移）—— 存在未显式绑定 formula_id 的 FORMULA 字段
     *  （条件公式豁免，判据与 {@link FormulaIdBinder#listUnboundFormulaFields} 一致）。
     *  R3 的 ignoreUnboundFormulas=true 放行场景、以及 BL-0098 之前遗留的坏配置都会被标出来。 */
    public Boolean hasUnboundFormula;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ComponentDTO from(Component component) {
        ComponentDTO dto = new ComponentDTO();
        dto.id = component.id;
        dto.directoryId = component.directoryId;
        dto.name = component.name;
        dto.code = component.code;
        dto.columnCount = component.columnCount;
        dto.componentType = component.componentType;
        dto.dataDriverPath = component.dataDriverPath;
        dto.status = component.status;
        dto.createdAt = component.createdAt;
        dto.updatedAt = component.updatedAt;
        dto.fields = parseJsonArray(component.fields);
        dto.formulas = parseJsonArray(component.formulas);
        dto.rowKeyFields = parseStringList(component.rowKeyFields);
        dto.treeConfig = parseJsonObject(component.treeConfig);
        dto.bomRecursiveExpand = component.bomRecursiveExpand != null ? component.bomRecursiveExpand : Boolean.FALSE;
        dto.tabType = component.tabType;
        dto.partNoField = component.partNoField;
        dto.partNameField = component.partNameField;
        dto.sortField = component.sortField;
        dto.elementCodeField = component.elementCodeField;
        dto.elementPriceField = component.elementPriceField;
        dto.elementCurrencyField = component.elementCurrencyField;
        dto.excelColumns = component.excelColumns;
        dto.hasUnboundFormula = !FormulaIdBinder.listUnboundFormulaFields(dto.fields).isEmpty();
        return dto;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseJsonArray(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 解析 row_key_fields JSON 字符串(如 ["子件","元素"])为 List；null/空 → null(前端按 [] 处理)。 */
    private static List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析 tree_config JSON 对象为 Map;null/空/非法 → null。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonObject(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
