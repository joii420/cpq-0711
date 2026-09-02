package com.cpq.configure.dto;

/**
 * 配置下的一条元素含量（task-260901 起归属配置，不再归属材质）。
 *
 * <p>🚨 含量字段一律 {@code String}（api.md §1）：{@code numeric(16,12)} 走 JS number 会丢尾数。
 * 去尾随零是<b>渲染层</b>的事，接口出参保持完整精度（AC-30 反向断言）。
 */
public class MaterialRecipeElementDTO {
    /** '10001' —— 权威元素链（task-0709 · B2） */
    public String elementNo;
    public String elementCode;
    public String elementName;
    /** '90.000000000000'（100 制、12 位） */
    public String defaultPct;
    public String minPct;
    public String maxPct;
    public boolean isLocked;
    public int sortOrder;
}
