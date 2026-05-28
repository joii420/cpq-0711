package com.cpq.template.dto;

import com.cpq.template.entity.TemplateSqlView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 产品卡片模板 SQL 视图的只读 DTO（Resource 层返回使用）。
 *
 * <p>结构与 {@link com.cpq.component.dto.ComponentSqlViewDTO} 对齐，
 * owner 字段换为 {@code templateId}（template.id）。
 * V249 起替代 Phase 1 的 CostingTemplateSqlViewDTO。
 */
public class TemplateSqlViewDTO {

    private static final ObjectMapper DTO_MAPPER = new ObjectMapper();

    public UUID id;
    /** 所属产品卡片模板 ID（template.id）。 */
    public UUID templateId;
    public String sqlViewName;
    public String sqlTemplate;
    /**
     * 列签名数组（前端 type=SqlViewColumn[]）：
     * 从 entity 的 raw JSONB 字符串反序列化为 List&lt;Map&gt;。
     */
    public List<Map<String, Object>> declaredColumns;
    public List<String> requiredVariables;
    public String scope;
    public String status;
    public String description;
    public UUID createdBy;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;

    public static TemplateSqlViewDTO from(TemplateSqlView entity) {
        TemplateSqlViewDTO dto = new TemplateSqlViewDTO();
        dto.id = entity.id;
        dto.templateId = entity.templateId;
        dto.sqlViewName = entity.sqlViewName;
        dto.sqlTemplate = entity.sqlTemplate;
        // 把 entity 的 raw JSONB 字符串反序列化为数组（前端 SqlViewColumn[] 类型契约）
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
