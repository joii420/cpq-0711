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

    // ── repair-0727 B4 新增字段（api.md §1.1）──
    public String productNo;
    public String productName;
    /** 业务类别中文名（BOM 组成 / 材质元素构成 / 单价 / 工时产能 / 电镀方案）。 */
    public String categoryLabel;
    /** REBUILD / FLIP / OFFLINE（NOOP 组已被收集器整组过滤，不会出现在预览里）。 */
    public String route;
    /** PENDING / CURRENT / NONE（基底行来源）。 */
    public String baseSource;
    /** 基底行数。 */
    public int baseRowCount;
    /** 通过后该组行数（预期值）。 */
    public int resultRowCount;
    /** 轴的人类可读表达。 */
    public List<AxisLabel> axisLabels = new ArrayList<>();

    public static class AxisLabel {
        public String column;
        public String label;
        public Object value;
        public String display;
    }
}
