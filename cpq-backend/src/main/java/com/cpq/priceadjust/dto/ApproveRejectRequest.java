package com.cpq.priceadjust.dto;

import java.util.List;
import java.util.UUID;

/** api.md §2.4/§2.5 — 通过/驳回请求体。reject 的 reason 必填（裁决 8）。 */
public class ApproveRejectRequest {
    public List<UUID> reviewIds;
    public String comment;
    public String reason;
}
