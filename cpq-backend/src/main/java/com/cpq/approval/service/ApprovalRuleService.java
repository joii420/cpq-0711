package com.cpq.approval.service;

import com.cpq.approval.dto.ApprovalRuleDTO;
import com.cpq.approval.dto.CreateApprovalRuleRequest;
import com.cpq.approval.entity.ApprovalRule;
import com.cpq.common.exception.BusinessException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ApprovalRuleService {

    private static final Logger LOG = Logger.getLogger(ApprovalRuleService.class);

    public List<ApprovalRuleDTO> list() {
        List<ApprovalRule> rules = ApprovalRule.find("ORDER BY priority ASC").list();
        LOG.debugf("list approval rules count=%d", rules.size());
        return rules.stream().map(ApprovalRuleDTO::from).collect(Collectors.toList());
    }

    @Transactional
    public ApprovalRuleDTO create(CreateApprovalRuleRequest request) {
        ApprovalRule rule = new ApprovalRule();
        rule.ruleType = request.ruleType;
        rule.approverId = request.approverId;
        rule.matchField = request.matchField;
        rule.matchValueId = request.matchValueId;
        rule.priority = request.priority != null ? request.priority : 100;
        rule.persist();
        LOG.infof("Created approval rule ruleType=%s approverId=%s priority=%d",
                rule.ruleType, rule.approverId, rule.priority);
        return ApprovalRuleDTO.from(rule);
    }

    @Transactional
    public ApprovalRuleDTO update(UUID id, CreateApprovalRuleRequest request) {
        ApprovalRule rule = ApprovalRule.findById(id);
        if (rule == null) {
            throw new BusinessException(404, "Approval rule not found: " + id);
        }
        rule.ruleType = request.ruleType;
        rule.approverId = request.approverId;
        rule.matchField = request.matchField;
        rule.matchValueId = request.matchValueId;
        if (request.priority != null) {
            rule.priority = request.priority;
        }
        LOG.infof("Updated approval rule id=%s", id);
        return ApprovalRuleDTO.from(rule);
    }

    @Transactional
    public void delete(UUID id) {
        ApprovalRule rule = ApprovalRule.findById(id);
        if (rule == null) {
            throw new BusinessException(404, "Approval rule not found: " + id);
        }
        rule.delete();
        LOG.infof("Deleted approval rule id=%s", id);
    }
}
