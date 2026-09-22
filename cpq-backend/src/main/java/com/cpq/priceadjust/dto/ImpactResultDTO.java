package com.cpq.priceadjust.dto;

import java.util.List;
import java.util.Map;

/** api.md §2.3 — 通过前影响面确认（屏 5 Modal 数据源）。只读预览，不产生任何副作用。 */
public class ImpactResultDTO {
    public int materialCount;
    public List<VersionPath> versionPaths;
    public int quotationCount;
    public Map<String, Integer> byStatus;
    public List<BreachedMaterial> breachedMaterials;
    public int excludedQuotationCount;
    public Map<String, Integer> excludedByStatus;
    /** task-260920 B-9：逐料号 {@code col-default} 比对列金额（口径同列表行同名字段）。 */
    public List<MaterialAmount> materials;

    public static class MaterialAmount {
        public String materialNo;
        public java.math.BigDecimal quoteCostCurrent;
        public java.math.BigDecimal quoteCostAdjusted;
        public java.math.BigDecimal diffAdjusted;
        /** NORMAL | MISSING | STALE（及 RED / AMBER，取自比对列 status） */
        public String status;
        /** QUOTE | COSTING | BOTH | null */
        public String missingSide;
    }

    public static class VersionPath {
        public String materialNo;
        public String from;
        public String to;
    }

    public static class BreachedMaterial {
        public String materialNo;
        public int breachedCount;
    }
}
