package com.cpq.quotation.dto;

import java.util.UUID;

/** task-0721 B6：树上加叶子请求体（api.md §3）。 */
public class BomTreeAddLeafRequest {
    /** 触发操作的树页签组件 id。 */
    public UUID componentId;
    /** 宿主节点的 __nodeId。 */
    public String hostNodeId;
    /** 用户选中的料号。 */
    public String partNo;
}
