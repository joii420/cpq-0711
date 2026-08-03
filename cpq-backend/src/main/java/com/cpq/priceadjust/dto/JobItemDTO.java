package com.cpq.priceadjust.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** api.md §3.3 — 更新批次明细：报价单 × 料号。 */
public class JobItemDTO {
    public UUID itemId;
    public UUID quotationId;
    public String quotationNo;
    public String materialNo;
    public UUID lineItemId;
    public String status;
    public String errorCode;
    public String errorMessage;
    public BigDecimal diffValue;
    public int retryCount;
    public OffsetDateTime updatedAt;
}
