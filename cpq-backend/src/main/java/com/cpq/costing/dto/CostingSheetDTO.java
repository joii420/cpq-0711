package com.cpq.costing.dto;

import com.cpq.costing.entity.CostingSheet;
import com.cpq.costing.entity.CostingTemplate;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CostingSheetDTO {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public UUID id;
    public UUID quotationId;
    public UUID costingTemplateId;
    public String costingTemplateName;
    public List<Map<String, Object>> columns;            // 模板列定义
    public List<Map<String, Object>> rows;
    public BigDecimal totalCost;
    public String status;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;

    public static CostingSheetDTO from(CostingSheet s) {
        CostingSheetDTO dto = new CostingSheetDTO();
        dto.id = s.id;
        dto.quotationId = s.quotationId;
        dto.costingTemplateId = s.costingTemplateId;
        dto.totalCost = s.totalCost;
        dto.status = s.status;
        dto.createdAt = s.createdAt;
        dto.updatedAt = s.updatedAt;
        try {
            dto.rows = MAPPER.readValue(s.rows, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            dto.rows = List.of();
        }
        if (s.costingTemplateId != null) {
            CostingTemplate ct = CostingTemplate.findById(s.costingTemplateId);
            if (ct != null) {
                dto.costingTemplateName = ct.name;
                try {
                    dto.columns = MAPPER.readValue(ct.columns, new TypeReference<List<Map<String, Object>>>() {});
                } catch (Exception e) {
                    dto.columns = List.of();
                }
            }
        }
        return dto;
    }
}
