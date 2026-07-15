package com.cpq.configure.dto;

import java.util.List;

/**
 * P2→P3 之间"确认前"指纹预览请求（task-0712 缺口2·3a）。
 *
 * <p><b>形态对齐提交端 {@link ConfigureProductRequest}</b>：3a 起本请求携带的是"待预览的完整配置"
 * （customerNo + parts + compositeProcesses），与提交端 {@code configure()} 消费的形状一致 —— 复用同一套
 * {@code SalesFingerprintCalculator} + {@code projectEnabledParams} 投影逻辑，保证「预览命中」与
 * 「提交命中」同口径（否则误导用户：预览说新建，提交却复用，或反之）。
 *
 * <p>SIMPLE（Σparts[].quantity == 1，quantity 缺省按 1 计）：{@code parts} 恰 1 项。
 * <p>COMPOSITE（Σquantity ≥ 2）：{@code parts} 多项（或单项 quantity≥2），{@code compositeProcesses} 可选。
 */
public class LookupFingerprintRequest {

    /** 客户编码（customerCode）—— 销售侧指纹按客户维度隔离，必填。 */
    public String customerNo;

    /** 待预览配件集，形态同 {@link ConfigureProductRequest#parts}（partMode=existing/custom 均支持）。 */
    public List<PartRequest> parts;

    /** 组合工艺（仅 Σquantity>=2 时用），形态同 {@link ConfigureProductRequest#compositeProcesses}。 */
    public List<CompositeProcessRequest> compositeProcesses;

    // ─────────────────────────────────────────────────────────────────────
    // ⚠️ 2026-05 T19 生产侧全局指纹过渡态遗留字段。3a 起 lookupFingerprint 不再读取，
    // 保留仅防老调用方（若有）反序列化报 UnrecognizedPropertyException。前端未接入过（F5 未消费
    // 本端点，见 SummaryFingerprintPanel.tsx 头注），无迁移成本。
    // ─────────────────────────────────────────────────────────────────────
    @Deprecated public String productType;
    @Deprecated public String recipeCode;
    @Deprecated public List<ElementOverride> elements;
    @Deprecated public List<String> childHfPartNos;
}
