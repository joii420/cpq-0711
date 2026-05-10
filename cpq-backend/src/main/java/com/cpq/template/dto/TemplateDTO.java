package com.cpq.template.dto;

import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class TemplateDTO {

    public UUID id;
    public UUID templateSeriesId;
    public String name;
    public String version;
    public String category;
    public UUID customerId;
    public String customerName;
    public UUID categoryId;
    public String categoryName;
    public String description;
    public String usageNote;
    public List<Map<String, Object>> productAttributes;
    public List<Map<String, Object>> subtotalFormula;
    public String componentsSnapshot;
    public String excelViewConfig;
    public String status;
    /** V71：模板类型 — 'QUOTATION'(报价模板) / 'COSTING'(核价模板) */
    public String templateKind;
    public UUID createdBy;
    public OffsetDateTime publishedAt;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
    public List<TemplateComponentDTO> components;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static TemplateDTO from(Template template, List<TemplateComponent> tcs) {
        TemplateDTO dto = new TemplateDTO();
        dto.id = template.id;
        dto.templateSeriesId = template.templateSeriesId;
        dto.name = template.name;
        dto.version = template.version;
        dto.category = template.category;
        dto.customerId = template.customerId;
        dto.categoryId = template.categoryId;
        if (template.customerId != null) {
            com.cpq.customer.entity.Customer c = com.cpq.customer.entity.Customer.findById(template.customerId);
            if (c != null) dto.customerName = c.name;
        }
        if (template.categoryId != null) {
            com.cpq.basicdata.entity.ProductCategory pc =
                    com.cpq.basicdata.entity.ProductCategory.findById(template.categoryId);
            if (pc != null) dto.categoryName = pc.name;
        }
        dto.description = template.description;
        dto.usageNote = template.usageNote;
        dto.productAttributes = parseJsonArray(template.productAttributes);
        dto.subtotalFormula = parseJsonArray(template.subtotalFormula);
        dto.componentsSnapshot = template.componentsSnapshot;
        dto.excelViewConfig = template.excelViewConfig;
        dto.status = template.status;
        dto.templateKind = template.templateKind;
        dto.createdBy = template.createdBy;
        dto.publishedAt = template.publishedAt;
        dto.createdAt = template.createdAt;
        dto.updatedAt = template.updatedAt;
        dto.components = tcs == null ? new ArrayList<>() :
            tcs.stream().map(TemplateComponentDTO::from).collect(Collectors.toList());
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
}
