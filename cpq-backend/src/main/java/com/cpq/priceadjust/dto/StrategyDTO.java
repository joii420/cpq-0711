package com.cpq.priceadjust.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * task-0729 策略 CRUD 补交 · api.md §1.1/§1.2 响应体（前端 {@code PriceAdjustStrategyDTO &
 * BudgetRecomputeInfo} 的后端镜像，字段名逐字对齐 {@code cpq-frontend/src/types/price-adjust.ts}）。
 *
 * <p>GET（§1.1）只填策略字段，{@code budgetRecomputeTriggered}/{@code affectedReviewCount}
 * 留 null；PUT（§1.2）成功响应额外把这两个字段填上——响应体是同一个类，不新造 wrapper，
 * 与前端 {@code StrategySaveResponse = PriceAdjustStrategyDTO & BudgetRecomputeInfo}（扁平合并，
 * 非嵌套）的形状一致。
 */
public class StrategyDTO {
    public boolean exists;
    public String customerNo;
    public Boolean enabled;
    public String cycleType;
    public Short cycleWeekday;
    public Short cycleDayOfMonth;
    public Short cycleNthWeek;
    public String executeTime;
    public String materialScopeMode;
    public BigDecimal costDiffThreshold;
    public String latestVersionNo;
    public String pendingVersionNo;
    public int materialCount;
    public int elementCount;
    public boolean hasComparisonConfig;
    public OffsetDateTime updatedAt;
    public String updatedBy;

    // PUT 专属，GET 恒 null（前端类型标 optional）
    public Boolean budgetRecomputeTriggered;
    public Integer affectedReviewCount;

    /** §1.1 策略不存在时的空壳（200，不是 404）。 */
    public static StrategyDTO notExists(String customerNo) {
        StrategyDTO dto = new StrategyDTO();
        dto.exists = false;
        dto.customerNo = customerNo;
        return dto;
    }
}
