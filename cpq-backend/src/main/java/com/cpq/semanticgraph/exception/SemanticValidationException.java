package com.cpq.semanticgraph.exception;

import com.cpq.common.exception.BusinessException;

import java.util.List;
import java.util.Map;

/**
 * 语义图四道保存期校验未通过（task-260819 B-3）。响应形状照 api.md §1.2：
 * {@code failedCheck} + {@code detail} + {@code checks[]}（四道校验的逐项状态）。
 */
public class SemanticValidationException extends BusinessException {

    private final String failedCheck;
    private final Map<String, Object> detail;
    private final List<Map<String, String>> checks;

    public SemanticValidationException(String failedCheck, String message,
                                        Map<String, Object> detail,
                                        List<Map<String, String>> checks) {
        super(400, message);
        this.failedCheck = failedCheck;
        this.detail = detail;
        this.checks = checks;
    }

    public String getFailedCheck() { return failedCheck; }
    public Map<String, Object> getDetail() { return detail; }
    public List<Map<String, String>> getChecks() { return checks; }
}
