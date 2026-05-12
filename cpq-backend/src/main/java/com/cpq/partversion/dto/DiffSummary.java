package com.cpq.partversion.dto;

/** 单表的 diff 计数: 新增/变更/删除. */
public record DiffSummary(int added, int changed, int deleted) {
    public static DiffSummary empty() { return new DiffSummary(0, 0, 0); }
    public boolean isEmpty() { return added == 0 && changed == 0 && deleted == 0; }
}
