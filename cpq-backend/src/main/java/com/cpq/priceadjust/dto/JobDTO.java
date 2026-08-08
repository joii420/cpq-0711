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
    /** repair-0807 FR-4：SKIPPED 终态计数（wire 字段名 `skipped`，与兄弟字段无 Count 后缀风格一致）。 */
    public int skipped;
    public OffsetDateTime finishedAt;
    public boolean notified;
}
