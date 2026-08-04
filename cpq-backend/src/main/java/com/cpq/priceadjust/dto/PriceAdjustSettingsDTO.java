package com.cpq.priceadjust.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * api.md §6.1 · 调价系统参数读写体。GET / PUT 共用同一个类（形状一致，PUT 请求只读
 * {@code subtotalGuardThreshold}，其余字段服务端填充后回显）。
 */
public class PriceAdjustSettingsDTO {

    /** L3 升版口径守卫阈值（金额/元，E14-11）。 */
    public BigDecimal subtotalGuardThreshold;

    /** 回显用，PUT 请求体里忽略。 */
    public OffsetDateTime updatedAt;
}
