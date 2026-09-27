package com.cpq.customer.exception;

import com.cpq.common.exception.BusinessException;
import com.cpq.customer.dto.CustomerImportReportDTO;

import java.util.List;

/** Validation errors are returned as one structured list and roll the transaction back. */
public class CustomerImportException extends BusinessException {
    private final List<CustomerImportReportDTO.ImportError> errors;
    private final int totalRows;

    public CustomerImportException(int code, String message,
                                   int totalRows,
                                   List<CustomerImportReportDTO.ImportError> errors) {
        super(code, message);
        this.totalRows = totalRows;
        this.errors = errors;
    }

    public List<CustomerImportReportDTO.ImportError> getErrors() { return errors; }
    public int getTotalRows() { return totalRows; }
}
