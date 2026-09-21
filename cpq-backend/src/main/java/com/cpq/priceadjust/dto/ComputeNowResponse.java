package com.cpq.priceadjust.dto;

import java.util.UUID;

/** task-260920 api.md §2.1 —— {@code POST /reviews/{id}/compute-now} 的 202 响应体。 */
public class ComputeNowResponse {
    public UUID reviewId;
    public String budgetStatus;

    public ComputeNowResponse() { }

    public ComputeNowResponse(UUID reviewId, String budgetStatus) {
        this.reviewId = reviewId;
        this.budgetStatus = budgetStatus;
    }
}
