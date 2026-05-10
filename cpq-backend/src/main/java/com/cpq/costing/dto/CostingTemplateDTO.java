package com.cpq.costing.dto;

import com.cpq.costing.entity.CostingTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

public class CostingTemplateDTO {

    public UUID id;
    public UUID seriesId;
    public String name;
    /** V73：关联到 template 表中的具体模板 ID */
    public UUID linkedTemplateId;
    /** linkedTemplateId 对应的 template.name，便于前端列表/详情直接展示 */
    public String linkedTemplateName;
    /** linkedTemplateId 对应的 template.template_kind ('QUOTATION' / 'COSTING') */
    public String linkedTemplateKind;
    /** linkedTemplateId 对应的 template.version */
    public String linkedTemplateVersion;
    public Boolean isDefault;
    public String version;
    public String status;
    public String description;
    public String columns;              // JSON 字符串
    public String referencedVariables;  // JSON 字符串
    public UUID createdBy;
    public OffsetDateTime publishedAt;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;

    public static CostingTemplateDTO from(CostingTemplate t) {
        CostingTemplateDTO dto = new CostingTemplateDTO();
        dto.id = t.id;
        dto.seriesId = t.seriesId;
        dto.name = t.name;
        dto.linkedTemplateId = t.linkedTemplateId;
        if (t.linkedTemplateId != null) {
            com.cpq.template.entity.Template linked =
                    com.cpq.template.entity.Template.findById(t.linkedTemplateId);
            if (linked != null) {
                dto.linkedTemplateName = linked.name;
                dto.linkedTemplateKind = linked.templateKind;
                dto.linkedTemplateVersion = linked.version;
            }
        }
        dto.isDefault = t.isDefault;
        dto.version = t.version;
        dto.status = t.status;
        dto.description = t.description;
        dto.columns = t.columns;
        dto.referencedVariables = t.referencedVariables;
        dto.createdBy = t.createdBy;
        dto.publishedAt = t.publishedAt;
        dto.createdAt = t.createdAt;
        dto.updatedAt = t.updatedAt;
        return dto;
    }
}
