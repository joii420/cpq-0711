package com.cpq.component.dto;

import com.cpq.component.entity.ComponentSqlView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 组件 SQL 视图的只读 DTO（Resource 层返回使用）。
 */
public class ComponentSqlViewDTO {

    private static final ObjectMapper DTO_MAPPER = new ObjectMapper();

    public UUID id;
    public UUID componentId;
    /** 组件 code（业务标识符）—— 跨组件 BNF 引用 $$&lt;componentCode&gt;.&lt;sql_view_name&gt; 用此字段。 */
    public String componentCode;
    public String sqlViewName;
    public String sqlTemplate;
    /** 列签名数组（前端 type=SqlViewColumn[]）：从 entity 的 raw JSONB 字符串反序列化为 List&lt;Map&gt;，与 dryRun 响应格式对齐 */
    public List<Map<String, Object>> declaredColumns;
    public List<String> requiredVariables;
    public String scope;
    public String status;
    public String description;
    public UUID createdBy;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;

    public static ComponentSqlViewDTO from(ComponentSqlView entity) {
        return from(entity, null);
    }

    /**
     * @param componentCode 关联组件的 code（业务标识符），由 Service 层 enrich 传入
     */
    public static ComponentSqlViewDTO from(ComponentSqlView entity, String componentCode) {
        ComponentSqlViewDTO dto = new ComponentSqlViewDTO();
        dto.id = entity.id;
        dto.componentId = entity.componentId;
        dto.componentCode = componentCode;
        dto.sqlViewName = entity.sqlViewName;
        dto.sqlTemplate = entity.sqlTemplate;
        // 把 entity 的 raw JSONB 字符串反序列化为数组（与前端 SqlViewColumn[] 类型契约对齐）
        dto.declaredColumns = parseDeclaredColumns(entity.declaredColumns);
        dto.requiredVariables = entity.requiredVariables != null
                ? Arrays.asList(entity.requiredVariables)
                : List.of();
        dto.scope = entity.scope;
        dto.status = entity.status;
        dto.description = entity.description;
        dto.createdBy = entity.createdBy;
        dto.createdAt = entity.createdAt;
        dto.updatedAt = entity.updatedAt;
        return dto;
    }

    /**
     * 把 raw JSONB 字符串解析为列签名数组。容忍 null / 空字符串 / 非合法 JSON 等场景返 []。
     */
    private static List<Map<String, Object>> parseDeclaredColumns(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return DTO_MAPPER.readValue(raw, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
