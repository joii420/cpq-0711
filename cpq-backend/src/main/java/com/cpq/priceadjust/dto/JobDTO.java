package com.cpq.priceadjust.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** api.md §3.1/§3.2 — 更新批次（屏 6 + 常驻更新任务页）。 */
public class JobDTO {
    public UUID jobId;
    public String customerNo;
    public String versionNo;
    public String triggeredBy;
    public OffsetDateTime triggeredAt;
    public String status;
    public int total;
    public int success;
    public int failed;
    public int conflict;
    public int stale;
    public OffsetDateTime finishedAt;
    public boolean notified;
}
