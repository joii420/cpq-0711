package com.cpq.basicdata.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public class CreateDerivedAttributeRequest {

    public UUID hostSheetId;

    @NotBlank
    public String variableCode;

    @NotBlank
    public String variableLabel;

    public String dataType;

    @NotBlank
    public String computationType;  // LOOKUP / EXPRESSION / AGGREGATE

    public Object computation;  // 任意 JSON，后端写入时 toString

    public String status;
    public Integer sortOrder;
}
