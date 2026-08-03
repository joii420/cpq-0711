package com.cpq.priceadjust.dto;

import java.math.BigDecimal;

/** api.md §1.2 请求体（前端 {@code StrategySaveRequest}）。 */
public class PutStrategyRequest {
    public Boolean enabled;
    public String cycleType;
    public Short cycleWeekday;
    public Short cycleDayOfMonth;
    public Short cycleNthWeek;
    public String executeTime;
    public String materialScopeMode;
    public BigDecimal costDiffThreshold;
}
