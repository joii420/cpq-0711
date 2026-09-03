package com.cpq.dataset.exception;

import com.cpq.common.exception.BusinessException;
import com.cpq.dataset.dto.DsValidationError;

import java.util.List;

/**
 * api.md §7 保存校验失败（400）：与导入同构的逐条错误清单。
 *
 * <p>🚨 <b>一次收集全部错误后再抛</b>，不许 fail-fast（AC-10 的同源纪律：前端 F-9
 * 用同一个 {@code <ValidationErrorTable>} 渲染，不截断、不「仅显示前 N 条」）。
 * 抛出即整份拒收，一行不写（本异常在 {@code @Transactional} 内抛出 → 事务回滚）。
 */
public class DatasetValidationException extends BusinessException {

    private final List<DsValidationError> errors;

    public DatasetValidationException(String message, List<DsValidationError> errors) {
        super(400, message);
        this.errors = List.copyOf(errors);
    }

    public List<DsValidationError> getErrors() { return errors; }
}
