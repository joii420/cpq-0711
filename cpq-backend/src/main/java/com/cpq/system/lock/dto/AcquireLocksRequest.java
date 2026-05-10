package com.cpq.system.lock.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public class AcquireLocksRequest {

    @NotNull(message = "customerId 不能为空")
    public UUID customerId;

    /** 料号列表；为空或 null 表示客户级全量锁 */
    public List<String> partNos;

    /** 关联的导入记录 ID（可选，便于锁监控追溯） */
    public UUID importRecordId;
}
