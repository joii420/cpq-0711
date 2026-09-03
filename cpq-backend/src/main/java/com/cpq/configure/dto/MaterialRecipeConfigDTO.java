package com.cpq.configure.dto;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 含量配置（task-260901，api.md §1 MaterialRecipeConfig）。
 *
 * <p>🚨 含量一律 {@code String} 传输：{@code default_pct} 是 numeric(16,12)，
 * 走 JS {@code number} 会丢尾数且无法区分 12.345678901200 与 12.3456789012。
 */
public class MaterialRecipeConfigDTO {
    public UUID id;
    /** '00006-01' */
    public String configNo;
    /** 发号水位 */
    public int seq;
    public String remark;
    /** ACTIVE / INACTIVE */
    public String status;
    public List<MaterialRecipeElementDTO> elements = new ArrayList<>();
    /** 合计，100 制、12 位小数字符串，如 '100.000000000000' */
    public String totalPct;
    public OffsetDateTime createdAt;
}
