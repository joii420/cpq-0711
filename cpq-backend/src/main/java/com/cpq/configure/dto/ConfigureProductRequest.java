package com.cpq.configure.dto;

import java.util.List;

public class ConfigureProductRequest {
    public String productType;                              // 'SIMPLE' | 'COMPOSITE'
    public List<PartRequest> parts;                         // SIMPLE 时 size=1; COMPOSITE 时 size>=2
    public List<CompositeProcessRequest> compositeProcesses; // 仅 COMPOSITE 才用
    /**
     * Bug B 解法 B: 前端 ConfigureProductDrawer 生成的 tempId (crypto.randomUUID)。
     * insertLineItem 优先用此 UUID 作 line_item.id（而不是后端自生成），
     * 使前端在提交前就知道 id，无需二次映射。
     * SIMPLE 时代表唯一 line item; COMPOSITE 时代表父 line item。
     * null / 空 = 老路径，后端 UUID.randomUUID() 兜底。
     */
    public String tempId;                                   // 前端 tempId (UUID 字符串，optional)

    /**
     * task-260902（AC-1 / AC-2，api.md §1.1）：<b>客户产品编号</b>，必填。
     *
     * <p>后端硬拦：已存在于 {@code sel_product_no} 或 {@code material_customer_map.customer_product_no}
     * → 409 {@code CUSTOMER_PRODUCT_NO_TAKEN}。前端拦截是体验，后端拦是正确性，两处都要有。
     */
    public String customerProductNo;

    /** task-260902（AC-1）：客户产品名称，选填 → {@code sel_product_no.customer_product_name}（🚫 不写 mcm）。 */
    public String customerProductName;

    /**
     * <b>task-260910 · B-18（AC-18 / AC-20，api.md §2.3）</b>：直接绑定已有销售料号。
     *
     * <p>场景（用户 2026-09-10 原话）：「客户的产品编号在我们客户料号表中不存在，所以需要选配新增，
     * 此时可以选择一个已有的销售料号进行直接绑定，这样子最简单，直接不用零件选配」。
     *
     * <p>非空 ⇒ 走<b>绑定路径</b>（{@code ConfigureProductService#configureByBinding}）：
     * <b>不铸新号、不进指纹、不写 BOM / 元素</b>，只写 {@code ds_quote_customer_part}
     * + {@code quotation_line_item}。
     *
     * <p>🚫 与 {@link #parts} <b>互斥</b>：两者同时非空 → 400 {@code BIND_AND_PARTS_EXCLUSIVE}。
     * <p>🚫 料号不在<b>该客户</b>的 {@code ds_quote_material} 里 → 400 {@code BIND_MATERIAL_NOT_FOUND}。
     * <p>📌 加法式字段：{@code null} / 空 = 老 payload，行为逐字不变。
     */
    public String bindExistingMaterialNo;
}
