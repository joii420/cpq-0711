package com.cpq.quotation.dto.backfill;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** api.md §1.1 响应体。 */
public class BackfillPreviewDTO {
    public UUID quotationId;
    public String previewToken;
    public Summary summary = new Summary();
    /** repair-0727 B4：按产品聚合的视图（{@code groups} 仍保留，见 api.md §1.3）。 */
    public List<BackfillProductDTO> products = new ArrayList<>();
    /** repair-0727 B4：无产品维度的全局共享组（当前仅 {@code plating_scheme}）。 */
    public GlobalShared globalShared = new GlobalShared();
    public List<BackfillGroupDTO> groups = new ArrayList<>();

    public static class Summary {
        public int versionedGroups;
        public int addedRows;
        public int deletedRows;
        public int changedRows;
        /** repair-0727 B4：涉及产品数（groups 里 productNo 去重，null 不计）。 */
        public int affectedProducts;
    }

    public static class GlobalShared {
        public List<Integer> groupIndexes = new ArrayList<>();
    }
}
