package com.cpq.template.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * V212: 模板全局变量绑定响应 DTO.
 *
 * <p>globalVariableName / varType / unit / isActive 是 JOIN global_variable_definition 回填字段,
 * 方便前端绑定列表直接渲染徽章, 不需要二次请求.
 */
public class TemplateGvBindingDTO {

    public UUID id;
    public UUID templateId;

    /** V104 global_variable_definition.code (VARCHAR(64) 业务主键) */
    public String globalVariableCode;

    /** JOIN 回填: global_variable_definition.name */
    public String globalVariableName;

    /** JOIN 回填: LOOKUP_TABLE | SCALAR */
    public String varType;

    /** JOIN 回填: global_variable_definition.unit */
    public String unit;

    /** JOIN 回填: global_variable_definition.is_active */
    public boolean isActive;

    public int displayOrder;
    public OffsetDateTime createdAt;
}
