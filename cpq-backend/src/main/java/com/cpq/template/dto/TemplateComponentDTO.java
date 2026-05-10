package com.cpq.template.dto;

import com.cpq.template.entity.TemplateComponent;

import java.util.UUID;

public class TemplateComponentDTO {

    public UUID id;
    public UUID componentId;
    public String tabName;
    public Integer sortOrder;
    public String presetRows;
    public String formulaAssignments;

    public static TemplateComponentDTO from(TemplateComponent tc) {
        TemplateComponentDTO dto = new TemplateComponentDTO();
        dto.id = tc.id;
        dto.componentId = tc.componentId;
        dto.tabName = tc.tabName;
        dto.sortOrder = tc.sortOrder;
        dto.presetRows = tc.presetRows;
        dto.formulaAssignments = tc.formulaAssignments;
        return dto;
    }
}
