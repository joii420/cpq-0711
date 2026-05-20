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
    /**
     * V200/V204: 模板级 driver_path 覆盖 (相对 component 表的字段). 非空 = 此 Tab 用 override 路径.
     * 编辑视图据此呈现"是否已 override / 当前实际生效路径"; null 表示沿用 component 默认.
     */
    public String dataDriverPathOverride;
    /**
     * V200/V204: 模板级 fields 覆盖 (JSON 数组字符串). 非空 = 此 Tab 字段集独立于 component.
     * 编辑视图据此呈现"是否已 override / 当前字段定义"; null 表示沿用 component 默认.
     */
    public String fieldsOverride;

    public static TemplateComponentDTO from(TemplateComponent tc) {
        TemplateComponentDTO dto = new TemplateComponentDTO();
        dto.id = tc.id;
        dto.componentId = tc.componentId;
        dto.tabName = tc.tabName;
        dto.sortOrder = tc.sortOrder;
        dto.presetRows = tc.presetRows;
        dto.formulaAssignments = tc.formulaAssignments;
        dto.dataDriverPathOverride = tc.dataDriverPathOverride;
        dto.fieldsOverride = tc.fieldsOverride;
        return dto;
    }
}
