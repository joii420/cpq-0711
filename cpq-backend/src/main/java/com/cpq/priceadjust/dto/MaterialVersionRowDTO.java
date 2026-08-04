package com.cpq.priceadjust.dto;

import java.util.UUID;

/**
 * api.md §4.1 · 料号级价格版本表一行 ——「单内混合价的**可查证据**」（§11.1.1）。
 * 字段名逐字对齐前端 {@code price-adjust.ts#MaterialVersionRowDTO}（其纯函数渲染层
 * {@code materialVersionLabel.ts} 有 4 条单测锁住文案，本 DTO 的字段名/枚举值不得偏离）。
 */
public class MaterialVersionRowDTO {

    /** 四态，与前端 {@code MaterialVersionState} 联合类型逐字对齐。 */
    public static final String UPGRADED = "UPGRADED";
    public static final String REJECTED = "REJECTED";
    public static final String NOT_UPDATED = "NOT_UPDATED";
    public static final String NOT_PARTICIPATING = "NOT_PARTICIPATING";

    public String materialNo;
    public String materialName;
    /** 该料号**指针当前指向**的版本号；{@code NOT_PARTICIPATING} 时为 null。 */
    public String currentVersionNo;
    public String state;
    /**
     * {@code NOT_UPDATED} 且能定位到锚点 job_item 时带出，供诊断（前端不展示版本号，只用于排查）。
     * 「指针已推进但本单未更新成功」这一类才有值；「本单从未进过该版批次」那一类为 null。
     */
    public UUID pendingJobItemId;
}
