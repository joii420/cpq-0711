package com.cpq.priceadjust.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** api.md §2.2 — 料号审核抽屉（屏 4）三段结构，一次返回。 */
public class ReviewDetailDTO {
    public UUID reviewId;
    public String customerNo;
    public String materialNo;
    public String materialName;
    public String currentVersionNo;
    public String targetVersionNo;
    public String budgetStatus;
    public String reviewStatus;

    // 一、为什么变
    public List<ElementChange> elementChanges;
    public BigDecimal elementImpactTotal;

    public static class ElementChange {
        public String elementCode;
        public String elementName;
        public String matchedRule;
        public BigDecimal previousPrice;
        public BigDecimal currentPrice;
        public BigDecimal changeRate;
        /** 简化实现：精确用量/单价影响分解需逐行 driver 回溯，本期未做，如实留空 */
        public BigDecimal usageQty;
        public BigDecimal unitPriceImpact;
        public boolean noPrice;
        public boolean inheritedFromPrevious;
    }

    // 二、比对结果
    public UUID templateSeriesId;
    public String templateSeriesName;
    public List<ColumnResult> comparisonColumns;

    public static class ColumnResult {
        public String columnId;
        public String label;
        public BigDecimal threshold;
        public BigDecimal quoteCurrent;
        public BigDecimal quoteAdjusted;
        public BigDecimal costingCurrent;
        public BigDecimal costingAdjusted;
        public BigDecimal diffCurrent;
        public BigDecimal diffAdjusted;
        public String status;
        public String missingSide;
    }

    // 三、逐单明细
    public List<QuotationRef> quotations;

    public static class QuotationRef {
        public UUID quotationId;
        public String quotationNo;
        public OffsetDateTime createdAt;
        public String status;
        public boolean isBasis;
        public BigDecimal quoteSubtotalCurrent;
        public BigDecimal quoteSubtotalAdjusted;
        public String comparisonViewUrl;
    }
}
