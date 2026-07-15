package com.cpq.quotation.dto;

import java.util.List;

/** GET /api/cpq/costing-orders/{coid}/version-options 响应体（api.md §2）。 */
public class VersionOptionsResponseDTO {
    public String componentId;
    public String partNo;
    /** 当前生效/已 override 的版本（高亮用）；查不到返回 null。 */
    public String currentVersion;
    /** view_version 候选列表，严格倒序。无 view_version 列的组件返回空列表。 */
    public List<String> options;
}
