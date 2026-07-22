package com.cpq.quotation.dto.backfill;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** api.md §1.1 响应体。 */
public class BackfillPreviewDTO {
    public UUID quotationId;
    public String previewToken;
    public Summary summary = new Summary();
    public List<BackfillGroupDTO> groups = new ArrayList<>();

    public static class Summary {
        public int versionedGroups;
        public int addedRows;
        public int deletedRows;
        public int changedRows;
    }
}
