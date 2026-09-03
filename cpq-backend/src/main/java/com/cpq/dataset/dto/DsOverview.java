package com.cpq.dataset.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;

/** api.md §4 抽屉徽标：该轴值在每个带版本 sheet 上的当前状态。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class DsOverview {

    public String axisValue;
    public String materialName;
    public List<SheetStatus> sheets;

    /**
     * {@code versionNo == null} 表示该 sheet 该轴值<b>从未有过数据</b> ——
     * 前端 tab 不打徽标，进去是空态（AC-32）。此时 {@code rowCount = 0}，
     * <b>不是</b> 404（读端点对无数据 sheet 一律正常返回）。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static final class SheetStatus {
        public String sheetKey;
        public int rowCount;
        public Integer versionNo;
        public OffsetDateTime lastUpdatedAt;
        public String source;
    }
}
