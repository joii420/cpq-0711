package com.cpq.costing.dto;

import java.util.List;

public class ComparisonDTO {

    public List<BasicFieldDiff> basicFieldDiffs;       // Tab 1: 基础数据字段
    public List<TagGroup> tagGroups;                    // Tab 2: 公式 / 业务标签分组
    public BigDecimalSummary summary;

    public static class BasicFieldDiff {
        public String variableCode;
        public String variableLabel;
        public Object costingValue;
        public Object quotationValue;
        public String diffStatus;  // SAME / MODIFIED / MISSING / NEW
    }

    public static class TagGroup {
        public String groupName;
        public List<TagDiff> tags;
    }

    public static class TagDiff {
        public String tag;          // comparison_tag code
        public String tagLabel;
        public Object costingValue;
        public Object quotationValue;
        public Object delta;
        public String deltaPct;     // "5.00%"
    }

    public static class BigDecimalSummary {
        public Object costingTotal;
        public Object quotationTotal;
        public Object profit;
        public String profitRate;
        public Integer modifiedFieldsCount;
    }
}
