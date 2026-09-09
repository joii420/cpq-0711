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

    /**
     * task-260909 B-7（api.md §3）：<b>加法式</b>扩展 —— 发布期「本模板含非本数据集页签」告警。
     *
     * <p>约定：
     * <ul>
     *   <li>类型 {@code string[]}，<b>恒非 null</b>；无告警时为 {@code []}（🚫 不是 null ——
     *       消费方写 {@code data.warnings.length} 不该被迫先判空）。</li>
     *   <li><b>仅 {@code publish} 填充</b>；{@link #from} 产出的其它端点响应恒为 {@code []}，
     *       故既有消费方零改动即可继续工作。</li>
     *   <li>🚫 <b>只告警不拦</b>（用户裁决 D-3/D-7）：{@code publish} 仍返 200，
     *       <b>不得</b>因告警抛异常或回滚。</li>
     * </ul>
     */
    public List<String> warnings = new ArrayList<>();

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
        // warnings 保持字段初始值 []（见字段注释）：只有 TemplateService.publish 会往里填。
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
