package com.cpq.importexcel.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 业务校验结果容器 —— 收集 BV-01~BV-32 所有校验错误（不 fail-fast）。
 */
public class ValidationResult {

    public boolean hasErrors;
    public boolean hasWarnings;
    public final List<ValidationError> errors = new ArrayList<>();
    public final List<ValidationError> warnings = new ArrayList<>();

    public void addError(String bvCode, int row, String sheet, String message) {
        hasErrors = true;
        errors.add(new ValidationError(bvCode, row, sheet, message));
    }

    public void addWarning(String bvCode, int row, String sheet, String message) {
        hasWarnings = true;
        warnings.add(new ValidationError(bvCode, row, sheet, message));
    }

    public void merge(ValidationResult other) {
        if (other.hasErrors) {
            hasErrors = true;
            errors.addAll(other.errors);
        }
        if (other.hasWarnings) {
            hasWarnings = true;
            warnings.addAll(other.warnings);
        }
    }

    /** 合计总数（errors + warnings）*/
    public int totalIssues() {
        return errors.size() + warnings.size();
    }

    public static class ValidationError {
        public final String bvCode;
        public final int row;
        public final String sheet;
        public final String message;

        public ValidationError(String bvCode, int row, String sheet, String message) {
            this.bvCode = bvCode;
            this.row = row;
            this.sheet = sheet;
            this.message = message;
        }
    }
}
