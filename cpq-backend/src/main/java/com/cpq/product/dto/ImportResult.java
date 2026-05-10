package com.cpq.product.dto;

import java.util.List;

public class ImportResult {

    public int added;
    public int skipped;
    public int failed;
    public List<String> errors;

    public ImportResult(int added, int skipped, int failed, List<String> errors) {
        this.added = added;
        this.skipped = skipped;
        this.failed = failed;
        this.errors = errors;
    }
}
