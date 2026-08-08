package com.cpq.priceadjust.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * api.md §1/§2 · 调价系统参数读写体。GET / PUT 共用同一个类（形状一致）。
 *
 * <p>🔒 task-0806 起 PUT 语义：{@code subtotalGuardThreshold} / {@code subtotalGuardEnabled}
 * 两字段均可单独提交，{@code null} = 不改动该项（不是"置默认值"）——故 {@code subtotalGuardEnabled}
 * 用 {@link Boolean} 包装类而非 {@code boolean} 基本类型，才能承载"未提交"这个第三态
 * （Jackson：JSON 缺字段/显式 {@code null} → 反序列化为 {@code null}；显式 {@code false} → 反序列化为
 * {@code Boolean.FALSE}，两者可区分）。见 api.md §2 实现陷阱备注。
 */
public class PriceAdjustSettingsDTO {

    /** L3 升版口径守卫阈值（金额/元，E14-11）。PUT 请求体里 {@code null} = 不改动该项。 */
    public BigDecimal subtotalGuardThreshold;

    /**
     * 🆕 task-0806 FR-9：S0 L3 口径守卫总开关。GET 响应恒非 null；PUT 请求体里 {@code null} =
     * 不改动该项。
     */
    public Boolean subtotalGuardEnabled;

    /** 回显用，PUT 请求体里忽略。 */
    public OffsetDateTime updatedAt;
}
