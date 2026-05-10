package com.cpq.approval.dto;

import com.cpq.approval.entity.ApprovalRule;

import java.time.OffsetDateTime;
import java.util.UUID;

public class ApprovalRuleDTO {

    public UUID id;
    public String ruleType;
    public UUID approverId;
    public String matchField;
    public UUID matchValueId;
    public Integer priority;
    public OffsetDateTime createdAt;

    public static ApprovalRuleDTO from(ApprovalRule rule) {
        ApprovalRuleDTO dto = new ApprovalRuleDTO();
        dto.id = rule.id;
        dto.ruleType = rule.ruleType;
        dto.approverId = rule.approverId;
        dto.matchField = rule.matchField;
        dto.matchValueId = rule.matchValueId;
        dto.priority = rule.priority;
        dto.createdAt = rule.createdAt;
        return dto;
    }
}
