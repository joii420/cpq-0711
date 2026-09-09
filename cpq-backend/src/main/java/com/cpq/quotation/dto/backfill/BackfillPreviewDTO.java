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

    /**
     * 🆕 task-260907 第二段（api.md §1）：ds_ 新链路的回填预览。
     *
     * <p>上面的既有字段（{@code summary} / {@code products} / {@code globalShared} / {@code groups}）
     * 是<b>老回填</b>（V6 表）的摘要，一律保持原样 —— 老链路仍在用（AC-15）。
     * 新链路对老回填是安全 no-op（第一段 AC-16 实证：摘要恒 0/0/0/0），两段互不覆盖。
     *
     * <p>{@code applicable=false} = 本单没有任何组件绑定到 {@code ds_quote_*} 带版本表，前端不渲染该区。
     */
    public DsBackfillDTO dsBackfill;

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
