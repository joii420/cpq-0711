package com.cpq.basicdata.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public class CreateBasicDataAttributeRequest {

    public UUID configId;

    @NotBlank
    public String columnLetter;

    @NotBlank
    public String columnTitle;

    @NotBlank
    public String variableCode;

    @NotBlank
    public String variableLabel;

    public String dataType;
    public String status;
    public Integer sortOrder;

    /**
     * 字段重要性等级：CRITICAL / IMPORTANT / NORMAL（默认 NORMAL）
     * D-5 v5.1 §6.2 CONF-2
     */
    public String importanceLevel;

    /**
     * 字段变更是否触发公式重算（默认 false）
     * D-5 v5.1 §6.2 CONF-2
     */
    public Boolean affectsCalculation;

    /**
     * V58: 该列是否必填（默认 false）
     */
    public Boolean isRequired;
}
