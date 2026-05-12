package com.cpq.importsession.dto;

import java.util.List;

/**
 * 差异检测结果载体（对应 upload 端点 diffPayload 字段）。
 * 包含三大类决策项 + 校验汇总，供前端 Step 2「版本确认」界面渲染。
 */
public class DiffPayloadDTO {

    /**
     * 料号版本变更决策列表（Step 2 区块 A）。
     * 每个 (customerProductNo, hfPartNo) pair 对应一条，前端渲染 BUMP/NO_BUMP 开关。
     */
    public List<PartVersionDecisionItem> partVersionDecisions;

    /**
     * 客户料号冲突列表（Step 2 区块 B，对应 V5 的 customerDataConflicts）。
     */
    public List<CustomerConflictItem> customerConflicts;

    /**
     * 孤儿行列表（Step 2 区块 C，对应 V5 的 orphanRows）。
     */
    public List<OrphanItem> orphanRows;

    /**
     * 校验汇总（BV-01~BV-32 结果）。
     * hasErrors=true 时上传被阻塞，不建 session。
     */
    public ValidationSummary validation;

    /** 校验汇总内嵌 DTO */
    public static class ValidationSummary {
        /** 是否有阻塞级错误 */
        public boolean hasErrors;
        /** 错误列表（BV 代码 + 中文描述） */
        public List<String> errors;
        /** 警告列表（非阻塞，提示用） */
        public List<String> warnings;
    }
}
