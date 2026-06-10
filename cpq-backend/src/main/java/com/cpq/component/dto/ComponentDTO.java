package com.cpq.component.dto;

import com.cpq.component.entity.Component;
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
    public String status;
    /** EXCEL 类型组件的列配置 JSON（数组），Task 1.1 新增字段 */
    public String excelColumns;
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
        dto.excelColumns = component.excelColumns;
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
