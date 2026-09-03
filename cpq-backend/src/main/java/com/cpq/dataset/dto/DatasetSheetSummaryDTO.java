package com.cpq.dataset.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单个 sheet 的导入汇总（api.md §1 · AC-11）。
 * <p>免版本 sheet 出 {@code inserted}/{@code updated}；带版本 sheet 出 {@code created}/{@code upgraded}/{@code unchanged}。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DatasetSheetSummaryDTO {

    public String sheet;
    public boolean versioned;
    public int axisCount;        // 本次 Excel 中该 sheet 出现的轴值个数（免版本 sheet 恒 0）

    public Integer inserted;     // 免版本
    public Integer updated;      // 免版本
    public Integer created;      // 带版本
    public Integer upgraded;     // 带版本
    public Integer unchanged;    // 带版本

    public static DatasetSheetSummaryDTO plain(String sheet, int inserted, int updated) {
        DatasetSheetSummaryDTO d = new DatasetSheetSummaryDTO();
        d.sheet = sheet; d.versioned = false; d.axisCount = 0;
        d.inserted = inserted; d.updated = updated;
        return d;
    }

    public static DatasetSheetSummaryDTO versioned(String sheet, int axisCount,
                                                   int created, int upgraded, int unchanged) {
        DatasetSheetSummaryDTO d = new DatasetSheetSummaryDTO();
        d.sheet = sheet; d.versioned = true; d.axisCount = axisCount;
        d.created = created; d.upgraded = upgraded; d.unchanged = unchanged;
        return d;
    }
}
