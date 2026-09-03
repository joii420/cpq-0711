package com.cpq.existingproduct.dto;

/**
 * 报价单「从已有产品添加」列表行（task-0712 B3，api.md §2.1）。
 *
 * <p>数据源 {@code material_customer_map}（按本报价单客户过滤），two LEFT JOIN 一次带出：
 * <ul>
 *   <li>{@code material_master} → {@link #spec}（{@code COALESCE(NULLIF(specification,''), dimension)}，
 *       决策 3-A）；</li>
 *   <li>{@code model_config}（{@code subject_type='SALES_PART' AND is_current}）→ {@link #has3d} /
 *       {@link #thumbnailUrl}。</li>
 * </ul>
 * {@link #productName} 与 {@link #customerMaterialName} 同源 {@code material_customer_map.customer_material_name}
 * （api.md §2.1 示例注释：productName ← customer_material_name；表本身无独立"通用品名"列，两字段供前端分别映射到
 * {@code CustomerPartCandidate.partName} / {@code customerPartName} 两个槽位，见 fronttask.md §F4 落行映射）。
 */
public class ExistingProductDTO {

    /** 销售料号（= material_customer_map.material_no）。 */
    public String materialNo;

    /**
     * 客户产品编号 —— <b>代表编号</b>（一料号多编号时取 created_at 最早的那个）。
     * 占位行（选配发号副作用）此列为 NULL，已在服务层过滤排除（F005）。
     *
     * <p>🚫 <b>保留单值语义、不要改名/改类型</b>：前端与既有用例都在读它。
     * 要拿全部编号请用 {@link #customerProductNos}。
     */
    public String customerProductNo;

    /**
     * task-260902 · AC-12b⑤-b：该 {@code (customer_no, material_no)} 名下的<b>全部</b>客户产品编号，
     * 按 {@code created_at} 升序；来源 = {@code sel_product_no} ∪ {@code material_customer_map}。
     *
     * <p>🚨 <b>为什么必须有它</b>：AC-12b④ 要求列表里该产品<b>只出现一次</b>（防 AP-22 重复渲染），
     * 于是行按销售料号去重、{@link #customerProductNo} 只能显示一个代表编号。
     * 但「一料号多编号」正是 AC-12b 的核心场景 —— 用第二个编号的销售不带过滤打开列表，
     * 看到的是别人的编号，会<b>认不出这是自己的产品</b>。
     * 那就是本任务要修的「选配产品在产品库里找不回」的另一面：从<b>找不到</b>变成<b>认不出</b>。
     * ⇒ 去重保留，编号全给（用户裁决 2026-09-03）。
     */
    public java.util.List<String> customerProductNos = new java.util.ArrayList<>();

    /** 品名（= customer_material_name）。 */
    public String productName;

    /** 规格：COALESCE(NULLIF(material_master.specification,''), material_master.dimension)。 */
    public String spec;

    /** 客户物料名称（= customer_material_name，与 productName 同源）。 */
    public String customerMaterialName;

    /** 该料号是否配了当前版本 3D 模型（model_config is_current 命中）。 */
    public Boolean has3d;

    /** 3D 缩略图 URL；无则 null。 */
    public String thumbnailUrl;

    /**
     * 来源（A 方案 2026-07-16）：{@code EXISTING}=真·已有产品（有客户产品号，导入建立）；
     * {@code CONFIGURED}=选配发号产品（customer_product_no 待导入分配，已在 sel_part_signature 登记）。
     * 前端据此标「选配」。
     */
    public String source;

    /** 选配产品类型：{@code SIMPLE} | {@code COMPOSITE}（仅 source=CONFIGURED 有值），非选配为 null。 */
    public String configProductType;
}
