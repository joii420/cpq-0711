package com.cpq.configure.dto;

import java.util.List;
import java.util.Map;

public class ConfigureProductResponse {
    public List<Map<String, Object>> lineItems;    // SIMPLE: 1 行;COMPOSITE: 1 父 + N 子
    public boolean fingerprintMatched;             // 至少 1 个料号是复用
    public List<String> reusedHfPartNos;           // 哪些料号是复用的
    /**
     * B2.3: 后端按 Σqty 兜底裁决后的有效 productType（"SIMPLE"/"COMPOSITE"），
     * 可能与请求里的 req.productType 不同（如单行 qty>=2 请求声明 SIMPLE 也会被裁成 COMPOSITE）。
     */
    public String productType;

    /**
     * task-260902 · B-11（api.md §1.3，AC-7 状态 C）：命中复用时带出的销售产品信息；
     * 未命中为 null。
     */
    public ReusedProductInfoDTO reusedProductInfo;

    /**
     * task-260902 · B-11：本次指纹的结构版本（{@code SalesFingerprintCalculator.STRUCTURE_VERSION}），
     * 便于前端与排查时对账「这个料号是用哪一版指纹口径算出来的」。
     */
    public String structureVersion;
}
