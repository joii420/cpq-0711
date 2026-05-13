package com.cpq.partno;

import java.util.UUID;

/**
 * 命名上下文 — PartNoProvider 生成 hf_part_no 时需要的输入。
 *
 * <p>独立产品: {@code symbol="AgCu", productType="SIMPLE"} → "CFG-AgCu-000001"
 * <p>组合产品: {@code symbol="COMBO", productType="COMPOSITE"} → "CFG-COMBO-000001"
 */
public class PartNoContext {

    /** 化学式符号 (AgCu/AgNi/AgSnO₂/AgCdO/AgW/CuCr/AgPd/AuAg) 或 "COMBO". 用作 CFG-{symbol}- 前缀. */
    public String symbol;

    /** "SIMPLE" 独立产品 / "COMPOSITE" 组合产品父料号. */
    public String productType;

    /** 审计:操作人 user id (允许 null 表示系统调用). */
    public UUID operatorId;

    public PartNoContext() {}

    public PartNoContext(String symbol, String productType, UUID operatorId) {
        this.symbol = symbol;
        this.productType = productType;
        this.operatorId = operatorId;
    }
}
