package com.cpq.component.dto;

import java.util.UUID;

/**
 * Y1.5 组件按 driver 路径展开请求。
 */
public class ExpandDriverRequest {
    public UUID customerId;
    public String partNo;
    /**
     * 料号版本号（可选）。传入后 ImplicitJoinRewriter 注入 AND part_version=N 谓词，
     * 只拉该版本数据，避免历史版本叠加重复。
     * null = 不注入版本过滤（向后兼容旧调用方）。
     */
    public Integer partVersion;
}
