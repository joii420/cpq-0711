package com.cpq.dataset.dto;

import java.util.List;

/** 导入校验失败 400 的 data 体（api.md §1）：{@code { "errors": [ … ] }}。 */
public class DatasetImportErrorsDTO {

    public List<DsValidationError> errors;

    public DatasetImportErrorsDTO(List<DsValidationError> errors) {
        this.errors = errors;
    }
}
