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
