package com.cpq.customer.dto;

import java.util.ArrayList;
import java.util.List;

/** Result of an all-or-nothing customer workbook import. */
public class CustomerImportReportDTO {
    public int totalRows;
    public int createdCustomers;
    public int createdContacts;
    public List<ImportError> errors = new ArrayList<>();

    public static class ImportError {
        public int row;
        public String column;
        public String reason;

        public ImportError() {}

        public ImportError(int row, String column, String reason) {
            this.row = row;
            this.column = column;
            this.reason = reason;
        }
    }
}
