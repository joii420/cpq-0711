package com.cpq.priceadjust.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** api.md §2.1 — 跨客户的料号待办池（屏 3 主列表）。纯读库，不实时算。 */
public class ReviewListItemDTO {
    public UUID reviewId;
    public String customerNo;
    public String customerName;
    public String materialNo;
    public String materialName;
    public String currentVersionNo;
    public String targetVersionNo;
    public String budgetStatus;
    public String reviewStatus;
    public String basisQuotationNo;
    public LocalDate basisQuotationDate;

    // 固定列：产品总价口径
    public BigDecimal quoteCostCurrent;
    public BigDecimal quoteCostAdjusted;
    public BigDecimal costingCost;
    public BigDecimal diffCurrent;
    public BigDecimal diffAdjusted;

    public int columnCount;
    public int breachedCount;
    public int amberCount;
    public int missingCount;
    public int staleCount;
    /** = breachedCount > 0（硬约束 19），服务端给出，前端不得自算 */
    public boolean rowRed;
}
