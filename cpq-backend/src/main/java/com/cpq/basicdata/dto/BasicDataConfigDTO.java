package com.cpq.basicdata.dto;

import com.cpq.basicdata.entity.BasicDataConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BasicDataConfigDTO {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public UUID id;
    public String sheetName;
    public Integer sheetIndex;
    public Integer headerRowIndex;
    public Integer dataStartRowIndex;
    public String description;
    public UUID parentConfigId;
    public List<String> joinColumns;
    public Integer sortOrder;
    public String status;

    /**
     * V58: 行数据写入的物理表名（mat_part / mat_bom 等）。
     * null 表示该 sheet 不参与导入。
     */
    public String targetTable;

    /**
     * V58: 写入物理表时附加的固定列值（如 {"bom_type":"INCOMING"}）。
     * JSONB 存储，前端传 Map，后端序列化为 String 写入。
     */
    public Map<String, Object> targetDiscriminator;

    /** V79: 模板类型分类 — QUOTATION / COSTING / BOTH（缺省 BOTH） */
    public String templateKind;

    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;

    public static BasicDataConfigDTO from(BasicDataConfig c) {
        BasicDataConfigDTO dto = new BasicDataConfigDTO();
        dto.id = c.id;
        dto.sheetName = c.sheetName;
        dto.sheetIndex = c.sheetIndex;
        dto.headerRowIndex = c.headerRowIndex;
        dto.dataStartRowIndex = c.dataStartRowIndex;
        dto.description = c.description;
        dto.parentConfigId = c.parentConfigId;
        dto.joinColumns = parseList(c.joinColumns);
        dto.sortOrder = c.sortOrder;
        dto.status = c.status;
        dto.targetTable = c.targetTable;
        dto.targetDiscriminator = parseMap(c.targetDiscriminator);
        dto.templateKind = c.templateKind;
        dto.createdAt = c.createdAt;
        dto.updatedAt = c.updatedAt;
        return dto;
    }

    private static List<String> parseList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
