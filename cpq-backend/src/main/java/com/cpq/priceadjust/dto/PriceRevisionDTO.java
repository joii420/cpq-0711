package com.cpq.priceadjust.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * api.md §4.1 · 整单版本轨迹表一行（裁决 15 / 24 / 30）。
 * 字段名逐字对齐前端 {@code cpq-frontend/src/types/price-adjust.ts#PriceRevisionDTO}。
 */
public class PriceRevisionDTO {
    public UUID revisionId;
    public String revisionNo;
    /** 初版（{@link #isInitial}=true）恒为 null——D6：初版不挂 based_version_id。 */
    public String basedVersionNo;
    public boolean isInitial;
    public boolean sealed;
    public OffsetDateTime firstEffectiveAt;
    public OffsetDateTime lastUpdatedAt;
    /** 同一 V 版内多次升版累积（裁决 30）。 */
    public List<String> upgradedMaterialNos;
    public BigDecimal quoteTotalAmount;
}
