package com.cpq.quotation.dto.backfill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** api.md §1.1 {@code groups[]}。 */
public class BackfillGroupDTO {
    public String table;
    public String tabName;
    public Map<String, Object> groupKey = new LinkedHashMap<>();
    public String versionFrom;
    public String versionTo;
    public boolean isGlobalShared;
    public List<BackfillRowDTO> rows = new ArrayList<>();
}
