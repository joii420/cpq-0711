package com.cpq.costing.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public class CreateCostingTemplateRequest {

    @NotBlank
    public String name;

    public Boolean isDefault;
    public String version;
    public String description;
    public Object columns;              // 任意 JSON
    public Object referencedVariables;  // 任意 JSON
    public UUID seriesId;               // 升级版本时传入
    /** V73：关联的「模板配置」中的模板 ID（template.id）—— 报价模板或核价模板 */
    public UUID linkedTemplateId;
}
