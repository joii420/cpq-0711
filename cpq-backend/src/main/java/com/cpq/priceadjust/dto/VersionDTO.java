package com.cpq.priceadjust.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** api.md §1.11/§1.12 — 版本生成响应 + 版本轨迹列表行。 */
public class VersionDTO {
    public UUID versionId;
    public String versionNo;
    public LocalDate baseDate;
    public String status;
    public String triggerType;
    public OffsetDateTime createdAt;
    public String createdBy;
    public int itemCount;

    // §1.11 生成响应专用
    public UUID budgetJobId;
    public String budgetStatus;

    // §1.12 版本轨迹列表专用：进度摘要，不落库、实时派生（§11.3.3(2)）
    public Progress progress;

    public static class Progress {
        public long total;
        public long approved;
        public long rejected;
        public long pending;
        public long budgeting;
    }
}
