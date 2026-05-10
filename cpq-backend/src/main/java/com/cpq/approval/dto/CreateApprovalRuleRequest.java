package com.cpq.approval.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public class CreateApprovalRuleRequest {

    @NotBlank
    public String ruleType;

    @NotNull
    public UUID approverId;

    public String matchField;

    public UUID matchValueId;

    public Integer priority = 100;
}
