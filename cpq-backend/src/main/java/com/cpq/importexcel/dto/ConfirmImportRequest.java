package com.cpq.importexcel.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ConfirmImportRequest {

    /** The CPQ template id whose excel_view_config drives the import. */
    public UUID templateId;

    public UUID customerId;

    /** Original uploaded file name (for import record). */
    public String fileName;

    /** Saved file path from a prior preview call (so we don't re-upload). */
    public String savedFilePath;

    /** Pre-parsed rows from preview, each map is col_key -> value. */
    public List<Map<String, Object>> rows;

    /** Match results from preview, each map contains customerPartNo, matched, materialNo, materialName. */
    public List<Map<String, Object>> matchResults;
}
