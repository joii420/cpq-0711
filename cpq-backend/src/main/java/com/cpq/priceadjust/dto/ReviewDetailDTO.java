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
        /**
         * repair-0807 FR-6：该元素在判断依据单该料号上的用量 = unitPriceImpact ÷ (currentPrice −
         * previousPrice)。分母为 0 或任一价为 null → null（不得返回 0 冒充"没有用量"）。
         */
        public BigDecimal usageQty;
        /** repair-0807 FR-6：该元素涨跌对判断依据单单价的影响额，单元素版本=整体 Δ，多元素版本=逐元素 dryRun Δ。 */
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
        /** repair-0807 FR-5：该行是否跑过 dryRun 试算。false → 前端渲染「未试算」；仅 isBasis 行为 true。 */
        public boolean adjustedComputed;
        public String comparisonViewUrl;
    }
}
