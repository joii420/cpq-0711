package com.cpq.basicdata.v6.maintenance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;

/** §1 料号列表分页响应（结构对齐 api.md：total/page/size/items）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class PartListPage {

    public long total;
    public int page;
    public int size;
    public List<PartListItem> items;

    public PartListPage(long total, int page, int size, List<PartListItem> items) {
        this.total = total; this.page = page; this.size = size; this.items = items;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class PartListItem {
        public String materialNo;
        public String materialName;
        public String specification;
        public String dimension;
        public int configuredCount;      // 已配置版本组数 N
        public int totalSheets;          // 固定 16
        public OffsetDateTime lastUpdatedAt;
    }
}
