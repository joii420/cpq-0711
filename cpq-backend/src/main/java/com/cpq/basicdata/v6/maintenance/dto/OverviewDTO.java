package com.cpq.basicdata.v6.maintenance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;

/** §3 料号概览：16 组当前状态（抽屉 tab 徽标）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class OverviewDTO {

    public String materialNo;
    public String materialName;
    public String specification;
    public String dimension;
    public List<SheetStatus> sheets;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class SheetStatus {
        public String sheetKey;
        public boolean hasData;
        public String currentVersion;    // 无数据=null
        public int versionCount;
        public OffsetDateTime lastUpdatedAt;
    }
}
