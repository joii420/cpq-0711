package com.cpq.quotation.dto;

import java.util.UUID;

/** POST /api/cpq/costing-orders/{coid}/version-switch 请求体（api.md §3）。 */
public class VersionSwitchRequest {
    public UUID lineItemId;
    public UUID componentId;
    public String partNo;
    public String viewVersion;
}
